package site.vackstudio.vplaytime.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;

/**
 * Player lifecycle wiring. Delegates everything to {@link PlaytimeManager};
 * no business logic lives here.
 */
public final class PlayerListener implements Listener {

    private final PlaytimeManager playtimeManager;

    public PlayerListener(PlaytimeManager playtimeManager) {
        this.playtimeManager = playtimeManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Async load; never blocks the region thread. Load failures are logged
        // inside the manager, which falls back to memory-only state.
        playtimeManager.handleJoin(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Phase 3 persists the returned data asynchronously.
        playtimeManager.handleQuit(event.getPlayer().getUniqueId());
    }
}
