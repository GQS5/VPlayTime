package site.vackstudio.vplaytime.papi;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * The only production wiring between VPlaytime and PlaceholderAPI.
 *
 * <p>This class references PAPI types, so it must only be loaded when
 * PlaceholderAPI is present — every caller guards with
 * {@link PapiGuard#present(JavaPlugin)} first. (A PAPI presence check must
 * live in {@link PapiGuard}, never here: merely loading this class on a
 * server without PAPI would fail class verification.)
 *
 * <p>Resolution runs synchronously on the caller's thread. All call sites
 * (menu open/refresh, click, claim validation) already run on the
 * player's region/entity thread under Folia, and placeholder expansion
 * lookups are in-memory map reads — no blocking, no thread hops.
 */
public final class PlaceholderLookup {

    private PlaceholderLookup() {
    }

    /**
     * Builds the raw-value lookup for one configured placeholder template
     * (e.g. {@code %statistic_seconds_played%}): online player in,
     * resolved text out. Offline players yield empty (routine, silent).
     * Resolution failures yield empty with a warn-once log.
     */
    public static Function<UUID, Optional<String>> bukkit(JavaPlugin plugin, String template) {
        return uuid -> {
            if (uuid == null) {
                return Optional.empty();
            }
            Player player;
            try {
                player = Bukkit.getPlayer(uuid);
            } catch (Exception ex) {
                return Optional.empty();
            }
            if (player == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(PlaceholderAPI.setPlaceholders(player, template));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING,
                        "PlaceholderAPI failed to resolve '" + template + "' for "
                        + player.getName() + ": " + ex.getMessage());
                return Optional.empty();
            }
        };
    }
}
