package site.vackstudio.vplaytime.reward;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Folia-safe {@link PlayerScheduler}: continuations run on the player's entity
 * scheduler. Contract: only used for online players (the GUI claim path).
 * An offline player logs loudly and runs inline as a last resort so claim
 * futures always complete; target operations then fail safely into the revoke
 * path.
 */
public final class BukkitPlayerScheduler implements PlayerScheduler {

    private final JavaPlugin plugin;
    private final Logger logger;

    public BukkitPlayerScheduler(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @Override
    public void run(UUID playerId, Runnable task) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.getScheduler().run(plugin, scheduled -> task.run(), null);
            return;
        }
        logger.warning("Player " + playerId + " went offline mid-claim; running inline fallback.");
        task.run();
    }
}
