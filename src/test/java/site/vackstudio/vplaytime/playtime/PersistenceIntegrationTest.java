package site.vackstudio.vplaytime.playtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vplaytime.model.StoredData;
import site.vackstudio.vplaytime.storage.SQLiteStorage;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PlaytimeManager + real SQLite storage: join-load, quit-save, autosave,
 * shutdown flush and dirty/version integration.
 */
class PersistenceIntegrationTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID UUID_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return fail("future did not complete: " + ex.getMessage());
        }
    }

    @Test
    void joinLoadsPersistedPlaytime(@TempDir Path dir) {
        Path db = dir.resolve("data.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        await(storage.savePlayer(new StoredData(UUID_A, 3_600L, Set.of(), 0L)));

        PlaytimeManager manager = new PlaytimeManager(new FakeTimeSource(1_000L), storage);
        try {
            PlayerData data = await(manager.handleJoin(UUID_A));
            assertEquals(3_600L, data.getStoredPlaytimeSeconds());
            assertEquals(3_600L, manager.effectivePlaytimeSeconds(UUID_A));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void quitPersistsSessionTime(@TempDir Path dir) {
        Path db = dir.resolve("data.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        FakeTimeSource clock = new FakeTimeSource(2_000L);
        PlaytimeManager manager = new PlaytimeManager(clock, storage);

        await(manager.handleJoin(UUID_A));
        clock.advance(600L);
        manager.handleQuit(UUID_A).orElseThrow();
        // Quit-save and this load serialize on the single storage thread, so
        // the load deterministically observes the completed save.

        Optional<StoredData> persisted = await(storage.loadPlayer(UUID_A));
        assertTrue(persisted.isPresent());
        assertEquals(600L, persisted.get().playtimeSeconds());
        manager.shutdown();
    }

    @Test
    void rejoinRestoresAfterQuit(@TempDir Path dir) {
        Path db = dir.resolve("data.db");
        FakeTimeSource clock = new FakeTimeSource(1_000L);
        PlaytimeManager first = new PlaytimeManager(clock, new SQLiteStorage(db, LOG));
        await(first.handleJoin(UUID_A));
        clock.advance(100L);
        first.handleQuit(UUID_A);
        first.shutdown();

        PlaytimeManager second = new PlaytimeManager(clock, new SQLiteStorage(db, LOG));
        try {
            assertEquals(100L, await(second.handleJoin(UUID_A)).getStoredPlaytimeSeconds());
        } finally {
            second.shutdown();
        }
    }

    @Test
    void newPlayerWithNoPlaytimeWritesNoRow(@TempDir Path dir) {
        Path db = dir.resolve("data.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        PlaytimeManager manager = new PlaytimeManager(new FakeTimeSource(1_000L), storage);
        try {
            await(manager.handleJoin(UUID_A));
            manager.handleQuit(UUID_A); // 0 elapsed: nothing changed, nothing to save
            assertTrue(await(storage.loadPlayer(UUID_A)).isEmpty(),
                    "clean players must not produce database writes");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void autosavePersistsDirtyOnly(@TempDir Path dir) {
        Path db = dir.resolve("data.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        FakeTimeSource clock = new FakeTimeSource(1_000L);
        PlaytimeManager manager = new PlaytimeManager(clock, storage);
        try {
            await(manager.handleJoin(UUID_A));
            await(manager.handleJoin(UUID_B));
            clock.advance(600L); // only A quits below; B stays clean... advance affects both
            manager.handleQuit(UUID_A); // A banks 600 and saves on quit

            // B is still online with an open session: force-end via a fresh cycle
            // is unnecessary; instead verify autosave writes B's snapshot too once
            // dirty. First assert A persisted via quit path:
            assertEquals(600L, await(storage.loadPlayer(UUID_A)).orElseThrow().playtimeSeconds());
            await(manager.saveDirtySnapshots());
            // B never banked (session still open, stored still 0 and clean).
            assertTrue(await(storage.loadPlayer(UUID_B)).isEmpty(),
                    "clean online players must not be written by autosave");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void shutdownFlushesOpenSessions(@TempDir Path dir) {
        Path db = dir.resolve("data.db");
        FakeTimeSource clock = new FakeTimeSource(5_000L);
        PlaytimeManager manager = new PlaytimeManager(clock, new SQLiteStorage(db, LOG));
        await(manager.handleJoin(UUID_A));
        await(manager.handleJoin(UUID_B));
        clock.advance(300L);
        manager.handleQuit(UUID_B); // B saved via quit path
        manager.shutdown(); // A still online: session must be flushed here

        SQLiteStorage reopened = new SQLiteStorage(db, LOG);
        try {
            assertEquals(300L, await(reopened.loadPlayer(UUID_A)).orElseThrow().playtimeSeconds());
            assertEquals(300L, await(reopened.loadPlayer(UUID_B)).orElseThrow().playtimeSeconds());
        } finally {
            reopened.close();
        }
    }

    @Test
    void staleSnapshotSaveDoesNotMarkNewerChangeClean(@TempDir Path dir) {
        Path db = dir.resolve("data.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        try {
            PlayerData data = new PlayerData(UUID_A, 0L);
            data.startSession(1_000L);
            data.endSession(1_100L); // v1, dirty
            StoredData stale = data.snapshot();
            data.startSession(1_100L);
            data.endSession(1_200L); // v2, still dirty

            await(storage.savePlayer(stale)); // persists v1 state...
            data.markCleanIfVersion(stale.version()); // ...but must not clean v2
            assertTrue(data.isDirty(), "newer change must survive an older save");
            assertEquals(200L, data.getStoredPlaytimeSeconds());
        } finally {
            storage.close();
        }
    }

    @Test
    void failedSaveKeepsDirtyFlag(@TempDir Path dir) {
        Path db = dir.resolve("data.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        storage.close(); // every subsequent save fails
        FakeTimeSource clock = new FakeTimeSource(1_000L);
        PlaytimeManager manager = new PlaytimeManager(clock, storage);
        // Join falls back to memory-only state (load fails loudly, player can play).
        await(manager.handleJoin(UUID_A));
        clock.advance(60L);
        Optional<PlayerData> quit = manager.handleQuit(UUID_A);

        // The quit-save failed; the dirty flag must be untouched so a later
        // autosave/shutdown retries instead of losing the 60 seconds.
        assertTrue(quit.isPresent());
        assertTrue(quit.get().isDirty(), "failed save must not mark state clean");
        manager.shutdown(); // must not hang on the broken storage
    }

    @Test
    void quitDuringLoadNeverLeaksOrLoses(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("data.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        await(storage.savePlayer(new StoredData(UUID_A, 900L, Set.of(), 0L)));
        FakeTimeSource clock = new FakeTimeSource(1_000L);
        PlaytimeManager manager = new PlaytimeManager(clock, storage);

        // Hammer join+quit from many threads: every outcome must be consistent —
        // either the load activated (quit finalizes) or it was dropped (nothing).
        Thread[] threads = new Thread[12];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 50; j++) {
                    await(manager.handleJoin(UUID_A));
                    manager.handleQuit(UUID_A);
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join(30_000L);
        }
        try {
            long persisted = await(storage.loadPlayer(UUID_A)).orElseThrow().playtimeSeconds();
            assertTrue(persisted >= 900L, "stored playtime must never decrease");
            assertFalse(manager.isTracked(UUID_A), "no session may leak after quit");
        } finally {
            manager.shutdown();
        }
    }
}
