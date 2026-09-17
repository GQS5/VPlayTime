package site.vackstudio.vplaytime.playtime;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PlaytimeManager} lifecycle and {@link PlayerCache}.
 */
class PlaytimeManagerTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID UUID_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void joinTracksNewPlayerWithZeroPlaytime() {
        PlaytimeManager manager = new PlaytimeManager(new FakeTimeSource(1_000L));

        PlayerData data = manager.handleJoin(UUID_A).join();

        assertEquals(0L, data.getStoredPlaytimeSeconds());
        assertTrue(manager.isTracked(UUID_A));
        assertEquals(1, manager.trackedCount());
    }

    @Test
    void joinAccumulatesAcrossRepeatedJoinQuitCycles() {
        FakeTimeSource clock = new FakeTimeSource(1_000L);
        PlaytimeManager manager = new PlaytimeManager(clock);

        // Cycle 1: 100 seconds. The finalized data (100s) is the handoff that
        // Phase 3 persistence will store; Phase 2 keeps no database.
        manager.handleJoin(UUID_A).join();
        clock.advance(100L);
        assertEquals(100L, manager.handleQuit(UUID_A).orElseThrow().getStoredPlaytimeSeconds());
        assertFalse(manager.isTracked(UUID_A));

        // Cycle 2: without persistence the rejoin starts fresh; a new session
        // still accumulates exactly.
        PlayerData rejoined = manager.handleJoin(UUID_A).join();
        assertEquals(0L, rejoined.getStoredPlaytimeSeconds());
        clock.advance(50L);
        assertEquals(50L, manager.effectivePlaytimeSeconds(UUID_A));
        assertEquals(50L, manager.handleQuit(UUID_A).orElseThrow().getStoredPlaytimeSeconds());

        // Quit when untracked is a no-op.
        assertTrue(manager.handleQuit(UUID_A).isEmpty(), "second quit after removal is a no-op");
    }

    @Test
    void quitFinalizesAndUntracks() {
        FakeTimeSource clock = new FakeTimeSource(2_000L);
        PlaytimeManager manager = new PlaytimeManager(clock);
        manager.handleJoin(UUID_A).join();
        clock.advance(600L);

        Optional<PlayerData> quit = manager.handleQuit(UUID_A);

        assertTrue(quit.isPresent());
        assertEquals(600L, quit.get().getStoredPlaytimeSeconds());
        assertTrue(quit.get().isDirty());
        assertFalse(manager.isTracked(UUID_A));
        assertEquals(0, manager.trackedCount());
    }

    @Test
    void immediateQuitAddsNothing() {
        PlaytimeManager manager = new PlaytimeManager(new FakeTimeSource(7_000L));
        manager.handleJoin(UUID_A).join();

        Optional<PlayerData> quit = manager.handleQuit(UUID_A);

        assertTrue(quit.isPresent());
        assertEquals(0L, quit.get().getStoredPlaytimeSeconds());
        assertFalse(quit.get().isDirty());
    }

    @Test
    void playersAreIsolated() {
        FakeTimeSource clock = new FakeTimeSource(1_000L);
        PlaytimeManager manager = new PlaytimeManager(clock);
        manager.handleJoin(UUID_A).join();
        manager.handleJoin(UUID_B).join();

        clock.advance(300L);
        long bankedA = manager.handleQuit(UUID_A).orElseThrow().getStoredPlaytimeSeconds();
        clock.advance(300L);

        assertEquals(300L, bankedA);
        assertEquals(600L, manager.effectivePlaytimeSeconds(UUID_B));
        assertFalse(manager.isTracked(UUID_A));
        assertTrue(manager.isTracked(UUID_B));
    }

    @Test
    void untrackedPlayerReadsZero() {
        PlaytimeManager manager = new PlaytimeManager(new FakeTimeSource(1_000L));

        assertEquals(0L, manager.effectivePlaytimeSeconds(UUID_A));
        assertTrue(manager.find(UUID_A).isEmpty());
        assertTrue(manager.handleQuit(UUID_A).isEmpty());
    }

    @Test
    void cachePutGetContainsRemove() {
        PlayerCache cache = new PlayerCache();
        PlayerData data = new PlayerData(UUID_A, 10L);

        assertFalse(cache.contains(UUID_A));
        cache.put(UUID_A, data);
        assertTrue(cache.contains(UUID_A));
        assertEquals(data, cache.get(UUID_A).orElseThrow());
        assertEquals(1, cache.size());

        assertEquals(data, cache.remove(UUID_A).orElseThrow());
        assertFalse(cache.contains(UUID_A));
        assertTrue(cache.get(UUID_A).isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    void concurrentJoinsStayConsistent() throws InterruptedException {
        PlaytimeManager manager = new PlaytimeManager(new FakeTimeSource(1_000L));
        Thread[] threads = new Thread[16];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 250; j++) {
                    manager.handleJoin(UUID_A).join();
                    manager.effectivePlaytimeSeconds(UUID_A);
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        // Exactly one entry, one session start, no corruption or duplicates.
        assertTrue(manager.isTracked(UUID_A));
        assertEquals(1, manager.trackedCount());
        assertEquals(0L, manager.handleQuit(UUID_A).orElseThrow().getStoredPlaytimeSeconds());
    }
}
