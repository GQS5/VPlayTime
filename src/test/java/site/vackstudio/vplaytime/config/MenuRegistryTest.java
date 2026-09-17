package site.vackstudio.vplaytime.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * MenuRegistry: nested menus.yml-in-rewards.yml parsing, validation,
 * ordering and navigation foundation.
 */
class MenuRegistryTest {

    private static Map<String, MenuDefinition> parse(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return MenuRegistry.parse(config.getConfigurationSection("menus"));
    }

    private static void assertInvalid(String yaml, String expectedFragment) throws Exception {
        try {
            parse(yaml);
            fail("expected IllegalStateException containing: " + expectedFragment);
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains(expectedFragment),
                    "message '" + ex.getMessage() + "' should contain '" + expectedFragment + "'");
        }
    }

    static String menuBlock(String id, String order, String rows, String rewardsYaml) {
        return """
                  %s:
                    name: "%s label"
                    order: %s
                    title: "%s title"
                    rows: %s
                    sounds:
                      open: BLOCK_CHEST_OPEN
                      claim: ENTITY_PLAYER_LEVELUP
                      locked: BLOCK_NOTE_BLOCK_BASS
                      already-claimed: BLOCK_NOTE_BLOCK_BASS
                    rewards:
                """.formatted(id, id, order, id, rows) + rewardsYaml;
    }

    static String rewardBlock(String id, int slot, long required) {
        return """
                      %s:
                        slot: %d
                        required-seconds: %d
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
                          - type: item
                            material: DIAMOND
                            amount: 1
                """.formatted(id, slot, required);
    }

    static String validMenus() {
        return "menus:\n"
                + menuBlock("main", "1", "3",
                        rewardBlock("reward_1", 11, 1800) + rewardBlock("reward_2", 13, 3600));
    }

    @Test
    void parsesValidMenu() throws Exception {
        Map<String, MenuDefinition> menus = parse(validMenus());
        MenuDefinition main = menus.get("main");
        assertEquals("main", main.id());
        assertEquals("main label", main.name());
        assertEquals(1, main.order());
        assertEquals("main title", main.title());
        assertEquals(3, main.rows());
        assertEquals(27, main.size());
        assertEquals(new SoundConfig("BLOCK_CHEST_OPEN", 1.0f, 1.0f), main.sounds().get("open"));
        assertEquals(11, main.rewards().get("reward_1").slot());
        assertEquals(1_800L, main.rewards().get("reward_1").requiredSeconds());
        assertEquals(11, main.placements().get("reward_1"));
        assertEquals("reward_2", main.slotToReward().get(13));
    }

    @Test
    void parsesMultipleMenusIndependently() throws Exception {
        Map<String, MenuDefinition> menus = parse("menus:\n"
                + menuBlock("main", "1", "3", rewardBlock("reward_1", 11, 1800))
                + menuBlock("menu_2", "2", "6", rewardBlock("reward_2", 53, 3600))
                + menuBlock("menu_3", "3", "1", rewardBlock("reward_3", 4, 100)));
        assertEquals(3, menus.size());
        assertEquals(54, menus.get("menu_2").size());
        assertEquals(9, menus.get("menu_3").size());
        assertEquals(53, menus.get("menu_2").rewards().get("reward_2").slot());
    }

    @Test
    void ordersByOrderNotId() throws Exception {
        Map<String, MenuDefinition> menus = parse("menus:\n"
                + menuBlock("main", "10", "3", rewardBlock("reward_1", 0, 10))
                + menuBlock("zebras", "20", "3", rewardBlock("reward_2", 0, 10))
                + menuBlock("apples", "30", "3", rewardBlock("reward_3", 0, 10)));
        List<MenuDefinition> ordered = MenuRegistry.inOrder(menus);
        assertEquals(List.of("main", "zebras", "apples"),
                ordered.stream().map(MenuDefinition::id).toList());
        assertEquals(3, MenuRegistry.pageCount(menus));
        assertEquals(1, MenuRegistry.pageIndex(menus, "main"));
        assertEquals(2, MenuRegistry.pageIndex(menus, "zebras"));
        assertEquals(3, MenuRegistry.pageIndex(menus, "apples"));
        assertEquals("zebras", MenuRegistry.next(menus, "main").orElseThrow().id());
        assertEquals("main", MenuRegistry.previous(menus, "zebras").orElseThrow().id());
        assertTrue(MenuRegistry.next(menus, "apples").isEmpty());
        assertTrue(MenuRegistry.previous(menus, "main").isEmpty());
        assertEquals(-1, MenuRegistry.pageIndex(menus, "nope"));
    }

    @Test
    void rejectsMissingMain() throws Exception {
        assertInvalid("menus:\n" + menuBlock("menu_2", "1", "3", rewardBlock("reward_1", 0, 10)),
                "'main'");
    }

    @Test
    void rejectsBadRows() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "0", rewardBlock("reward_1", 0, 10)),
                "rows");
        assertInvalid("menus:\n" + menuBlock("main", "1", "7", rewardBlock("reward_1", 0, 10)),
                "rows");
    }

    @Test
    void rejectsBlankNameAndTitle() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("name: \"main label\"", "name: \"  \""), "'name'");
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("title: \"main title\"", "title: \"\""), "'title'");
    }

    @Test
    void rejectsBadAndDuplicateOrder() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "0", "3", rewardBlock("reward_1", 0, 10)),
                "needs 'order");
        assertInvalid("menus:\n"
                + menuBlock("main", "2", "3", rewardBlock("reward_1", 0, 10))
                + menuBlock("menu_2", "2", "3", rewardBlock("reward_2", 0, 10)), "share order 2");
    }

    @Test
    void rejectsInvalidMenuId() throws Exception {
        assertInvalid("menus:\n"
                + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                + "  'Bad Id!':\n    name: B\n    order: 2\n    title: B\n    rows: 3\n    sounds: {}\n    rewards: {}\n",
                "Invalid menu id");
    }

    @Test
    void rejectsSlotOutOfRangeWithHumanMessage() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 30, 10)),
                "uses slot 30, but the menu only has 3 rows");
    }

    @Test
    void rejectsDuplicateSlot() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "3",
                rewardBlock("reward_1", 11, 10) + rewardBlock("reward_2", 11, 10)), "same slot 11");
    }

    @Test
    void rejectsInvalidMaterialWithHumanMessage() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("material: STONE", "material: NOT_A_BLOCK"), "unknown material 'NOT_A_BLOCK'");
    }

    @Test
    void rejectsBadItemAmount() throws Exception {
        String withItem = "menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("amount: 1", "amount: 65");
        assertInvalid(withItem, "1 to 64");
    }

    @Test
    void rejectsBadRewardId() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("reward_1:\n", "'Reward 1!':\n"), "invalid reward id");
    }

    @Test
    void rejectsUnknownActionType() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("actions:\n          - type: item\n            material: DIAMOND\n            amount: 1",
                        "actions:\n                        - type: particles"), "Use 'item' or 'command'");
    }

    @Test
    void rejectsNegativeRequiredSeconds() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("required-seconds: 10", "required-seconds: -5"), "required-seconds: N");
    }

    @Test
    void rejectsCommandWithoutCommand() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("actions:\n          - type: item\n            material: DIAMOND\n            amount: 1",
                        "actions:\n                        - type: command"),
                "without 'command'");
    }

    @Test
    void rejectsAirItemAction() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("material: DIAMOND", "material: AIR"),
                "unknown item 'AIR'");
    }

    @Test
    void rejectsEmptyActions() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("actions:\n          - type: item\n            material: DIAMOND\n            amount: 1",
                        "actions: []"),
                "defines no actions");
    }

    @Test
    void rejectsTooManyActions() throws Exception {
        StringBuilder many = new StringBuilder("actions:");
        for (int i = 0; i < 65; i++) {
            many.append("\n                        - type: item\n                          material: STONE\n                          amount: 1");
        }
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("actions:\n          - type: item\n            material: DIAMOND\n            amount: 1",
                        many.toString()),
                "at most 64");
    }

    @Test
    void rejectsMissingMenusSection() {
        try {
            MenuRegistry.parse(null);
            fail("expected failure");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("'menus'"));
        }
    }

    @Test
    void defaultMenuIdIsMain() {
        assertEquals("main", MenuRegistry.DEFAULT_MENU_ID);
    }

    static String soundsMenu(String soundsLines) {
        return "menus:\n"
                + "  main:\n"
                + "    name: M\n"
                + "    order: 1\n"
                + "    title: T\n"
                + "    rows: 3\n"
                + "    sounds:\n"
                + soundsLines
                + "    rewards:\n"
                + "      reward_1:\n"
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
                + "            amount: 1\n";
    }

    @Test
    void parsesSoundForms() throws Exception {
        Map<String, MenuDefinition> menus = parse(soundsMenu(
                "      open: BLOCK_CHEST_OPEN\n"
                + "      claim: \"entity.player.levelup\"\n"
                + "      locked: \"minecraft:block.note_block.bass\"\n"
                + "      already-claimed: \"\"\n"));
        var sounds = menus.get("main").sounds();
        assertEquals(new SoundConfig("BLOCK_CHEST_OPEN", 1.0f, 1.0f), sounds.get("open"));
        assertEquals(new SoundConfig("entity.player.levelup", 1.0f, 1.0f), sounds.get("claim"));
        assertEquals(new SoundConfig("minecraft:block.note_block.bass", 1.0f, 1.0f),
                sounds.get("locked"));
        assertEquals(new SoundConfig("", 1.0f, 1.0f), sounds.get("already-claimed"));
    }

    @Test
    void parsesAdvancedSoundObject() throws Exception {
        Map<String, MenuDefinition> menus = parse(soundsMenu(
                "      open:\n"
                + "        sound: \"block.chest.open\"\n"
                + "        volume: 0.8\n"
                + "        pitch: 1.2\n"
                + "      claim:\n"
                + "        sound: \"entity.player.levelup\"\n"));
        var sounds = menus.get("main").sounds();
        assertEquals(new SoundConfig("block.chest.open", 0.8f, 1.2f), sounds.get("open"));
        assertEquals(new SoundConfig("entity.player.levelup", 1.0f, 1.0f), sounds.get("claim"));
    }

    @Test
    void rejectsBadVolumeAndPitch() throws Exception {
        assertInvalid(soundsMenu(
                "      open:\n"
                + "        sound: \"block.chest.open\"\n"
                + "        volume: -1\n"), "Volume must be 0 or higher");
        assertInvalid(soundsMenu(
                "      open:\n"
                + "        sound: \"block.chest.open\"\n"
                + "        pitch: 0\n"), "Pitch must be above 0");
        assertInvalid(soundsMenu(
                "      open:\n"
                + "        sound: \"block.chest.open\"\n"
                + "        pitch: loud\n"), "Use a number");
    }

    @Test
    void rejectsNonStringSound() throws Exception {
        assertInvalid(soundsMenu("      open: 42\n"), "must be a sound key or a section");
    }

    // ---- time syntax ----

    @Test
    void parsesTimeUnits() {
        assertEquals(30L, MenuRegistry.parseTime("m", "r", "30s"));
        assertEquals(600L, MenuRegistry.parseTime("m", "r", "10m"));
        assertEquals(3600L, MenuRegistry.parseTime("m", "r", "1h"));
        assertEquals(7200L, MenuRegistry.parseTime("m", "r", "2H"));
        assertEquals(86400L, MenuRegistry.parseTime("m", "r", "1d"));
        assertEquals(604800L, MenuRegistry.parseTime("m", "r", "1w"));
        assertEquals(0L, MenuRegistry.parseTime("m", "r", "0s"));
    }

    @Test
    void rejectsBadTime() throws Exception {
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("required-seconds: 10", "required-seconds: 10\n        time: abc"), "s/m/h/d/w");
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("required-seconds: 10", "required-seconds: 10\n        time: -5"), "s/m/h/d/w");
        assertInvalid("menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                .replace("required-seconds: 10", "required-seconds: 10\n        time: 99999999999999999w"), "too large");
    }

    @Test
    void timeWinsOverRequiredSeconds() throws Exception {
        Map<String, MenuDefinition> menus = parse("menus:\n"
                + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                        .replace("required-seconds: 10", "required-seconds: 10\n        time: 2h"));
        assertEquals(7200L, menus.get("main").rewards().get("reward_1").requiredSeconds());
    }

    @Test
    void legacyRequiredSecondsKeepsWorking() throws Exception {
        Map<String, MenuDefinition> menus = parse(validMenus());
        assertEquals(1800L, menus.get("main").rewards().get("reward_1").requiredSeconds());
    }

    // ---- compact actions ----

    static String compactReward() {
        return "      mixed_compact:\n"
                + "        slot: 5\n"
                + "        required-seconds: 10\n"
                + "        display:\n"
                + "          name: M\n"
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
                + "          - command: \"say one\"\n"
                + "          - item: \"DIAMOND 10\"\n"
                + "          - command: \"say two\"\n"
                + "          - item: \"GOLD_INGOT\"\n";
    }

    static String menuWithCompact() {
        return "menus:\n" + menuBlock("main", "1", "3", compactReward());
    }

    @Test
    void compactActionsPreserveOrder() throws Exception {
        Map<String, MenuDefinition> menus = parse(menuWithCompact());
        var actions = menus.get("main").rewards().get("mixed_compact").actions();
        assertEquals(4, actions.size());
        assertEquals("say one", ((site.vackstudio.vplaytime.model.RewardAction.CommandAction) actions.get(0)).command());
        var item = (site.vackstudio.vplaytime.model.RewardAction.ItemAction) actions.get(1);
        assertEquals(org.bukkit.Material.DIAMOND, item.material());
        assertEquals(10, item.amount());
        assertEquals("say two", ((site.vackstudio.vplaytime.model.RewardAction.CommandAction) actions.get(2)).command());
        var bare = (site.vackstudio.vplaytime.model.RewardAction.ItemAction) actions.get(3);
        assertEquals(org.bukkit.Material.GOLD_INGOT, bare.material());
        assertEquals(1, bare.amount(), "missing amount defaults to 1");
    }

    @Test
    void rejectsCompactBadItem() throws Exception {
        assertInvalid(menuWithCompact().replace("- item: \"DIAMOND 10\"", "- item: \"NOPE 3\""),
                "unknown item 'NOPE'");
        assertInvalid(menuWithCompact().replace("- item: \"DIAMOND 10\"", "- item: \"DIAMOND 99\""),
                "1 to 64");
    }

    // ---- pagination shortcuts ----

    static String pagedMenus() {
        return "menus:\n"
                + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                + "    items:\n"
                + "      next:\n"
                + "        slot: 26\n"
                + "        material: ARROW\n"
                + "        name: N\n"
                + "        action: next-page\n"
                + menuBlock("menu_2", "2", "3", rewardBlock("reward_2", 0, 10))
                + "    items:\n"
                + "      prev:\n"
                + "        slot: 22\n"
                + "        material: ARROW\n"
                + "        name: P\n"
                + "        action: previous-page\n"
                + "      close:\n"
                + "        slot: 23\n"
                + "        material: BARRIER\n"
                + "        name: C\n"
                + "        action: close\n";
    }

    @Test
    void paginationShortcutsResolveToNeighbors() throws Exception {
        Map<String, MenuDefinition> menus = parse(pagedMenus());
        assertEquals(List.of(new MenuItem.Action.OpenMenu("menu_2")),
                menus.get("main").items().get("next").actions());
        assertEquals(List.of(new MenuItem.Action.OpenMenu("main")),
                menus.get("menu_2").items().get("prev").actions());
        assertEquals(List.of(new MenuItem.Action.Close()),
                menus.get("menu_2").items().get("close").actions());
    }

    @Test
    void nextPageOnLastMenuFails() throws Exception {
        assertInvalid(pagedMenus().replace("action: close", "action: next-page"), "last menu");
    }

    @Test
    void previousPageOnFirstMenuFails() throws Exception {
        assertInvalid(pagedMenus().replace("action: next-page", "action: previous-page"), "first menu");
    }

    @Test
    void rejectsUnknownShortcut() throws Exception {
        assertInvalid(pagedMenus().replace("action: next-page", "action: teleport"), "unknown action 'teleport'");
    }

    @Test
    void openMenuScalarShortcut() throws Exception {
        Map<String, MenuDefinition> menus = parse(
                pagedMenus().replace("action: next-page", "action: \"open-menu: menu_2\""));
        assertEquals(List.of(new MenuItem.Action.OpenMenu("menu_2")),
                menus.get("main").items().get("next").actions());
    }

    // ---- heads ----

    static String headButtons() {
        return "menus:\n"
                + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10))
                + "    items:\n"
                + "      me:\n"
                + "        slot: 20\n"
                + "        material: PLAYER_HEAD\n"
                + "        name: Me\n"
                + "        head: \"%player%\"\n"
                + "      notch:\n"
                + "        slot: 21\n"
                + "        material: PLAYER_HEAD\n"
                + "        name: N\n"
                + "        head: \"Notch\"\n"
                + "      textured:\n"
                + "        slot: 22\n"
                + "        material: PLAYER_HEAD\n"
                + "        name: T\n"
                + "        head:\n"
                + "          texture: \"aGVsbG8=\"\n";
    }

    @Test
    void parsesHeadVariants() throws Exception {
        Map<String, MenuDefinition> menus = parse(headButtons());
        var items = menus.get("main").items();
        assertEquals(new HeadData.Self(), items.get("me").head());
        assertEquals(new HeadData.Name("Notch"), items.get("notch").head());
        assertEquals(new HeadData.Texture("aGVsbG8="), items.get("textured").head());
    }

    @Test
    void rejectsBadHeadTexture() throws Exception {
        assertInvalid(headButtons().replace("texture: \"aGVsbG8=\"", "texture: \"!!!\""),
                "invalid head texture");
    }

    @Test
    void rejectsHeadOnRewardLook() throws Exception {
        String base = "menus:\n" + menuBlock("main", "1", "3", rewardBlock("reward_1", 0, 10));
        String withHead = base.replaceFirst("(?m)^(\\s*)material: STONE$",
                "$1material: PLAYER_HEAD\n$1head: \"%player%\"");
        assertInvalid(withHead, "only work on menu buttons");
    }

    // ---- display defaults ----

    static String menusWithDefaults() {
        return "defaults:\n"
                + "  reward:\n"
                + "    display:\n"
                + "      locked:\n"
                + "        material: STONE\n"
                + "      claimable:\n"
                + "        material: STONE\n"
                + "        glow: true\n"
                + "      claimed:\n"
                + "        material: STONE\n"
                + "menus:\n"
                + menuBlock("main", "1", "3",
                        "                      plain:\n"
                        + "                        slot: 0\n"
                        + "                        required-seconds: 5\n"
                        + "                        display:\n"
                        + "                          name: P\n"
                        + "                        actions:\n"
                        + "                          - type: item\n"
                        + "                            material: DIAMOND\n"
                        + "                            amount: 1\n");
    }

    private static Map<String, MenuDefinition> parseFile(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        RewardDefaults top = RewardDefaults.parse(config.getConfigurationSection("defaults"), "top:");
        return MenuRegistry.parse(config.getConfigurationSection("menus"), top);
    }

    private static void assertInvalidFile(String yaml, String expectedFragment) throws Exception {
        try {
            parseFile(yaml);
            fail("expected IllegalStateException containing: " + expectedFragment);
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains(expectedFragment),
                    "message '" + ex.getMessage() + "' should contain '" + expectedFragment + "'");
        }
    }

    @Test
    void defaultsFillMissingMaterials() throws Exception {
        var def = parseFile(menusWithDefaults()).get("main").rewards().get("plain");
        assertEquals(org.bukkit.Material.STONE, def.display().locked().material());
        assertEquals(org.bukkit.Material.STONE, def.display().claimable().material());
        assertTrue(def.display().claimable().glow());
    }

    @Test
    void perRewardValuesBeatDefaults() throws Exception {
        String yaml = menusWithDefaults().replace(
                "                        display:\n                          name: P\n",
                "                        display:\n                          name: P\n                          locked:\n                            material: DIAMOND\n");
        var def = parseFile(yaml).get("main").rewards().get("plain");
        assertEquals(org.bukkit.Material.DIAMOND, def.display().locked().material());
        assertEquals(org.bukkit.Material.STONE, def.display().claimed().material());
    }

    @Test
    void menuDefaultsBeatTopDefaults() throws Exception {
        String yaml = menusWithDefaults().replace("  main:\n", "  main:\n    defaults:\n      reward:\n        display:\n          locked:\n            material: GOLD_BLOCK\n");
        var def = parseFile(yaml).get("main").rewards().get("plain");
        assertEquals(org.bukkit.Material.GOLD_BLOCK, def.display().locked().material());
        assertEquals(org.bukkit.Material.STONE, def.display().claimed().material());
    }

    @Test
    void rejectsBadDefaultMaterial() throws Exception {
        assertInvalidFile(menusWithDefaults().replace("material: STONE", "material: NOPE"),
                "unknown material 'NOPE'");
    }

    // ---- compatibility: 1.4.0 style file loads unchanged ----

    @Test
    void legacyFullStyleLoads() throws Exception {
        Map<String, MenuDefinition> menus = parse("menus:\n"
                + "  main:\n"
                + "    name: \"&5&lPlaytime Rewards\"\n"
                + "    order: 1\n"
                + "    title: \"&5&lPlaytime Rewards\"\n"
                + "    rows: 6\n"
                + "    rewards:\n"
                + "      level_1:\n"
                + "        slot: 10\n"
                + "        required-seconds: 3600\n"
                + "        display:\n"
                + "          name: \"&d&l1 Hour Reward\"\n"
                + "          locked:\n"
                + "            material: MAGENTA_CANDLE\n"
                + "            lore:\n"
                + "              - \"&7Hi\"\n"
                + "          claimable:\n"
                + "            material: MAGENTA_CANDLE\n"
                + "            glow: true\n"
                + "            lore: []\n"
                + "          claimed:\n"
                + "            material: MAGENTA_CANDLE\n"
                + "            lore: []\n"
                + "        actions:\n"
                + "          - type: command\n"
                + "            command: \"addmoney %player% 1000\"\n"
                + "          - type: item\n"
                + "            material: DIAMOND\n"
                + "            amount: 10\n");
        var def = menus.get("main").rewards().get("level_1");
        assertEquals(3600L, def.requiredSeconds());
        assertEquals("<light_purple><bold>1 Hour Reward", def.display().name());
        assertEquals(2, def.actions().size());
    }

    static String menuWithSharedLore() {
        return "menus:\n"
                + "  main:\n"
                + "    name: M\n"
                + "    order: 1\n"
                + "    title: T\n"
                + "    rows: 3\n"
                + "    sounds: {}\n"
                + "    rewards:\n"
                + "      shared:\n"
                + "        slot: 0\n"
                + "        required-seconds: 10\n"
                + "        display:\n"
                + "          name: Shared\n"
                + "          lore:\n"
                + "            - \"<gray>One lore\"\n"
                + "          locked:\n"
                + "            material: STONE\n"
                + "          claimable:\n"
                + "            material: STONE\n"
                + "            glow: true\n"
                + "          claimed:\n"
                + "            material: STONE\n"
                + "        actions:\n"
                + "          - type: item\n"
                + "            material: DIAMOND\n"
                + "            amount: 1\n";
    }

    @Test
    void sharedLoreFallsBackToAllStates() throws Exception {
        var menu = parse(menuWithSharedLore()).get("main");
        var def = menu.rewards().get("shared");
        assertEquals(java.util.List.of("<gray>One lore"), def.display().locked().lore());
        assertEquals(java.util.List.of("<gray>One lore"), def.display().claimable().lore());
        assertEquals(java.util.List.of("<gray>One lore"), def.display().claimed().lore());
    }

    @Test
    void stateLoreOverridesShared() throws Exception {
        String yaml = menuWithSharedLore().replace(
                "          claimed:\n            material: STONE\n",
                "          claimed:\n            material: STONE\n            lore:\n              - \"<green>Own\"\n");
        var def = parse(yaml).get("main").rewards().get("shared");
        assertEquals(java.util.List.of("<green>Own"), def.display().claimed().lore());
        assertEquals(java.util.List.of("<gray>One lore"), def.display().locked().lore());
    }

    @Test
    void explicitEmptyLoreStaysEmpty() throws Exception {
        String yaml = menuWithSharedLore().replace(
                "          claimed:\n            material: STONE\n",
                "          claimed:\n            material: STONE\n            lore: []\n");
        var def = parse(yaml).get("main").rewards().get("shared");
        assertTrue(def.display().claimed().lore().isEmpty());
    }

    static String menuWithItems() {
        // NOTE: plain concatenation (no text block) so indentation is literal:
        // menus:0 main:2 fields:4 rewards:4 reward:6 items:4 button:6.
        return "menus:\n"
                + menuBlock("main", "1", "3", rewardBlock("reward_1", 11, 1800))
                + "    items:\n"
                + "      next_page:\n"
                + "        slot: 26\n"
                + "        material: ARROW\n"
                + "        name: \"<green>Next\"\n"
                + "        lore:\n"
                + "          - \"<gray>Open menu_2\"\n"
                + "        action:\n"
                + "          open-menu: menu_2\n"
                + "      close_button:\n"
                + "        slot: 22\n"
                + "        material: BARRIER\n"
                + "        name: \"<red>Close\"\n"
                + "        lore: []\n"
                + "        action:\n"
                + "          close: true\n"
                + menuBlock("menu_2", "2", "3", rewardBlock("reward_2", 0, 10));
    }

    @Test
    void parsesItems() throws Exception {
        Map<String, MenuDefinition> menus = parse(menuWithItems());
        MenuDefinition main = menus.get("main");
        assertEquals(2, main.items().size());
        var next = main.items().get("next_page");
        assertEquals(26, next.slot());
        assertEquals(org.bukkit.Material.ARROW, next.material());
        assertEquals("<green>Next", next.name());
        assertEquals(List.of(new MenuItem.Action.OpenMenu("menu_2")), next.actions());
        var close = main.items().get("close_button");
        assertEquals(List.of(new MenuItem.Action.Close()), close.actions());
        assertEquals("reward_1", main.slotToReward().get(11));
        assertEquals("next_page", main.slotToItem().get(26));
    }

    @Test
    void rejectsHyphenatedButtonId() throws Exception {
        assertInvalid(menuWithItems().replace("next_page:", "'next-page':"), "invalid button id");
    }

    @Test
    void rejectsItemSlotClashWithReward() throws Exception {
        assertInvalid(menuWithItems().replace("slot: 26", "slot: 11"), "same slot 11");
    }

    @Test
    void rejectsItemBadSlot() throws Exception {
        assertInvalid(menuWithItems().replace("slot: 26", "slot: 27"), "uses slot 27");
    }

    @Test
    void rejectsUnknownOpenMenuTarget() throws Exception {
        assertInvalid(menuWithItems().replace("open-menu: menu_2", "open-menu: menu_9"),
                "opens unknown menu 'menu_9'");
    }

    @Test
    void rejectsMissingItemAction() throws Exception {
        assertInvalid(menuWithItems().replace("action:\n          open-menu: menu_2",
                "action: {}"), "needs an 'action'");
    }

    @Test
    void rejectsItemBadMaterial() throws Exception {
        assertInvalid(menuWithItems().replace("material: ARROW", "material: NOPE"), "unknown material 'NOPE'");
    }

    static String menuWithMessageAndFill() {
        return "menus:\n"
                + menuBlock("main", "1", "3", rewardBlock("reward_1", 11, 1800))
                + "    fill:\n"
                + "      material: GREEN_STAINED_GLASS_PANE\n"
                + "      name: \"<white> \"\n"
                + "      lore: []\n"
                + "    items:\n"
                + "      store:\n"
                + "        slot: 16\n"
                + "        material: EMERALD\n"
                + "        name: \"<green>Store\"\n"
                + "        lore: []\n"
                + "        action:\n"
                + "          message: \"<gray>Shop soon\"\n"
                + "          close: true\n"
                + "      stats:\n"
                + "        slot: 25\n"
                + "        material: CLOCK\n"
                + "        name: \"<white>Stats\"\n"
                + "        lore:\n"
                + "          - \"<gray>%playtime%\"\n";
    }

    @Test
    void parsesMessageCloseAndDisplayOnlyAndFill() throws Exception {
        Map<String, MenuDefinition> menus = parse(menuWithMessageAndFill());
        MenuDefinition main = menus.get("main");
        assertEquals(List.of(new MenuItem.Action.Message("<gray>Shop soon"),
                new MenuItem.Action.Close()), main.items().get("store").actions());
        assertTrue(main.items().get("stats").actions().isEmpty(), "no action = display only");
        assertEquals(org.bukkit.Material.GREEN_STAINED_GLASS_PANE, main.fill().material());
    }

    @Test
    void rejectsOpenPlusClose() throws Exception {
        assertInvalid(menuWithMessageAndFill().replace("          message: \"<gray>Shop soon\"\n", "")
                .replace("          close: true\n", "          open-menu: main\n          close: true\n"),
                "both 'open-menu' and 'close'");
    }

    @Test
    void rejectsFillBadMaterial() throws Exception {
        assertInvalid(menuWithMessageAndFill().replace("material: GREEN_STAINED_GLASS_PANE",
                "material: NOPE"), "Fill in menu 'main'");
    }
}
