package site.vackstudio.vplaytime.model;

import org.bukkit.Material;

import java.util.List;

/**
 * Immutable display configuration of one reward. Text stays MiniMessage;
 * rendering happens in the GUI phase.
 */
public record RewardDisplay(
        String name,
        DisplayState locked,
        DisplayState claimable,
        DisplayState claimed) {

    public DisplayState forState(RewardState state) {
        return switch (state) {
            case LOCKED -> locked;
            case CLAIMABLE -> claimable;
            case CLAIMED -> claimed;
        };
    }

    public record DisplayState(Material material, List<String> lore, boolean glow) {
        public DisplayState {
            if (material == null) {
                throw new IllegalArgumentException("display material must not be null");
            }
            lore = List.copyOf(lore);
        }
    }
}
