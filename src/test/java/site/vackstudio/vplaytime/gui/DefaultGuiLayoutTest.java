package site.vackstudio.vplaytime.gui;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.config.MenuDefinition;
import site.vackstudio.vplaytime.config.MenuRegistry;
import site.vackstudio.vplaytime.model.RewardState;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VPlaytime 1.9.2 default GUI: the DeluxeMenus-inspired 4-page layout,
 * rebuilt natively. Slots, pages and reward positions mirror the reference
 * design; every behavior (state, claims, navigation, validation) is the
 * existing VPlaytime system — no DeluxeMenus concepts leak in.
 */
class DefaultGuiLayoutTest {

    private static Map<String, MenuDefinition> menus() throws Exception {
        var stream = DefaultGuiLayoutTest.class.getResourceAsStream("/rewards.yml");
        Objects.requireNonNull(stream, "missing shipped rewards.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        return MenuRegistry.parse(yaml.getConfigurationSection("menus"));
    }

    private static String shippedRaw(String name) throws Exception {
        var stream = DefaultGuiLayoutTest.class.getResourceAsStream("/" + name);
        Objects.requireNonNull(stream, "missing shipped resource " + name);
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    // ---- page structure ----

    @Test
    void fourPagesInOrder() throws Exception {
        var menus = menus();
        assertEquals(4, MenuRegistry.pageCount(menus));
        List<String> ids = MenuRegistry.inOrder(menus).stream().map(MenuDefinition::id).toList();
        assertEquals(List.of("main", "menu_2", "menu_3", "menu_4"), ids);
        assertEquals(1, MenuRegistry.pageIndex(menus, "main"));
        assertEquals(4, MenuRegistry.pageIndex(menus, "menu_4"));
        assertEquals("menu_2", MenuRegistry.next(menus, "main").orElseThrow().id());
        assertEquals("menu_3", MenuRegistry.next(menus, "menu_2").orElseThrow().id());
        assertEquals("menu_4", MenuRegistry.next(menus, "menu_3").orElseThrow().id());
        assertTrue(MenuRegistry.next(menus, "menu_4").isEmpty());
        assertTrue(MenuRegistry.previous(menus, "main").isEmpty());
        assertEquals("main", MenuRegistry.previous(menus, "menu_2").orElseThrow().id());
    }

    // ---- slot mapping (DeluxeMenus reference positions) ----

    @Test
    void rewardSlotsMatchReferenceDesign() throws Exception {
        var menus = menus();
        int[] slots = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23, 28, 29, 30, 31, 32};
        String[] pages = {"main", "menu_2", "menu_3", "menu_4"};
        for (int page = 0; page < 4; page++) {
            var menu = menus.get(pages[page]);
            assertEquals(54, menu.size());
            for (int index = 0; index < 15; index++) {
                String id = "level_" + (page * 15 + index + 1);
                assertEquals(slots[index], menu.rewards().get(id).slot(),
                        pages[page] + " " + id);
            }
        }
    }

    @Test
    void noSlotCollisionsAndAllSlotsValid() throws Exception {
        var menus = menus();
        for (var menu : menus.values()) {
            Set<Integer> used = new HashSet<>();
            for (var reward : menu.rewards().values()) {
                assertTrue(reward.slot() >= 0 && reward.slot() < 54);
                assertTrue(used.add(reward.slot()), "duplicate reward slot " + reward.slot());
            }
            for (var item : menu.items().values()) {
                assertTrue(item.slot() >= 0 && item.slot() < 54);
                assertTrue(used.add(item.slot()),
                        "button '" + item.id() + "' collides at slot " + item.slot());
            }
        }
    }

    @Test
    void buttonSlotsMatchReferenceDesign() throws Exception {
        var menus = menus();
        for (String id : List.of("main", "menu_2", "menu_3", "menu_4")) {
            var items = menus.get(id).items();
            assertEquals(49, items.get("close").slot(), id + " close");
            assertEquals(16, items.get("store").slot(), id + " store");
            assertEquals(25, items.get("stats").slot(), id + " stats");
            assertEquals(Material.BARRIER, items.get("close").material());
        }
        // Navigation chain: 1 -> 2 -> 3 -> 4.
        assertEquals("menu_2", nextTarget(menus, "main"));
        assertEquals("menu_3", nextTarget(menus, "menu_2"));
        assertEquals("menu_4", nextTarget(menus, "menu_3"));
        assertEquals("main", prevTarget(menus, "menu_2"));
        assertEquals("menu_2", prevTarget(menus, "menu_3"));
        assertEquals("menu_3", prevTarget(menus, "menu_4"));
    }

    private static String nextTarget(Map<String, MenuDefinition> menus, String id) {
        return menus.get(id).items().get("next").actions().stream()
                .filter(a -> a instanceof site.vackstudio.vplaytime.config.MenuItem.Action.OpenMenu)
                .map(a -> ((site.vackstudio.vplaytime.config.MenuItem.Action.OpenMenu) a).menuId())
                .findFirst().orElseThrow();
    }

    private static String prevTarget(Map<String, MenuDefinition> menus, String id) {
        return menus.get(id).items().get("prev").actions().stream()
                .filter(a -> a instanceof site.vackstudio.vplaytime.config.MenuItem.Action.OpenMenu)
                .map(a -> ((site.vackstudio.vplaytime.config.MenuItem.Action.OpenMenu) a).menuId())
                .findFirst().orElseThrow();
    }

    @Test
    void fillerCoversBackground() throws Exception {
        var menus = menus();
        for (var menu : menus.values()) {
            assertTrue(menu.fill() != null, menu.id() + " should have background fill");
            assertEquals(Material.GREEN_STAINED_GLASS_PANE, menu.fill().material());
        }
    }

    // ---- reward mapping: hours per level (reference design values) ----

    @Test
    void requiredHoursMatchReferenceDesign() throws Exception {
        long[] hours = {
            1, 4, 8, 12, 18, 26, 34, 42, 52, 64, 72, 82, 92, 102, 112,
            122, 132, 142, 150, 160, 170, 180, 190, 200, 215,
            230, 245, 260, 280, 300,
            315, 330, 345, 360, 375, 390, 405, 420, 435, 440,
            450, 460, 470, 480, 490,
            500, 510, 520, 530, 535, 540, 544, 547, 550, 553,
            555, 557, 558, 559, 560,
        };
        var menus = menus();
        String[] pages = {"main", "menu_2", "menu_3", "menu_4"};
        for (int level = 1; level <= 60; level++) {
            var def = menus.get(pages[(level - 1) / 15]).rewards().get("level_" + level);
            assertEquals(hours[level - 1] * 3600L, def.requiredSeconds(), "level_" + level);
        }
    }

    // ---- visual states ----

    @Test
    void trafficLightStatesOnEveryLevel() throws Exception {
        var menus = menus();
        for (var menu : menus.values()) {
            for (var def : menu.rewards().values()) {
                var locked = def.display().forState(RewardState.LOCKED);
                var claimable = def.display().forState(RewardState.CLAIMABLE);
                var claimed = def.display().forState(RewardState.CLAIMED);
                assertEquals(Material.RED_CANDLE, locked.material());
                assertFalse(locked.glow());
                assertEquals(Material.ORANGE_CANDLE, claimable.material());
                assertFalse(claimable.glow());
                assertEquals(Material.LIME_CANDLE, claimed.material());
                assertTrue(claimed.glow());
            }
        }
    }

    @Test
    void stateLoreEndings() throws Exception {
        var menus = menus();
        var def = menus.get("main").rewards().get("level_1");
        List<String> locked = def.display().forState(RewardState.LOCKED).lore();
        List<String> claimable = def.display().forState(RewardState.CLAIMABLE).lore();
        List<String> claimed = def.display().forState(RewardState.CLAIMED).lore();
        assertTrue(locked.get(locked.size() - 1).contains("Locked"));
        assertTrue(claimable.stream().anyMatch(line -> line.contains("CLICK")));
        assertFalse(locked.stream().anyMatch(line -> line.contains("CLICK")));
        assertFalse(claimed.stream().anyMatch(line -> line.contains("CLICK")));
        assertTrue(claimed.get(claimed.size() - 1).contains("Claimed"));
    }

    // ---- no DeluxeMenus leakage ----

    @Test
    void shippedResourcesContainNoDeluxeMenusConcepts() throws Exception {
        for (String name : List.of("rewards.yml", "config.yml", "messages.yml", "paper-plugin.yml")) {
            String raw = shippedRaw(name).toLowerCase();
            // NB: config.yml legitimately documents '%statistic_seconds_played%'
            // as the placeholder-provider example (provider defaults to
            // internal, so nothing resolves it); covered separately below.
            for (String marker : List.of("deluxemenus", "basehead",
                    "click_requirement", "on_left_click", "on_right_click", "deny_commands",
                    "levels1", "levels2", "levels3", "levels4", "lp user",
                    "minimum_requirements", "has permission", "gui_menus")) {
                assertFalse(raw.contains(marker),
                        name + " must not contain DeluxeMenus concept '" + marker + "'");
            }
        }
        String rewards = shippedRaw("rewards.yml");
        assertFalse(rewards.contains("exp give"), "use 'xp add', not removed 'exp give'");
        assertFalse(rewards.contains("points give"), "use 'addshards', not 'points give'");
        assertFalse(rewards.contains("YELLOW_CANDLE"), "claimable is ORANGE_CANDLE");
    }

    @Test
    void javaSourcesReferenceNoDeluxeMenus() throws Exception {
        Path root = Path.of("src/main/java");
        if (!Files.isDirectory(root)) {
            return; // Only meaningful when run from the project directory.
        }
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> hits = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).toLowerCase().contains("deluxemenus");
                        } catch (Exception ex) {
                            return false;
                        }
                    }).toList();
            assertTrue(hits.isEmpty(), "sources referencing DeluxeMenus: " + hits);
        }
    }

    @Test
    void shippedButtonsUseOnlyNativePlaceholders() throws Exception {
        // The stats readout renders from the active provider; nothing in the
        // default requires PlaceholderAPI or a statistics expansion.
        String rewards = shippedRaw("rewards.yml");
        assertTrue(rewards.contains("%playtime_hours%"));
        assertFalse(rewards.contains("%player_name%"));
        assertFalse(rewards.contains("%statistic_hours_played%"));
    }

    @Test
    void shippedDefaultKeepsInternalProvider() throws Exception {
        var stream = DefaultGuiLayoutTest.class.getResourceAsStream("/config.yml");
        Objects.requireNonNull(stream);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        assertEquals("internal", yaml.getString("playtime.provider"));
    }
}
