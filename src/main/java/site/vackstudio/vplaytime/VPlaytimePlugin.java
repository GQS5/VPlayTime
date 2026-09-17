package site.vackstudio.vplaytime;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import site.vackstudio.vplaytime.command.VPlaytimeCommand;
import site.vackstudio.vplaytime.admin.AdminService;
import site.vackstudio.vplaytime.api.VPlaytimeAPI;
import site.vackstudio.vplaytime.config.ConfigManager;
import site.vackstudio.vplaytime.config.MenuDefinition;
import site.vackstudio.vplaytime.config.PlaytimeProviderConfig;
import site.vackstudio.vplaytime.gui.MenuSounds;
import site.vackstudio.vplaytime.gui.RewardMenu;
import site.vackstudio.vplaytime.gui.RewardMenuListener;
import site.vackstudio.vplaytime.listener.PlayerListener;
import site.vackstudio.vplaytime.papi.ExpansionHost;
import site.vackstudio.vplaytime.papi.PapiGuard;
import site.vackstudio.vplaytime.papi.PlaceholderLookup;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.playtime.SystemTimeSource;
import site.vackstudio.vplaytime.playtime.TimeSource;
import site.vackstudio.vplaytime.playtime.provider.InternalPlaytimeProvider;
import site.vackstudio.vplaytime.playtime.provider.PlaceholderPlaytimeProvider;
import site.vackstudio.vplaytime.playtime.provider.PlaytimeProvider;
import site.vackstudio.vplaytime.reward.BukkitGlobalScheduler;
import site.vackstudio.vplaytime.reward.BukkitPlayerScheduler;
import site.vackstudio.vplaytime.reward.ClaimManager;
import site.vackstudio.vplaytime.reward.RewardManager;
import site.vackstudio.vplaytime.storage.DatabaseMigrator;
import site.vackstudio.vplaytime.storage.SQLiteStorage;
import site.vackstudio.vplaytime.storage.Storage;

import org.bukkit.Registry;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * VPlaytime bootstrap.
 *
 * <p>Phase 1: plugin metadata, configuration loading and validation.
 * Phase 2: in-memory player state (session tracking, cache).
 * Phase 3: SQLite persistence, autosave, durable shutdown.
 * Phase 4: reward definitions, states and atomic claims. No GUI lives here yet.
 * Phase 9: multi-menu engine — config split (config/messages/menus/rewards),
 * database moved to {@code data/data.db}, one RewardMenu per MenuDefinition.
 */
public final class VPlaytimePlugin extends JavaPlugin {

    private ConfigManager configManager;
    private PlaytimeManager playtimeManager;
    private RewardManager rewardManager;
    private ClaimManager claimManager;
    private AdminService adminService;
    private Map<String, RewardMenu> menus = Map.of();
    private TimeSource clock;
    /**
     * Expansion handle as {@link Object} on purpose: naming the PAPI type
     * here would break class verification on servers without
     * PlaceholderAPI. See {@link ExpansionHost}.
     */
    private Object expansion;

    @Override
    public void onEnable() {
        this.rewardManager = new RewardManager(getLogger());
        this.configManager = new ConfigManager(this, rewardManager);
        // Fail-closed startup: an invalid reward configuration parks the
        // reward system DISABLED (diagnostics on console) while the plugin
        // itself stays operational — commands, info and GUI visibility keep
        // working, but nothing can claim, execute or persist.
        Map<String, MenuDefinition> bootMenus = null;
        try {
            configManager.load();
        } catch (site.vackstudio.vplaytime.config.PreflightRejection rejection) {
            for (String line : rejection.reportLines()) {
                if (!line.isBlank()) {
                    getLogger().log(Level.SEVERE, line);
                }
            }
            getLogger().log(Level.SEVERE, "Reward System: DISABLED (" + rejection.playerReason() + ")");
            bootMenus = rejection.partialMenus();
        } catch (IllegalStateException ex) {
            if (ex instanceof site.vackstudio.vplaytime.config.ConfigError error) {
                getLogger().log(Level.SEVERE, "Invalid configuration in " + error.file()
                        + (error.path().isBlank() ? "" : " at " + error.path())
                        + ": " + error.reason());
            } else {
                getLogger().log(Level.SEVERE,
                        "Invalid configuration, disabling reward system: " + ex.getMessage());
            }
            getLogger().log(Level.SEVERE, "Reward System: DISABLED");
            rewardManager.disable("configuration could not be loaded", null);
            bootMenus = Map.of();
        }

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        "vplaytime",
                        "Open playtime rewards menu.",
                        List.of("playtime", "ptr"),
                        new VPlaytimeCommand(this)));

        Storage storage = initStorage();
        if (storage == null) {
            return;
        }
        TimeSource clock = new SystemTimeSource();
        this.clock = clock;
        this.playtimeManager = new PlaytimeManager(clock, storage);
        activateProvider();
        this.claimManager = new ClaimManager(
                rewardManager,
                playtimeManager,
                storage,
                new BukkitPlayerScheduler(this, getLogger()),
                new BukkitGlobalScheduler(this),
                clock,
                getLogger(),
                configManager.debugEnabled());
        rebuildMenus(bootMenus != null ? bootMenus : configManager.menus());
        this.adminService = new AdminService(playtimeManager, rewardManager, storage, clock, getLogger());
        VPlaytimeAPI.setInstance(new VPlaytimeAPI(playtimeManager, rewardManager, claimManager, clock));
        getServer().getPluginManager().registerEvents(
                new RewardMenuListener(rewardManager, playtimeManager, claimManager, clock, this::findMenuOrNull,
                        configManager, getLogger()),
                this);
        getServer().getPluginManager().registerEvents(new PlayerListener(playtimeManager), this);
        registerExpansion();

        int autosaveSeconds = configManager.autosaveSeconds();
        getServer().getAsyncScheduler().runAtFixedRate(
                this,
                task -> playtimeManager.saveDirtySnapshots(),
                autosaveSeconds, autosaveSeconds, TimeUnit.SECONDS);

        getLogger().info("Enabled VPlaytime " + getPluginMeta().getVersion()
                + " (menus=" + menus.size() + ", rewards=" + rewardManager.count() + ", storage=sqlite)");
    }

    @Override
    public void onDisable() {
        VPlaytimeAPI.setInstance(null);
        if (expansion != null) {
            ExpansionHost.unregister(this, expansion);
            expansion = null;
        }
        // AsyncScheduler tasks are cancelled automatically. Flush sessions and
        // queued saves with a bounded wait, then close storage.
        if (playtimeManager != null) {
            playtimeManager.shutdown();
        }
    }

    /**
     * Opens SQLite storage, or {@code null} after cleanly failing startup
     * (instead of running with fake persistence) when the database is unusable.
     */    private Storage initStorage() {
        final Path databaseFile;
        try {
            databaseFile = DatabaseMigrator.resolveDatabaseFile(getDataFolder().toPath(), getLogger());
        } catch (IllegalStateException ex) {
            getLogger().log(Level.SEVERE, "Database migration failed, disabling VPlaytime: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return null;
        }
        try {
            return new SQLiteStorage(databaseFile, getLogger());
        } catch (IllegalStateException ex) {
            getLogger().log(Level.SEVERE, "Storage initialization failed, disabling VPlaytime: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return null;
        }
    }

    /**
     * Builds the configured {@link PlaytimeProvider} from the current
     * snapshot and swaps it into the playtime manager, stopping the
     * internal session timer while an external source is active.
     *
     * <p>Infallible by construction: {@code ConfigManager.load()} already
     * rejected a {@code placeholder} provider without PlaceholderAPI, so
     * the only failure left (PAPI vanishing mid-reload) falls back to the
     * internal timer with a loud log instead of breaking the reload.
     * Called on enable and after every successful config load, inside the
     * same synchronized reload that swaps menus — the old provider is
     * stateless (no cache, no tasks), so there is nothing to close.
     */
    private void activateProvider() {
        PlaytimeProviderConfig cfg = configManager.global().provider();
        PlaytimeProvider provider;
        if (PlaytimeProviderConfig.PLACEHOLDER.equals(cfg.provider())
                && PapiGuard.present(this)) {
            provider = new PlaceholderPlaytimeProvider(
                    cfg.placeholder(), cfg.unit(),
                    PlaceholderLookup.bukkit(this, cfg.placeholder()), getLogger());
            getLogger().info("Playtime provider: placeholder '" + cfg.placeholder()
                    + "' (" + cfg.unit().name().toLowerCase(java.util.Locale.ROOT)
                    + "); internal session timer stopped.");
        } else {
            if (PlaytimeProviderConfig.PLACEHOLDER.equals(cfg.provider())) {
                getLogger().log(Level.SEVERE, "PlaceholderAPI vanished during reload; "
                        + "falling back to the internal playtime timer.");
            }
            provider = new InternalPlaytimeProvider(playtimeManager::internalEffectiveSeconds);
        }
        playtimeManager.setProvider(provider);
        playtimeManager.setTrackSessions(!provider.external());
    }

    /**
     * Registers the {@code %vplaytime_*%} expansion once, when
     * PlaceholderAPI is present. Never re-registered on reload: the
     * expansion reads the live manager, so provider switches apply
     * immediately. A failed registration only logs — the plugin works
     * fully without its own expansion.
     */
    private void registerExpansion() {
        if (!PapiGuard.present(this)) {
            return;
        }
        this.expansion = ExpansionHost.register(this, playtimeManager, getPluginMeta().getVersion());
    }

    private void rebuildMenus(Map<String, MenuDefinition> menus) {
        Map<String, RewardMenu> built = new HashMap<>();
        Map<String, String> spellings = soundSpellings();
        for (MenuDefinition menu : menus.values()) {
            Map<MenuSounds.Kind, site.vackstudio.vplaytime.config.SoundConfig> configured =
                    new EnumMap<>(MenuSounds.Kind.class);
            for (var entry : menu.sounds().entrySet()) {
                MenuSounds.Kind.byKey(entry.getKey()).ifPresent(kind -> configured.put(kind, entry.getValue()));
            }
            MenuSounds sounds = MenuSounds.load(spellings, menu.id(), configured, getLogger());
            built.put(menu.id(), new RewardMenu(rewardManager, playtimeManager, clock, menu, sounds,
                    configManager.messages()));
        }
        this.menus = Map.copyOf(built);
    }

    /**
     * Walks the live sound registry once per load into canonical keys
     * (server side; unit tests feed the pure spelling map directly).
     */
    private Map<String, String> soundSpellings() {
        java.util.List<String> canonical = new java.util.ArrayList<>();
        Registry.SOUNDS.keyStream().forEach(key -> canonical.add(key.asString()));
        return MenuSounds.spellings(canonical);
    }

    /**
     * Reloads all three configuration files. PlayerData, claims and storage
     * are untouched. The whole body holds the configuration monitor, so two
     * simultaneous reloads cannot interleave a half-rebuilt menu map.
     * Listeners, tasks and registrations are created once in onEnable and
     * never duplicated here; failed reloads keep every previous object —
     * including the previous playtime provider. Open menus stay valid until
     * their next refresh/open.
     *
     * @return empty when the new configuration is active, otherwise the
     *         precise failure detail for the console report and the player
     */
    public synchronized Optional<String> vplaytimeReload() {
        try {
            configManager.load();
        } catch (IllegalStateException ex) {
            for (String line : reloadReport(ex)) {
                if (!line.isBlank()) {
                    getLogger().log(Level.SEVERE, line);
                }
            }
            return Optional.of(firstReason(ex));
        }
        activateProvider();
        rebuildMenus(configManager.menus());
        int reenabled = claimManager.clearSuspended();
        if (reenabled > 0) {
            getLogger().info("Reload re-enabled " + reenabled + " suspended reward(s).");
        }
        return Optional.empty();
    }

    /** Backwards-compatible boolean form (detail discarded). */
    public boolean vplaytimeReloaded() {
        return vplaytimeReload().isEmpty();
    }

    /** Diagnostic report lines for a reload failure (public for testing). */
    public     static java.util.List<String> reloadReport(Throwable ex) {
        if (ex instanceof site.vackstudio.vplaytime.config.ConfigError error) {
            return error.reportLines();
        }
        return java.util.List.of(
                "Reload failed: unknown file",
                String.valueOf(ex.getMessage()),
                "Previous configuration remains active.");
    }

    /** Player-facing reason for a reload failure (public for testing). */
    public static String firstReason(Throwable ex) {
        if (ex instanceof site.vackstudio.vplaytime.config.ConfigError error) {
            return error.playerReason();
        }
        return String.valueOf(ex.getMessage());
    }

    /**
     * Menu opened by bare {@code /vplaytime}; present when a valid plan (or
     * a rejected layout for diagnostics) was loaded, null when nothing
     * parseable exists. Callers must handle null (unavailable message).
     */
    public RewardMenu mainMenu() {
        return menus.get(site.vackstudio.vplaytime.config.MenuRegistry.DEFAULT_MENU_ID);
    }

    public Optional<RewardMenu> findMenu(String id) {
        return Optional.ofNullable(menus.get(id));
    }

    private RewardMenu findMenuOrNull(String id) {
        return menus.get(id);
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public PlaytimeManager playtimeManager() {
        return playtimeManager;
    }

    public RewardManager rewardManager() {
        return rewardManager;
    }

    public ClaimManager claimManager() {
        return claimManager;
    }

    public AdminService adminService() {
        return adminService;
    }
}
