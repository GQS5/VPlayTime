package site.vackstudio.vplaytime.reward;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.config.MenuDefinition;
import site.vackstudio.vplaytime.config.MenuRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure preflight validation: strict rules, collect-all diagnostics and
 * atomic assembly — no server, no I/O, faked environment answers.
 */
class PreflightValidatorTest {

    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");

    /** Fake environment: only these roots (plus vanilla) count as provided. */
    static final class FakeEnv implements PreflightEnv {
        final Set<String> registered;
        int probes;

        FakeEnv(Set<String> registered) {
            this.registered = registered;
        }

        @Override
        public boolean commandRegistered(String root) {
            probes++;
            return registered.contains(root);
        }
    }

    static Map<String, MenuDefinition> menus(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return MenuRegistry.parse(config.getConfigurationSection("menus"));
    }

    static String reward(String id, int slot, String... actions) {
        StringBuilder out = new StringBuilder();
        out.append("      ").append(id).append(":\n"
                + "        slot: ").append(slot).append("\n"
                + "        required-seconds: 10\n"
                + "        display:\n"
                + "          name: R\n"
                + "          locked:\n"
                + "            material: STONE\n"
                + "            lore: []\n"
                + "          claimable:\n"
                + "            material: STONE\n"
                + "            lore: []\n"
                + "          claimed:\n"
                + "            material: STONE\n"
                + "            lore: []\n"
                + "        actions:\n");
        for (String action : actions) {
            out.append("          - ").append(action).append("\n");
        }
        return out.toString();
    }

    static String wrap(String rewards) {
        return "menus:\n"
                + "  main:\n"
                + "    name: M\n"
                + "    order: 1\n"
                + "    title: T\n"
                + "    rows: 3\n"
                + "    sounds: {}\n"
                + "    rewards:\n"
                + rewards;
    }

    private static PreflightResult only(Map<String, PreflightResult> results) {
        assertEquals(1, results.size());
        return results.values().iterator().next();
    }

    // ---- all valid ----

    @Test
    void allRewardsValid() throws Exception {
        var parsed = menus(wrap(
                reward("r1", 0, "command: \"say hi %player%\"", "item: \"DIAMOND 10\"")
                + reward("r2", 1, "command: \"xp add %player% 500\"")));
        var results = RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of()));
        assertEquals(2, results.size());
        assertEquals(PreflightResult.Status.VALID, results.get("r1").status());
        assertEquals(PreflightResult.Status.VALID, results.get("r2").status());
    }

    @Test
    void validatesEveryRewardNotJustVisible() throws Exception {
        var parsed = menus(wrap(
                reward("r1", 0, "command: \"say ok %player%\"")
                + reward("r2", 1, "command: \"xp give %player% 5\"")
                + reward("r3", 2, "command: \"say ok %player%\"")));
        var results = RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of()));
        assertEquals(PreflightResult.Status.VALID, results.get("r1").status());
        assertEquals(PreflightResult.Status.INVALID, results.get("r2").status());
        assertEquals(PreflightResult.Status.VALID, results.get("r3").status());
    }

    // ---- invalid commands ----

    @Test
    void xpGiveIsInvalid() throws Exception {
        var parsed = menus(wrap(reward("r", 0, "command: \"xp give %player% 100\"")));
        var result = only(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of())));
        assertEquals(PreflightResult.Status.INVALID, result.status());
        assertTrue(result.issues().get(0).reason().contains("XP"),
                result.issues().get(0).reason());
        assertTrue(result.issues().get(0).fix().contains("xp add %player% <amount>"));
    }

    @Test
    void expGiveIsInvalid() throws Exception {
        var parsed = menus(wrap(reward("r", 0, "command: \"exp give %player% 100\"")));
        var result = only(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of())));
        assertEquals(PreflightResult.Status.INVALID, result.status());
        assertEquals(PreflightIssue.Type.COMMAND, result.issues().get(0).type());
    }

    @Test
    void xpAddWithBadAmountIsInvalid() throws Exception {
        var parsed = menus(wrap(reward("r", 0, "command: \"xp add %player% zero\"")));
        var result = only(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of())));
        assertEquals(PreflightResult.Status.INVALID, result.status());
    }

    // ---- missing dependency / provider ----

    @Test
    void missingExternalProviderIsInvalid() throws Exception {
        var parsed = menus(wrap(reward("r", 0, "command: \"cc give physical Common 1 %player%\"")));
        var result = only(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of())));
        assertEquals(PreflightResult.Status.INVALID, result.status());
        var issue = result.issues().get(0);
        assertEquals(PreflightIssue.Type.EXTERNAL_PROVIDER, issue.type());
        assertEquals("cc", issue.value());
        assertTrue(issue.reason().contains("crate"), issue.reason());
    }

    @Test
    void presentExternalProviderIsValid() throws Exception {
        var parsed = menus(wrap(reward("r", 0, "command: \"cc give physical Common 1 %player%\"")));
        var result = only(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of("cc"))));
        assertEquals(PreflightResult.Status.VALID, result.status());
    }

    @Test
    void missingPlayerPlaceholderIsInvalid() throws Exception {
        var parsed = menus(wrap(reward("r", 0, "command: \"addmoney Server 100\"")));
        var result = only(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of("addmoney"))));
        assertEquals(PreflightResult.Status.INVALID, result.status());
        assertTrue(result.issues().get(0).reason().contains("player placeholder"));
    }

    // ---- placeholders ----

    @Test
    void legacyPlayerNamePlaceholderIsInvalid() throws Exception {
        var parsed = menus(wrap(reward("r", 0, "command: \"eco give %player_name% 100\"")));
        var result = only(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of("eco"))));
        assertEquals(PreflightResult.Status.INVALID, result.status());
        assertEquals(PreflightIssue.Type.PLACEHOLDER, result.issues().get(0).type());
        assertTrue(result.issues().get(0).fix().contains("%player%"));
    }

    @Test
    void malformedPlaceholdersAreInvalid() throws Exception {
        var empty = menus(wrap(reward("r", 0, "command: \"say %%x\"")));
        assertEquals(PreflightResult.Status.INVALID,
                only(RewardPreflightValidator.validate(empty, new FakeEnv(Set.of()))).status());
        var upper = menus(wrap(reward("r", 0, "command: \"say %Player%\"")));
        var result = only(RewardPreflightValidator.validate(upper, new FakeEnv(Set.of())));
        assertEquals(PreflightResult.Status.INVALID, result.status());
        assertEquals(PreflightIssue.Type.PLACEHOLDER, result.issues().get(0).type());
    }

    @Test
    void lonePercentIsLiteralText() throws Exception {
        var parsed = menus(wrap(reward("r", 0, "command: \"say 100% legit %player%\"")));
        var result = only(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of())));
        assertEquals(PreflightResult.Status.VALID, result.status());
    }

    // ---- items ----

    @Test
    void supportedGiveCommandIsValid() throws Exception {
        var parsed = menus(wrap(reward("r", 0, "command: \"give %player% diamond 5\"")));
        var result = only(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of())));
        assertEquals(PreflightResult.Status.VALID, result.status());
    }

    @Test
    void giveWithBadMaterialIsInvalid() throws Exception {
        var parsed = menus(wrap(reward("r", 0, "command: \"give %player% INVALID_BLOCK\"")));
        var result = only(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of())));
        assertEquals(PreflightResult.Status.INVALID, result.status());
        assertEquals(PreflightIssue.Type.MATERIAL, result.issues().get(0).type());
    }

    @Test
    void selectorTargetIsUnverifiableNotInvalid() throws Exception {
        var parsed = menus(wrap(reward("r", 0, "command: \"give @p diamond 1\"")));
        var result = only(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of())));
        assertEquals(PreflightResult.Status.UNVERIFIABLE, result.status());
    }

    // ---- assembly: transactional, collect-all ----

    @Test
    void multipleInvalidRewardsAllReported() throws Exception {
        var parsed = menus(wrap(
                reward("r1", 0, "command: \"xp give %player% 5\"")
                + reward("r2", 1, "command: \"cc give physical Common 1 %player%\"")
                + reward("r3", 2, "command: \"say ok %player%\"")));
        var manager = new RewardManager(LOG);
        var outcome = RewardPlanAssembly.assemble(parsed, java.util.List.of(), manager, new FakeEnv(Set.of()));
        assertTrue(outcome instanceof RewardPlanAssembly.Outcome.Rejected, "must reject");
        var rejected = (RewardPlanAssembly.Outcome.Rejected) outcome;
        assertEquals(3, rejected.report().scanned());
        assertEquals(1, rejected.report().validCount());
        assertEquals(2, rejected.report().invalidCount());
        assertFalse(rejected.report().valid());
        // Old state untouched: nothing partially activated.
        assertEquals(0, manager.count());
        assertTrue(manager.systemEnabled());
    }

    @Test
    void duplicateRewardContentMismatchIsRejected() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("menus:\n"
                + "  main:\n"
                + "    name: M\n"
                + "    order: 1\n"
                + "    title: T\n"
                + "    rows: 3\n"
                + "    sounds: {}\n"
                + "    rewards:\n"
                + reward("r", 0, "item: \"DIAMOND 1\"")
                + "  menu_2:\n"
                + "    name: M2\n"
                + "    order: 2\n"
                + "    title: T2\n"
                + "    rows: 3\n"
                + "    sounds: {}\n"
                + "    rewards:\n"
                + reward("r", 5, "item: \"GOLD_INGOT 1\""));
        var parsed = MenuRegistry.parse(config.getConfigurationSection("menus"));
        var outcome = RewardPlanAssembly.assemble(parsed, java.util.List.of(),
                new RewardManager(LOG), new FakeEnv(Set.of()));
        assertTrue(outcome instanceof RewardPlanAssembly.Outcome.Rejected);
        var rejected = (RewardPlanAssembly.Outcome.Rejected) outcome;
        assertEquals(1, rejected.report().invalidCount());
        assertTrue(rejected.report().allIssues().get(0).reason().contains("differently"));
    }

    @Test
    void emptyRegistryIsRejected() {
        var outcome = RewardPlanAssembly.assemble(
                Map.of(), java.util.List.of(), new RewardManager(LOG), new FakeEnv(Set.of()));
        assertTrue(outcome instanceof RewardPlanAssembly.Outcome.Rejected);
        assertFalse(((RewardPlanAssembly.Outcome.Rejected) outcome).report().valid());
    }

    @Test
    void successfulAssemblyProducesCommittablePlan() throws Exception {
        var parsed = menus(wrap(reward("r1", 0, "item: \"DIAMOND 1\"")));
        var outcome = RewardPlanAssembly.assemble(parsed, java.util.List.of(),
                new RewardManager(LOG), new FakeEnv(Set.of()));
        assertTrue(outcome instanceof RewardPlanAssembly.Outcome.Ready);
        var ready = (RewardPlanAssembly.Outcome.Ready) outcome;
        assertTrue(ready.plan().valid());
        assertEquals(1, ready.plan().size());
    }

    @Test
    void fixedConfigurationAssemblesSuccessfully() throws Exception {
        var broken = menus(wrap(reward("r1", 0, "command: \"xp give %player% 5\"")));
        var fixed = menus(wrap(reward("r1", 0, "command: \"xp add %player% 5\"")));
        var manager = new RewardManager(LOG);
        var first = RewardPlanAssembly.assemble(broken, java.util.List.of(), manager, new FakeEnv(Set.of()));
        assertTrue(first instanceof RewardPlanAssembly.Outcome.Rejected);
        var second = RewardPlanAssembly.assemble(fixed, java.util.List.of(), manager, new FakeEnv(Set.of()));
        assertTrue(second instanceof RewardPlanAssembly.Outcome.Ready);
        manager.activatePlan(((RewardPlanAssembly.Outcome.Ready) second).plan());
        assertTrue(manager.systemEnabled());
        assertEquals(1, manager.count());
    }

    // ---- diagnostics shape (§12) ----

    @Test
    void diagnosticsCarryFilePathRewardTypeValueReasonFix() throws Exception {
        var parsed = menus(wrap(
                reward("level_28", 0, "command: \"xp give %player% 100\"")
                + reward("level_34", 1, "command: \"say ok %player%\"")));
        // level_34 gets a structural material-style issue via a second unit below.
        var results = new HashMap<>(RewardPreflightValidator.validate(parsed, new FakeEnv(Set.of())));
        var report = PreflightReport.combine(2,
                java.util.List.of(new PreflightIssue("rewards.yml", "menus.main.rewards.level_34",
                        "level_34", "34", -1, PreflightIssue.Type.MATERIAL, "INVALID_BLOCK",
                        "Material does not exist.", "Replace it with a valid 1.21.11 material.")),
                results);
        var lines = report.consoleReport();
        assertTrue(lines.contains("VPlayTime Reward Preflight Validation FAILED"));
        assertTrue(lines.contains("Rewards scanned: 2"));
        assertTrue(lines.contains("Valid: 1"));
        assertTrue(lines.contains("Invalid: 2"));
        assertTrue(lines.contains("Unverifiable: 0"));
        assertTrue(lines.contains("[1]"));
        assertTrue(lines.contains("[2]"));
        assertTrue(lines.contains("File: rewards.yml"));
        assertTrue(lines.contains("Reward: level_28"));
        assertTrue(lines.contains("Type: COMMAND"));
        assertTrue(lines.contains("Value: xp give %player% 100"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Reason: ")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Fix: ")));
        assertTrue(lines.contains("Previous configuration remains active."));
    }

    // ---- shipped default end to end ----

    private static Map<String, MenuDefinition> shipped() throws Exception {
        var stream = PreflightValidatorTest.class.getResourceAsStream("/rewards.yml");
        org.junit.jupiter.api.Assertions.assertNotNull(stream, "missing shipped rewards.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        var parsed = MenuRegistry.parseAll(yaml.getConfigurationSection("menus"));
        assertTrue(parsed.errors().isEmpty(), "shipped default must be structurally clean");
        return parsed.menus();
    }

    @Test
    void shippedDefaultValidatesCleanWithProviders() throws Exception {
        var outcome = RewardPlanAssembly.assemble(shipped(), java.util.List.of(),
                new RewardManager(LOG), new FakeEnv(Set.of("addmoney", "addshards", "cc")));
        assertTrue(outcome instanceof RewardPlanAssembly.Outcome.Ready, "must activate");
        var ready = (RewardPlanAssembly.Outcome.Ready) outcome;
        assertEquals(60, ready.plan().size());
        assertEquals(0, ready.plan().report().invalidCount());
    }

    @Test
    void shippedDefaultRejectedWithoutProvidersAndNothingActivates() throws Exception {
        var manager = new RewardManager(LOG);
        var outcome = RewardPlanAssembly.assemble(shipped(), java.util.List.of(),
                manager, new FakeEnv(Set.of()));
        assertTrue(outcome instanceof RewardPlanAssembly.Outcome.Rejected, "must reject");
        var rejected = (RewardPlanAssembly.Outcome.Rejected) outcome;
        assertEquals(60, rejected.report().scanned());
        assertTrue(rejected.report().invalidCount() > 0);
        assertTrue(rejected.report().allIssues().stream()
                .anyMatch(i -> i.type() == PreflightIssue.Type.EXTERNAL_PROVIDER && i.value().equals("cc")));
        assertTrue(rejected.report().allIssues().stream()
                .anyMatch(i -> i.type() == PreflightIssue.Type.EXTERNAL_PROVIDER && i.value().equals("addmoney")));
        // Nothing partially activated.
        assertEquals(0, manager.count());
        assertTrue(manager.systemEnabled());
    }
}
