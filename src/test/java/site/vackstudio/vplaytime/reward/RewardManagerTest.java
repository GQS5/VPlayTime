package site.vackstudio.vplaytime.reward;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.config.MenuRegistry;
import site.vackstudio.vplaytime.model.RewardAction;
import site.vackstudio.vplaytime.model.RewardDefinition;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RewardManager over nested menus: per-menu slots, cross-menu content merge.
 */
class RewardManagerTest {

    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");

    private static Map<String, site.vackstudio.vplaytime.config.MenuDefinition> menus(String yaml)
            throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return MenuRegistry.parse(config.getConfigurationSection("menus"));
    }

    private static RewardManager load(String yaml) throws Exception {
        RewardManager manager = new RewardManager(LOG);
        manager.load(menus(yaml));
        return manager;
    }

    static String validConfig() {
        return """
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
                            lore: ["<red>Locked"]
                          claimable:
                            material: GRAY_STAINED_GLASS_PANE
                            glow: true
                            lore: ["<gray>Ready"]
                          claimed:
                            material: LIME_STAINED_GLASS_PANE
                            lore: ["<green>Claimed"]
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
                          - type: command
                            command: "say Reward granted to %player%"
                """;
    }

    private static void assertInvalidMenus(String yaml, String expectedFragment) throws Exception {
        try {
            load(yaml);
            fail("expected IllegalStateException containing: " + expectedFragment);
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains(expectedFragment),
                    "message '" + ex.getMessage() + "' should contain '" + expectedFragment + "'");
        }
    }

    @Test
    void parsesNestedRewards() throws Exception {
        RewardManager manager = load(validConfig());

        assertEquals(2, manager.count());
        RewardDefinition one = manager.find("reward_1").orElseThrow();
        assertEquals(11, one.slot());
        assertEquals(1_800L, one.requiredSeconds());
        assertEquals(Material.RED_STAINED_GLASS_PANE, one.display().locked().material());
        assertTrue(one.display().claimable().glow());

        assertEquals(1, one.actions().size());
        RewardAction.ItemAction item = assertInstanceOf(RewardAction.ItemAction.class, one.actions().get(0));
        assertEquals(Material.DIAMOND, item.material());
        assertEquals(10, item.amount());
    }

    static String sharedRewardBlock(int slot, long required) {
        return """
                      reward_1:
                        slot: %d
                        required-seconds: %d
                        display:
                          name: "<white>Reward I"
                          locked:
                            material: RED_STAINED_GLASS_PANE
                            lore: ["<red>Locked"]
                          claimable:
                            material: GRAY_STAINED_GLASS_PANE
                            glow: true
                            lore: ["<gray>Ready"]
                          claimed:
                            material: LIME_STAINED_GLASS_PANE
                            lore: ["<green>Claimed"]
                        actions:
                          - type: item
                            material: DIAMOND
                            amount: 10
                """.formatted(slot, required);
    }

    static String twoMenusSameReward() {
        return """
                menus:
                  main:
                    name: M
                    order: 1
                    title: T
                    rows: 3
                    sounds: {}
                    rewards:
                """
                + sharedRewardBlock(11, 1800)
                + """
                  menu_2:
                    name: M2
                    order: 2
                    title: T2
                    rows: 3
                    sounds: {}
                    rewards:
                """
                + sharedRewardBlock(22, 1800);
    }

    @Test
    void sameRewardAtDifferentSlotsMerges() throws Exception {
        Map<String, site.vackstudio.vplaytime.config.MenuDefinition> parsed = menus(twoMenusSameReward());
        RewardManager manager = new RewardManager(LOG);
        manager.load(parsed);

        // One identity, shared claim state; placements stay per-menu.
        assertEquals(1, manager.count());
        assertEquals(11, parsed.get("main").rewards().get("reward_1").slot());
        assertEquals(22, parsed.get("menu_2").rewards().get("reward_1").slot());

        // Changing the slot in one menu never affects the other.
        assertEquals(11, parsed.get("main").slotToReward().keySet().iterator().next().intValue());
        assertEquals("reward_1", parsed.get("menu_2").slotToReward().get(22));
    }

    @Test
    void rejectsSameIdWithDifferentContent() throws Exception {
        String yaml = twoMenusSameReward().replace("required-seconds: 1800", "required-seconds: 9999");
        // Only menu_2's copy differs now (replace hits both; restore main's copy).
        yaml = yaml.replaceFirst("required-seconds: 9999", "required-seconds: 1800");
        assertInvalidMenus(yaml, "defined differently");
    }

    @Test
    void failedReloadKeepsOldDefinitions() throws Exception {
        RewardManager manager = load(validConfig());
        try {
            manager.load(menus("menus: {}"));
            fail("reload should have failed");
        } catch (IllegalStateException expected) {
            // old definitions intact
        }
        assertEquals(2, manager.count());
        assertTrue(manager.find("reward_1").isPresent());
    }

    @Test
    void parseThenSwapIsTransactional() throws Exception {
        RewardManager manager = load(validConfig());
        var parsed = manager.parseFromMenus(menus(validConfig()));
        assertEquals(2, manager.count(), "parse alone swaps nothing new");
        manager.swap(parsed);
        assertEquals(2, manager.count());
    }

    @Test
    void lookupUnknownIsEmpty() throws Exception {
        assertTrue(load(validConfig()).find("nope").isEmpty());
    }

    @Test
    void parsesMultipleCommandsInOrder() throws Exception {
        RewardManager manager = load("""
                menus:
                  main:
                    name: M
                    order: 1
                    title: T
                    rows: 3
                    sounds: {}
                    rewards:
                      multi_cmd:
                        slot: 5
                        required-seconds: 10
                        display:
                          name: M
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
                          - type: command
                            command: "say one"
                          - type: command
                            command: "say two"
                          - type: command
                            command: "say three"
                """);
        List<RewardAction> actions = manager.find("multi_cmd").orElseThrow().actions();
        assertEquals(3, actions.size());
        assertEquals("say one", ((RewardAction.CommandAction) actions.get(0)).command());
    }
}
