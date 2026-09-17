package site.vackstudio.vplaytime.admin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vplaytime.config.MenuRegistry;
import site.vackstudio.vplaytime.playtime.FakeTimeSource;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.reward.RewardManager;
import site.vackstudio.vplaytime.storage.SQLiteStorage;

import java.nio.file.Path;
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
 * Admin operations: info, single reset, reset-all, online and offline.
 */
class AdminServiceTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");

    private static final String CONFIG = """
            menus:
              main:
                name: M
                order: 1
                title: T
                rows: 3
                sounds: {}
                rewards:
                  reward_1:
                    slot: 11
                    required-seconds: 1800
                    display:
                      name: R1
                      locked: {material: STONE, lore: []}
                      claimable: {material: STONE, lore: []}
                      claimed: {material: STONE, lore: []}
                    actions:
                      - type: item
                        material: DIAMOND
                        amount: 1
                  reward_2:
                    slot: 13
                    required-seconds: 3600
                    display:
                      name: R2
                      locked: {material: STONE, lore: []}
                      claimable: {material: STONE, lore: []}
                      claimed: {material: STONE, lore: []}
                    actions:
                      - type: item
                        material: GOLD_INGOT
                        amount: 1
            """;

    static final class Harness implements AutoCloseable {
        final FakeTimeSource clock = new FakeTimeSource(50_000L);
        final SQLiteStorage storage;
        final PlaytimeManager playtime;
        final RewardManager rewards = new RewardManager(LOG);
        final AdminService admin;

        Harness(Path db) throws Exception {
            this.storage = new SQLiteStorage(db, LOG);
            this.playtime = new PlaytimeManager(clock, storage);
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(CONFIG);
            rewards.load(MenuRegistry.parse(config.getConfigurationSection("menus")));
            this.admin = new AdminService(playtime, rewards, storage, clock, LOG);
        }

        void claimBoth(UUID uuid) {
            // Mirrors production: memory reserve + durable record per claim.
            var data = playtime.find(uuid).orElseThrow();
            data.tryClaim("reward_1");
            await(storage.recordClaim(data.snapshot(), "reward_1", clock.epochSeconds()));
            data.tryClaim("reward_2");
            await(storage.recordClaim(data.snapshot(), "reward_2", clock.epochSeconds()));
        }

        @Override
        public void close() {
            playtime.shutdown();
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return fail("future did not complete: " + ex.getMessage());
        }
    }

    @Test
    void infoOnlineUsesMemory(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            await(h.playtime.handleJoin(UUID_A));
            h.clock.advance(600L);
            h.claimBoth(UUID_A);

            AdminService.PlayerInfo info = await(h.admin.info(UUID_A));
            assertTrue(info.found());
            assertTrue(info.online());
            assertEquals(0L, info.storedSeconds());
            assertEquals(600L, info.effectiveSeconds());
            assertEquals(Set.of("reward_1", "reward_2"), info.claimed());
            assertEquals(2, info.rewardCount());
        }
    }

    @Test
    void infoOfflineUsesStorage(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("data.db");
        try (Harness h = new Harness(db)) {
            await(h.playtime.handleJoin(UUID_A));
            h.clock.advance(600L);
            h.claimBoth(UUID_A);
            h.playtime.handleQuit(UUID_A);
        }
        try (Harness h = new Harness(db)) {
            AdminService.PlayerInfo info = await(h.admin.info(UUID_A));
            assertTrue(info.found());
            assertFalse(info.online());
            assertEquals(600L, info.storedSeconds());
            assertEquals(600L, info.effectiveSeconds());
            assertEquals(Set.of("reward_1", "reward_2"), info.claimed());
        }
    }

    @Test
    void infoUnknownPlayerNotFound(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            AdminService.PlayerInfo info = await(h.admin.info(UUID_A));
            assertFalse(info.found());
        }
    }

    @Test
    void resetOnlineThenReclaim(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            await(h.playtime.handleJoin(UUID_A));
            h.clock.advance(100L);
            h.claimBoth(UUID_A);

            AdminService.ResetOutcome outcome = await(h.admin.reset(UUID_A, "reward_1"));
            assertTrue(outcome.ok());
            assertTrue(outcome.hadClaim());
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"));
            assertTrue(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_2"));
            // Playtime untouched: 100s still in the open session.
            assertEquals(0L, h.playtime.find(UUID_A).orElseThrow().getStoredPlaytimeSeconds());
            assertEquals(100L, h.playtime.effectivePlaytimeSeconds(UUID_A));
            // Claim again works.
            assertTrue(h.playtime.find(UUID_A).orElseThrow().tryClaim("reward_1"));
        }
    }

    @Test
    void resetOfflineThenReconnect(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("data.db");
        try (Harness h = new Harness(db)) {
            await(h.playtime.handleJoin(UUID_A));
            h.clock.advance(100L);
            h.claimBoth(UUID_A);
            h.playtime.handleQuit(UUID_A);
        }
        try (Harness h = new Harness(db)) {
            AdminService.ResetOutcome outcome = await(h.admin.reset(UUID_A, "reward_1"));
            assertTrue(outcome.ok());
            assertTrue(outcome.hadClaim());
            // Reconnect: reset claim stays gone, the other survives.
            await(h.playtime.handleJoin(UUID_A));
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"));
            assertTrue(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_2"));
        }
    }

    @Test
    void resetUnknownRewardFails(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            AdminService.ResetOutcome outcome = await(h.admin.reset(UUID_A, "nope"));
            assertFalse(outcome.ok());
        }
    }

    @Test
    void resetWithoutClaimIsIdempotent(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            await(h.playtime.handleJoin(UUID_A));
            AdminService.ResetOutcome outcome = await(h.admin.reset(UUID_A, "reward_1"));
            assertTrue(outcome.ok());
            assertFalse(outcome.hadClaim());
        }
    }

    @Test
    void resetAllOnlineKeepsPlaytime(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            await(h.playtime.handleJoin(UUID_A));
            h.clock.advance(100L);
            h.claimBoth(UUID_A);
            h.playtime.handleQuit(UUID_A); // bank 100s so a row exists

            AdminService.ResetOutcome outcome = await(h.admin.resetAll(UUID_A));
            assertTrue(outcome.ok());
            assertEquals(100L, await(h.storage.loadPlayer(UUID_A)).orElseThrow().playtimeSeconds(),
                    "playtime must survive reset-all");
            assertTrue(await(h.storage.loadPlayer(UUID_A)).orElseThrow().claimedRewardIds().isEmpty());
        }
    }

    @Test
    void resetAllOfflineSurvivesRestart(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("data.db");
        try (Harness h = new Harness(db)) {
            await(h.playtime.handleJoin(UUID_A));
            h.clock.advance(100L);
            h.claimBoth(UUID_A);
            h.playtime.handleQuit(UUID_A);
            AdminService.ResetOutcome outcome = await(h.admin.resetAll(UUID_A));
            assertTrue(outcome.ok());
        }
        try (Harness h = new Harness(db)) {
            await(h.playtime.handleJoin(UUID_A));
            assertTrue(h.playtime.find(UUID_A).orElseThrow().claimedRewardIds().isEmpty());
            assertEquals(100L, h.playtime.find(UUID_A).orElseThrow().getStoredPlaytimeSeconds());
        }
    }

    @Test
    void resetOnBrokenStorageFails(@TempDir Path dir) throws Exception {
        Harness h = new Harness(dir.resolve("t.db"));
        h.storage.close();
        try {
            AdminService.ResetOutcome outcome = await(h.admin.reset(UUID_A, "reward_1"));
            assertFalse(outcome.ok());
        } finally {
            h.playtime.shutdown();
        }
    }

    /**
     * A claim landing between the revoke and the verification load must
     * survive: memory converges to the durable truth instead of wiping it.
     */
    @Test
    void resetKeepsConcurrentReclaim(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        SQLiteStorage real = new SQLiteStorage(db, LOG);
        ReclaimingStorage storage = new ReclaimingStorage(real);
        FakeTimeSource clock = new FakeTimeSource(50_000L);
        PlaytimeManager playtime = new PlaytimeManager(clock, storage);
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(CONFIG);
        RewardManager rewards = new RewardManager(LOG);
        rewards.load(MenuRegistry.parse(config.getConfigurationSection("menus")));
        AdminService admin = new AdminService(playtime, rewards, storage, clock, LOG);
        try {
            await(playtime.handleJoin(UUID_A));
            var data = playtime.find(UUID_A).orElseThrow();
            data.tryClaim("reward_1");

            AdminService.ResetOutcome outcome = await(admin.reset(UUID_A, "reward_1"));
            assertTrue(outcome.ok());
            assertTrue(outcome.hadClaim());
            assertTrue(playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"),
                    "fresh durable claim wins over the reset");
            assertTrue(await(real.loadPlayer(UUID_A)).orElseThrow().claimedRewardIds().contains("reward_1"));
        } finally {
            playtime.shutdown();
            real.close();
        }
    }

    /** Simulates a rival claim committing immediately after every revoke. */
    static final class ReclaimingStorage implements site.vackstudio.vplaytime.storage.Storage {
        private final SQLiteStorage delegate;

        ReclaimingStorage(SQLiteStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<java.util.Optional<site.vackstudio.vplaytime.model.StoredData>> loadPlayer(UUID uuid) {
            return delegate.loadPlayer(uuid);
        }

        @Override
        public CompletableFuture<Void> savePlayer(site.vackstudio.vplaytime.model.StoredData snapshot) {
            return delegate.savePlayer(snapshot);
        }

        @Override
        public CompletableFuture<Boolean> recordClaim(
                site.vackstudio.vplaytime.model.StoredData snapshot, String rewardId, long claimedAt) {
            return delegate.recordClaim(snapshot, rewardId, claimedAt);
        }

        @Override
        public CompletableFuture<Void> revokeClaim(UUID uuid, String rewardId) {
            return delegate.revokeClaim(uuid, rewardId).thenCompose(ignored ->
                    delegate.recordClaim(
                            new site.vackstudio.vplaytime.model.StoredData(uuid, 0L, Set.of(), 0L),
                            rewardId, 1L).thenApply(inserted -> null));
        }

        @Override
        public CompletableFuture<Void> revokeAllClaims(UUID uuid) {
            return delegate.revokeAllClaims(uuid);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
