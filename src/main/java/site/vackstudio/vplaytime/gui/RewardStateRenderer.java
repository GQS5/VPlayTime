package site.vackstudio.vplaytime.gui;

import site.vackstudio.vplaytime.model.RewardDefinition;
import site.vackstudio.vplaytime.model.RewardDisplay;
import site.vackstudio.vplaytime.model.RewardState;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure state-to-visual resolution. State comes from the caller (Core);
 * nothing is recalculated here, so a stale menu can never repaint itself
 * into a wrong state — it only renders what Core reported.
 */
public final class RewardStateRenderer {

    private RewardStateRenderer() {
    }

    public static RenderedReward resolve(
            RewardDefinition def, RewardState state, long effectiveSeconds) {
        RewardDisplay.DisplayState display = def.display().forState(state);
        Placeholders.Context ctx = new Placeholders.Context(effectiveSeconds, def.requiredSeconds(), state);
        List<String> lore = new ArrayList<>(display.lore().size());
        for (String line : display.lore()) {
            lore.add(Placeholders.resolve(line, ctx));
        }
        return new RenderedReward(
                display.material(),
                Placeholders.resolve(def.display().name(), ctx),
                List.copyOf(lore),
                display.glow());
    }
}
