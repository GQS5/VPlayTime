package site.vackstudio.vplaytime.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vplaytime.model.StoredData;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
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
 * SQLite persistence tests. Every test uses an isolated database under
 * {@code @TempDir}; nothing ever touches {@code plugins/VPlaytime/data.db}.
 */
class SQLiteStorageTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID UUID_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");

    private static SQLiteStorage open(Path dir, String name) {
        return new SQLiteStorage(dir.resolve(name), LOG);
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return fail("storage future did not complete: " + ex.getMessage());
        }
    }

    private static Set<String> tablesOf(Path db) throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    @Test
    void initCreatesRequiredTables(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("fresh.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        try {
            Set<String> tables = tablesOf(db);
            assertTrue(tables.contains("players"));
            assertTrue(tables.contains("claims"));
        } finally {
            storage.close();
        }
    }

    @Test
    void loadUnknownPlayerReturnsEmpty(@TempDir Path dir) {
        SQLiteStorage storage = open(dir, "t.db");
        try {
            assertTrue(await(storage.loadPlayer(UUID_A)).isEmpty());
        } finally {
            storage.close();
        }
    }

    @Test
    void saveThenLoadRoundTrip(@TempDir Path dir) {
        SQLiteStorage storage = open(dir, "t.db");
        try {
            await(storage.savePlayer(new StoredData(UUID_A, 3_600L, Set.of(), 0L)));
            Optional<StoredData> loaded = await(storage.loadPlayer(UUID_A));
            assertTrue(loaded.isPresent());
            assertEquals(3_600L, loaded.get().playtimeSeconds());
        } finally {
            storage.close();
        }
    }

    @Test
    void saveWritesRealRow(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        try {
            await(storage.savePlayer(new StoredData(UUID_A, 777L, Set.of(), 0L)));
        } finally {
            storage.close();
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             PreparedStatement stmt = conn.prepareStatement("SELECT playtime_seconds FROM players WHERE uuid = ?")) {
            stmt.setString(1, UUID_A.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "row must exist in players table");
                assertEquals(777L, rs.getLong(1));
            }
        }
    }

    @Test
    void upsertOverwritesOlderValue(@TempDir Path dir) {
        SQLiteStorage storage = open(dir, "t.db");
        try {
            await(storage.savePlayer(new StoredData(UUID_A, 100L, Set.of(), 0L)));
            await(storage.savePlayer(new StoredData(UUID_A, 200L, Set.of(), 1L)));
            assertEquals(200L, await(storage.loadPlayer(UUID_A)).orElseThrow().playtimeSeconds());
        } finally {
            storage.close();
        }
    }

    @Test
    void restartSimulation(@TempDir Path dir) {
        Path db = dir.resolve("data.db");
        SQLiteStorage first = new SQLiteStorage(db, LOG);
        await(first.savePlayer(new StoredData(UUID_A, 5_000L, Set.of(), 0L)));
        await(first.savePlayer(new StoredData(UUID_B, 6_000L, Set.of(), 0L)));
        first.close();

        SQLiteStorage second = new SQLiteStorage(db, LOG);
        try {
            assertEquals(5_000L, await(second.loadPlayer(UUID_A)).orElseThrow().playtimeSeconds());
            assertEquals(6_000L, await(second.loadPlayer(UUID_B)).orElseThrow().playtimeSeconds());
        } finally {
            second.close();
        }
    }

    @Test
    void playersStayIsolated(@TempDir Path dir) {
        SQLiteStorage storage = open(dir, "t.db");
        try {
            await(storage.savePlayer(new StoredData(UUID_A, 111L, Set.of(), 0L)));
            await(storage.savePlayer(new StoredData(UUID_B, 222L, Set.of(), 0L)));
            assertEquals(111L, await(storage.loadPlayer(UUID_A)).orElseThrow().playtimeSeconds());
            assertEquals(222L, await(storage.loadPlayer(UUID_B)).orElseThrow().playtimeSeconds());
        } finally {
            storage.close();
        }
    }

    @Test
    void playtimeSaveLeavesClaimsTableUntouched(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        try {
            await(storage.savePlayer(new StoredData(UUID_A, 50L, Set.of(), 0L)));
        } finally {
            storage.close();
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM claims")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "Phase 3 must never write claims rows");
        }
    }

    @Test
    void negativeStoredValueLoadsAsZero(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        try {
            await(storage.savePlayer(new StoredData(UUID_A, 10L, Set.of(), 0L)));
        } finally {
            storage.close();
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE players SET playtime_seconds = -50 WHERE uuid = '" + UUID_A + "'");
            // Separate connection is auto-commit: the corrupt value is durable here.
        }
        SQLiteStorage reopened = new SQLiteStorage(db, LOG);
        try {
            Optional<StoredData> loaded = await(reopened.loadPlayer(UUID_A));
            assertTrue(loaded.isPresent());
            assertEquals(0L, loaded.get().playtimeSeconds());
        } finally {
            reopened.close();
        }
    }

    @Test
    void saveAfterCloseFails(@TempDir Path dir) {
        SQLiteStorage storage = open(dir, "t.db");
        storage.close();
        try {
            await(storage.savePlayer(new StoredData(UUID_A, 10L, Set.of(), 0L)));
            fail("save on closed storage must fail");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage().contains("did not complete"),
                    "failure must surface, not hang or succeed silently");
        }
    }

    @Test
    void orderedSavesPersistNewestLast(@TempDir Path dir) {
        SQLiteStorage storage = open(dir, "t.db");
        try {
            for (long v = 1; v <= 50; v++) {
                await(storage.savePlayer(new StoredData(UUID_A, v * 10, Set.of(), v)));
            }
            assertEquals(500L, await(storage.loadPlayer(UUID_A)).orElseThrow().playtimeSeconds());
        } finally {
            storage.close();
        }
    }

    @Test
    void concurrentSavesStayConsistent(@TempDir Path dir) throws Exception {
        SQLiteStorage storage = open(dir, "t.db");
        int players = 20;
        try {
            Thread[] threads = new Thread[players];
            for (int i = 0; i < players; i++) {
                UUID uuid = new UUID(0L, i);
                threads[i] = new Thread(() -> {
                    for (long v = 1; v <= 10; v++) {
                        await(storage.savePlayer(new StoredData(uuid, v, Set.of(), v)));
                    }
                });
                threads[i].start();
            }
            for (Thread thread : threads) {
                thread.join(15_000L);
            }
            for (int i = 0; i < players; i++) {
                UUID uuid = new UUID(0L, i);
                assertEquals(10L, await(storage.loadPlayer(uuid)).orElseThrow().playtimeSeconds(),
                        "player " + i + " must hold its own newest value");
            }
        } finally {
            storage.close();
        }
    }
}
