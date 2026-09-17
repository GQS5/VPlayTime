package site.vackstudio.vplaytime.gui;

import site.vackstudio.vplaytime.model.RewardState;

/**
 * Lightweight internal placeholder resolver for GUI text. No PlaceholderAPI,
 * no external calls. Unknown placeholders are left untouched.
 *
 * <p>Supported: {@code %playtime%} (formatted effective),
 * {@code %playtime_seconds%}, {@code %playtime_minutes%},
 * {@code %playtime_hours%}, {@code %required_playtime%} (formatted),
 * {@code %reward_status%} (LOCKED/CLAIMABLE/CLAIMED).
 */
public final class Placeholders {

    private Placeholders() {
    }

    public record Context(long effectiveSeconds, long requiredSeconds, RewardState state) {
    }

    public static String resolve(String text, Context ctx) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return text.replace("%playtime%", PlaytimeFormat.format(ctx.effectiveSeconds()))
                .replace("%playtime_seconds%", Long.toString(ctx.effectiveSeconds()))
                .replace("%playtime_minutes%", Long.toString(ctx.effectiveSeconds() / 60))
                .replace("%playtime_hours%", Long.toString(ctx.effectiveSeconds() / 3600))
                .replace("%required_playtime%", PlaytimeFormat.format(ctx.requiredSeconds()))
                .replace("%reward_status%", ctx.state() == null ? "UNKNOWN" : ctx.state().name());
    }
}
