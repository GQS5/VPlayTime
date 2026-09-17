package site.vackstudio.vplaytime.storage;

import site.vackstudio.vplaytime.model.StoredData;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Persistent player-state store. Implementations perform all blocking work off
 * the calling thread; every method returns immediately.
 *
 * <p>Callers must never block a player/region thread waiting on these futures.
 * Use continuations ({@code thenAccept}, {@code exceptionally}, ...).
 */
public interface Storage {

    /**
     * Loads persisted state. Empty when the player has no row (new player).
     */
    CompletableFuture<Optional<StoredData>> loadPlayer(UUID uuid);

    /**
     * Persists playtime from an immutable snapshot. Implementations must only
     * touch the tables the snapshot covers (Phase 3: {@code players} only, so
     * future claim rows are never wiped by a playtime save).
     */
    CompletableFuture<Void> savePlayer(StoredData snapshot);

    /**
     * Durably records one claim: upserts playtime and inserts the claim row in
     * a single transaction.
     *
     * @return {@code true} when the row was newly inserted, {@code false} when
     *         the claim already existed (uniqueness safety net)
     */
    CompletableFuture<Boolean> recordClaim(StoredData snapshot, String rewardId, long claimedAtEpochSeconds);

    /**
     * Compensation for a granted-but-failed claim: removes the claim row so
     * the player can retry. Best-effort; a failed revoke self-heals on the
     * next claim attempt via {@code recordClaim}'s conflict signal.
     */
    CompletableFuture<Void> revokeClaim(UUID uuid, String rewardId);

    /**
     * Removes every claim row for one player (admin reset). Playtime is
     * untouched. Memory state is converged separately by the caller.
     */
    CompletableFuture<Void> revokeAllClaims(UUID uuid);

    /**
     * Drains queued work with a bounded wait, then releases all resources.
     * Called on plugin disable; never on a player-facing thread.
     */
    void close();
}
