package site.vackstudio.vplaytime.gui;

import org.bukkit.Material;

import java.util.List;

/**
 * Pure resolution of the dedicated non-claimable GUI error state. Used for
 * every reward slot while no valid reward plan is active (invalid startup):
 * a visually distinct item with no claim instruction, no glow and no
 * successful-claim look. Clicks on it resolve to IGNORE in
 * {@link MenuClickHandler} terms — the listener additionally answers with
 * the configured unavailable message and never reaches the claim path.
 *
 * <p>Blank configuration falls back to built-in English so the error state
 * can never render as an empty item, no matter what messages.yml contains.
 */
public final class RewardErrorState {

    /** Distinct from every gameplay material (candles, buttons, filler). */
    public static final Material MATERIAL = Material.REDSTONE_BLOCK;

    static final String FALLBACK_NAME = "<red>⚠ CONFIGURATION ERROR";
    static final List<String> FALLBACK_LORE = List.of(
            "<gray>This reward is temporarily unavailable.",
            "<gray>Please contact an administrator.");

    private RewardErrorState() {
    }

    public static RenderedReward resolve(String name, List<String> lore) {
        String safeName = name == null || name.isBlank() ? FALLBACK_NAME : name;
        List<String> safeLore = lore == null || lore.isEmpty() ? FALLBACK_LORE : List.copyOf(lore);
        return new RenderedReward(MATERIAL, safeName, safeLore, false);
    }
}
