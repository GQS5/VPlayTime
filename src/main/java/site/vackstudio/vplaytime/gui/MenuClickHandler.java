package site.vackstudio.vplaytime.gui;

import site.vackstudio.vplaytime.model.RewardState;

import java.util.Map;
import java.util.UUID;

/**
 * Pure click decision for menu events. The handler receives the CURRENT core
 * state — never the clicked ItemStack's appearance — so a stale visual can
 * never bypass ClaimManager: the claim path revalidates everything anyway.
 */
public final class MenuClickHandler {

    private MenuClickHandler() {
    }

    public enum ClickAction {
        /** Not our menu, or not a reward slot: ignore (caller still cancels). */
        IGNORE,
        LOCKED_FEEDBACK,
        CLAIMED_FEEDBACK,
        CLAIM
    }

    public record ClickDecision(ClickAction action, String rewardId) {
        public static ClickDecision ignore() {
            return new ClickDecision(ClickAction.IGNORE, null);
        }
    }

    /**
     * @param isMenu true when the top inventory holder is a {@link MenuHolder}
     * @param clickedTop true when the click landed in the top (menu) inventory
     * @param rawSlot clicked raw slot
     * @param topSize menu inventory size
     * @param slotToReward configured slot to reward-id mapping
     * @param currentState current core state of the resolved reward (null = unknown)
     */
    public static ClickDecision decide(
            boolean isMenu,
            boolean clickedTop,
            int rawSlot,
            int topSize,
            Map<Integer, String> slotToReward,
            RewardState currentState) {
        if (!isMenu || !clickedTop || rawSlot < 0 || rawSlot >= topSize) {
            return ClickDecision.ignore();
        }
        String rewardId = slotToReward.get(rawSlot);
        if (rewardId == null || currentState == null) {
            return ClickDecision.ignore();
        }
        return switch (currentState) {
            case LOCKED -> new ClickDecision(ClickAction.LOCKED_FEEDBACK, rewardId);
            case CLAIMED -> new ClickDecision(ClickAction.CLAIMED_FEEDBACK, rewardId);
            case CLAIMABLE -> new ClickDecision(ClickAction.CLAIM, rewardId);
        };
    }
}
