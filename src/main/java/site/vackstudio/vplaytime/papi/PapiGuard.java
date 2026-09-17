package site.vackstudio.vplaytime.papi;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * PlaceholderAPI presence check with zero PAPI references, so this class
 * (and its callers) loads and verifies fine on servers WITHOUT
 * PlaceholderAPI. Anything that touches PAPI types
 * ({@link PlaceholderLookup}, {@link VPlaytimeExpansion},
 * {@link ExpansionHost}) is only ever loaded after this returns true —
 * class verification would otherwise fail with NoClassDefFoundError
 * before our guards could run.
 */
public final class PapiGuard {

    private PapiGuard() {
    }

    /** Whether PlaceholderAPI is installed AND enabled right now. Never throws. */
    public static boolean present(JavaPlugin plugin) {
        try {
            Plugin papi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
            return papi != null && papi.isEnabled();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "PlaceholderAPI presence check failed: " + ex.getMessage());
            return false;
        }
    }
}
