package site.vackstudio.vplaytime.api;

import site.vackstudio.vplaytime.model.ClaimResult;
import site.vackstudio.vplaytime.model.RewardState;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.playtime.TimeSource;
import site.vackstudio.vplaytime.reward.ClaimManager;
import site.vackstudio.vplaytime.reward.ClaimTarget;
import site.vackstudio.vplaytime.reward.RewardManager;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Minimal public API for other plugins. Read-only views plus the single
 * protected claim path — there is no unrestricted grant route, so external
 * callers cannot bypass duplicate protection or mutate internal state.
 *
 * <p>Obtain via {@link #get()} after enable. Nothing here exposes
 * {@code PlayerData}, storage internals, executors or GUI classes.
 */
public final class VPlaytimeAPI {

    private static volatile VPlaytimeAPI instance;

    /** Set by the plugin on enable, cleared on disable. */
    public static void setInstance(VPlaytimeAPI api) {
        instance = api;
    }

    public static Optional<VPlaytimeAPI> get() {
        return Optional.ofNullable(instance);
    }

    private final PlaytimeManager playtime;
    private final RewardManager rewards;
    private final ClaimManager claims;
    private final TimeSource clock;

    public VPlaytimeAPI(
            PlaytimeManager playtime, RewardManager rewards, ClaimManager claims, TimeSource clock) {
        this.playtime = playtime;
        this.rewards = rewards;
        this.claims = claims;
        this.clock = clock;
    }

    /** Stored (banked) playtime, or 0 for untracked players. */
    public long getStoredPlaytime(UUID uuid) {
        return playtime.find(uuid).map(d -> d.getStoredPlaytimeSeconds()).orElse(0L);
    }

    /** Stored plus open session, or 0 for untracked players. Read-only. */
    public long getEffectivePlaytime(UUID uuid) {
        return playtime.effectivePlaytimeSeconds(uuid);
    }

    public boolean isClaimed(UUID uuid, String rewardId) {
        return playtime.find(uuid).map(d -> d.isClaimed(rewardId)).orElse(false);
    }

    public Optional<RewardState> getRewardState(UUID uuid, String rewardId) {
        return playtime.find(uuid)
                .flatMap(d -> rewards.stateFor(d, rewardId, playtime.effectivePlaytimeSeconds(uuid)));
    }

    public Set<String> rewardIds() {
        return rewards.all().keySet().stream().collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Claims through the same protected {@link ClaimManager} path the GUI
     * uses — same validation, atomicity and durability. Never grants twice.
     */
    public CompletableFuture<ClaimResult> claim(UUID uuid, String rewardId, ClaimTarget target) {
        return claims.claim(uuid, rewardId, target);
    }
}
