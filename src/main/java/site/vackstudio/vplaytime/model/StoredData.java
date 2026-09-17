package site.vackstudio.vplaytime.model;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable persistence snapshot of one player.
 *
 * <p>Created from live {@code PlayerData} under its lock, then handed to async
 * storage work which must never observe the mutable object changing underneath
 * it. The {@code version} is the {@code PlayerData} generation counter at
 * snapshot time; callers mark state clean only if the version is unchanged
 * when the save completes (§16: a newer change must not be marked clean by an
 * older save).
 *
 * <p>{@code claimedRewardIds} is empty in Phase 3 (no claim system yet); the
 * field and the {@code claims} table exist so Phase 4 needs no migration.
 */
public record StoredData(
        UUID uuid,
        long playtimeSeconds,
        Set<String> claimedRewardIds,
        long version) {

    public StoredData {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid must not be null");
        }
        if (playtimeSeconds < 0) {
            throw new IllegalArgumentException("playtimeSeconds must be >= 0");
        }
        claimedRewardIds = Set.copyOf(claimedRewardIds);
    }
}
