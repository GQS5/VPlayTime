package site.vackstudio.vplaytime.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import site.vackstudio.vplaytime.VPlaytimePlugin;
import site.vackstudio.vplaytime.model.RewardDefinition;
import site.vackstudio.vplaytime.reward.RewardManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and validates the three configuration files.
 *
 * <p>Every load (startup or {@code /vplaytime reload}) validates everything
 * into locals first and only then publishes: menus via
 * {@link MenuRegistry#parse}, reward content via
 * {@link RewardManager#parseFromMenus} + {@link RewardManager#swap}, and the
 * rest via an immutable {@link ConfigSnapshot}. A failed load never leaves
 * half-updated configuration behind.
 *
 * <p>Also performs one-time migrations ({@code config-version: 1 → 3} and
 * {@code 2 → 3}): old files are backed up before anything is touched, and
 * superseded files are left in place (never silently deleted).
 */
public final class ConfigManager {

    public static final int CONFIG_VERSION = 3;
    private static final int LEGACY_V2 = 2;
    private static final int LEGACY_V1 = 1;

    private final VPlaytimePlugin plugin;
    private final RewardManager rewards;

    private volatile ConfigSnapshot snapshot = new ConfigSnapshot(
            new GlobalConfig(false, 30, PlaytimeProviderConfig.defaults()),
            new MessageConfig("", "", "", "", "", "", "", ""),
            Map.of(MenuRegistry.DEFAULT_MENU_ID, new MenuDefinition(
                    MenuRegistry.DEFAULT_MENU_ID, "Playtime", 1, "Playtime", 3, Map.of(), Map.of(),
                    Map.of(), null)));

    public ConfigManager(VPlaytimePlugin plugin, RewardManager rewards) {
        this.plugin = plugin;
        this.rewards = rewards;
    }

    /** Validates all three files, then atomically publishes the new snapshot. */
    public synchronized void load() {
        // config.yml first: its version decides whether migration must run
        // BEFORE defaults for the other files are materialized (otherwise
        // fresh defaults would shadow the user's legacy values).
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        int version = plugin.getConfig().getInt("config-version", LEGACY_V1);
        if (version == LEGACY_V1) {
            migrateV1ToV3();
            plugin.reloadConfig();
            version = plugin.getConfig().getInt("config-version", -1);
        } else if (version == LEGACY_V2) {
            migrateV2ToV3();
            plugin.reloadConfig();
            version = plugin.getConfig().getInt("config-version", -1);
        }
        // Materialize any still-missing files with shipped defaults
        // (guarded: saveResource warns when the target already exists).
        for (String name : new String[]{"messages.yml", "rewards.yml"}) {
            if (!new File(plugin.getDataFolder(), name).exists()) {
                plugin.saveResource(name, false);
            }
        }
        if (version != CONFIG_VERSION) {
            throw new ConfigError("config.yml", "config-version",
                    "Unsupported config-version " + version
                    + " (this build reads version " + CONFIG_VERSION + ").");
        }

        GlobalConfig global = parseGlobal(plugin.getConfig());
        checkProviderRuntime(global.provider(),
                site.vackstudio.vplaytime.papi.PapiGuard.present(plugin));
        MessageConfig messages = parseMessages(loadYaml("messages.yml"));
        YamlConfiguration rewardsYaml = loadYaml("rewards.yml");
        RewardDefaults topDefaults = RewardDefaults.parse(
                rewardsYaml.getConfigurationSection("defaults"), "defaults");
        Map<String, MenuDefinition> menus =
                MenuRegistry.parse(rewardsYaml.getConfigurationSection("menus"), topDefaults);
        Map<String, RewardDefinition> merged;
        try {
            merged = rewards.parseFromMenus(menus);
        } catch (ConfigError already) {
            throw already;
        } catch (IllegalStateException ex) {
            throw new ConfigError("rewards.yml", "menus", ex.getMessage());
        }

        // All valid: warn about console commands nothing provides (warn-only),
        // then commit together.
        warnForUnknownCommands(merged);
        rewards.swap(merged);
        this.snapshot = new ConfigSnapshot(global, messages, menus);
        plugin.getLogger().info("Configuration valid: " + menus.size() + " menu(s), "
                + merged.size() + " reward(s).");
        warnIfStaleMenusFile();
    }

    /**
     * A leftover {@code menus.yml} (Phase 9 layout) is never read anymore.
     * Say so on every load: editing it and reloading otherwise looks like
     * a silently ignored reload failure.
     */
    private void warnIfStaleMenusFile() {
        if (new File(plugin.getDataFolder(), "menus.yml").exists()) {
            plugin.getLogger().warning("menus.yml exists but is no longer read;"
                    + " menus live in rewards.yml now. Delete menus.yml to silence this warning.");
        }
    }

    /**
     * Warns once per load about console command roots no enabled plugin
     * provides. Warn-only, never a load failure: a command from a plugin
     * that enables later still works. But a permanently unknown root means
     * every claim using it fails and is revoked for retry — re-granting
     * earlier actions on each attempt — so the warning names the fix.
     */
    private void warnForUnknownCommands(Map<String, RewardDefinition> merged) {
        Map<String, String> unknown =
                site.vackstudio.vplaytime.reward.CommandFailure.unknownRoots(
                        merged, root -> {
                            try {
                                return org.bukkit.Bukkit.getCommandMap().getCommand(root) != null;
                            } catch (Exception ex) {
                                return true;
                            }
                        });
        if (!unknown.isEmpty()) {
            plugin.getLogger().warning("rewards.yml uses console commands no enabled plugin provides: "
                    + String.join(", ",
                            site.vackstudio.vplaytime.reward.CommandFailure.describeUnknown(unknown))
                    + ". Claims using them will fail and be revoked for retry (earlier actions"
                    + " re-grant on every retry). Fix the command or install the plugin, then"
                    + " /vplaytime reload.");
        }
    }

    /** Pure global-settings validation; package-visible for unit tests. */
    static GlobalConfig parseGlobal(ConfigurationSection config) {
        String storageType = config.getString("storage.type", "sqlite");
        if (!"sqlite".equalsIgnoreCase(storageType)) {
            throw new ConfigError("config.yml", "storage.type",
                    "storage.type '" + storageType + "' is not supported. Use 'sqlite'.");
        }
        boolean debug = config.getBoolean("debug.enabled", false);
        int autosave = config.getInt("storage.autosave-seconds", 30);
        if (autosave < 5) {
            throw new ConfigError("config.yml", "storage.autosave-seconds",
                    "storage.autosave-seconds is " + autosave + ", but it must be 5 or higher.");
        }
        PlaytimeProviderConfig provider;
        try {
            provider = PlaytimeProviderConfig.parse(config.getConfigurationSection("playtime"));
        } catch (ConfigError already) {
            throw already;
        } catch (IllegalStateException ex) {
            throw new ConfigError("config.yml", "playtime", ex.getMessage());
        }
        return new GlobalConfig(debug, autosave, provider);
    }

    /**
     * Runtime half of provider validation: the syntax half lives in
     * {@link PlaytimeProviderConfig#parse}, this checks the live server.
     * Pure (no server needed) so unit tests cover both branches. Called
     * inside {@link #load()} BEFORE anything is published, so a missing
     * PlaceholderAPI fails the reload transactionally — the previous
     * working provider stays active.
     */
    static void checkProviderRuntime(PlaytimeProviderConfig provider, boolean placeholderApiPresent) {
        if (provider != null && PlaytimeProviderConfig.PLACEHOLDER.equals(provider.provider())
                && !placeholderApiPresent) {
            throw new ConfigError("config.yml", "playtime.provider",
                    "playtime.provider is 'placeholder' but PlaceholderAPI is not installed or enabled. "
                    + "Install PlaceholderAPI plus the expansion that provides '"
                    + provider.placeholder() + "', or set playtime.provider to 'internal'.");
        }
    }

    /** Pure message validation; package-visible for unit tests. */
    static MessageConfig parseMessages(YamlConfiguration yaml) {
        for (String section : new String[]{"general", "claim", "admin", "gui"}) {
            if (yaml.isSet(section) && !yaml.isConfigurationSection(section)) {
                throw new ConfigError("messages.yml", section,
                        "'" + section + "' must be a section (check indentation).");
            }
        }
        ConfigurationSection general = yaml.getConfigurationSection("general");
        ConfigurationSection claim = yaml.getConfigurationSection("claim");
        ConfigurationSection admin = yaml.getConfigurationSection("admin");
        ConfigurationSection gui = yaml.getConfigurationSection("gui");
        return new MessageConfig(
                text("prefix", yaml.getString("prefix", "")),
                text("loading", yaml.getString("loading", "")),
                general == null ? "" : text("general.usage", general.getString("usage", "")),
                general == null ? "" : text("general.no-permission", general.getString("no-permission", "")),
                general == null ? "" : text("general.players-only", general.getString("players-only", "")),
                general == null ? "" : text("general.unknown-menu", general.getString("unknown-menu", "")),
                general == null ? "" : text("general.unknown-player", general.getString("unknown-player", "")),
                general == null ? "" : text("general.no-data", general.getString("no-data", "")),
                claim == null ? "" : text("claim.success", claim.getString("success", "")),
                claim == null ? "" : text("claim.locked", claim.getString("locked", "")),
                claim == null ? "" : text("claim.already-claimed", claim.getString("already-claimed", "")),
                claim == null ? "" : text("claim.failed", claim.getString("failed", "")),
                claim == null ? "" : text("claim.unavailable", claim.getString("unavailable", "")),
                admin == null ? "" : text("admin.reload-success", admin.getString("reload-success", "")),
                admin == null ? "" : text("admin.reload-failed", admin.getString("reload-failed", "")),
                admin == null ? "" : text("admin.reload-detail", admin.getString("reload-detail", "")),
                admin == null ? "" : text("admin.info-header", admin.getString("info-header", "")),
                admin == null ? "" : text("admin.info-uuid", admin.getString("info-uuid", "")),
                admin == null ? "" : text("admin.info-playtime-online",
                        admin.getString("info-playtime-online", "")),
                admin == null ? "" : text("admin.info-playtime-offline",
                        admin.getString("info-playtime-offline", "")),
                admin == null ? "" : text("admin.info-claims-none", admin.getString("info-claims-none", "")),
                admin == null ? "" : text("admin.info-claims", admin.getString("info-claims", "")),
                admin == null ? "" : text("admin.info-provider", admin.getString("info-provider", "")),
                admin == null ? "" : text("admin.reset-done", admin.getString("reset-done", "")),
                admin == null ? "" : text("admin.reset-failed", admin.getString("reset-failed", "")),
                admin == null ? "" : text("admin.resetall-done", admin.getString("resetall-done", "")),
                gui == null ? "" : text("gui.menu-opened", gui.getString("menu-opened", "")),
                gui == null ? "" : text("gui.menu-closed", gui.getString("menu-closed", "")));
    }

    private static String text(String field, String value) {
        try {
            return site.vackstudio.vplaytime.gui.Text.normalize(field, value);
        } catch (ConfigError already) {
            throw already;
        } catch (IllegalStateException ex) {
            throw new ConfigError("messages.yml", field, ex.getMessage());
        }
    }

    private YamlConfiguration loadYaml(String name) {
        File file = new File(plugin.getDataFolder(), name);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            // SnakeYAML messages carry file line/column info; keep them verbatim.
            throw new ConfigError(name, "", "Cannot read " + name + ": " + ex.getMessage()
                    + " (check indentation and quotes).");
        }
        return yaml;
    }

    // ---- one-time migrations ----

    private void backup(String name, String backupName) {
        File original = new File(plugin.getDataFolder(), name);
        File backup = new File(plugin.getDataFolder(), backupName);
        if (!original.exists() || backup.exists()) {
            return;
        }
        try {
            Files.copy(original.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            plugin.getLogger().info("Backed up " + name + " to " + backupName + ".");
        } catch (IOException ex) {
            throw new ConfigError(name, "",
                    "Migration backup of " + name + " failed, aborting: " + ex.getMessage());
        }
    }

    private void writeFreshConfig(ConfigurationSection old) {
        YamlConfiguration fresh = new YamlConfiguration();
        fresh.set("config-version", CONFIG_VERSION);
        fresh.set("debug.enabled", old.getBoolean("debug.enabled", false));
        fresh.set("storage.type", "sqlite");
        fresh.set("storage.autosave-seconds", old.getInt("storage.autosave-seconds", 30));
        // count-afk was removed in 1.9 (playtime always counts; the key did
        // nothing) — old files may still carry it, it is simply ignored.
        fresh.set("playtime.provider", old.getString("playtime.provider", "internal"));
        fresh.set("playtime.placeholder.value",
                old.getString("playtime.placeholder.value", "%statistic_seconds_played%"));
        fresh.set("playtime.placeholder.unit", old.getString("playtime.placeholder.unit", "seconds"));
        try {
            fresh.save(new File(plugin.getDataFolder(), "config.yml"));
        } catch (IOException ex) {
            throw new ConfigError("config.yml", "",
                    "Cannot write migrated config.yml: " + ex.getMessage());
        }
    }

    private void writeIfAbsent(String name, YamlConfiguration yaml) {
        File file = new File(plugin.getDataFolder(), name);
        if (file.exists()) {
            plugin.getLogger().info(name + " already exists, keeping it (not overwritten by migration).");
            return;
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            throw new ConfigError(name, "",
                    "Cannot write migrated " + name + ": " + ex.getMessage());
        }
    }

    /**
     * Legacy single-file layout (Phase ≤ 8) → v3. Splits messages out and
     * nests the one implicit menu's rewards with their slots.
     */
    private void migrateV1ToV3() {
        plugin.getLogger().info("Legacy config-version 1 found, migrating to version 3...");
        backup("config.yml", "config.yml.bak-v1");
        ConfigurationSection old = plugin.getConfig();

        YamlConfiguration messages = new YamlConfiguration();
        messages.set("prefix", "");
        messages.set("loading", old.getString("messages.loading", ""));
        messages.set("claim.success", old.getString("messages.claim-success", ""));
        messages.set("claim.locked", old.getString("messages.claim-locked", ""));
        messages.set("claim.already-claimed", old.getString("messages.claim-already", ""));
        messages.set("claim.failed", old.getString("messages.claim-failed", ""));
        messages.set("admin.reload-success", old.getString("messages.reloaded", ""));
        messages.set("admin.reload-failed", old.getString("messages.reload-failed", ""));
        writeIfAbsent("messages.yml", messages);

        YamlConfiguration rewards = new YamlConfiguration();
        String title = old.getString("menu.title", "Playtime");
        rewards.set("menus.main.name", title);
        rewards.set("menus.main.order", 1);
        rewards.set("menus.main.title", title);
        rewards.set("menus.main.rows", old.getInt("menu.rows", 3));
        for (String key : new String[]{"open", "claim", "locked", "already-claimed"}) {
            rewards.set("menus.main.sounds." + key, old.getString("menu.sounds." + key, ""));
        }
        ConfigurationSection oldRewards = old.getConfigurationSection("rewards");
        if (oldRewards != null) {
            for (String id : oldRewards.getKeys(false)) {
                String from = "rewards." + id;
                String to = "menus.main.rewards." + id;
                rewards.set(to + ".slot", old.getInt(from + ".slot", 11));
                copyRewardContent(old, from, rewards, to);
            }
        }
        // Validate BEFORE writing: a corrupt legacy file aborts the
        // migration instead of producing a corrupt v3 file.
        validateMigratedMenus(rewards);
        writeIfAbsent("rewards.yml", rewards);
        writeFreshConfig(old);
        plugin.getLogger().info("Configuration migration to version 3 complete.");
    }

    /**
     * Validates a migration result in memory before it touches disk or
     * runtime state. Used by paths whose inputs come from legacy files.
     */
    static void validateMigratedMenus(YamlConfiguration rewards) {
        try {
            MenuRegistry.parse(rewards.getConfigurationSection("menus"), RewardDefaults.empty());
        } catch (ConfigError already) {
            throw already;
        } catch (IllegalStateException ex) {
            throw new ConfigError("rewards.yml", "menus", "Migrated configuration is invalid: "
                    + ex.getMessage());
        }
    }

    /**
     * Split layout (menus.yml + rewards.yml, Phase 9) → v3. Nests each
     * menu's placements with the reward content. menus.yml is left in
     * place (superseded, no longer read) so nothing is silently deleted.
     */
    private void migrateV2ToV3() {
        plugin.getLogger().info("Config-version 2 found, migrating menus into rewards.yml (version 3)...");
        backup("config.yml", "config.yml.bak-v2");
        backup("rewards.yml", "rewards.yml.bak-v2");
        ConfigurationSection old = plugin.getConfig();

        YamlConfiguration menusFile = loadYaml("menus.yml");
        YamlConfiguration rewardsFile = loadYaml("rewards.yml");
        ConfigurationSection oldMenus = menusFile.getConfigurationSection("menus");
        ConfigurationSection oldRewards = rewardsFile.getConfigurationSection("rewards");
        if (oldMenus == null || oldMenus.getKeys(false).isEmpty()) {
            throw new ConfigError("menus.yml", "menus",
                    "Migration found no 'menus' section. Restore menus.yml from its backup or delete it (menus now live in rewards.yml).");
        }
        YamlConfiguration nested = new YamlConfiguration();
        int order = 0;
        for (String menuId : oldMenus.getKeys(false)) {
            order++;
            String from = "menus." + menuId;
            String to = "menus." + menuId;
            String title = menusFile.getString(from + ".title", "Playtime");
            nested.set(to + ".name", title);
            nested.set(to + ".order", order);
            nested.set(to + ".title", title);
            nested.set(to + ".rows", menusFile.getInt(from + ".rows", 3));
            for (String key : new String[]{"open", "claim", "locked", "already-claimed"}) {
                nested.set(to + ".sounds." + key, menusFile.getString(from + ".sounds." + key, ""));
            }
            ConfigurationSection placements = menusFile.getConfigurationSection(from + ".rewards");
            if (placements != null) {
                for (String rewardId : placements.getKeys(false)) {
                    if (oldRewards == null || !oldRewards.isSet(rewardId)) {
                        throw new ConfigError("menus.yml", "menus." + menuId + ".rewards." + rewardId,
                                "Menu '" + menuId + "' places reward '" + rewardId
                                + "', but rewards.yml does not define it.");
                    }
                    String dest = to + ".rewards." + rewardId;
                    nested.set(dest + ".slot", placements.getInt(rewardId + ".slot", 0));
                    copyRewardContent(rewardsFile, "rewards." + rewardId, nested, dest);
                }
            }
        }
        // Validate the migrated result before writing anything.
        MenuRegistry.parse(nested.getConfigurationSection("menus"));
        try {
            nested.save(new File(plugin.getDataFolder(), "rewards.yml"));
        } catch (IOException ex) {
            throw new ConfigError("rewards.yml", "menus",
                    "Cannot write migrated rewards.yml: " + ex.getMessage());
        }
        writeFreshConfig(old);
        plugin.getLogger().warning("menus.yml is superseded by rewards.yml and is no longer read. "
                + "After verifying your menus in game you may delete it.");
        plugin.getLogger().info("Configuration migration to version 3 complete.");
    }

    /** Copies required-seconds/display/actions of one reward between Yaml trees. */
    private static void copyRewardContent(ConfigurationSection from, String fromBase,
            YamlConfiguration to, String toBase) {
        to.set(toBase + ".required-seconds", from.getLong(fromBase + ".required-seconds", 0));
        to.set(toBase + ".display.name", from.getString(fromBase + ".display.name", "<white>"));
        for (String state : new String[]{"locked", "claimable", "claimed"}) {
            to.set(toBase + ".display." + state + ".material",
                    from.getString(fromBase + ".display." + state + ".material", "STONE"));
            to.set(toBase + ".display." + state + ".lore",
                    from.getStringList(fromBase + ".display." + state + ".lore"));
        }
        to.set(toBase + ".display.claimable.glow",
                from.getBoolean(fromBase + ".display.claimable.glow", false));
        to.set(toBase + ".actions", from.getMapList(fromBase + ".actions"));
    }

    // ---- accessors ----

    public ConfigSnapshot snapshot() {
        return snapshot;
    }

    public GlobalConfig global() {
        return snapshot.global();
    }

    public MessageConfig messages() {
        return snapshot.messages();
    }

    public Map<String, MenuDefinition> menus() {
        return snapshot.menus();
    }

    public Optional<MenuDefinition> menu(String id) {
        return Optional.ofNullable(snapshot.menus().get(id));
    }

    /** Compatibility: previous single-menu title. Prefer {@link #menus()}. */
    public String menuTitle() {
        return snapshot.mainMenu().title();
    }

    /** Compatibility: previous single-menu size. Prefer {@link #menus()}. */
    public int menuSize() {
        return snapshot.mainMenu().size();
    }

    public int autosaveSeconds() {
        return snapshot.global().autosaveSeconds();
    }

    public boolean debugEnabled() {
        return snapshot.global().debugEnabled();
    }

    public String loadingMessage() {
        return snapshot.messages().prefixed(snapshot.messages().loading());
    }

    /**
     * Formats a message template with values and prepends the prefix.
     * Missing values render as empty; values are MiniMessage-escaped.
     * A blank result stays blank (no lone prefix) so senders can skip it.
     */
    public String message(String template, Map<String, String> values) {
        String formatted = site.vackstudio.vplaytime.gui.MessageFormat.format(template, values);
        if (formatted.isBlank()) {
            return "";
        }
        return snapshot.messages().prefixed(formatted);
    }

    /** Formats a raw (already read from {@link #messages()}) template. */
    public String message(String template) {
        return message(template, Map.of());
    }

    public String reloadedOkMessage() {
        return snapshot.messages().prefixed(snapshot.messages().reloadOk());
    }

    public String reloadedFailMessage() {
        return snapshot.messages().prefixed(snapshot.messages().reloadFailed());
    }

    public String lockedMessage() {
        return snapshot.messages().prefixed(snapshot.messages().claimLocked());
    }

    public String alreadyClaimedMessage() {
        return snapshot.messages().prefixed(snapshot.messages().claimAlready());
    }

    public String claimSuccessMessage() {
        return snapshot.messages().prefixed(snapshot.messages().claimSuccess());
    }

    public String claimFailedMessage() {
        return snapshot.messages().prefixed(snapshot.messages().claimFailed());
    }
}
