package site.vackstudio.vplaytime.papi;

import org.bukkit.plugin.java.JavaPlugin;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;

import java.util.logging.Level;

/**
 * Owns the {@code %vplaytime_*%} expansion lifecycle behind
 * {@link Object} handles, so the plugin bootstrap never mentions a PAPI
 * type in a field or signature (class verification of the main class must
 * succeed with or without PlaceholderAPI installed).
 *
 * <p>This class itself references PAPI types in its method bodies, so it
 * must only be loaded after {@link PapiGuard#present(JavaPlugin)} returns
 * true — both methods below are called exclusively behind that guard.
 */
public final class ExpansionHost {

    private ExpansionHost() {
    }

    /**
     * Registers the expansion once. Returns the registered handle, or
     * {@code null} (with a log line) when registration fails — the plugin
     * works fully without its own expansion.
     */
    public static Object register(JavaPlugin plugin, PlaytimeManager playtime, String version) {
        try {
            VPlaytimeExpansion expansion = new VPlaytimeExpansion(playtime, version);
            if (expansion.register()) {
                plugin.getLogger().info("PlaceholderAPI expansion registered:"
                        + " %vplaytime_seconds%, %vplaytime_minutes%, %vplaytime_hours%.");
                return expansion;
            }
            plugin.getLogger().warning("PlaceholderAPI expansion registration failed;"
                    + " %vplaytime_*% placeholders will be unavailable.");
            return null;
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "PlaceholderAPI expansion registration failed;"
                    + " continuing without %vplaytime_*%: " + ex.getMessage());
            return null;
        }
    }

    /** Unregisters a handle from {@link #register}; never throws. */
    public static void unregister(JavaPlugin plugin, Object handle) {
        if (!(handle instanceof VPlaytimeExpansion expansion)) {
            return;
        }
        try {
            expansion.unregister();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to unregister PlaceholderAPI expansion: " + ex.getMessage());
        }
    }
}
