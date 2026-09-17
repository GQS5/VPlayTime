package site.vackstudio.vplaytime.model;

import org.bukkit.Material;

/**
 * One structured reward action from configuration.
 *
 * <p>Sealed: new action types are added as new permitted records plus an
 * executor, without touching existing code paths. No raw command strings.
 */
public sealed interface RewardAction permits RewardAction.ItemAction, RewardAction.CommandAction {

    /**
     * Grants {@code amount} of {@code material} via the inventory API.
     * Amounts are validated to {@code 1..64} at parse time. Full item-type
     * checks need the server registry (unavailable in unit tests), so parsing
     * gates on {@code matchMaterial} plus an AIR rejection; anything else
     * ungrantable fails safely at delivery time via the revoke path.
     */
    record ItemAction(Material material, int amount) implements RewardAction {
        public ItemAction {
            if (material == null || material == Material.AIR) {
                throw new IllegalArgumentException("item action needs a real item material");
            }
            if (amount < 1 || amount > 64) {
                throw new IllegalArgumentException("item amount must be 1..64 (got " + amount + ")");
            }
        }
    }

    /**
     * Runs a console command after internal placeholder substitution.
     * Foundation for V1; no scripting.
     */
    record CommandAction(String command) implements RewardAction {
        public CommandAction {
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException("command action needs a non-blank command");
            }
        }
    }
}
