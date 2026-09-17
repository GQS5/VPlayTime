package site.vackstudio.vplaytime.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vplaytime.model.StoredData;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * Durable claim rows: insert uniqueness, revoke, restart survival.
 */
class ClaimStorageTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return fail("storage future did not complete: " + ex.getMessage());
        }
    }

    @Test
    void recordClaimInsertsAndLoads(@TempDir Path dir) {
        SQLiteStorage storage = new SQLiteStorage(dir.resolve("t.db"), LOG);
        try {
            StoredData snap = new StoredData(UUID_A, 1_000L, Set.of("reward_1"), 1L);
            assertTrue(await(storage.recordClaim(snap, "reward_1", 9_999L)));

            Optional<StoredData> loaded = await(storage.loadPlayer(UUID_A));
            assertTrue(loaded.isPresent());
            assertEquals(1_000L, loaded.get().playtimeSeconds());
            assertEquals(Set.of("reward_1"), loaded.get().claimedRewardIds());
        } finally {
            storage.close();
        }
    }

    @Test
    void duplicateRecordReturnsFalse(@TempDir Path dir) {
        SQLiteStorage storage = new SQLiteStorage(dir.resolve("t.db"), LOG);
        try {
            StoredData snap = new StoredData(UUID_A, 1_000L, Set.of(), 0L);
            assertTrue(await(storage.recordClaim(snap, "reward_1", 1L)));
            assertFalse(await(storage.recordClaim(snap, "reward_1", 2L)),
                    "conflicting insert is the uniqueness safety net");

            assertEquals(Set.of("reward_1"),
                    await(storage.loadPlayer(UUID_A)).orElseThrow().claimedRewardIds());
        } finally {
            storage.close();
        }
    }

    @Test
    void revokeRemovesClaimRow(@TempDir Path dir) {
        SQLiteStorage storage = new SQLiteStorage(dir.resolve("t.db"), LOG);
        try {
            await(storage.recordClaim(new StoredData(UUID_A, 5L, Set.of(), 0L), "reward_1", 1L));
            await(storage.revokeClaim(UUID_A, "reward_1"));
            assertTrue(await(storage.loadPlayer(UUID_A)).orElseThrow().claimedRewardIds().isEmpty());
            // Revoking a missing row is harmless.
            await(storage.revokeClaim(UUID_A, "reward_1"));
        } finally {
            storage.close();
        }
    }

        @Test
    void claimsSurviveRestart(@TempDir Path dir) {
        Path db = dir.resolve("data.db");
        SQLiteStorage first = new SQLiteStorage(db, LOG);
        await(first.recordClaim(new StoredData(UUID_A, 2_000L, Set.of("reward_2"), 3L), "reward_2", 42L));
        first.close();

        SQLiteStorage second = new SQLiteStorage(db, LOG);
        try {
            Optional<StoredData> loaded = await(second.loadPlayer(UUID_A));
            assertTrue(loaded.isPresent());
            assertEquals(2_000L, loaded.get().playtimeSeconds());
            assertEquals(Set.of("reward_2"), loaded.get().claimedRewardIds());
        } finally {
            second.close();
        }
    }

    @Test
    void revokeAllRemovesEveryClaimKeepsPlaytime(@TempDir Path dir) {
        SQLiteStorage storage = new SQLiteStorage(dir.resolve("t.db"), LOG);
        try {
            await(storage.recordClaim(new StoredData(UUID_A, 900L, Set.of(), 0L), "reward_1", 1L));
            await(storage.recordClaim(new StoredData(UUID_A, 900L, Set.of(), 1L), "reward_2", 2L));
            await(storage.revokeAllClaims(UUID_A));
            Optional<StoredData> loaded = await(storage.loadPlayer(UUID_A));
            assertTrue(loaded.isPresent());
            assertEquals(900L, loaded.get().playtimeSeconds());
            assertTrue(loaded.get().claimedRewardIds().isEmpty());
            // Idempotent.
            await(storage.revokeAllClaims(UUID_A));
        } finally {
            storage.close();
        }
    }

    @Test
    void schemaVersionStampedOnInit(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        storage.close();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            assertTrue(rs.next());
            assertEquals(SQLiteStorage.SCHEMA_VERSION, rs.getInt(1));
        }
    }

    @Test
    void newerSchemaVersionRefusesToOpen(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        storage.close();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA user_version = " + (SQLiteStorage.SCHEMA_VERSION + 99));
        }
        try {
            new SQLiteStorage(db, LOG);
            fail("newer schema must refuse to open");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("newer"));
        }
    }
}
