package site.vackstudio.vplaytime.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import site.vackstudio.vplaytime.gui.Text;
import site.vackstudio.vplaytime.model.RewardAction;
import site.vackstudio.vplaytime.model.RewardDefinition;
import site.vackstudio.vplaytime.model.RewardDisplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure parser for the {@code menus} section of rewards.yml: validates
 * everything and returns an immutable menu map without touching any live
 * state, so callers can validate all files before publishing any of them
 * (transactional reload).
 *
 * <p>Every failure carries a human-readable message that names the file,
 * menu and reward involved — a server owner should be able to fix the
 * file without reading source code. Nothing is silently fixed or ignored.
 *
 * <p>Note: duplicate ids inside one file cannot survive YAML parsing
 * (mapping keys are unique; a repeated key keeps its last value), so there
 * is no ambiguous double-definition to detect at this layer.
 */
public final class MenuRegistry {

    /** Menu id opened by bare {@code /vplaytime}. Must always be configured. */
    public static final String DEFAULT_MENU_ID = "main";

    /** All input parsed here comes from this file (used in diagnostics). */
    static final String FILE = "rewards.yml";

    private static ConfigError err(String path, String reason) {
        return new ConfigError(FILE, path, reason);
    }

    private static String text(String path, String value) {
        try {
            return Text.normalize(path, value);
        } catch (IllegalStateException ex) {
            throw err(path, ex.getMessage());
        }
    }

    private static List<String> loreLines(String path, List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(text(path, line));
        }
        return List.copyOf(out);
    }

    /**
     * Safety cap per reward: large enough for any realistic setup (a full
     * 6-row menu holds 54 items), small enough to catch runaway generation.
     */
    public static final int MAX_ACTIONS_PER_REWARD = 64;

    private static final List<String> SOUND_KEYS = List.of("open", "claim", "locked", "already-claimed");

    private MenuRegistry() {
    }

    /**
     * @param root {@code menus} section of rewards.yml
     * @return menus in file order, unmodifiable
     * @throws IllegalStateException with a human-readable message on any invalid data
     */
    public static Map<String, MenuDefinition> parse(ConfigurationSection root) {
        return parse(root, RewardDefaults.empty());
    }

    /**
     * Parses with file-level display defaults (from the top-level
     * {@code defaults:} section of rewards.yml, may be empty).
     */
    public static Map<String, MenuDefinition> parse(ConfigurationSection root, RewardDefaults topDefaults) {
        if (root == null) {
            throw err("menus", "rewards.yml is missing the 'menus' section.");
        }
        Set<String> ids = root.getKeys(false);
        if (ids.isEmpty()) {
            throw err("menus", "rewards.yml defines no menus under 'menus'.");
        }
        Map<String, MenuDefinition> parsed = new LinkedHashMap<>();
        for (String id : ids) {
            parsed.put(id, parseMenu(id, root.getConfigurationSection(id), topDefaults));
        }
        if (!parsed.containsKey(DEFAULT_MENU_ID)) {
            throw err("menus",
                    "rewards.yml must define a 'main' menu (it is opened by /vplaytime).");
        }
        Map<String, Integer> orders = new HashMap<>();
        for (MenuDefinition menu : parsed.values()) {
            String clash = null;
            for (var entry : orders.entrySet()) {
                if (entry.getValue() == menu.order()) {
                    clash = entry.getKey();
                    break;
                }
            }
            if (clash != null) {
                throw err("menus",
                        "Menus '" + clash + "' and '" + menu.id()
                        + "' share order " + menu.order()
                        + ". Give each menu its own order number (1, 2, 3, ...).");
            }
            orders.put(menu.id(), menu.order());
        }
        for (MenuDefinition menu : parsed.values()) {
            for (MenuItem item : menu.items().values()) {
                for (MenuItem.Action action : item.actions()) {
                    if (action instanceof MenuItem.Action.OpenMenu open
                            && !parsed.containsKey(open.menuId())) {
                        throw err("menus." + menu.id() + ".items." + item.id() + ".action.open-menu",
                                "Button '" + item.id() + "' in menu '" + menu.id()
                                + "' opens unknown menu '" + open.menuId()
                                + "'. Available menus: " + String.join(", ", parsed.keySet()) + ".");
                    }
                }
            }
        }
        resolvePageShortcuts(parsed);
        return Map.copyOf(parsed);
    }

    /**
     * Rewrites {@code next-page} / {@code previous-page} shortcuts into the
     * neighboring menu in order-sorted sequence (page 1, 2, 3, ...).
     */
    private static void resolvePageShortcuts(Map<String, MenuDefinition> parsed) {
        List<MenuDefinition> ordered = parsed.values().stream()
                .sorted(Comparator.comparingInt(MenuDefinition::order).thenComparing(MenuDefinition::id))
                .toList();
        Map<String, MenuDefinition> resolved = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            MenuDefinition menu = ordered.get(index);
            if (menu.items().values().stream().noneMatch(item -> item.actions().stream()
                    .anyMatch(action -> action instanceof MenuItem.Action.NextPage
                            || action instanceof MenuItem.Action.PreviousPage))) {
                resolved.put(menu.id(), menu);
                continue;
            }
            Map<String, MenuItem> items = new LinkedHashMap<>();
            for (MenuItem item : menu.items().values()) {
                List<MenuItem.Action> actions = new ArrayList<>(item.actions().size());
                for (MenuItem.Action action : item.actions()) {
                    if (action instanceof MenuItem.Action.NextPage) {
                        if (index + 1 >= ordered.size()) {
                            throw err("menus." + menu.id() + ".items." + item.id() + ".action",
                                    "Button '" + item.id() + "' in menu '" + menu.id()
                                    + "' uses 'next-page', but '" + menu.id()
                                    + "' is the last menu. Point it at a menu with 'open-menu:' instead.");
                        }
                        actions.add(new MenuItem.Action.OpenMenu(ordered.get(index + 1).id()));
                    } else if (action instanceof MenuItem.Action.PreviousPage) {
                        if (index == 0) {
                            throw err("menus." + menu.id() + ".items." + item.id() + ".action",
                                    "Button '" + item.id() + "' in menu '" + menu.id()
                                    + "' uses 'previous-page', but '" + menu.id()
                                    + "' is the first menu. Point it at a menu with 'open-menu:' instead.");
                        }
                        actions.add(new MenuItem.Action.OpenMenu(ordered.get(index - 1).id()));
                    } else {
                        actions.add(action);
                    }
                }
                items.put(item.id(), new MenuItem(item.id(), item.slot(), item.material(), item.name(),
                        item.lore(), item.glow(), actions, item.head()));
            }
            resolved.put(menu.id(), new MenuDefinition(menu.id(), menu.name(), menu.order(), menu.title(),
                    menu.rows(), menu.sounds(), menu.rewards(), items, menu.fill()));
        }
        parsed.clear();
        parsed.putAll(resolved);
    }

    // ---- ordering / navigation foundation (no GUI navigation yet) ----

    /** Menus sorted by order ascending (ties broken by menu id). Page 1 first. */
    public static List<MenuDefinition> inOrder(Map<String, MenuDefinition> menus) {
        return menus.values().stream()
                .sorted(Comparator.comparingInt(MenuDefinition::order).thenComparing(MenuDefinition::id))
                .toList();
    }

    /** 1-based page number of a menu in order-sorted sequence, or -1. */
    public static int pageIndex(Map<String, MenuDefinition> menus, String menuId) {
        List<MenuDefinition> ordered = inOrder(menus);
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id().equals(menuId)) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Total page count (one page per menu). */
    public static int pageCount(Map<String, MenuDefinition> menus) {
        return menus.size();
    }

    /** Menu after the given one in order-sorted sequence, if any. */
    public static Optional<MenuDefinition> next(Map<String, MenuDefinition> menus, String menuId) {
        List<MenuDefinition> ordered = inOrder(menus);
        for (int i = 0; i < ordered.size() - 1; i++) {
            if (ordered.get(i).id().equals(menuId)) {
                return Optional.of(ordered.get(i + 1));
            }
        }
        return Optional.empty();
    }

    /** Menu before the given one in order-sorted sequence, if any. */
    public static Optional<MenuDefinition> previous(Map<String, MenuDefinition> menus, String menuId) {
        List<MenuDefinition> ordered = inOrder(menus);
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).id().equals(menuId)) {
                return Optional.of(ordered.get(i - 1));
            }
        }
        return Optional.empty();
    }

    // ---- parsing ----

    private static MenuDefinition parseMenu(String id, ConfigurationSection sec, RewardDefaults top) {
        String base = "menus." + id;
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw err("menus",
                    "Invalid menu id '" + id + "'. Use lowercase letters, numbers and underscores (for example 'menu_2').");
        }
        if (sec == null) {
            throw err(base, "Menu '" + id + "' must be a section.");
        }
        String title = sec.getString("title", "");
        if (title == null || title.isBlank()) {
            throw err(base + ".title",
                    "Menu '" + id + "' needs a non-blank 'title' (the inventory window title).");
        }
        title = text(base + ".title", title);
        // 'name' is optional legacy: when omitted the title doubles as the
        // label. Nothing user-visible reads it separately anymore.
        String rawName = sec.getString("name", "");
        String name = (rawName == null || rawName.isBlank()) ? title : text(base + ".name", rawName);
        int order = sec.getInt("order", 0);
        if (order < 1) {
            throw err(base + ".order", "Menu '" + id
                    + "' needs 'order: N' (a unique whole number starting at 1, for example 1, 2, 3).");
        }
        int rows = sec.getInt("rows", 3);
        if (rows < 1 || rows > 6) {
            throw err(base + ".rows",
                    "Menu '" + id + "' has rows: " + rows + ", but rows must be 1-6.");
        }
        int size = rows * 9;

        Map<String, SoundConfig> sounds = new HashMap<>();
        ConfigurationSection soundsSec = sec.getConfigurationSection("sounds");
        for (String key : SOUND_KEYS) {
            // Values stay raw here (unknown sounds warn and stay silent at
            // sound-resolution time, never a load failure); only the SHAPE
            // (string vs section) and volume/pitch numbers are validated now.
            sounds.put(key, parseSound(id, key, soundsSec == null ? null : soundsSec.get(key)));
        }

        Map<String, RewardDefinition> rewards = new LinkedHashMap<>();
        Map<Integer, String> usedSlots = new HashMap<>();
        ConfigurationSection rewardsSec = sec.getConfigurationSection("rewards");
        if (rewardsSec == null || rewardsSec.getKeys(false).isEmpty()) {
            throw err("menus." + id + ".rewards",
                    "Menu '" + id + "' defines no rewards under 'rewards'.");
        }
        RewardDefaults menuDefaults = RewardDefaults.parse(sec.getConfigurationSection("defaults"),
                "menus." + id + ".defaults").mergedOver(top);
        for (String rewardId : rewardsSec.getKeys(false)) {
            RewardDefinition def = parseReward(id, rewardId,
                    rewardsSec.getConfigurationSection(rewardId), size, menuDefaults, top);
            String clash = usedSlots.put(def.slot(), rewardId);
            if (clash != null) {
                throw err("menus." + id + ".rewards",
                        "Menu '" + id + "' shows reward '" + clash + "' and reward '"
                        + rewardId + "' in the same slot " + def.slot()
                        + ". Give each reward its own slot.");
            }
            rewards.put(rewardId, def);
        }

        Map<String, MenuItem> items = new LinkedHashMap<>();
        ConfigurationSection itemsSec = sec.getConfigurationSection("items");
        if (itemsSec != null) {
            for (String itemId : itemsSec.getKeys(false)) {
                MenuItem item = parseItem(id, itemId, itemsSec.getConfigurationSection(itemId), size);
                String clash = usedSlots.put(item.slot(), "button '" + itemId + "'");
                if (clash != null) {
                    throw err("menus." + id + ".items",
                            "Menu '" + id + "' shows " + clash + " and button '"
                            + itemId + "' in the same slot " + item.slot()
                            + ". Give each reward and button its own slot.");
                }
                items.put(itemId, item);
            }
        }
        MenuFill fill = parseFill(id, sec.getConfigurationSection("fill"));
        return new MenuDefinition(id, name, order, title, rows, sounds, rewards, items, fill);
    }

    /** Parses one menu button (navigation or close). */
    static MenuItem parseItem(String menuId, String id, ConfigurationSection sec, int menuSize) {
        String base = "menus." + menuId + ".items." + id;
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw err("menus." + menuId + ".items",
                    "Menu '" + menuId + "' contains invalid button id '" + id
                    + "'. Use lowercase letters, numbers and underscores (for example 'next_page').");
        }
        if (sec == null) {
            throw err(base, "Button '" + id + "' in menu '" + menuId + "' must be a section.");
        }
        int slot = sec.getInt("slot", -1);
        if (slot < 0 || slot >= menuSize) {
            throw err(base + ".slot",
                    "Button '" + id + "' in menu '" + menuId + "' uses slot " + slot
                    + ", but the menu only has " + (menuSize / 9) + " rows (" + menuSize + " slots, numbered 0-"
                    + (menuSize - 1) + ").");
        }
        String matName = sec.getString("material", "");
        Material material = Material.matchMaterial(String.valueOf(matName));
        if (material == null) {
            throw err(base + ".material",
                    "Button '" + id + "' in menu '" + menuId
                    + "' uses unknown material '" + matName
                    + "'. Use a Bukkit material name such as ARROW or BARRIER.");
        }
        String name = sec.getString("name", "<white>" + id);
        List<String> lore = loreLines(base + ".lore", sec.getStringList("lore"));
        boolean glow = sec.getBoolean("glow", false);
        List<MenuItem.Action> actions = parseItemActions(menuId, id, sec.get("action"));
        HeadData head = parseHead(menuId, id, sec.get("head"));
        return new MenuItem(id, slot, material, text(base + ".name", name), lore, glow, actions, head);
    }

    /** Player-head identity for {@code material: PLAYER_HEAD} (null = none). */
    static HeadData parseHead(String menuId, String id, Object raw) {
        String base = "menus." + menuId + ".items." + id + ".head";
        if (raw == null) {
            return new HeadData.None();
        }
        if (raw instanceof String text) {
            if (text.equalsIgnoreCase("%player%")) {
                return new HeadData.Self();
            }
            if (text.isBlank()) {
                throw err(base,
                        "Button '" + id + "' in menu '" + menuId
                        + "' has a blank 'head:'. Use '%player%', a player name, or a texture section.");
            }
            return new HeadData.Name(text.strip());
        }
        if (raw instanceof ConfigurationSection sec) {
            String texture = sec.getString("texture", "");
            if (texture == null || texture.isBlank()) {
                throw err(base,
                        "Button '" + id + "' in menu '" + menuId
                        + "' has 'head:' without 'texture:'.");
            }
            try {
                java.util.Base64.getDecoder().decode(texture.strip());
            } catch (IllegalArgumentException ex) {
                throw err(base,
                        "Button '" + id + "' in menu '" + menuId
                        + "' has an invalid head texture (not valid Base64).");
            }
            return new HeadData.Texture(texture.strip());
        }
        throw err(base,
                "Button '" + id + "' in menu '" + menuId
                + "' has an invalid 'head:'. Use '%player%', a player name, or 'texture: ...'.");
    }

    /**
     * Click actions run in file order: an optional message first, then
     * exactly one of open-menu / close. A missing action means a pure
     * display button. A plain string is a shortcut ({@code next-page},
     * {@code previous-page}, {@code close}, {@code open-menu: <id>},
     * {@code message: <text>}).
     */
    static List<MenuItem.Action> parseItemActions(String menuId, String id, Object raw) {
        String base = "menus." + menuId + ".items." + id + ".action";
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof String shortcut) {
            return List.of(parseShortcut(menuId, id, shortcut));
        }
        if (!(raw instanceof ConfigurationSection sec)) {
            throw err(base,
                    "Button '" + id + "' in menu '" + menuId
                    + "' has an invalid 'action:'. Use 'next-page', 'previous-page', 'close',"
                    + " 'open-menu: <id>', 'message: ...' or a section with those keys.");
        }
        List<MenuItem.Action> actions = new ArrayList<>();
        String message = sec.getString("message", "");
        if (message != null && !message.isBlank()) {
            actions.add(new MenuItem.Action.Message(text(base + ".message", message)));
        }
        // NOTE: isSet() is blind to null-valued keys, so presence of an
        // explicitly empty 'open-menu:' is detected via getKeys().
        boolean openSet = sec.getKeys(false).contains("open-menu");
        String target = sec.getString("open-menu", "");
        boolean open = target != null && !target.isBlank();
        boolean close = sec.getBoolean("close", false);
        if (openSet && !open) {
            throw err(base + ".open-menu",
                    "Expected a menu id but received an empty value."
                    + " Use 'open-menu: menu_2', a shortcut like 'next-page', or remove the action.");
        }
        if (open && close) {
            throw err(base,
                    "Button '" + id + "' in menu '" + menuId
                    + "' uses both 'open-menu' and 'close'. Use one of them.");
        }
        if (open) {
            if (!target.matches("[a-z0-9_]+")) {
                throw err(base + ".open-menu",
                        "Button '" + id + "' in menu '" + menuId
                        + "' opens invalid menu id '" + target + "'. Use the menu ID from rewards.yml.");
            }
            actions.add(new MenuItem.Action.OpenMenu(target));
        } else if (close) {
            actions.add(new MenuItem.Action.Close());
        } else if (actions.isEmpty()) {
            throw err(base,
                    "Button '" + id + "' in menu '" + menuId
                    + "' needs an 'action' (for example 'message: ...', 'open-menu: menu_2' or 'close: true').");
        }
        return List.copyOf(actions);
    }

    private static MenuItem.Action parseShortcut(String menuId, String id, String shortcut) {
        String base = "menus." + menuId + ".items." + id + ".action";
        String text = shortcut.strip();
        if (text.equalsIgnoreCase("next-page")) {
            return new MenuItem.Action.NextPage();
        }
        if (text.equalsIgnoreCase("previous-page")) {
            return new MenuItem.Action.PreviousPage();
        }
        if (text.equalsIgnoreCase("close")) {
            return new MenuItem.Action.Close();
        }
        if (text.toLowerCase(java.util.Locale.ROOT).startsWith("open-menu:")) {
            String target = text.substring("open-menu:".length()).strip();
            if (!target.matches("[a-z0-9_]+")) {
                throw err(base,
                        "Button '" + id + "' in menu '" + menuId
                        + "' opens invalid menu id '" + target + "'. Use the menu ID from rewards.yml.");
            }
            return new MenuItem.Action.OpenMenu(target);
        }
        if (text.toLowerCase(java.util.Locale.ROOT).startsWith("message:")) {
            String message = text.substring("message:".length()).strip();
            if (message.isBlank()) {
                throw err(base,
                        "Button '" + id + "' in menu '" + menuId + "' has an empty 'message:' shortcut.");
            }
            return new MenuItem.Action.Message(text(base, message));
        }
        throw err(base,
                "Button '" + id + "' in menu '" + menuId
                + "' has unknown action '" + shortcut + "'. Use 'next-page', 'previous-page', 'close',"
                + " 'open-menu: <id>' or 'message: ...'.");
    }

    /**
     * One menu sound event in three accepted shapes: a plain key
     * ({@code BLOCK_CHEST_OPEN}, {@code block.chest.open} or
     * {@code minecraft:block.chest.open}), an advanced section
     * ({@code sound:} + optional {@code volume:} / {@code pitch:}), or
     * missing/blank (silence). Registry existence is checked later, once,
     * against the live server registry.
     */
    static SoundConfig parseSound(String menuId, String field, Object raw) {
        String base = "menus." + menuId + ".sounds." + field;
        if (raw == null) {
            return new SoundConfig("", 1.0f, 1.0f);
        }
        if (raw instanceof String text) {
            return new SoundConfig(text.trim(), 1.0f, 1.0f);
        }
        if (raw instanceof ConfigurationSection sec) {
            String key = sec.getString("sound", "");
            float volume = parseVolume(menuId, field, sec.get("volume"));
            float pitch = parsePitch(menuId, field, sec.get("pitch"));
            return new SoundConfig(key == null ? "" : key.trim(), volume, pitch);
        }
        throw err(base,
                "Menu '" + menuId + "' sound '" + field
                + "' must be a sound key or a section with 'sound:'/'volume:'/'pitch:'.");
    }

    private static float parseVolume(String menuId, String field, Object raw) {
        if (raw == null) {
            return 1.0f;
        }
        double value = toDouble(menuId, field, "volume", raw);
        if (!Double.isFinite(value) || value < 0) {
            throw err("menus." + menuId + ".sounds." + field,
                    "Menu '" + menuId + "' sound '" + field
                    + "' has volume '" + raw + "'. Volume must be 0 or higher.");
        }
        return (float) value;
    }

    private static float parsePitch(String menuId, String field, Object raw) {
        if (raw == null) {
            return 1.0f;
        }
        double value = toDouble(menuId, field, "pitch", raw);
        if (!Double.isFinite(value) || value <= 0 || value > 2) {
            throw err("menus." + menuId + ".sounds." + field,
                    "Menu '" + menuId + "' sound '" + field
                    + "' has pitch '" + raw + "'. Pitch must be above 0 and at most 2.");
        }
        return (float) value;
    }

    private static double toDouble(String menuId, String field, String what, Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ex) {
                // Fall through to the human error below.
            }
        }
        throw err("menus." + menuId + ".sounds." + field,
                "Menu '" + menuId + "' sound '" + field
                + "' has " + what + " '" + raw + "'. Use a number (for example volume: 0.8).");
    }

    /** Optional background filler for a menu's empty slots (null = none). */
    static MenuFill parseFill(String menuId, ConfigurationSection sec) {
        if (sec == null) {
            return null;
        }
        String base = "menus." + menuId + ".fill";
        String matName = sec.getString("material", "");
        Material material = Material.matchMaterial(String.valueOf(matName));
        if (material == null) {
            throw err(base + ".material",
                    "Fill in menu '" + menuId + "' uses unknown material '" + matName
                    + "'. Use a Bukkit material name such as GREEN_STAINED_GLASS_PANE.");
        }
        return new MenuFill(material, text(base + ".name", sec.getString("name", "")),
                loreLines(base + ".lore", sec.getStringList("lore")));
    }

    /** Parses one reward entry; also used by RewardManager's consistency merge. */
    static RewardDefinition parseReward(String menuId, String id, ConfigurationSection sec, int menuSize) {
        return parseReward(menuId, id, sec, menuSize, RewardDefaults.empty(), RewardDefaults.empty());
    }

    static RewardDefinition parseReward(String menuId, String id, ConfigurationSection sec, int menuSize,
            RewardDefaults menuDefaults, RewardDefaults topDefaults) {
        String base = "menus." + menuId + ".rewards." + id;
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw err("menus." + menuId + ".rewards",
                    "Menu '" + menuId + "' contains invalid reward id '" + id
                    + "'. Use lowercase letters, numbers and underscores.");
        }
        if (sec == null) {
            throw err(base, "Reward '" + id + "' in menu '" + menuId + "' must be a section.");
        }
        int slot = sec.getInt("slot", -1);
        if (slot < 0 || slot >= menuSize) {
            throw err(base + ".slot",
                    "Reward '" + id + "' in menu '" + menuId + "' uses slot " + slot
                    + ", but the menu only has " + (menuSize / 9) + " rows (" + menuSize + " slots, numbered 0-"
                    + (menuSize - 1) + ").");
        }
        long required = parseRequiredSeconds(menuId, id, sec);
        String name = text(base + ".display.name", sec.getString("display.name", "<white>" + id));
        RewardDisplay display = new RewardDisplay(
                name,
                parseDisplayState(menuId, id, sec, "locked", menuDefaults, topDefaults),
                parseDisplayState(menuId, id, sec, "claimable", menuDefaults, topDefaults),
                parseDisplayState(menuId, id, sec, "claimed", menuDefaults, topDefaults));
        List<RewardAction> actions = parseActions(menuId, id, sec);
        return new RewardDefinition(id, slot, required, display, actions);
    }

    /**
     * Preferred {@code time: 1h} wins when both time syntaxes are present
     * (deterministic rule, documented in rewards.yml). Legacy
     * {@code required-seconds} keeps working exactly as before.
     */
    static long parseRequiredSeconds(String menuId, String id, ConfigurationSection sec) {
        String base = "menus." + menuId + ".rewards." + id;
        if (sec.isSet("time")) {
            return parseTime(menuId, id, String.valueOf(sec.getString("time", "")));
        }
        long required = sec.getLong("required-seconds", -1);
        if (required < 0) {
            throw err(base,
                    "Reward '" + id + "' in menu '" + menuId
                    + "' needs 'time: 1h' (or legacy 'required-seconds: N' with N >= 0).");
        }
        return required;
    }

    /** Human durations: {@code 30s}, {@code 10m}, {@code 1h}, {@code 2d}, {@code 1w}. */
    static long parseTime(String menuId, String id, String raw) {
        String base = "menus." + menuId + ".rewards." + id + ".time";
        var matcher = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*([smhdwSMHDW])\\s*$")
                .matcher(raw == null ? "" : raw);
        if (!matcher.matches()) {
            throw err(base,
                    "Reward '" + id + "' in menu '" + menuId
                    + "' has time: '" + raw + "'. Use a number plus s/m/h/d/w"
                    + " (for example 30s, 10m, 1h, 2d, 1w).");
        }
        long amount = Long.parseLong(matcher.group(1));
        long factor = switch (Character.toLowerCase(matcher.group(2).charAt(0))) {
            case 's' -> 1L;
            case 'm' -> 60L;
            case 'h' -> 3_600L;
            case 'd' -> 86_400L;
            case 'w' -> 604_800L;
            default -> throw err(base, "unreachable time unit");
        };
        try {
            return Math.multiplyExact(amount, factor);
        } catch (ArithmeticException ex) {
            throw err(base,
                    "Reward '" + id + "' in menu '" + menuId
                    + "' has time: '" + raw + "' which is too large.");
        }
    }
    private static RewardDisplay.DisplayState parseDisplayState(
            String menuId, String id, ConfigurationSection sec, String state,
            RewardDefaults menuDefaults, RewardDefaults topDefaults) {
        String base = "menus." + menuId + ".rewards." + id + ".display";
        String look = base + "." + state;
        if (sec.isSet("display." + state + ".head")) {
            throw err(look + ".head",
                    "Reward '" + id + "' in menu '" + menuId + "' (" + state + " look)"
                    + " sets 'head:'. Player heads only work on menu buttons (items:), not on rewards.");
        }
        String configured = sec.getString("display." + state + ".material", null);
        String matName = configured != null ? configured
                : firstSet(menuDefaults.forState(state), topDefaults.forState(state));
        Material material = matName == null ? null : Material.matchMaterial(matName);
        if (material == null && configured != null) {
            throw err(look + ".material",
                    "Reward '" + id + "' in menu '" + menuId + "' (" + state + " look)"
                    + " uses unknown material '" + configured
                    + "'. Use a Bukkit material name such as DIAMOND or RED_STAINED_GLASS_PANE.");
        }
        if (material == null) {
            throw err(look + ".material",
                    "Reward '" + id + "' in menu '" + menuId + "' (" + state + " look)"
                    + " has no material. Set 'material:' or add a defaults: block.");
        }
        // Lore written once under display: is shared by all three looks.
        // A look can still set its own lore to override (even an empty one).
        List<String> lore = sec.isSet("display." + state + ".lore")
                ? sec.getStringList("display." + state + ".lore")
                : sec.getStringList("display.lore");
        boolean glow = sec.isSet("display." + state + ".glow")
                ? sec.getBoolean("display." + state + ".glow")
                : firstGlow(menuDefaults.forState(state), topDefaults.forState(state));
        return new RewardDisplay.DisplayState(material, loreLines(look + ".lore", lore), glow);
    }

    private static String firstSet(RewardDefaults.DisplayDefault menu, RewardDefaults.DisplayDefault top) {
        if (menu != null && menu.materialName() != null) {
            return menu.materialName();
        }
        return top == null ? null : top.materialName();
    }

    private static boolean firstGlow(RewardDefaults.DisplayDefault menu, RewardDefaults.DisplayDefault top) {
        if (menu != null && menu.glow() != null) {
            return menu.glow();
        }
        return top != null && Boolean.TRUE.equals(top.glow());
    }

    private static List<RewardAction> parseActions(String menuId, String id, ConfigurationSection sec) {
        String base = "menus." + menuId + ".rewards." + id + ".actions";
        List<Map<?, ?>> raw = sec.getMapList("actions");
        if (raw.isEmpty()) {
            throw err(base,
                    "Reward '" + id + "' in menu '" + menuId
                    + "' defines no actions. Add at least one '- type: item' or '- type: command' entry.");
        }
        if (raw.size() > MAX_ACTIONS_PER_REWARD) {
            throw err(base,
                    "Reward '" + id + "' in menu '" + menuId + "' has " + raw.size()
                    + " actions, but at most " + MAX_ACTIONS_PER_REWARD
                    + " are allowed. Split the reward into several rewards.");
        }
        List<RewardAction> actions = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Map<?, ?> entry = raw.get(index);
            String actionPath = base + "[" + index + "]";
            Object type = entry.get("type");
            if (type == null) {
                // Compact forms: '- command: "..."' and '- item: "DIAMOND 10"'.
                // Same list position, same model, same order guarantees.
                actions.add(parseShortcutAction(menuId, id, entry, actionPath));
                continue;
            }
            if ("item".equals(type)) {
                Object mat = entry.get("material");
                Material material = mat == null ? null : Material.matchMaterial(String.valueOf(mat));
                if (material == null || material == Material.AIR) {
                    throw err(actionPath,
                            "Reward '" + id + "' in menu '" + menuId
                            + "' gives unknown item '" + mat + "'. Use a real item name such as DIAMOND.");
                }
                int amount = toInt(entry.get("amount"), 1);
                if (amount < 1 || amount > 64) {
                    throw err(actionPath,
                            "Reward '" + id + "' in menu '" + menuId
                            + "' gives amount '" + entry.get("amount")
                            + "', but amounts must be whole numbers from 1 to 64.");
                }
                actions.add(new RewardAction.ItemAction(material, amount));
            } else if ("command".equals(type)) {
                Object command = entry.get("command");
                if (command == null || command.toString().isBlank()) {
                    throw err(actionPath,
                            "Reward '" + id + "' in menu '" + menuId
                            + "' has a command action without 'command'.");
                }
                actions.add(new RewardAction.CommandAction(command.toString()));
            } else {
                throw err(actionPath,
                        "Reward '" + id + "' in menu '" + menuId
                        + "' has action type '" + type + "'. Use 'item' or 'command'.");
            }
        }
        return List.copyOf(actions);
    }

    /** Compact single-key action entries (order-preserving, same model). */
    private static RewardAction parseShortcutAction(
            String menuId, String id, Map<?, ?> entry, String actionPath) {
        if (entry.size() == 1 && entry.containsKey("command")) {
            Object command = entry.get("command");
            if (command == null || command.toString().isBlank()) {
                throw err(actionPath,
                        "Reward '" + id + "' in menu '" + menuId + "' has an empty '- command:'.");
            }
            return new RewardAction.CommandAction(command.toString());
        }
        if (entry.size() == 1 && entry.containsKey("item")) {
            String[] parts = String.valueOf(entry.get("item")).trim().split("\\s+");
            if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) {
                throw err(actionPath,
                        "Reward '" + id + "' in menu '" + menuId
                        + "' has '- item: \"" + entry.get("item") + "\". Use '- item: \"DIAMOND 10\"'.");
            }
            Material material = Material.matchMaterial(parts[0]);
            if (material == null || material == Material.AIR) {
                throw err(actionPath,
                        "Reward '" + id + "' in menu '" + menuId
                        + "' gives unknown item '" + parts[0] + "'. Use a real item name such as DIAMOND.");
            }
            int amount = 1;
            if (parts.length == 2) {
                try {
                    amount = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ex) {
                    amount = -1;
                }
            }
            if (amount < 1 || amount > 64) {
                throw err(actionPath,
                        "Reward '" + id + "' in menu '" + menuId
                        + "' gives amount '" + (parts.length == 2 ? parts[1] : "1")
                        + "', but amounts must be whole numbers from 1 to 64.");
            }
            return new RewardAction.ItemAction(material, amount);
        }
        throw err(actionPath,
                "Reward '" + id + "' in menu '" + menuId
                + "' has an invalid action entry. Use '- type: item' with material/amount,"
                + " '- type: command' with command, or the shortcuts '- command:' / '- item:'.");
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
        return fallback;
    }
}
