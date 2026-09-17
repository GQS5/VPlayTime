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
 * (currently 60 levels), config.yml or messages.yml ever shipping.
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
        assertEquals(java.util.List.of(1, 2, 3, 4), MenuRegistry.inOrder(menus).stream()
                .map(MenuDefinition::order).toList());
        var manager = new site.vackstudio.vplaytime.reward.RewardManager(
                java.util.logging.Logger.getLogger("VPlaytimeTest"));
        assertEquals(60, manager.parseFromMenus(menus).size());
    }

    @Test
    void shippedConfigAndMessagesLoad() throws Exception {
        var config = resource("config.yml");
        assertEquals(3, config.getInt("config-version"));
        ConfigManager.parseGlobal(config);
        ConfigManager.parseMessages(resource("messages.yml"));
    }
}
