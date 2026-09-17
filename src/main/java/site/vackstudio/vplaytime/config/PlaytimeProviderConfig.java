package site.vackstudio.vplaytime.config;

import org.bukkit.configuration.ConfigurationSection;
import site.vackstudio.vplaytime.playtime.provider.PlaytimeUnit;

/**
 * The {@code playtime.provider} block of {@code config.yml}: which clock
 * measures playtime for levels, rewards and GUI text.
 *
 * <pre>
 * playtime:
 *   provider: placeholder   # internal | placeholder (hexacore/vcore later)
 *   placeholder:
 *     value: '%statistic_seconds_played%'
 *     unit: seconds         # seconds | minutes | hours | milliseconds
 * </pre>
 *
 * <p>Parsing is pure (no server needed) and throws {@link ConfigError}
 * with file + dotted path. The {@code placeholder} block is validated
 * strictly only when {@code provider: placeholder} is selected, so admins
 * can prepare it while still running on {@code internal}.
 */
public record PlaytimeProviderConfig(
        String provider,
        String placeholder,
        PlaytimeUnit unit) {

    public static final String INTERNAL = "internal";
    public static final String PLACEHOLDER = "placeholder";

    public static PlaytimeProviderConfig defaults() {
        return new PlaytimeProviderConfig(INTERNAL, "", PlaytimeUnit.SECONDS);
    }

    public PlaytimeProviderConfig {
        provider = provider == null ? INTERNAL : provider;
        placeholder = placeholder == null ? "" : placeholder;
        unit = unit == null ? PlaytimeUnit.SECONDS : unit;
    }

    /** Whether this config replaces the internal session timer. */
    public boolean external() {
        return PLACEHOLDER.equals(provider);
    }

    /**
     * Parses the {@code playtime} section. A missing section (or a missing
     * {@code provider} key) means {@code internal} defaults.
     */
    public static PlaytimeProviderConfig parse(ConfigurationSection playtime) {
        if (playtime == null) {
            return defaults();
        }
        String provider = playtime.getString("provider", INTERNAL).trim().toLowerCase(java.util.Locale.ROOT);
        if (!INTERNAL.equals(provider) && !PLACEHOLDER.equals(provider)) {
            throw new ConfigError("config.yml", "playtime.provider",
                    "Unknown playtime provider '" + provider + "'. Supported: '"
                    + INTERNAL + "', '" + PLACEHOLDER + "'.");
        }
        if (playtime.isSet("placeholder") && !playtime.isConfigurationSection("placeholder")) {
            throw new ConfigError("config.yml", "playtime.placeholder",
                    "'playtime.placeholder' must be a section with 'value' and 'unit' (check indentation).");
        }
        ConfigurationSection block = playtime.getConfigurationSection("placeholder");
        String value = block == null ? "" : block.getString("value", "").trim();
        String unitRaw = block == null ? "seconds" : block.getString("unit", "seconds");
        PlaytimeUnit unit = PlaytimeUnit.parse(unitRaw);
        if (INTERNAL.equals(provider)) {
            // Lenient while internal: prepare the block freely, it is unused.
            return new PlaytimeProviderConfig(provider, normalize(value), unit == null ? PlaytimeUnit.SECONDS : unit);
        }
        if (value.isEmpty()) {
            throw new ConfigError("config.yml", "playtime.placeholder.value",
                    "'playtime.placeholder.value' is blank, but playtime.provider is 'placeholder'. "
                    + "Set the placeholder to read, e.g. '%statistic_seconds_played%'.");
        }
        if (unit == null) {
            throw new ConfigError("config.yml", "playtime.placeholder.unit",
                    "Unknown unit '" + unitRaw + "'. Supported: seconds, minutes, hours, milliseconds.");
        }
        return new PlaytimeProviderConfig(provider, normalize(value), unit);
    }

    /**
     * Normalizes a configured placeholder to {@code %...%} form. Accepts
     * both {@code %name%} and a bare {@code name}.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() >= 2 && value.startsWith("%") && value.endsWith("%")) {
            return value;
        }
        return "%" + value + "%";
    }
}
