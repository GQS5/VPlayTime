package site.vackstudio.vplaytime.admin;

import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.playtime.PlayerData;
import site.vackstudio.vplaytime.playtime.TimeSource;
import site.vackstudio.vplaytime.reward.RewardManager;
import site.vackstudio.vplaytime.storage.Storage;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Admin operations over UUIDs (never player names, never Bukkit objects).
 * Name resolution and sender feedback live in the command layer; everything
 * here is unit-testable.
 *
 * <p>Ordering rule for resets: the durable row is revoked FIRST, memory is
 * converged after. The reverse order could delete a legitimately re-made
 * claim from the DB while memory keeps it — a divergence that autosave
 * cannot repair. Revoke-first divergences self-heal through
 * {@code recordClaim}'s conflict signal instead.
 */
public final class AdminService {

    public record PlayerInfo(
            boolean found,
            UUID uuid,
            long storedSeconds,
            long effectiveSeconds,
            boolean online,
            Set<String> claimed,
            int rewardCount) {
    }

    public record ResetOutcome(boolean ok, String detail, boolean hadClaim) {
        public static ResetOutcome ok(boolean hadClaim, String detail) {
            return new ResetOutcome(true, detail, hadClaim);
        }

        public static ResetOutcome failed(String detail) {
            return new ResetOutcome(false, detail, false);
        }
    }

    private final PlaytimeManager playtime;
    private final RewardManager rewards;
    private final Storage storage;
    private final TimeSource clock;
    private final Logger logger;

    public AdminService(
            PlaytimeManager playtime,
            RewardManager rewards,
            Storage storage,
            TimeSource clock,
            Logger logger) {
        this.playtime = playtime;
        this.rewards = rewards;
        this.storage = storage;
        this.clock = clock;
        this.logger = logger;
    }

    /**
     * Player overview. Online players are read from memory; offline players
     * from async storage. Never blocks.
     */
    public CompletableFuture<PlayerInfo> info(UUID uuid) {
        Optional<PlayerData> data = playtime.find(uuid);
        if (data.isPresent()) {
            PlayerData d = data.get();
            return CompletableFuture.completedFuture(new PlayerInfo(true, uuid,
                    d.getStoredPlaytimeSeconds(),
                    playtime.effectivePlaytimeSeconds(uuid),
                    true, d.claimedRewardIds(), rewards.count()));
        }
        return storage.loadPlayer(uuid).thenApply(stored -> stored
                .map(s -> new PlayerInfo(true, uuid, s.playtimeSeconds(), s.playtimeSeconds(),
                        false, s.claimedRewardIds(), rewards.count()))
                .orElseGet(() -> new PlayerInfo(false, uuid, 0L, 0L, false, Set.of(), rewards.count()))
        ).exceptionally(ex -> {
            logger.log(Level.WARNING, "Admin info failed for " + uuid + ": " + messageOf(ex));
            return new PlayerInfo(false, uuid, 0L, 0L, false, Set.of(), rewards.count());
        });
    }

    /** Removes one claim without granting anything and without touching playtime. */
    public CompletableFuture<ResetOutcome> reset(UUID uuid, String rewardId) {
        if (rewards.find(rewardId).isEmpty()) {
            return CompletableFuture.completedFuture(
                    ResetOutcome.failed("unknown reward '" + rewardId + "'"));
        }
        // Messaging-only pre-read; authority is the post-revoke verification load.
        boolean memoryHad = playtime.find(uuid).map(d -> d.isClaimed(rewardId)).orElse(false);
        return storage.loadPlayer(uuid).thenCompose(stored -> {
            boolean durableHad = stored
                    .map(s -> s.claimedRewardIds().contains(rewardId))
                    .orElse(false);
            return storage.revokeClaim(uuid, rewardId)
                    .thenCompose(ignored -> storage.loadPlayer(uuid))
                    .thenApply(reloaded -> {
                        boolean present = reloaded
                                .map(s -> s.claimedRewardIds().contains(rewardId))
                                .orElse(false);
                        // Converge memory TO the durable truth, never the reverse.
                        playtime.find(uuid).ifPresent(d -> {
                            if (present) {
                                d.tryClaim(rewardId);
                            } else {
                                d.unclaim(rewardId);
                            }
                        });
                        if (present) {
                            return ResetOutcome.ok(true,
                                    "claim was re-made during reset; kept current state, retry if needed");
                        }
                        boolean had = durableHad || memoryHad;
                        return ResetOutcome.ok(had, had ? "claim reset" : "player had no such claim");
                    });
        }).exceptionally(ex -> {
            logger.log(Level.SEVERE, "Admin reset failed for " + uuid + " / " + rewardId + ": " + messageOf(ex));
            return ResetOutcome.failed("storage unavailable, try again");
        });
    }

    /** Removes every claim for one player. Playtime and the player row are untouched. */
    public CompletableFuture<ResetOutcome> resetAll(UUID uuid) {
        return storage.loadPlayer(uuid).thenCompose(stored -> {
            int durableCount = stored.map(s -> s.claimedRewardIds().size()).orElse(0);
            return storage.revokeAllClaims(uuid)
                    .thenCompose(ignored -> storage.loadPlayer(uuid))
                    .thenApply(reloaded -> {
                        Set<String> present = reloaded
                                .map(s -> s.claimedRewardIds())
                                .orElse(Set.of());
                        playtime.find(uuid).ifPresent(d -> {
                            d.unclaimAll();
                            present.forEach(d::tryClaim);
                        });
                        if (!present.isEmpty()) {
                            return ResetOutcome.ok(true, "claims re-made during reset; kept current state");
                        }
                        return ResetOutcome.ok(durableCount > 0, "reset " + durableCount + " claim(s)");
                    });
        }).exceptionally(ex -> {
            logger.log(Level.SEVERE, "Admin reset-all failed for " + uuid + ": " + messageOf(ex));
            return ResetOutcome.failed("storage unavailable, try again");
        });
    }

    private static String messageOf(Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        return String.valueOf(cause.getMessage());
    }
}
