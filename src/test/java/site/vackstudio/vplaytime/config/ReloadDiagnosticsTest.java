package site.vackstudio.vplaytime.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.reward.RewardManager;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reload safety net: precise diagnostics, transactional swaps, and stable
 * repeated reloads — without needing a server.
 */
class ReloadDiagnosticsTest {

    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");

    private static Map<String, MenuDefinition> parse(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return MenuRegistry.parse(config.getConfigurationSection("menus"));
    }

    /** The user's exact failure: button with an empty open-menu value. */
    static String menuWithEmptyOpenMenu() {
        return "menus:\n"
                + "  main:\n"
                + "    name: M\n"
                + "    order: 1\n"
                + "    title: T\n"
                + "    rows: 3\n"
                + "    sounds: {}\n"
                + "    rewards:\n"
                + "      r:\n"
                + "        slot: 0\n"
                + "        required-seconds: 5\n"
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
                + "        actions:\n"
                + "          - type: item\n"
                + "            material: DIAMOND\n"
                + "            amount: 1\n"
                + "    items:\n"
                + "      next:\n"
                + "        slot: 26\n"
                + "        material: ARROW\n"
                + "        name: N\n"
                + "        action:\n"
                + "          open-menu: \"\"\n";
    }

    @Test
    void emptyOpenMenuReportsFilePathAndReason() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(menuWithEmptyOpenMenu());
        try {
            MenuRegistry.parse(config.getConfigurationSection("menus"));
            fail("expected ConfigError");
        } catch (ConfigError error) {
            assertEquals("rewards.yml", error.file());
            assertEquals("menus.main.items.next.action.open-menu", error.path());
            assertTrue(error.reason().contains("empty value"), error.reason());
            var report = error.reportLines();
            assertEquals(4, report.size());
            assertEquals("Reload failed: rewards.yml", report.get(0));
            assertEquals("Invalid value at menus.main.items.next.action.open-menu",
                    report.get(1));
            assertTrue(report.get(2).contains("empty value"));
            assertEquals("Previous configuration remains active.", report.get(3));
        }
    }

    @Test
    void bareNullOpenMenuTreatedAsMissingAction() throws Exception {
        // YAML/Bukkit discards null-valued keys on load, so a bare
        // 'open-menu:' (no value) arrives as an empty action section.
        // The diagnostic still guides the fix; it just can't name the key.
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(menuWithEmptyOpenMenu().replace("open-menu: \"\"", "open-menu:"));
        try {
            MenuRegistry.parse(config.getConfigurationSection("menus"));
            fail("expected ConfigError");
        } catch (ConfigError error) {
            assertEquals("rewards.yml", error.file());
            assertEquals("menus.main.items.next.action", error.path());
            assertTrue(error.reason().contains("needs an 'action'"), error.reason());
        }
    }

    @Test
    void invalidYamlCarriesLineInfo() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString("menus:\n\tmain:\n");
            fail("expected YAML syntax failure");
        } catch (Exception ex) {
            // SnakeYAML reports file position; ConfigManager.loadYaml keeps it verbatim.
            assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("line"),
                    ex.getMessage());
        }
    }

    @Test
    void nonConfigErrorStillReportsSafely() {
        var report = site.vackstudio.vplaytime.VPlaytimePlugin
                .reloadReport(new IllegalStateException("boom"));
        assertEquals(3, report.size());
        assertTrue(report.get(0).contains("Reload failed"));
        assertEquals("Previous configuration remains active.", report.get(2));
        assertEquals("boom",
                site.vackstudio.vplaytime.VPlaytimePlugin.firstReason(new IllegalStateException("boom")));
    }

    @Test
    void validInvalidFixedSequenceKeepsOldActive() throws Exception {
        RewardManager manager = new RewardManager(LOG);
        manager.load(parse(MenuRegistryTest.validMenus()));
        assertEquals(2, manager.count());
        try {
            manager.load(parse(menuWithEmptyOpenMenu()));
            fail("invalid must not swap");
        } catch (ConfigError expected) {
            // Old definitions untouched.
        }
        assertEquals(2, manager.count());
        assertTrue(manager.find("reward_1").isPresent());
        manager.load(parse(MenuRegistryTest.validMenus()));
        assertEquals(2, manager.count());
    }

    @Test
    void repeatedReloadCyclesStayStable() throws Exception {
        RewardManager manager = new RewardManager(LOG);
        var first = parse(MenuRegistryTest.validMenus());
        var second = parse("menus:\n"
                + MenuRegistryTest.menuBlock("main", "1", "3",
                        MenuRegistryTest.rewardBlock("reward_9", 5, 60)));
        for (int i = 0; i < 10; i++) {
            manager.load(i % 2 == 0 ? first : second);
            assertEquals(i % 2 == 0 ? 2 : 1, manager.count(), "cycle " + i);
        }
        assertEquals(0, new RewardManager(LOG).all().size(), "fresh managers start empty");
    }

    @Test
    void nextPageShortcutLoads() throws Exception {
        Map<String, MenuDefinition> menus = parse("menus:\n"
                + MenuRegistryTest.menuBlock("main", "1", "3",
                        MenuRegistryTest.rewardBlock("reward_1", 0, 10))
                + "    items:\n"
                + "      next:\n"
                + "        slot: 26\n"
                + "        material: ARROW\n"
                + "        name: N\n"
                + "        action: next-page\n"
                + MenuRegistryTest.menuBlock("menu_2", "2", "3",
                        MenuRegistryTest.rewardBlock("reward_2", 0, 10)));
        assertEquals(List.of(new MenuItem.Action.OpenMenu("menu_2")),
                menus.get("main").items().get("next").actions());
    }
}
