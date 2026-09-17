package site.vackstudio.vplaytime.reward;

import org.bukkit.Bukkit;
import site.vackstudio.vplaytime.VPlaytimePlugin;

/**
 * Production {@link PreflightEnv}: answers come from the live server at
 * load time (startup/reload, main thread). Command registration is the
 * installation evidence — a root counts as available when an enabled
 * plugin or vanilla currently provides it. Results are frozen into the
 * validated plan; the claim path never probes again (no per-click cost,
 * no metadata scans at runtime).
 */
public final class BukkitPreflightEnv implements PreflightEnv {

    private final VPlaytimePlugin plugin;

    public BukkitPreflightEnv(VPlaytimePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean commandRegistered(String root) {
        if (root == null || root.isBlank()) {
            return false;
        }
        try {
            return Bukkit.getCommandMap().getCommand(root) != null;
        } catch (Exception ex) {
            // Probe failed: never misreport a usable command as missing at
            // load (a false INVALID would disable the whole reward system).
            plugin.getLogger().warning("Preflight command probe failed for '/" + root
                    + "'; treating it as available: " + ex.getMessage());
            return true;
        }
    }
}
