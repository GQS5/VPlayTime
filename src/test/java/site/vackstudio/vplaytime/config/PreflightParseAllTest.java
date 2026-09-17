package site.vackstudio.vplaytime.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Collecting parse: every problem recorded, parsing continues — one broken
 * reward never hides the next one, a broken menu never hides its siblings.
 * The strict {@link MenuRegistry#parse} behavior is unchanged (first error
 * still throws identically).
 */
class PreflightParseAllTest {

    private static MenuRegistry.MenuParseReport parseAll(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return MenuRegistry.parseAll(config.getConfigurationSection("menus"));
    }

    private static Map<String, MenuDefinition> parse(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return MenuRegistry.parse(config.getConfigurationSection("menus"));
    }

    static String reward(String id, int slot, String body) {
        return rewardMat(id, slot, "STONE", body);
    }

    static String rewardMat(String id, int slot, String lockedMaterial, String body) {
        return "      " + id + ":\n"
                + "        slot: " + slot + "\n"
                + "        required-seconds: 10\n"
                + "        display:\n"
                + "          name: R\n"
                + "          locked:\n"
                + "            material: " + lockedMaterial + "\n"
                + "            lore: []\n"
                + "          claimable:\n"
                + "            material: STONE\n"
                + "            lore: []\n"
                + "          claimed:\n"
                + "            material: STONE\n"
                + "            lore: []\n"
                + body;
    }

    static String actions(String... lines) {
        StringBuilder out = new StringBuilder("        actions:\n");
        for (String line : lines) {
            out.append("          - ").append(line).append("\n");
        }
        return out.toString();
    }

    static String menu(String id, String order, String rewards) {
        return "  " + id + ":\n"
                + "    name: M\n"
                + "    order: " + order + "\n"
                + "    title: T\n"
                + "    rows: 3\n"
                + "    sounds: {}\n"
                + "    rewards:\n"
                + rewards;
    }

    // ---- collect-all within one menu ----

    @Test
    void collectsMultipleRewardErrorsInOneMenu() throws Exception {
        String yaml = "menus:\n"
                + menu("main", "1",
                        reward("bad_slot", 99, actions("item: \"DIAMOND 1\""))
                        + rewardMat("bad_mat", 1, "INVALID_BLOCK", actions("item: \"DIAMOND 1\""))
                        + reward("bad_amount", 2, actions("type: item\n            material: DIAMOND\n            amount: 0"))
                        + reward("empty_action", 3, "        actions:\n          - command: \"\"\n")
                        + reward("unsupported", 4, "        actions:\n          - frobnicate: \"x\"\n")
                        + reward("good", 5, actions("item: \"DIAMOND 1\"")));
        var report = parseAll(yaml);
        // 5 broken units, 1 survivor.
        assertEquals(5, report.errors().size());
        assertTrue(report.menus().get("main").rewards().containsKey("good"));
        assertEquals(1, report.menus().get("main").rewards().size());
        assertTrue(report.errors().stream().anyMatch(e -> e.path().contains("bad_slot")));
        assertTrue(report.errors().stream().anyMatch(e -> e.path().contains("bad_mat")));
    }

    @Test
    void collectsSlotCollision() throws Exception {
        String yaml = "menus:\n"
                + menu("main", "1",
                        reward("r1", 0, actions("item: \"DIAMOND 1\""))
                        + reward("r2", 0, actions("item: \"DIAMOND 1\"")));
        var report = parseAll(yaml);
        assertEquals(1, report.errors().size());
        assertTrue(report.errors().get(0).reason().contains("same slot"));
        assertEquals(1, report.menus().get("main").rewards().size());
    }

    @Test
    void collectsAcrossMenus() throws Exception {
        String yaml = "menus:\n"
                + menu("main", "1", reward("r1", 99, actions("item: \"DIAMOND 1\"")))
                + menu("menu_2", "2", reward("r2", 98, actions("item: \"DIAMOND 1\"")));
        var report = parseAll(yaml);
        assertEquals(2, report.errors().size());
    }

    @Test
    void collectsDuplicateOrderAndUnknownTarget() throws Exception {
        String yaml = "menus:\n"
                + menu("main", "1", reward("r1", 0, actions("item: \"DIAMOND 1\"")))
                + "    items:\n"
                + "      go:\n"
                + "        slot: 26\n"
                + "        material: ARROW\n"
                + "        name: N\n"
                + "        action:\n"
                + "          open-menu: menu_9\n"
                + menu("menu_2", "1", reward("r2", 0, actions("item: \"DIAMOND 1\"")));
        var report = parseAll(yaml);
        assertTrue(report.errors().stream().anyMatch(e -> e.reason().contains("share order")));
        assertTrue(report.errors().stream().anyMatch(e -> e.reason().contains("menu_9")));
        // Both menus survive for diagnostics.
        assertEquals(2, report.menus().size());
    }

    @Test
    void missingMainIsCollected() throws Exception {
        String yaml = "menus:\n" + menu("menu_2", "1", reward("r", 0, actions("item: \"DIAMOND 1\"")));
        var report = parseAll(yaml);
        assertTrue(report.errors().stream().anyMatch(e -> e.reason().contains("'main'")));
        assertEquals(1, report.menus().size());
    }

    // ---- parity with strict parse ----

    @Test
    void firstErrorMatchesStrictParse() throws Exception {
        String yaml = "menus:\n"
                + menu("main", "1",
                        reward("bad_slot", 99, actions("item: \"DIAMOND 1\""))
                        + reward("bad_mat", 1, actions("item: \"DIAMOND 1\"")));
        ConfigError strict;
        try {
            parse(yaml);
            fail("strict parse must throw");
            return;
        } catch (ConfigError error) {
            strict = error;
        }
        var report = parseAll(yaml);
        // bad_mat has a valid slot here (1) — only bad_slot fails, same first error.
        assertEquals(1, report.errors().size());
        assertEquals(strict.file(), report.errors().get(0).file());
        assertEquals(strict.path(), report.errors().get(0).path());
        assertEquals(strict.reason(), report.errors().get(0).reason());
    }

    @Test
    void cleanConfigParsesIdentically() throws Exception {
        String yaml = "menus:\n" + menu("main", "1", reward("r", 0, actions("item: \"DIAMOND 1\"")));
        var report = parseAll(yaml);
        assertTrue(report.errors().isEmpty());
        assertEquals(parse(yaml).keySet(), report.menus().keySet());
        assertEquals(
                parse(yaml).get("main").rewards().get("r").slot(),
                report.menus().get("main").rewards().get("r").slot());
    }

    @Test
    void badSoundDegradesToSilence() throws Exception {
        String yaml = "menus:\n"
                + "  main:\n"
                + "    name: M\n"
                + "    order: 1\n"
                + "    title: T\n"
                + "    rows: 3\n"
                + "    sounds:\n"
                + "      open: 12345\n"
                + "    rewards:\n"
                + reward("r", 0, actions("item: \"DIAMOND 1\""));
        var report = parseAll(yaml);
        assertEquals(1, report.errors().size());
        assertEquals(1, report.menus().size());
    }

    // ---- rejection carries everything ----

    @Test
    void rejectionReportCountsStructuralIssues() {
        var errors = List.of(
                new ConfigError("rewards.yml", "menus.main.rewards.bad_slot.slot", "bad slot"),
                new ConfigError("rewards.yml", "menus", "duplicate order"));
        var outcome = site.vackstudio.vplaytime.reward.RewardPlanAssembly.assemble(
                Map.of(), errors,
                new site.vackstudio.vplaytime.reward.RewardManager(
                        java.util.logging.Logger.getLogger("VPlaytimeTest")),
                root -> true);
        assertTrue(outcome instanceof site.vackstudio.vplaytime.reward.RewardPlanAssembly.Outcome.Rejected);
        var rejected = (site.vackstudio.vplaytime.reward.RewardPlanAssembly.Outcome.Rejected) outcome;
        assertEquals(2, rejected.report().invalidCount());
        assertFalse(rejected.report().valid());
    }
}
