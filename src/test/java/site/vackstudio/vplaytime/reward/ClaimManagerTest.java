package site.vackstudio.vplaytime.reward;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vplaytime.config.MenuRegistry;
import site.vackstudio.vplaytime.model.ClaimResult;
import site.vackstudio.vplaytime.model.RewardAction;
import site.vackstudio.vplaytime.playtime.FakeTimeSource;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.storage.SQLiteStorage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end claim behavior: validation, atomicity, delivery, durability.
 * Uses real SQLite (temp DBs), an inline player scheduler and a recording
 * fake target — no server needed.
 */
class ClaimManagerTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");

    /** Shared by ClaimedStateTest (same package): one valid menu set. */
    static final String CONFIG = """
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
                      name: "<white>Reward I"
                      locked:
                        material: RED_STAINED_GLASS_PANE
                        lore: []
                      claimable:
                        material: GRAY_STAINED_GLASS_PANE
                        lore: []
                      claimed:
                        material: LIME_STAINED_GLASS_PANE
                        lore: []
                    actions:
                      - type: item
                        material: DIAMOND
                        amount: 10
                  reward_2:
                    slot: 13
                    required-seconds: 3600
                    display:
                      name: "<white>Reward II"
                      locked:
                        material: RED_STAINED_GLASS_PANE
                        lore: []
                      claimable:
                        material: GRAY_STAINED_GLASS_PANE
                        lore: []
                      claimed:
                        material: LIME_STAINED_GLASS_PANE
                        lore: []
                    actions:
                      - type: item
                        material: GOLD_INGOT
                        amount: 5
                  reward_3:
                    slot: 15
                    required-seconds: 100
                    display:
                      name: "<white>Reward III"
                      locked:
                        material: RED_STAINED_GLASS_PANE
                        lore: []
                      claimable:
                        material: GRAY_STAINED_GLASS_PANE
                        lore: []
                      claimed:
                        material: LIME_STAINED_GLASS_PANE
                        lore: []
                    actions:
                      - type: command
                        command: "say Thanks %player%|%uuid%|%claim_id%"
                  reward_multi:
                    slot: 16
                    required-seconds: 100
                    display:
                      name: "<white>Reward Multi"
                      locked:
                        material: RED_STAINED_GLASS_PANE
                        lore: []
                      claimable:
                        material: GRAY_STAINED_GLASS_PANE
                        lore: []
                      claimed:
                        material: LIME_STAINED_GLASS_PANE
                        lore: []
                    actions:
                      - type: item
                        material: DIAMOND
                        amount: 10
                      - type: item
                        material: GOLD_INGOT
                        amount: 60
                  reward_mixed:
                    slot: 17
                    required-seconds: 100
                    display:
                      name: "<white>Reward Mixed"
                      locked:
                        material: RED_STAINED_GLASS_PANE
                        lore: []
                      claimable:
                        material: GRAY_STAINED_GLASS_PANE
                        lore: []
                      claimed:
                        material: LIME_STAINED_GLASS_PANE
                        lore: []
                    actions:
                      - type: command
                        command: "say First %player%"
                      - type: item
                        material: EMERALD
                        amount: 3
                      - type: command
                        command: "say Second %player%"
            """;

    /** Slot-based ClaimTarget with injectable failures. Models a 36-slot inventory. */
    static final class FakeTarget implements ClaimTarget {
        final UUID uuid;
        final String name;
        final Material[] slotMat = new Material[36];
        final int[] slotAmt = new int[36];
        final List<String> commands = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicInteger giveCalls = new AtomicInteger();
        final AtomicInteger canAcceptCalls = new AtomicInteger();
        volatile boolean acceptSpace = true;
        volatile boolean failGive;
        volatile boolean failCommand;
        volatile boolean online = true;
        /** When >= 0, that canAccept call (1-based) fails: simulates change mid-claim. */
        volatile int failCanAcceptOnCall = -1;

        FakeTarget(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        @Override public UUID uuid() { return uuid; }
        @Override public String name() { return name; }

        List<ItemGrant.SlotView> views() {
            List<ItemGrant.SlotView> views = new ArrayList<>(36);
            for (int i = 0; i < 36; i++) {
                views.add(new ItemGrant.SlotView(slotMat[i], slotAmt[i], 64));
            }
            return views;
        }

        static List<RewardAction.ItemAction> itemActions(List<RewardAction> actions) {
            List<RewardAction.ItemAction> items = new ArrayList<>();
            for (RewardAction action : actions) {
                if (action instanceof RewardAction.ItemAction item) {
                    items.add(item);
                }
            }
            return items;
        }

        @Override
        public boolean canAccept(List<RewardAction> actions) {
            int call = canAcceptCalls.incrementAndGet();
            if (!online || !acceptSpace || call == failCanAcceptOnCall) {
                return false;
            }
            List<RewardAction.ItemAction> items = itemActions(actions);
            if (items.isEmpty()) {
                return true;
            }
            return ItemGrant.plan(views(), items, mat -> 64).isPresent();
        }

        @Override
        public boolean giveItem(Material material, int amount) {
            return giveItems(List.of(new RewardAction.ItemAction(material, amount))) == -1;
        }

        @Override
        public int giveItems(List<RewardAction.ItemAction> items) {
            giveCalls.incrementAndGet();
            if (items.isEmpty()) {
                return -1;
            }
            if (!online || failGive) {
                return 0;
            }
            // ONE combined plan like production: everything fits or nothing.
            Optional<List<ItemGrant.Placement>> plan =
                    ItemGrant.plan(views(), items, mat -> 64);
            if (plan.isEmpty()) {
                return 0;
            }
            for (ItemGrant.Placement placement : plan.get()) {
                slotMat[placement.slot()] = placement.material();
                slotAmt[placement.slot()] = placement.newAmount();
            }
            for (RewardAction.ItemAction item : items) {
                events.add("item:" + item.material());
            }
            return -1;
        }

        @Override
        public boolean runCommand(String command) {
            if (!online || failCommand) {
                return false;
            }
            commands.add(command);
            events.add("cmd:" + command);
            return true;
        }

        void fillSlots(int count, Material material, int amount) {
            for (int i = 0; i < count && i < 36; i++) {
                slotMat[i] = material;
                slotAmt[i] = amount;
            }
        }

        int itemCount(Material material) {            int total = 0;
            for (int i = 0; i < 36; i++) {
                if (material.equals(slotMat[i])) {
                    total += slotAmt[i];
                }
            }
            return total;
        }

        int totalItems() {
            int total = 0;
            for (int amt : slotAmt) {
                total += amt;
            }
            return total;
        }

        int usedSlots() {
            int used = 0;
            for (int amt : slotAmt) {
                if (amt > 0) used++;
            }
            return used;
        }
    }

    static final class Harness implements AutoCloseable {
        final FakeTimeSource clock = new FakeTimeSource(10_000L);
        final SQLiteStorage storage;
        final PlaytimeManager playtime;
        final RewardManager rewards = new RewardManager(LOG);
        final ClaimManager claims;

        Harness(Path db) throws Exception {
            this(db, (id, task) -> task.run(), (task) -> task.run());
        }

        Harness(Path db, PlayerScheduler scheduler, GlobalScheduler global) throws Exception {
            this.storage = new SQLiteStorage(db, LOG);
            this.playtime = new PlaytimeManager(clock, storage);
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(CONFIG);
            rewards.load(MenuRegistry.parse(config.getConfigurationSection("menus")));
            this.claims = new ClaimManager(rewards, playtime, storage,
                    scheduler, global, clock, LOG, false);
        }

        void join(UUID uuid) {
            await(playtime.handleJoin(uuid));
        }

        ClaimResult claim(UUID uuid, String reward, FakeTarget target) {
            return await(claims.claim(uuid, reward, target));
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
    void successfulClaimGrantsExactlyOnce(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");

            ClaimResult result = h.claim(UUID_A, "reward_1", target);

            assertEquals(ClaimResult.Status.SUCCESS, result.status());
            assertEquals(10, target.totalItems());
            assertEquals(10, target.itemCount(Material.DIAMOND));
            assertTrue(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"));
            assertEquals(1, await(h.storage.loadPlayer(UUID_A)).orElseThrow().claimedRewardIds().size());
        }
    }

    @Test
    void lockedClaimGrantsNothing(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            FakeTarget target = new FakeTarget(UUID_A, "A");

            ClaimResult result = h.claim(UUID_A, "reward_1", target);

            assertEquals(ClaimResult.Status.LOCKED, result.status());
            assertEquals(0, target.totalItems());
            assertEquals(0, target.giveCalls.get());
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"));
        }
    }

    @Test
    void unknownRewardIsNotFound(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(5_000L);
            ClaimResult result = h.claim(UUID_A, "nope", new FakeTarget(UUID_A, "A"));
            assertEquals(ClaimResult.Status.NOT_FOUND, result.status());
        }
    }

    @Test
    void untrackedPlayerIsNotLoaded(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            ClaimResult result = h.claim(UUID_A, "reward_1", new FakeTarget(UUID_A, "A"));
            assertEquals(ClaimResult.Status.NOT_LOADED, result.status());
        }
    }

    @Test
    void repeatedClaimsStayRejected(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");

            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_1", target).status());
            for (int i = 0; i < 10; i++) {
                assertEquals(ClaimResult.Status.ALREADY_CLAIMED,
                        h.claim(UUID_A, "reward_1", target).status(), "attempt " + i);
            }
            assertEquals(10, target.totalItems(), "no duplicate grants across 11 attempts");
        }
    }

    @Test
    void threeRewardsAreIndependent(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(4_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");

            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_1", target).status());
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_2", target).status());
            assertEquals(10, target.itemCount(Material.DIAMOND));
            assertEquals(5, target.itemCount(Material.GOLD_INGOT));
            assertEquals(ClaimResult.Status.ALREADY_CLAIMED, h.claim(UUID_A, "reward_1", target).status());
        }
    }

    @Test
    void concurrentClaimsGrantExactlyOnce(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");

            int attempts = 100;
            AtomicInteger success = new AtomicInteger();
            AtomicInteger already = new AtomicInteger();
            Thread[] threads = new Thread[attempts];
            for (int i = 0; i < attempts; i++) {
                threads[i] = new Thread(() -> {
                    ClaimResult r = h.claim(UUID_A, "reward_1", target);
                    if (r.status() == ClaimResult.Status.SUCCESS) success.incrementAndGet();
                    else if (r.status() == ClaimResult.Status.ALREADY_CLAIMED) already.incrementAndGet();
                    else fail("unexpected status " + r.status());
                });
                threads[i].start();
            }
            for (Thread t : threads) {
                t.join(15_000L);
            }
            assertEquals(1, success.get(), "exactly one SUCCESS");
            assertEquals(attempts - 1, already.get(), "all others ALREADY_CLAIMED");
            assertEquals(10, target.totalItems(), "exactly one grant of 10 diamonds");
            assertEquals(1, await(h.storage.loadPlayer(UUID_A)).orElseThrow().claimedRewardIds().size());
        }
    }

    @Test
    void concurrentPlayersDoNotInterfere(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            int players = 100;
            UUID[] uuids = new UUID[players];
            for (int i = 0; i < players; i++) {
                uuids[i] = new UUID(0L, i);
                h.join(uuids[i]);
            }
            h.clock.advance(2_000L);

            AtomicInteger success = new AtomicInteger();
            Thread[] threads = new Thread[players];
            for (int i = 0; i < players; i++) {
                final UUID uuid = uuids[i];
                threads[i] = new Thread(() -> {
                    FakeTarget target = new FakeTarget(uuid, "P" + uuid.getLeastSignificantBits());
                    if (h.claim(uuid, "reward_1", target).status() == ClaimResult.Status.SUCCESS
                            && target.totalItems() == 10) {
                        success.incrementAndGet();
                    }
                });
                threads[i].start();
            }
            for (Thread t : threads) {
                t.join(15_000L);
            }
            assertEquals(players, success.get(), "every player claims independently");
        }
    }

    @Test
    void fullInventoryRejectsWithoutFinalizing(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.acceptSpace = false;

            ClaimResult rejected = h.claim(UUID_A, "reward_1", target);
            assertEquals(ClaimResult.Status.REWARD_FAILED, rejected.status());
            assertEquals(0, target.totalItems());
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"),
                    "full inventory must not finalize the claim");

            target.acceptSpace = true;
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_1", target).status());
            assertEquals(10, target.totalItems());
        }
    }

    @Test
    void executionFailureRevokesForRetry(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.failGive = true;

            ClaimResult failed = h.claim(UUID_A, "reward_1", target);
            assertEquals(ClaimResult.Status.REWARD_FAILED, failed.status());
            assertEquals(0, target.totalItems());
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"),
                    "failed execution revokes the memory reservation synchronously");
            // Await the async revoke, then verify durability too.
            long deadline = System.currentTimeMillis() + 5_000L;
            boolean revoked = false;
            while (System.currentTimeMillis() < deadline) {
                if (await(h.storage.loadPlayer(UUID_A)).map(s -> s.claimedRewardIds().isEmpty()).orElse(true)) {
                    revoked = true;
                    break;
                }
                Thread.sleep(25L);
            }
            assertTrue(revoked, "no durable claim row after failed execution");

            target.failGive = false;
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_1", target).status());
            assertEquals(10, target.totalItems());
        }
    }

    @Test
    void storageFailureGrantsNothingAndAllowsRetry(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        FakeTimeSource clock = new FakeTimeSource(10_000L);
        PlaytimeManager playtime = new PlaytimeManager(clock, storage);
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(CONFIG);
        RewardManager rewards = new RewardManager(LOG);
        rewards.load(MenuRegistry.parse(config.getConfigurationSection("menus")));
        ClaimManager claims = new ClaimManager(rewards, playtime, storage,
                (id, task) -> task.run(), clock, LOG);

        await(playtime.handleJoin(UUID_A));
        clock.advance(2_000L);
        storage.close(); // durability is gone from here on

        FakeTarget target = new FakeTarget(UUID_A, "A");
        ClaimResult result = await(claims.claim(UUID_A, "reward_1", target));
        assertEquals(ClaimResult.Status.STORAGE_FAILED, result.status());
        assertEquals(0, target.totalItems(), "never grant when durability failed");
        assertFalse(playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"),
                "reservation rolled back for retry");
        playtime.shutdown();
    }

    @Test
    void claimSurvivesRestart(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("data.db");
        try (Harness h = new Harness(db)) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            assertEquals(ClaimResult.Status.SUCCESS,
                    h.claim(UUID_A, "reward_1", new FakeTarget(UUID_A, "A")).status());
        }
        try (Harness h = new Harness(db)) {
            h.join(UUID_A);
            assertTrue(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"));
            FakeTarget target = new FakeTarget(UUID_A, "A");
            assertEquals(ClaimResult.Status.ALREADY_CLAIMED, h.claim(UUID_A, "reward_1", target).status());
            assertEquals(0, target.totalItems());
        }
    }

    @Test
    void commandRewardRunsOnceWithSubstitution(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "Steve");

            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_3", target).status());
            assertEquals(1, target.commands.size());
            String[] parts = target.commands.get(0).split("\\|", -1);
            assertEquals("say Thanks Steve", parts[0]);
            assertEquals(UUID_A.toString(), parts[1]);
            // %claim_id% is a unique audit id per attempt.
            UUID.fromString(parts[2]);
        }
    }

    @Test
    void concurrentCommandRewardRunsOnce(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "Steve");

            Thread[] threads = new Thread[20];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> h.claim(UUID_A, "reward_3", target));
                threads[i].start();
            }
            for (Thread t : threads) {
                t.join(15_000L);
            }
            assertEquals(1, target.commands.size(), "no duplicated commands");
        }
    }

    @Test
    void stackMergingUsesOneSlot(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.fillSlots(1, Material.DIAMOND, 32);

            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_1", target).status());
            assertEquals(42, target.itemCount(Material.DIAMOND));
            assertEquals(1, target.usedSlots(), "merge must not consume a new slot");
        }
    }

    @Test
    void fullSlotsRejectWithoutMutation(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.fillSlots(36, Material.STONE, 64);

            ClaimResult result = h.claim(UUID_A, "reward_1", target);
            assertEquals(ClaimResult.Status.REWARD_FAILED, result.status());
            assertEquals(36 * 64, target.totalItems(), "inventory must be byte-identical");
            assertEquals(0, target.giveCalls.get(), "no delivery attempted");
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"));
        }
    }

    @Test
    void multiItemSecondOverflowRejectsAll(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.fillSlots(35, Material.STONE, 64); // one free slot: diamonds fit, 60 gold does not

            ClaimResult result = h.claim(UUID_A, "reward_multi", target);
            assertEquals(ClaimResult.Status.REWARD_FAILED, result.status());
            assertEquals(35 * 64, target.totalItems(), "no partial delivery of the first action");
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_multi"));

            // Free space: both actions deliver together.
            target.slotAmt[35] = 0;
            target.slotMat[35] = null;
            target.slotAmt[34] = 0;
            target.slotMat[34] = null;
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_multi", target).status());
            assertEquals(10, target.itemCount(Material.DIAMOND));
            assertEquals(60, target.itemCount(Material.GOLD_INGOT));
        }
    }

    @Test
    void changedInventoryBeforeDeliveryRevokes(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.failCanAcceptOnCall = 2; // first pre-check passes, delivery-time check fails

            ClaimResult result = h.claim(UUID_A, "reward_1", target);
            assertEquals(ClaimResult.Status.REWARD_FAILED, result.status());
            assertEquals(0, target.totalItems(), "nothing delivered after the late capacity failure");
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"));

            long deadline = System.currentTimeMillis() + 5_000L;
            boolean revoked = false;
            while (System.currentTimeMillis() < deadline) {
                if (await(h.storage.loadPlayer(UUID_A)).map(s -> s.claimedRewardIds().isEmpty()).orElse(true)) {
                    revoked = true;
                    break;
                }
                Thread.sleep(25L);
            }
            assertTrue(revoked, "durable row must be revoked so the player can retry");
        }
    }

    @Test
    void offlineTargetFailsSafeAndRetries(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.online = false; // disconnected before delivery

            ClaimResult result = h.claim(UUID_A, "reward_1", target);
            assertEquals(ClaimResult.Status.REWARD_FAILED, result.status());
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"));

            target.online = true; // reconnect: retry works
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_1", target).status());
            assertEquals(10, target.totalItems());
        }
    }

    @Test
    void commandFailureRevokesAndRetries(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "Steve");
            target.failCommand = true;

            ClaimResult failed = h.claim(UUID_A, "reward_3", target);
            assertEquals(ClaimResult.Status.REWARD_FAILED, failed.status());
            assertTrue(target.commands.isEmpty());
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_3"));

            target.failCommand = false;
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_3", target).status());
            assertEquals(1, target.commands.size());
        }
    }

    @Test
    void repeatExecutionFailuresTrackStreakAndResetOnSuccess(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "Steve");
            target.failCommand = true;

            assertEquals(0, h.claims.failStreak("reward_3"));
            for (int i = 1; i <= ClaimManager.FAIL_ALARM_THRESHOLD; i++) {
                assertEquals(ClaimResult.Status.REWARD_FAILED,
                        h.claim(UUID_A, "reward_3", target).status());
                assertEquals(i, h.claims.failStreak("reward_3"));
            }
            // A streak on one reward never leaks into another.
            assertEquals(0, h.claims.failStreak("reward_1"));

            target.failCommand = false;
            // Three command failures suspended the reward, so even the
            // fixed command is refused until a reload clears it.
            assertEquals(ClaimResult.Status.SUSPENDED,
                    h.claim(UUID_A, "reward_3", target).status());
            assertEquals(1, h.claims.clearSuspended());
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_3", target).status());
            assertEquals(0, h.claims.failStreak("reward_3"));
        }
    }

    @Test
    void mixedActionsExecuteInConfiguredOrder(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "Alex");

            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_mixed", target).status());
            assertEquals(List.of("say First Alex", "say Second Alex"), target.commands);
            assertEquals(3, target.itemCount(Material.EMERALD));
        }
    }

    @Test
    void itemOnlyRewardDispatchesNoCommands(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");

            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_1", target).status());
            assertTrue(target.commands.isEmpty(), "item rewards must not dispatch commands");
        }
    }

    @Test
    void schedulerThrowRevokesDurableRow(@TempDir Path dir) throws Exception {        Path db = dir.resolve("t.db");
        SQLiteStorage storage = new SQLiteStorage(db, LOG);
        FakeTimeSource clock = new FakeTimeSource(10_000L);
        PlaytimeManager playtime = new PlaytimeManager(clock, storage);
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(CONFIG);
        RewardManager rewards = new RewardManager(LOG);
        rewards.load(MenuRegistry.parse(config.getConfigurationSection("menus")));
        PlayerScheduler boom = (id, task) -> {
            throw new RuntimeException("scheduler unavailable");
        };
        ClaimManager claims = new ClaimManager(rewards, playtime, storage,
                boom, clock, LOG);
        try {
            await(playtime.handleJoin(UUID_A));
            clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");

            ClaimResult result = await(claims.claim(UUID_A, "reward_1", target));
            assertEquals(ClaimResult.Status.REWARD_FAILED, result.status());
            assertEquals(0, target.totalItems(), "execution never ran");
            assertFalse(playtime.find(UUID_A).orElseThrow().isClaimed("reward_1"));

            long deadline = System.currentTimeMillis() + 5_000L;
            boolean revoked = false;
            while (System.currentTimeMillis() < deadline) {
                if (await(storage.loadPlayer(UUID_A)).map(s -> s.claimedRewardIds().isEmpty()).orElse(true)) {
                    revoked = true;
                    break;
                }
                Thread.sleep(25L);
            }
            assertTrue(revoked, "durable row must be revoked for retry");
        } finally {
            playtime.shutdown();
        }
    }

    /** Records which scheduler each stage runs on. */
    static final class RecordingPlayerScheduler implements PlayerScheduler {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void run(UUID playerId, Runnable task) {
            calls.incrementAndGet();
            task.run();
        }
    }

    static final class RecordingGlobalScheduler implements GlobalScheduler {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> commandsSeen = new CopyOnWriteArrayList<>();

        @Override
        public void run(Runnable task) {
            calls.incrementAndGet();
            task.run();
        }
    }

    @Test
    void commandsRunOnGlobalScheduler(@TempDir Path dir) throws Exception {
        RecordingPlayerScheduler playerScheduler = new RecordingPlayerScheduler();
        RecordingGlobalScheduler globalScheduler = new RecordingGlobalScheduler();
        try (Harness h = new Harness(dir.resolve("t.db"), playerScheduler, globalScheduler)) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "Steve");

            ClaimResult result = h.claim(UUID_A, "reward_3", target);
            assertEquals(ClaimResult.Status.SUCCESS, result.status());
            assertEquals(1, globalScheduler.calls.get(), "command stage must hop to global");
            assertEquals(1, target.commands.size());
            assertTrue(target.commands.get(0).startsWith("say Thanks Steve|"));
        }
    }

    @Test
    void mixedActionsKeepConfiguredOrder(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "Alex");

            // Configured order is [command, item, command]; delivery must
            // follow it exactly (one step per context run: cmd | item | cmd).
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_mixed", target).status());
            assertEquals(List.of("cmd:say First Alex", "item:EMERALD", "cmd:say Second Alex"), target.events);
        }
    }

    @Test
    void globalSchedulerThrowRevokes(@TempDir Path dir) throws Exception {
        GlobalScheduler boom = task -> {
            throw new RuntimeException("global unavailable");
        };
        try (Harness h = new Harness(dir.resolve("t.db"), (id, task) -> task.run(), boom)) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "Steve");

            ClaimResult result = h.claim(UUID_A, "reward_3", target);
            assertEquals(ClaimResult.Status.REWARD_FAILED, result.status());
            assertTrue(target.commands.isEmpty(), "commands never ran");
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_3"));

            long deadline = System.currentTimeMillis() + 5_000L;
            boolean revoked = false;
            while (System.currentTimeMillis() < deadline) {
                if (await(h.storage.loadPlayer(UUID_A)).map(s -> s.claimedRewardIds().isEmpty()).orElse(true)) {
                    revoked = true;
                    break;
                }
                Thread.sleep(25L);
            }
            assertTrue(revoked, "durable row must be revoked for retry");
        }
    }

    @Test
    void claimThroughputBenchmark(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            // Warmup.
            for (int i = 0; i < 20; i++) {
                UUID uuid = new UUID(1L, i);
                h.join(uuid);
            }
            h.clock.advance(2_000L);

            int n = 200;
            for (int i = 0; i < n; i++) {
                h.join(new UUID(2L, i));
            }
            h.clock.advance(2_000L);
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                UUID uuid = new UUID(2L, i);
                ClaimResult r = h.claim(uuid, "reward_1", new FakeTarget(uuid, "B"));
                assertEquals(ClaimResult.Status.SUCCESS, r.status());
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            System.out.println("BENCH claim full-path: " + n + " claims in "
                    + String.format("%.2f", seconds) + "s = "
                    + String.format("%.0f", n / seconds) + " claims/s (incl. SQLite WAL insert each)");

            long vStart = System.nanoTime();
            int validations = 100_000;
            long effective = h.playtime.effectivePlaytimeSeconds(new UUID(2L, 0));
            for (int i = 0; i < validations; i++) {
                h.rewards.stateFor(h.playtime.find(new UUID(2L, 0)).orElseThrow(), "reward_1", effective);
            }
            double vSeconds = (System.nanoTime() - vStart) / 1_000_000_000.0;
            System.out.println("BENCH memory validation: " + validations + " stateFor in "
                    + String.format("%.2f", vSeconds) + "s = "
                    + String.format("%.0f", validations / vSeconds) + " validations/s (GUI hot path)");
        }
    }
}
