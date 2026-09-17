package site.vackstudio.vplaytime.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;

import java.util.Locale;
import java.util.UUID;

/**
 * VPlaytime's own PlaceholderAPI expansion ({@code %vplaytime_...%}).
 *
 * <p>Registered only when PlaceholderAPI is present, and only once at
 * enable time — it reads the live playtime manager on every call, so
 * provider switches via {@code /vplaytime reload} need no re-registration.
 * Values always come from the ACTIVE provider (internal or external);
 * when the provider has no value the read fails closed to {@code 0},
 * exactly like the GUI and the claim check.
 *
 * <p>Provided: {@code %vplaytime_seconds%}, {@code %vplaytime_minutes%},
 * {@code %vplaytime_hours%}. Unknown parameters return {@code null} so
 * PlaceholderAPI leaves them untouched.
 */
public final class VPlaytimeExpansion extends PlaceholderExpansion {

    private final PlaytimeManager playtime;
    private final String version;

    public VPlaytimeExpansion(PlaytimeManager playtime, String version) {
        if (playtime == null) {
            throw new IllegalArgumentException("playtime must not be null");
        }
        this.playtime = playtime;
        this.version = version == null ? "unknown" : version;
    }

    @Override
    public String getIdentifier() {
        return "vplaytime";
    }

    @Override
    public String getAuthor() {
        return "VackStudio";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return null;
        }
        return resolve(playtime, player.getUniqueId(), params);
    }

    /**
     * Pure resolution core (no Bukkit objects beyond the UUID): provider
     * seconds in, placeholder text out, or {@code null} for unknown
     * parameters (PlaceholderAPI then leaves the text untouched).
     */
    public static String resolve(PlaytimeManager playtime, UUID uuid, String params) {
        if (playtime == null || uuid == null || params == null) {
            return null;
        }
        final long seconds;
        try {
            seconds = Math.max(0L, playtime.effectivePlaytimeSeconds(uuid));
        } catch (Exception ex) {
            return null;
        }
        return switch (params.trim().toLowerCase(Locale.ROOT)) {
            case "seconds" -> Long.toString(seconds);
            case "minutes" -> Long.toString(seconds / 60L);
            case "hours" -> Long.toString(seconds / 3_600L);
            default -> null;
        };
    }
}
