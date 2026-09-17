package site.vackstudio.vplaytime.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DatabaseMigrator: legacy {@code data.db} → {@code data/data.db}.
 */
class DatabaseMigratorTest {

    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");

    private static void createSchema(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        // Schema created by the constructor; close releases the file.
        open(file).close();
    }

    // SQLiteStorage is the schema owner; reuse it so tests never drift.
    private static SQLiteStorage open(Path file) {
        return new SQLiteStorage(file, LOG);
    }

    private static void insertPlayer(Path file, UUID uuid, long seconds, String reward) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO players(uuid, playtime_seconds) VALUES ('" + uuid + "', "
                    + seconds + ")");
            if (reward != null) {
                statement.executeUpdate("INSERT INTO claims(uuid, reward_id, claimed_at) VALUES ('" + uuid
                        + "', '" + reward + "', 1)");
            }
        }
    }

    private static long count(Path file, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getLong(1) : -1;
        }
    }

    @Test
    void freshStartReturnsNewPath(@TempDir Path folder) {
        Path resolved = DatabaseMigrator.resolveDatabaseFile(folder, LOG);
        assertEquals(folder.resolve("data").resolve("data.db"), resolved);
        assertTrue(Files.isDirectory(folder.resolve("data")));
    }

    @Test
    void legacyOnlyMigratesAndPreservesData(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("data.db");
        createSchema(legacy);
        UUID uuid = UUID.randomUUID();
        insertPlayer(legacy, uuid, 5_000L, "reward_1");

        Path resolved = DatabaseMigrator.resolveDatabaseFile(folder, LOG);

        Path next = folder.resolve("data").resolve("data.db");
        Path marker = folder.resolve("data").resolve(".migrated-from-legacy");
        assertEquals(next, resolved);
        assertTrue(Files.isRegularFile(next), "migrated copy must exist");
        assertTrue(Files.isRegularFile(marker), "migration marker must exist");
        assertTrue(Files.isRegularFile(legacy), "original must be kept as backup");
        assertEquals(1, count(next, "players"));
        assertEquals(1, count(next, "claims"));
        // Second startup uses the migrated file without touching the legacy one.
        assertEquals(next, DatabaseMigrator.resolveDatabaseFile(folder, LOG));
        assertEquals(1, count(next, "players"));
    }

    @Test
    void newOnlyUsesNew(@TempDir Path folder) throws Exception {
        Path next = folder.resolve("data").resolve("data.db");
        createSchema(next);

        assertEquals(next, DatabaseMigrator.resolveDatabaseFile(folder, LOG));
    }

    @Test
    void conflictBothWithDataThrows(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("data.db");
        Path next = folder.resolve("data").resolve("data.db");
        createSchema(legacy);
        createSchema(next);
        insertPlayer(legacy, UUID.randomUUID(), 100L, null);
        insertPlayer(next, UUID.randomUUID(), 200L, null);

        assertThrows(IllegalStateException.class,
                () -> DatabaseMigrator.resolveDatabaseFile(folder, LOG));
        // Neither database destroyed.
        assertEquals(1, count(legacy, "players"));
        assertEquals(1, count(next, "players"));
    }

    @Test
    void emptyLegacyBesideNewIsNoConflict(@TempDir Path folder) throws Exception {
        Path legacy = folder.resolve("data.db");
        Path next = folder.resolve("data").resolve("data.db");
        createSchema(legacy);
        createSchema(next);
        insertPlayer(next, UUID.randomUUID(), 200L, null);

        assertEquals(next, DatabaseMigrator.resolveDatabaseFile(folder, LOG));
    }
}
