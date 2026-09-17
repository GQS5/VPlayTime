package site.vackstudio.vplaytime.gui;

/**
 * Human-readable durations for GUI text. Pure string logic, no server needed.
 *
 * <p>Examples: {@code 0 -> "0 seconds"}, {@code 45 -> "45 seconds"},
 * {@code 720 -> "12 minutes"}, {@code 3720 -> "1 hour 2 minutes"}.
 */
public final class PlaytimeFormat {

    private PlaytimeFormat() {
    }

    public static String format(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "0 seconds";
        }
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder out = new StringBuilder();
        if (hours > 0) {
            out.append(hours).append(hours == 1 ? " hour" : " hours");
        }
        if (minutes > 0) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(minutes).append(minutes == 1 ? " minute" : " minutes");
        }
        if (seconds > 0 && hours == 0) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(seconds).append(seconds == 1 ? " second" : " seconds");
        }
        if (out.length() == 0) {
            // Exact hours with zero remainder, e.g. 3600 -> "1 hour".
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        return out.toString();
    }
}
