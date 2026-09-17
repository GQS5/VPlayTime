package site.vackstudio.vplaytime.config;

/**
 * Global (non-menu, non-reward) settings from {@code config.yml}.
 */
public record GlobalConfig(
        boolean debugEnabled,
        int autosaveSeconds,
        boolean countAfk,
        PlaytimeProviderConfig provider) {

    public GlobalConfig {
        if (autosaveSeconds < 5) {
            throw new IllegalArgumentException("autosaveSeconds must be >= 5");
        }
        if (provider == null) {
            provider = PlaytimeProviderConfig.defaults();
        }
    }

    /** Backwards-compatible constructor: provider defaults to internal. */
    public GlobalConfig(boolean debugEnabled, int autosaveSeconds, boolean countAfk) {
        this(debugEnabled, autosaveSeconds, countAfk, PlaytimeProviderConfig.defaults());
    }
}
