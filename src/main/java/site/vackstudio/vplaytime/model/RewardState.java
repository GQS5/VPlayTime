package site.vackstudio.vplaytime.model;

/**
 * Visual/claim status of one reward for one player.
 *
 * <p>Exactly three states in V1. CLAIMED always takes precedence over
 * playtime: a claimed reward is never CLAIMABLE again.
 */
public enum RewardState {
    LOCKED,
    CLAIMABLE,
    CLAIMED
}
