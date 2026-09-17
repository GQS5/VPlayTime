package site.vackstudio.vplaytime.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped 1.9.2 default must always load: fresh installs boot straight
 * into the DeluxeMenus-inspired 4-page layout (4 menus x 15 levels, 1h to
 * 560h). Guards against an invalid default rewards.yml, config.yml or
 * messages.yml ever shipping.
 */
class ShippedDefaultsTest {

    private static YamlConfiguration resource(String name) throws Exception {
        var stream = ShippedDefaultsTest.class.getResourceAsStream("/" + name);
        Objects.requireNonNull(stream, "missing shipped resource " + name);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        return yaml;
    }

    @Test
    void shippedRewardsLoad() throws Exception {
        var menus = MenuRegistry.parse(resource("rewards.yml").getConfigurationSection("menus"));
        assertEquals(4, menus.size());
        assertTrue(menus.containsKey("main"));
        assertEquals(60, menus.values().stream().mapToInt(menu -> menu.rewards().size()).sum());
        assertEquals(List.of(1, 2, 3, 4), MenuRegistry.inOrder(menus).stream()
                .map(MenuDefinition::order).toList());
        var manager = new site.vackstudio.vplaytime.reward.RewardManager(
                java.util.logging.Logger.getLogger("VPlaytimeTest"));
        assertEquals(60, manager.parseFromMenus(menus).size());
    }

    @Test
    void shippedRewardsFollowPageLayout() throws Exception {
        var menus = MenuRegistry.parse(resource("rewards.yml").getConfigurationSection("menus"));
        // 4 pages x 15 levels: main shows 1-15, menu_2 shows 16-30,
        // menu_3 shows 31-45, menu_4 shows 46-60, at the DeluxeMenus slots
        // (rows 2-4: 10-14, 19-23, 28-32).
        String[] pages = {"main", "menu_2", "menu_3", "menu_4"};
        int[] slots = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23, 28, 29, 30, 31, 32};
        for (int page = 0; page < 4; page++) {
            var rewards = menus.get(pages[page]).rewards();
            assertEquals(15, rewards.size());
            for (int index = 0; index < 15; index++) {
                String id = "level_" + (page * 15 + index + 1);
                assertTrue(rewards.containsKey(id), pages[page] + " should show " + id);
                assertEquals(slots[index], rewards.get(id).slot());
            }
        }
    }

    @Test
    void shippedRewardsUseTrafficLightVisuals() throws Exception {
        var menus = MenuRegistry.parse(resource("rewards.yml").getConfigurationSection("menus"));
        var manager = new site.vackstudio.vplaytime.reward.RewardManager(
                java.util.logging.Logger.getLogger("VPlaytimeTest"));
        manager.parseFromMenus(menus);
        for (var def : manager.all().values()) {
            assertEquals(org.bukkit.Material.RED_CANDLE, def.display().forState(
                    site.vackstudio.vplaytime.model.RewardState.LOCKED).material());
            assertEquals(org.bukkit.Material.ORANGE_CANDLE, def.display().forState(
                    site.vackstudio.vplaytime.model.RewardState.CLAIMABLE).material());
            var claimed = def.display().forState(
                    site.vackstudio.vplaytime.model.RewardState.CLAIMED);
            assertEquals(org.bukkit.Material.LIME_CANDLE, claimed.material());
            assertTrue(claimed.glow());
        }
    }

    @Test
    void shippedMenusUseSixRowsWithNativeNavigation() throws Exception {
        var menus = MenuRegistry.parse(resource("rewards.yml").getConfigurationSection("menus"));
        for (var menu : menus.values()) {
            assertEquals(6, menu.rows());
            assertEquals(54, menu.size());
        }
        // Close/store/stats on every page; next on all but last, prev on all but first.
        for (String id : List.of("main", "menu_2", "menu_3", "menu_4")) {
            var items = menus.get(id).items();
            assertEquals(49, items.get("close").slot());
            assertEquals(16, items.get("store").slot());
            assertEquals(25, items.get("stats").slot());
        }
        assertEquals(50, menus.get("main").items().get("next").slot());
        assertEquals(50, menus.get("menu_2").items().get("next").slot());
        assertEquals(50, menus.get("menu_3").items().get("next").slot());
        assertEquals(48, menus.get("menu_2").items().get("prev").slot());
        assertEquals(48, menus.get("menu_3").items().get("prev").slot());
        assertEquals(48, menus.get("menu_4").items().get("prev").slot());
        assertTrue(!menus.get("main").items().containsKey("prev"), "first page has no prev");
        assertTrue(!menus.get("menu_4").items().containsKey("next"), "last page has no next");
    }

    @Test
    void shippedConfigAndMessagesLoad() throws Exception {
        var config = resource("config.yml");
        assertEquals(3, config.getInt("config-version"));
        ConfigManager.parseGlobal(config);
        var messages = ConfigManager.parseMessages(resource("messages.yml"));
        // Every situation the code can send must have a shipped default.
        assertTrue(messages.usage().contains("/vplaytime"));
        assertTrue(messages.noPermission().contains("permission"));
        assertTrue(messages.playersOnly().contains("Players"));
        assertTrue(messages.unknownMenu().contains("%menu%"));
        assertTrue(messages.unknownPlayer().contains("%player%"));
        assertTrue(messages.noData().contains("%player%"));
        assertTrue(messages.claimLocked().contains("%required_playtime%"));
        assertTrue(messages.claimUnavailable().contains("unavailable"));
        assertTrue(messages.reloadDetail().contains("%detail%"));
        assertTrue(messages.infoHeader().contains("%player%"));
        assertTrue(messages.infoClaims().contains("%claimed%"));
        assertTrue(messages.resetDone().contains("%reward%"));
        assertTrue(messages.resetFailed().contains("%detail%"));
        assertTrue(messages.resetAllDone().contains("%player%"));
        assertTrue(messages.infoRewardSystem().contains("%status%"));
        assertTrue(messages.infoValidated().contains("%validated%"));
        assertTrue(messages.infoInvalid().contains("%invalid%"));
        assertTrue(messages.infoUnverifiable().contains("%unverifiable%"));
        assertTrue(messages.infoActiveConfig().contains("%status%"));
        assertTrue(messages.infoDisabledReason().contains("%reason%"));
        assertTrue(messages.guiRewardErrorName().contains("CONFIGURATION ERROR"));
        assertTrue(messages.guiRewardErrorLore().stream().anyMatch(l -> l.contains("administrator")));
    }
}
