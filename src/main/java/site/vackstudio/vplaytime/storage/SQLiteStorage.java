package site.vackstudio.vplaytime.storage;

import site.vackstudio.vplaytime.model.StoredData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite {@link Storage}. One connection, one {@link StorageExecutor} thread.
 *
 * <p>Pragmas (reasoning documented, not copied blindly):
 * <ul>
 *   <li>{@code busy_timeout=5000} — brief waits instead of instant SQLITE_BUSY
 *       when a checkpoint or another process holds a lock.</li>
 *   <li>{@code journal_mode=WAL} — readers never block the single writer and
 *       crash recovery is checkpoint-safe. WAL sidecars live next to data.db;
 *       checkpointed and removed on clean close.</li>
 *   <li>{@code synchronous=NORMAL} — with WAL this is durable (frames sync on
 *       checkpoint); FULL would only add fsync latency for no crash-safety
 *       gain under WAL.</li>
 *   <li>{@code foreign_keys=ON} — keeps future {@code claims} rows consistent
 *       with {@code players}.</li>
 * </ul>
 *
 * <p>All statements are {@link PreparedStatement}s; UUIDs are bound values,
 * never concatenated. Every JDBC resource is closed via try-with-resources.
 * Transactions commit explicitly; any failure rolls back and the in-memory
 * state keeps its dirty flag, so a failed write is retried later instead of
 * silently lost.
 */
public final class SQLiteStorage implements Storage {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;
    /** Current schema generation. Bump only with a real migration. */
    static final int SCHEMA_VERSION = 1;

    private final StorageExecutor executor;
    private final Connection connection;
    private final Logger logger;

    /**
     * Opens (creating if needed) the database and prepares the schema.
     *
     * @throws IllegalStateException if the database cannot be opened or the
     *         schema cannot be created/validated. Existing files are never
     *         deleted or recreated.
     */
    public SQLiteStorage(Path databaseFile, Logger logger) {
        this.logger = logger;
        Connection opened = null;
        try {
            Files.createDirectories(databaseFile.getParent());
            opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
            this.connection = opened;
            configure(); // auto-commit still on: journal_mode cannot change inside a transaction
            this.connection.setAutoCommit(false);
            createSchema();
            validateSchema();
            checkSchemaVersion();
        } catch (Exception ex) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (SQLException ignored) {
                    // Best effort: init already failed, don't mask the cause.
                }
            }
            throw new IllegalStateException("Cannot initialize SQLite storage at " + databaseFile + ": " + ex.getMessage(), ex);
        }
        this.executor = new StorageExecutor(logger);
        logger.info("SQLite storage ready at " + databaseFile);
    }

    private void configure() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA busy_timeout = 5000");
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
            stmt.execute("PRAGMA foreign_keys = ON");
        }
    }

    private void createSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS players ("
                    + "uuid TEXT PRIMARY KEY, "
                    + "playtime_seconds INTEGER NOT NULL DEFAULT 0)");
            // Created now so Phase 4 needs no migration. Never written in Phase 3.
            stmt.execute("CREATE TABLE IF NOT EXISTS claims ("
                    + "uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE, "
                    + "reward_id TEXT NOT NULL, "
                    + "claimed_at INTEGER NOT NULL, "
                    + "PRIMARY KEY (uuid, reward_id))");
        }
        connection.commit();
    }

    private void validateSchema() throws SQLException {
        Set<String> tables = new HashSet<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('players', 'claims')")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        if (!tables.contains("players") || !tables.contains("claims")) {
            throw new SQLException("required tables missing after schema creation: " + tables);
        }
        connection.commit(); // release the read transaction opened by validation
    }

    /**
     * Lightweight schema generation marker via {@code PRAGMA user_version}.
     * Version 0 (pre-versioning databases with the v1 tables) is stamped to 1.
     * Anything newer than this build refuses to open rather than risk
     * misreading data. Nothing is ever deleted or recreated.
     */
    private void checkSchemaVersion() throws SQLException {
        int version;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            version = rs.next() ? rs.getInt(1) : 0;
        }
        if (version > SCHEMA_VERSION) {
            throw new SQLException("database schema version " + version
                    + " is newer than this build supports (" + SCHEMA_VERSION + "); refusing to open");
        }
        if (version < SCHEMA_VERSION) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            }
        }
        connection.commit();
    }

    @Override
    public CompletableFuture<Optional<StoredData>> loadPlayer(UUID uuid) {
        return executor.submit(() -> {
            long playtime = 0;
            boolean found = false;
            try (PreparedStatement stmt = connection.prepareStatement(
                    "SELECT playtime_seconds FROM players WHERE uuid = ?")) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        found = true;
                        playtime = rs.getLong(1);
                    }
                }
            }
            Set<String> claimed = new HashSet<>();
            try (PreparedStatement stmt = connection.prepareStatement(
                    "SELECT reward_id FROM claims WHERE uuid = ?")) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        claimed.add(rs.getString(1));
                    }
                }
            }
            connection.commit();
            if (!found) {
                return Optional.empty();
            }
            if (playtime < 0) {
                logger.log(Level.WARNING, "Negative stored playtime (" + playtime
                        + ") for " + uuid + "; treating as 0.");
                playtime = 0;
            }
            return Optional.of(new StoredData(uuid, playtime, claimed, 0L));
        });
    }

    @Override
    public CompletableFuture<Void> savePlayer(StoredData snapshot) {
        return executor.submit(() -> {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO players(uuid, playtime_seconds) VALUES (?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET playtime_seconds = excluded.playtime_seconds")) {
                stmt.setString(1, snapshot.uuid().toString());
                stmt.setLong(2, snapshot.playtimeSeconds());
                stmt.executeUpdate();
                // NOTE: claims table intentionally untouched. A playtime save must
                // never wipe claim rows; claims get their own save path in Phase 4.
                connection.commit();
                return null;
            } catch (SQLException ex) {
                rollbackQuietly();
                logger.log(Level.SEVERE, "Failed to save player " + snapshot.uuid() + ": " + ex.getMessage());
                throw ex;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> recordClaim(StoredData snapshot, String rewardId, long claimedAtEpochSeconds) {
        return executor.submit(() -> {
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO players(uuid, playtime_seconds) VALUES (?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET playtime_seconds = excluded.playtime_seconds");
                 PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO claims(uuid, reward_id, claimed_at) VALUES (?, ?, ?) "
                            + "ON CONFLICT(uuid, reward_id) DO NOTHING")) {
                upsert.setString(1, snapshot.uuid().toString());
                upsert.setLong(2, snapshot.playtimeSeconds());
                upsert.executeUpdate();
                insert.setString(1, snapshot.uuid().toString());
                insert.setString(2, rewardId);
                insert.setLong(3, claimedAtEpochSeconds);
                boolean inserted = insert.executeUpdate() == 1;
                connection.commit();
                return inserted;
            } catch (SQLException ex) {
                rollbackQuietly();
                logger.log(Level.SEVERE, "Failed to record claim " + rewardId
                        + " for " + snapshot.uuid() + ": " + ex.getMessage());
                throw ex;
            }
        });
    }

    @Override
    public CompletableFuture<Void> revokeClaim(UUID uuid, String rewardId) {
        return executor.submit(() -> {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM claims WHERE uuid = ? AND reward_id = ?")) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, rewardId);
                stmt.executeUpdate();
                connection.commit();
                return null;
            } catch (SQLException ex) {
                rollbackQuietly();
                logger.log(Level.SEVERE, "Failed to revoke claim " + rewardId
                        + " for " + uuid + ": " + ex.getMessage());
                throw ex;
            }
        });
    }

    @Override
    public CompletableFuture<Void> revokeAllClaims(UUID uuid) {
        return executor.submit(() -> {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM claims WHERE uuid = ?")) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
                connection.commit();
                return null;
            } catch (SQLException ex) {
                rollbackQuietly();
                logger.log(Level.SEVERE, "Failed to revoke all claims for " + uuid + ": " + ex.getMessage());
                throw ex;
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        rollbackQuietly(); // release any dangling read transaction so checkpoint can lock
        try {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        } catch (SQLException ex) {
            // Non-fatal: frames stay in the WAL and are checkpointed on next open.
            logger.log(Level.WARNING, "WAL checkpoint on close did not complete: " + ex.getMessage());
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            logger.log(Level.SEVERE, "Error closing SQLite connection: " + ex.getMessage());
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException rollbackEx) {
            logger.log(Level.SEVERE, "Rollback failed: " + rollbackEx.getMessage());
        }
    }
}
