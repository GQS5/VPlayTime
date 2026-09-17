package site.vackstudio.vplaytime.playtime.provider;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads playtime from any PlaceholderAPI placeholder (e.g. a statistics
 * plugin's playtime value) and normalizes it to whole seconds.
 *
 * <p>The placeholder string and unit come from configuration — nothing is
 * hardcoded. Resolution itself is injected as {@code rawLookup} so this
 * class never touches Bukkit or PlaceholderAPI directly: production wires
 * the real lookup, unit tests wire fakes. The only allocation per read is
 * the {@link Optional} the lookup already returns; no caching is done
 * because every call site is event-driven (menu open/refresh, click,
 * claim) and values must always be current.
 *
 * <p>Failure policy: offline/unknown player → silent empty; unresolved
 * placeholder (still contains {@code %}) → warn-once + empty; blank or
 * non-numeric value → warn-once + empty. Never throws, never invents time.
 */
public final class PlaceholderPlaytimeProvider implements PlaytimeProvider {

    private final String placeholder;
    private final PlaytimeUnit unit;
    private final Function<UUID, Optional<String>> rawLookup;
    private final Logger logger;

    /** Warn-once keys so a broken placeholder can't spam the console. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public PlaceholderPlaytimeProvider(
            String placeholder,
            PlaytimeUnit unit,
            Function<UUID, Optional<String>> rawLookup,
            Logger logger) {
        if (placeholder == null || placeholder.isBlank()) {
            throw new IllegalArgumentException("placeholder must not be blank");
        }
        if (unit == null) {
            throw new IllegalArgumentException("unit must not be null");
        }
        if (rawLookup == null) {
            throw new IllegalArgumentException("rawLookup must not be null");
        }
        if (logger == null) {
            throw new IllegalArgumentException("logger must not be null");
        }
        this.placeholder = placeholder;
        this.unit = unit;
        this.rawLookup = rawLookup;
        this.logger = logger;
    }

    @Override
    public String id() {
        return "placeholder";
    }

    @Override
    public boolean external() {
        return true;
    }

    /** The configured placeholder template (with {@code %...%}). Diagnostics only. */
    public String placeholder() {
        return placeholder;
    }

    /** The configured unit. Diagnostics only. */
    public PlaytimeUnit unit() {
        return unit;
    }

    @Override
    public OptionalLong playtimeSeconds(UUID playerId) {
        if (playerId == null) {
            return OptionalLong.empty();
        }
        final Optional<String> raw;
        try {
            raw = rawLookup.apply(playerId);
        } catch (Exception ex) {
            warnOnce("lookup", "Placeholder lookup for '" + placeholder + "' failed: " + ex.getMessage());
            return OptionalLong.empty();
        }
        if (raw == null || raw.isEmpty()) {
            // Offline player or no value: silent, this is routine.
            return OptionalLong.empty();
        }
        String value = raw.get().trim();
        if (value.isEmpty()) {
            return OptionalLong.empty();
        }
        if (value.indexOf('%') >= 0) {
            warnOnce("unknown",
                    "Placeholder '" + placeholder + "' did not resolve (got '" + value + "'). "
                    + "The expansion is missing or the name is wrong; check /papi list.");
            return OptionalLong.empty();
        }
        OptionalLong converted = convert(value, unit);
        if (converted.isEmpty()) {
            warnOnce("invalid",
                    "Placeholder '" + placeholder + "' returned non-numeric value '" + value
                    + "'; expected a non-negative number in " + unit.name().toLowerCase(Locale.ROOT) + ".");
        }
        return converted;
    }

    /**
     * Pure parse + convert: trimmed raw text and a unit in, whole seconds
     * or empty out. Accepts integers and decimals (floored); rejects
     * negatives, NaN/Infinity and anything else. No logging, no I/O.
     */
    static OptionalLong convert(String trimmed, PlaytimeUnit unit) {
        if (trimmed == null || trimmed.isEmpty() || unit == null) {
            return OptionalLong.empty();
        }
        try {
            long whole = Long.parseLong(trimmed);
            if (whole < 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(unit.toSeconds(whole));
        } catch (NumberFormatException notLong) {
            return convertDecimal(trimmed, unit);
        }
    }

    /**
     * Decimal path: scale by the unit first, then floor — {@code 1.5}
     * minutes is 90 seconds, and {@code 1500.9} milliseconds is 1 second.
     * Saturates instead of overflowing.
     */
    private static OptionalLong convertDecimal(String trimmed, PlaytimeUnit unit) {
        final double decimal;
        try {
            decimal = Double.parseDouble(trimmed);
        } catch (NumberFormatException notNumber) {
            return OptionalLong.empty();
        }
        if (!Double.isFinite(decimal) || decimal < 0) {
            return OptionalLong.empty();
        }
        double seconds = decimal * unit.secondsFactor();
        if (seconds >= Long.MAX_VALUE) {
            return OptionalLong.of(Long.MAX_VALUE);
        }
        return OptionalLong.of((long) seconds);
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            logger.log(Level.WARNING, message);
        }
    }
}
