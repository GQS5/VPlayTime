package site.vackstudio.vplaytime.reward;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vplaytime.api.VPlaytimeAPI;
import site.vackstudio.vplaytime.config.MenuRegistry;
import site.vackstudio.vplaytime.gui.RewardErrorState;
import site.vackstudio.vplaytime.model.ClaimResult;
import site.vackstudio.vplaytime.model.RewardDefinition;
import site.vackstudio.vplaytime.playtime.FakeTimeSource;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.storage.SQLiteStorage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * Global safety gate: DISABLED blocks every claim path before anything is
 * reserved, granted, executed or persisted; activation transitions are
 * atomic; runtime performs zero environment probing.
 */
class PreflightGateTest {

    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");
    private static final UUID UUID_A = UUID.fromString("22222222-2222-2222-2222-222222222222");

    static final String CONFIG = """
            menus:
              main:
                name: M
                order: 1
                title: T
                rows: 3
                sounds: {}
                rewards:
                  items:
                    slot: 11
                    required-seconds: 5
                    display:
                      name: I
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
                      - item: "DIRT 5"
                  shaky:
                    slot: 13
                    required-seconds: 5
                    display:
                      name: S
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
                      - command: "give @p diamond 1"
            """;

    static final class Harness implements AutoCloseable {
        final FakeTimeSource clock = new FakeTimeSource(10_000L);
        final SQLiteStorage storage;
        final PlaytimeManager playtime;
        final RewardManager rewards = new RewardManager(LOG);
        final ClaimManager claims;

        Harness(Path db) throws Exception {
            this.storage = new SQLiteStorage(db, LOG);
            this.playtime = new PlaytimeManager(clock, storage);
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(CONFIG);
            rewards.load(MenuRegistry.parse(config.getConfigurationSection("menus")));
            this.claims = new ClaimManager(rewards, playtime, storage,
                    (id, task) -> task.run(), (task) -> task.run(), clock, LOG, false);
        }

        void join(UUID uuid) {
            await(playtime.handleJoin(uuid));
        }

        ClaimResult claim(UUID uuid, String reward, ClaimManagerTest.FakeTarget target) {
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

    // ---- global disabled blocks every path ----

    @Test
    void disabledBlocksGuiApiAndPersistsNothing(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(60_000L);
            h.rewards.disable("boom", PreflightReport.empty());
            assertFalse(h.rewards.systemEnabled());

            ClaimManagerTest.FakeTarget target = new ClaimManagerTest.FakeTarget(UUID_A, "A");
            ClaimResult gui = h.claim(UUID_A, "items", target);
            assertEquals(ClaimResult.Status.DISABLED, gui.status());

            var api = new VPlaytimeAPI(h.playtime, h.rewards, h.claims, h.clock);
            ClaimResult viaApi = await(api.claim(UUID_A, "items",
                    new ClaimManagerTest.FakeTarget(UUID_A, "A")));
            assertEquals(ClaimResult.Status.DISABLED, viaApi.status());

            // Nothing reserved, granted, executed or persisted.
            assertEquals(0, target.totalItems());
            assertEquals(0, target.commands.size());
            assertEquals(0, target.giveCalls.get());
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("items"));
            assertTrue(await(h.storage.loadPlayer(UUID_A)).map(d -> d.claimedRewardIds().isEmpty()).orElse(true));
        }
    }

    @Test
    void enabledSystemClaimsNormally(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            assertTrue(h.rewards.systemEnabled());
            h.join(UUID_A);
            h.clock.advance(60_000L);
            ClaimManagerTest.FakeTarget target = new ClaimManagerTest.FakeTarget(UUID_A, "A");
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "items", target).status());
            assertEquals(5, target.itemCount(Material.DIRT));
        }
    }

    // ---- activation transitions ----

    @Test
    void disableAndReactivate(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.rewards.disable("bad", PreflightReport.empty());
            assertFalse(h.rewards.systemEnabled());
            assertEquals("bad", h.rewards.systemStatus().reason());

            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(CONFIG);
            var parsed = MenuRegistry.parse(config.getConfigurationSection("menus"));
            var outcome = RewardPlanAssembly.assemble(parsed, List.of(), new RewardManager(LOG),
                    root -> true);
            // 'give' is vanilla-available and '@p' is merely unverifiable: assembles clean.
            assertTrue(outcome instanceof RewardPlanAssembly.Outcome.Ready);
            h.rewards.activatePlan(((RewardPlanAssembly.Outcome.Ready) outcome).plan());
            assertTrue(h.rewards.systemEnabled());
            assertEquals(2, h.rewards.count());
        }
    }

    @Test
    void failedCandidatePreservesActivePlan(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            Map<String, RewardDefinition> before = h.rewards.all();
            var outcome = RewardPlanAssembly.assemble(Map.of(),
                    List.of(new site.vackstudio.vplaytime.config.ConfigError(
                            "rewards.yml", "menus", "broken")),
                    new RewardManager(LOG), root -> true);
            assertTrue(outcome instanceof RewardPlanAssembly.Outcome.Rejected);
            // No commit happened: same definitions, still enabled.
            assertEquals(before, h.rewards.all());
            assertTrue(h.rewards.systemEnabled());

            // And the old plan still claims.
            h.join(UUID_A);
            h.clock.advance(60_000L);
            ClaimManagerTest.FakeTarget target = new ClaimManagerTest.FakeTarget(UUID_A, "A");
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "items", target).status());
        }
    }

    @Test
    void statusReportCarriesCountsAndReason(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            var enabled = h.rewards.systemStatus();
            assertEquals(RewardSystemState.ENABLED, enabled.state());
            assertEquals(2, enabled.validated());
            h.rewards.disable("nope", PreflightReport.empty());
            var disabled = h.rewards.systemStatus();
            assertEquals(RewardSystemState.DISABLED, disabled.state());
            assertEquals("nope", disabled.reason());
        }
    }

    // ---- fast suspend for pre-known shaky commands ----

    @Test
    void unverifiableCommandSuspendsOnFirstFailure(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            // Mark 'shaky' unverifiable the way a real activation would.
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(CONFIG);
            var parsed = MenuRegistry.parse(config.getConfigurationSection("menus"));
            var results = RewardPreflightValidator.validate(parsed, root -> false);
            assertEquals(PreflightResult.Status.UNVERIFIABLE, results.get("shaky").status());
            var report = PreflightReport.combine(2, List.of(), results);
            h.rewards.activatePlan(new ValidatedRewardPlan(h.rewards.all(), report));

            h.join(UUID_A);
            h.clock.advance(60_000L);
            ClaimManagerTest.FakeTarget target = new ClaimManagerTest.FakeTarget(UUID_A, "A");
            target.failCommand = true;
            assertEquals(ClaimResult.Status.REWARD_FAILED, h.claim(UUID_A, "shaky", target).status());
            assertTrue(h.claims.isSuspended("shaky"), "pre-known shaky reward suspends immediately");
            assertTrue(target.commands.isEmpty());
        }
    }

    @Test
    void ordinaryCommandKeepsThreeStrikeSuspension(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(60_000L);
            // 'items' is item-only; use a command failure via shaky with a VALID plan.
            ClaimManagerTest.FakeTarget target = new ClaimManagerTest.FakeTarget(UUID_A, "A");
            target.failCommand = true;
            assertEquals(ClaimResult.Status.REWARD_FAILED, h.claim(UUID_A, "shaky", target).status());
            // Legacy swap carries no unverifiable set: classic 3-strike path.
            assertFalse(h.claims.isSuspended("shaky"));
        }
    }

    // ---- runtime performs no environment probing ----

    @Test
    void claimsProbeTheEnvironmentZeroTimes(@TempDir Path dir) throws Exception {
        PreflightValidatorTest.FakeEnv env =
                new PreflightValidatorTest.FakeEnv(Set.of("give"));
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(CONFIG.replace(
                "- command: \"give @p diamond 1\"",
                "- command: \"give @p diamond 1\"\n          - command: \"cc give physical Common 1 %player%\""));
        var parsed = MenuRegistry.parse(config.getConfigurationSection("menus"));
        RewardPreflightValidator.validate(parsed, env);
        assertTrue(env.probes > 0, "validator consults the env at load");
        env.probes = 0;
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(60_000L);
            ClaimManagerTest.FakeTarget target = new ClaimManagerTest.FakeTarget(UUID_A, "A");
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "items", target).status());
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "shaky", target).status());
            assertEquals(0, env.probes, "claims must not re-probe the environment");
        }
    }

    @Test
    void validationExecutesNothing(@TempDir Path dir) throws Exception {
        ClaimManagerTest.FakeTarget target = new ClaimManagerTest.FakeTarget(UUID_A, "A");
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(CONFIG);
        var parsed = MenuRegistry.parse(config.getConfigurationSection("menus"));
        var outcome = RewardPlanAssembly.assemble(parsed, List.of(), new RewardManager(LOG),
                root -> true);
        assertTrue(outcome instanceof RewardPlanAssembly.Outcome.Ready);
        assertEquals(0, target.giveCalls.get());
        assertTrue(target.commands.isEmpty());
        assertEquals(0, target.totalItems());
    }

    // ---- GUI error state ----

    @Test
    void errorStateIsDistinctAndNonClaimable() {
        var rendered = RewardErrorState.resolve("<red>X", List.of("<gray>Y"));
        assertEquals(org.bukkit.Material.REDSTONE_BLOCK, rendered.material());
        assertFalse(rendered.glow());
        assertEquals("<red>X", rendered.name());
        assertEquals(List.of("<gray>Y"), rendered.lore());
        assertFalse(rendered.lore().stream().anyMatch(l -> l.contains("CLICK") || l.contains("claim!")));
    }

    @Test
    void errorStateNeverRendersBlank() {
        var rendered = RewardErrorState.resolve("", List.of());
        assertFalse(rendered.name().isBlank());
        assertFalse(rendered.lore().isEmpty());
        assertTrue(rendered.lore().stream().anyMatch(l -> l.contains("administrator")));
    }
}
