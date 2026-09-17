package site.vackstudio.vplaytime.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped defaults must always load: fresh installs boot straight
 * into a working setup. Guards against an invalid default rewards.yml
 * (3 pages x 5 levels), config.yml or messages.yml ever shipping.
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
        assertEquals(3, menus.size());
        assertTrue(menus.containsKey("main"));
        assertEquals(15, menus.values().stream().mapToInt(menu -> menu.rewards().size()).sum());
        assertEquals(java.util.List.of(1, 2, 3), MenuRegistry.inOrder(menus).stream()
                .map(MenuDefinition::order).toList());
        var manager = new site.vackstudio.vplaytime.reward.RewardManager(
                java.util.logging.Logger.getLogger("VPlaytimeTest"));
        assertEquals(15, manager.parseFromMenus(menus).size());
    }

    @Test
    void shippedRewardsFollowPageLayout() throws Exception {
        var menus = MenuRegistry.parse(resource("rewards.yml").getConfigurationSection("menus"));
        // 3 pages x 5 levels: main shows 1-5, menu_2 shows 6-10, menu_3 shows 11-15.
        String[] pages = {"main", "menu_2", "menu_3"};
        for (int page = 0; page < 3; page++) {
            var rewards = menus.get(pages[page]).rewards();
            assertEquals(5, rewards.size());
            for (int slot = 0; slot < 5; slot++) {
                String id = "level_" + (page * 5 + slot + 1);
                assertTrue(rewards.containsKey(id), pages[page] + " should show " + id);
                assertEquals(11 + slot, rewards.get(id).slot());
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
    }
}
