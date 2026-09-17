package site.vackstudio.vplaytime.reward;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Production {@link GlobalScheduler} over Folia's global region scheduler.
 * Scheduling on a disabled plugin throws and is handled by the caller.
 */
public final class BukkitGlobalScheduler implements GlobalScheduler {

    private final JavaPlugin plugin;

    public BukkitGlobalScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run(Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
    }
}
