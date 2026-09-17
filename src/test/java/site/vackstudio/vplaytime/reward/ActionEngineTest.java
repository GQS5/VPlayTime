package site.vackstudio.vplaytime.reward;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vplaytime.config.MenuRegistry;
import site.vackstudio.vplaytime.model.ClaimResult;
import site.vackstudio.vplaytime.model.StoredData;
import site.vackstudio.vplaytime.playtime.FakeTimeSource;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.reward.ClaimManagerTest.FakeTarget;
import site.vackstudio.vplaytime.reward.ClaimManagerTest.RecordingGlobalScheduler;
import site.vackstudio.vplaytime.reward.ClaimManagerTest.RecordingPlayerScheduler;
import site.vackstudio.vplaytime.storage.SQLiteStorage;
import site.vackstudio.vplaytime.storage.Storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Multi-action engine matrix: batching, order, hop/dispatch/db counts,
 * capacity, concurrency, failure policy — plus the timing benchmark.
 */
class ActionEngineTest {

    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");
    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** Builds "r" with the given action bodies (already-indented YAML lines). */
    private static String menuWith(String rewardId, String actionLines) {
        return """
                menus:
                  main:
                    name: M
                    order: 1
                    title: T
                    rows: 6
                    sounds: {}
                    rewards:
                      %s:
                        slot: 0
                        required-seconds: 0
                        display:
                          name: R
                          locked:
                            material: STONE
                            lore: []
                          claimable:
                            material: STONE
                            lore: []
                          claimed:
                            material: STONE
                            lore: []
                        actions:
                """.formatted(rewardId) + actionLines;
    }

    private static String itemActions(Material material, int[] amounts) {
        StringBuilder yaml = new StringBuilder();
        for (int amount : amounts) {
            yaml.append("""
                            - type: item
                              material: %s
                              amount: %d
                    """.formatted(material, amount));
        }
        return yaml.toString();
    }

    /** Distinct materials so nothing merges: one slot per action. */
    private static final Material[] DISTINCT = {
            Material.DIAMOND, Material.GOLD_INGOT, Material.EMERALD, Material.IRON_INGOT,
            Material.COAL, Material.REDSTONE, Material.LAPIS_LAZULI, Material.QUARTZ,
            Material.AMETHYST_SHARD, Material.COPPER_INGOT, Material.NETHERITE_SCRAP,
            Material.NETHER_STAR, Material.ENDER_PEARL, Material.BLAZE_ROD,
            Material.GHAST_TEAR, Material.SLIME_BALL, Material.HONEY_BOTTLE,
            Material.INK_SAC, Material.GLOWSTONE_DUST, Material.SUGAR};

    private static String distinctItemActions(int count, int amount) {
        StringBuilder yaml = new StringBuilder();
        for (int i = 0; i < count; i++) {
            yaml.append("""
                            - type: item
                              material: %s
                              amount: %d
                    """.formatted(DISTINCT[i % DISTINCT.length], amount));
        }
        return yaml.toString();
    }

    /** Decorates a Storage counting durable claim operations. */
    static final class CountingStorage implements Storage {
        final Storage delegate;
        final AtomicInteger recordClaims = new AtomicInteger();
        final AtomicInteger revokeClaims = new AtomicInteger();

        CountingStorage(Storage delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<Optional<StoredData>> loadPlayer(UUID u) {
            return delegate.loadPlayer(u);
        }

        @Override
        public CompletableFuture<Void> savePlayer(StoredData d) {
            return delegate.savePlayer(d);
        }

        @Override
        public CompletableFuture<Boolean> recordClaim(StoredData d, String r, long t) {
            recordClaims.incrementAndGet();
            return delegate.recordClaim(d, r, t);
        }

        @Override
        public CompletableFuture<Void> revokeClaim(UUID u, String r) {
            revokeClaims.incrementAndGet();
            return delegate.revokeClaim(u, r);
        }

        @Override
        public CompletableFuture<Void> revokeAllClaims(UUID u) {
            return delegate.revokeAllClaims(u);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    static final class Engine implements AutoCloseable {
        final FakeTimeSource clock = new FakeTimeSource(50_000L);
        final SQLiteStorage raw;
        final CountingStorage storage;
        final PlaytimeManager playtime;
        final RewardManager rewards = new RewardManager(LOG);
        final RecordingPlayerScheduler playerScheduler = new RecordingPlayerScheduler();
        final RecordingGlobalScheduler globalScheduler = new RecordingGlobalScheduler();
        final ClaimManager claims;

        Engine(Path db, String yaml) throws Exception {
            this.raw = new SQLiteStorage(db, LOG);
            this.storage = new CountingStorage(raw);
            this.playtime = new PlaytimeManager(clock, storage);
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(yaml);
            rewards.load(MenuRegistry.parse(config.getConfigurationSection("menus")));
            this.claims = new ClaimManager(rewards, playtime, storage,
                    playerScheduler, globalScheduler, clock, LOG, false);
        }

        void join(UUID uuid) throws Exception {
            playtime.handleJoin(uuid).get(5, TimeUnit.SECONDS);
        }

        ClaimResult claim(UUID uuid, String reward, FakeTarget target) throws Exception {
            return claims.claim(uuid, reward, target).get(5, TimeUnit.SECONDS);
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

    // ---- matrix: 1 / 5 / 10 / 20 items ----

    private void assertItemBatch(int count, int amount) throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("vpt-batch");
        try (Engine engine = new Engine(dir.resolve("t.db"),
                menuWith("r", distinctItemActions(count, amount)))) {
            engine.join(UUID_A);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            long start = System.nanoTime();
            ClaimResult result = engine.claim(UUID_A, "r", target);
            long millis = (System.nanoTime() - start) / 1_000_000L;
            assertEquals(ClaimResult.Status.SUCCESS, result.status());
            assertEquals(count, target.usedSlots(), "one slot per distinct action");
            assertEquals(1, target.giveCalls.get(), "ONE batched delivery, not " + count);
            assertEquals(1, engine.storage.recordClaims.get(), "ONE durable claim op");
            assertEquals(1, engine.playerScheduler.calls.get(), "ONE player hop");
            assertEquals(0, engine.globalScheduler.calls.get(), "no global hop for items");
            assertTrue(target.commands.isEmpty(), "0 command dispatches");
            System.out.println("[benchmark] items=" + count + " took=" + millis + "ms"
                    + " giveCalls=1 recordClaims=1 playerHops=1 globalHops=0");
        }
    }

    @Test
    void oneItemBatch() throws Exception {
        assertItemBatch(1, 10);
    }

    @Test
    void fiveItemBatch() throws Exception {
        assertItemBatch(5, 10);
    }

    @Test
    void tenItemBatch() throws Exception {
        assertItemBatch(10, 10);
    }

    @Test
    void twentyItemBatch() throws Exception {
        assertItemBatch(20, 10);
    }

    // ---- merging, commands, mixed ----

    @Test
    void compatibleStacksMerge(@TempDir Path dir) throws Exception {
        try (Engine engine = new Engine(dir.resolve("t.db"),
                menuWith("r", itemActions(Material.DIAMOND, new int[]{5, 5})))) {
            engine.join(UUID_A);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            assertEquals(ClaimResult.Status.SUCCESS, engine.claim(UUID_A, "r", target).status());
            assertEquals(10, target.itemCount(Material.DIAMOND));
            assertEquals(1, target.usedSlots(), "Diamond 5+5 merges into one stack");
            assertEquals(1, target.giveCalls.get());
        }
    }

    @Test
    void tenCommandsOneGlobalBlock(@TempDir Path dir) throws Exception {
        StringBuilder actions = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            actions.append("        - type: command\n          command: \"say n").append(i).append("\"\n");
        }
        try (Engine engine = new Engine(dir.resolve("t.db"), menuWith("r", actions.toString()))) {
            engine.join(UUID_A);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            assertEquals(ClaimResult.Status.SUCCESS, engine.claim(UUID_A, "r", target).status());
            assertEquals(10, target.commands.size(), "exactly 10 dispatches");
            assertEquals(1, engine.globalScheduler.calls.get(), "ONE global block");
            assertEquals(1, engine.storage.recordClaims.get(), "ONE durable claim op");
        }
    }

    @Test
    void tenItemsPlusCommand(@TempDir Path dir) throws Exception {
        String yaml = distinctItemActions(10, 5)
                + "        - type: command\n          command: \"say done %player%\"\n";
        try (Engine engine = new Engine(dir.resolve("t.db"), menuWith("r", yaml))) {
            engine.join(UUID_A);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            assertEquals(ClaimResult.Status.SUCCESS, engine.claim(UUID_A, "r", target).status());
            assertEquals(10, target.usedSlots());
            assertEquals(List.of("say done A"), target.commands);
            assertEquals(1, engine.globalScheduler.calls.get());
        }
    }

    @Test
    void mixedOrderCostsOneHopPerRun(@TempDir Path dir) throws Exception {
        String yaml = """
                        - type: item
                          material: DIAMOND
                          amount: 1
                        - type: command
                          command: "say middle"
                        - type: item
                          material: GOLD_INGOT
                          amount: 1
                """;
        try (Engine engine = new Engine(dir.resolve("t.db"), menuWith("r", yaml))) {
            engine.join(UUID_A);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            assertEquals(ClaimResult.Status.SUCCESS, engine.claim(UUID_A, "r", target).status());
            assertEquals(List.of("item:DIAMOND", "cmd:say middle", "item:GOLD_INGOT"), target.events);
            assertEquals(2, engine.playerScheduler.calls.get(), "player,global,player = 2 player hops");
            assertEquals(1, engine.globalScheduler.calls.get(), "1 global block");
        }
    }

    // ---- capacity, failure policy ----

    @Test
    void partialCapacityFailsWholeReward(@TempDir Path dir) throws Exception {
        try (Engine engine = new Engine(dir.resolve("t.db"),
                menuWith("r", distinctItemActions(2, 64)))) {
            engine.join(UUID_A);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.fillSlots(35, Material.STONE, 64); // one free slot: first fits, second does not
            ClaimResult result = engine.claim(UUID_A, "r", target);
            assertEquals(ClaimResult.Status.REWARD_FAILED, result.status());
            assertEquals(0, target.itemCount(DISTINCT[0]), "no partial delivery");
            assertEquals(0, target.itemCount(DISTINCT[1]));
            assertTrue(!engine.playtime.find(UUID_A).orElseThrow().isClaimed("r"),
                    "reservation rolled back for retry");
            assertEquals(0, engine.storage.recordClaims.get(),
                    "fast reject never touches durability");
            assertEquals(0, engine.storage.revokeClaims.get(),
                    "nothing durable existed, nothing to revoke");
        }
    }

    @Test
    void commandFailureRevokesForRetry(@TempDir Path dir) throws Exception {
        String yaml = """
                        - type: item
                          material: DIAMOND
                          amount: 3
                        - type: command
                          command: "say boom"
                """;
        try (Engine engine = new Engine(dir.resolve("t.db"), menuWith("r", yaml))) {
            engine.join(UUID_A);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.failCommand = true;
            ClaimResult failed = engine.claim(UUID_A, "r", target);
            assertEquals(ClaimResult.Status.REWARD_FAILED, failed.status());
            assertTrue(failed.detail().contains("action 1/2"),
                    "report names reward action index, got: " + failed.detail());
            target.failCommand = false;
            ClaimResult retry = engine.claim(UUID_A, "r", target);
            assertEquals(ClaimResult.Status.SUCCESS, retry.status(), "retry works after revoke");
        }
    }

    // ---- concurrency: 100 attempts, 10-action reward -> exactly 1 grant ----

    @Test
    void concurrentSpamGrantsOnce(@TempDir Path dir) throws Exception {
        try (Engine engine = new Engine(dir.resolve("t.db"),
                menuWith("r", distinctItemActions(10, 5)))) {
            engine.join(UUID_A);
            int threads = 100;
            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch ready = new CountDownLatch(1);
            List<java.util.concurrent.Future<ClaimResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                FakeTarget target = new FakeTarget(UUID_A, "A-" + i);
                futures.add(pool.submit(() -> {
                    ready.await(5, TimeUnit.SECONDS);
                    return await(engine.claims.claim(UUID_A, "r", target));
                }));
            }
            ready.countDown();
            int success = 0;
            for (var future : futures) {
                if (future.get(10, TimeUnit.SECONDS).status() == ClaimResult.Status.SUCCESS) {
                    success++;
                }
            }
            pool.shutdownNow();
            assertEquals(1, success, "exactly one complete delivery");
            assertEquals(1, engine.storage.recordClaims.get(), "ONE durable claim op");
        }
    }

    // ---- timing benchmark (reports real numbers; asserts architecture) ----

    @Test
    void benchmarkReportsTimings() throws Exception {
        System.out.println("[benchmark] actions | claim us | giveCalls | recordClaims | playerHops | globalHops");
        for (int count : new int[]{1, 5, 10, 20}) {
            Path dir = java.nio.file.Files.createTempDirectory("vpt-bench");
            try (Engine engine = new Engine(dir.resolve("t.db"),
                    menuWith("r", distinctItemActions(count, 10)))) {
                engine.join(UUID_A);
                FakeTarget target = new FakeTarget(UUID_A, "A");
                long start = System.nanoTime();
                ClaimResult result = engine.claim(UUID_A, "r", target);
                long micros = (System.nanoTime() - start) / 1_000L;
                assertEquals(ClaimResult.Status.SUCCESS, result.status());
                System.out.println("[benchmark] " + count + " | " + micros + "us"
                        + " | giveCalls=" + target.giveCalls.get()
                        + " | recordClaims=" + engine.storage.recordClaims.get()
                        + " | playerHops=" + engine.playerScheduler.calls.get()
                        + " | globalHops=" + engine.globalScheduler.calls.get());
                assertEquals(1, target.giveCalls.get(), "one batched delivery for " + count);
            }
        }
    }
}
