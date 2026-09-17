package site.vackstudio.vplaytime.playtime.provider;

import java.util.Locale;

/**
 * Unit of the raw number a placeholder returns. Converts to the canonical
 * whole seconds VPlaytime compares against {@code required-seconds}.
 */
public enum PlaytimeUnit {

    SECONDS,
    MINUTES,
    HOURS,
    MILLISECONDS;

    /**
     * Parses a config value (case-insensitive, singular/plural accepted).
     * Returns {@code null} for unknown input; the caller reports the
     * config error with file and path.
     */
    public static PlaytimeUnit parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "seconds", "second", "secs", "sec", "s" -> SECONDS;
            case "minutes", "minute", "mins", "min", "m" -> MINUTES;
            case "hours", "hour", "hrs", "hr", "h" -> HOURS;
            case "milliseconds", "millisecond", "millis", "ms" -> MILLISECONDS;
            default -> null;
        };
    }

    /**
     * Converts a non-negative raw value to whole seconds. Saturates at
     * {@link Long#MAX_VALUE} instead of overflowing; sub-second values
     * floor toward zero.
     */
    public long toSeconds(long value) {
        long safe = Math.max(0L, value);
        try {
            return switch (this) {
                case SECONDS -> safe;
                case MINUTES -> Math.multiplyExact(safe, 60L);
                case HOURS -> Math.multiplyExact(safe, 3_600L);
                case MILLISECONDS -> safe / 1_000L;
            };
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Seconds per raw unit, for the decimal parse path (floors after scaling). */
    double secondsFactor() {
        return switch (this) {
            case SECONDS -> 1.0;
            case MINUTES -> 60.0;
            case HOURS -> 3_600.0;
            case MILLISECONDS -> 0.001;
        };
    }
}
