package site.vackstudio.vplaytime.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Resolves the database file, migrating the legacy location once.
 *
 * <p>Phase 9 moves the database from {@code plugins/VPlaytime/data.db} to
 * {@code plugins/VPlaytime/data/data.db}. Rules, in order:
 * <ol>
 *   <li>If the new file exists (and there is no conflicting legacy data),
 *       use it.</li>
 *   <li>If only the legacy file exists, copy it (never move/delete the
 *       original first), validate the copy, write a marker, then use it.</li>
 *   <li>If both exist with real data and no migration marker, refuse to
 *       start: no silent overwrite, no silent merge — the console explains
 *       how to resolve it.</li>
 * </ol>
 */
public final class DatabaseMigrator {

    static final String LEGACY_NAME = "data.db";
    static final String DATA_DIR = "data";
    static final String MARKER_NAME = ".migrated-from-legacy";

    private DatabaseMigrator() {
    }

    /**
     * @param pluginFolder {@code plugins/VPlaytime}
     * @return path of the database file to open
     * @throws IllegalStateException when migration is unsafe or impossible
     */
    public static Path resolveDatabaseFile(Path pluginFolder, Logger logger) {
        try {
            Files.createDirectories(pluginFolder.resolve(DATA_DIR));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create data directory: " + ex.getMessage(), ex);
        }
        Path next = pluginFolder.resolve(DATA_DIR).resolve(LEGACY_NAME);
        Path legacy = pluginFolder.resolve(LEGACY_NAME);
        Path marker = pluginFolder.resolve(DATA_DIR).resolve(MARKER_NAME);

        boolean nextExists = Files.isRegularFile(next);
        boolean legacyExists = Files.isRegularFile(legacy);

        if (nextExists) {
            if (!legacyExists || Files.exists(marker) || isEmptyDatabase(legacy, logger)) {
                return next;
            }
            throw new IllegalStateException(
                    "Both '" + DATA_DIR + "/" + LEGACY_NAME + "' and legacy '" + LEGACY_NAME
                            + "' contain data and no migration marker exists. VPlaytime will not guess which one "
                            + "is yours. Keep the file you want as '" + DATA_DIR + "/" + LEGACY_NAME
                            + "', move the other one aside, then restart.");
        }
        if (legacyExists) {
            migrateCopy(legacy, next, logger);
            try {
                Files.writeString(marker, "migrated");
            } catch (IOException ex) {
                throw new IllegalStateException("Migration copy succeeded but the marker cannot be written: "
                        + ex.getMessage(), ex);
            }
            logger.info("Migrated legacy database to " + DATA_DIR + "/" + LEGACY_NAME
                    + " (original kept at " + LEGACY_NAME + "; it is now a backup and is no longer read).");
            return next;
        }
        return next;
    }

    private static void migrateCopy(Path legacy, Path next, Logger logger) {
        try {
            Files.copy(legacy, next);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot copy legacy database to " + DATA_DIR + "/" + LEGACY_NAME
                    + ": " + ex.getMessage(), ex);
        }
        if (!hasExpectedTables(next)) {
            try {
                Files.deleteIfExists(next);
            } catch (IOException ignored) {
                // Best effort: the copy is unusable; a fresh retry beats a corrupt shadow.
            }
            throw new IllegalStateException("Legacy database copy failed validation (expected tables missing); "
                    + "the original '" + LEGACY_NAME + "' was left untouched.");
        }
        logger.info("Legacy database copy validated.");
    }

    /** True when the file has no players and no claims (safe to ignore). */
    static boolean isEmptyDatabase(Path file, Logger logger) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            if (!hasExpectedTables(file)) {
                return true;
            }
            return count(statement, "players") == 0 && count(statement, "claims") == 0;
        } catch (SQLException ex) {
            logger.warning("Cannot inspect legacy database, treating it as non-empty: " + ex.getMessage());
            return false;
        }
    }

    private static boolean hasExpectedTables(Path file) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                ResultSet tables = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            Set<String> names = new HashSet<>();
            while (tables.next()) {
                names.add(tables.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT));
            }
            return names.contains("players") && names.contains("claims");
        } catch (SQLException ex) {
            return false;
        }
    }

    private static long count(Statement statement, String table) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getLong(1) : 0;
        } catch (SQLException missing) {
            return 0;
        }
    }
}
