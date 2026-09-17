package site.vackstudio.vplaytime.model;

import java.util.List;

/**
 * Immutable configuration of one reward <i>as placed in one menu</i>.
 *
 * <p>The slot belongs here on purpose: positions are specific to the menu
 * showing the reward. The same reward id may appear in another menu at a
 * different slot without changing the reward itself. Everything <i>except</i>
 * the slot must stay identical across menus for the same id (validated at
 * load); claim state is shared by id in {@code PlayerData} either way.
 */
public record RewardDefinition(
        String id,
        int slot,
        long requiredSeconds,
        RewardDisplay display,
        List<RewardAction> actions) {

    public RewardDefinition {
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("invalid reward id '" + id + "'");
        }
        if (requiredSeconds < 0) {
            throw new IllegalArgumentException("requiredSeconds must be >= 0");
        }
        if (display == null) {
            throw new IllegalArgumentException("display must not be null");
        }
        actions = List.copyOf(actions);
    }

    /**
     * True when both copies describe the same reward apart from placement:
     * same required time, same look, same actions.
     */
    public boolean sameContent(RewardDefinition other) {
        if (other == null) {
            return false;
        }
        return id.equals(other.id)
                && requiredSeconds == other.requiredSeconds
                && display.equals(other.display)
                && actions.equals(other.actions);
    }
}
