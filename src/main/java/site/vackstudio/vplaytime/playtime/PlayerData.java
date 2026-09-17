package site.vackstudio.vplaytime.playtime;

import site.vackstudio.vplaytime.model.StoredData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent player state: identity, accumulated playtime, open session.
 *
 * <p>Represents data, never a live {@code Player} entity. The canonical unit
 * is whole seconds. Fractional seconds lost to clock granularity are accepted;
 * sessions are never double-counted.
 *
 * <p>Thread safety: mutating methods are synchronized on this instance. They
 * perform no I/O, so the critical sections are tiny. Reads of
 * {@link #getStoredPlaytimeSeconds()} and {@link #isDirty()} are volatile and
 * lock-free.
 */
public final class PlayerData {

    private final UUID uuid;
    private volatile long storedPlaytimeSeconds;
    private volatile boolean dirty;

    /** Epoch second the current session started, or {@code null} when offline. */
    private Long sessionStartSeconds;

    /** Claimed reward IDs. Guarded by this instance's monitor; never exposed. */
    private final Set<String> claimedRewards = new HashSet<>();

    /**
     * Mutation generation. Incremented on every persistent-state change so an
     * older async save can never mark a newer change clean (§16).
     */
    private long version;

    public PlayerData(UUID uuid, long storedPlaytimeSeconds) {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid must not be null");
        }
        if (storedPlaytimeSeconds < 0) {
            throw new IllegalArgumentException("storedPlaytimeSeconds must be >= 0");
        }
        this.uuid = uuid;
        this.storedPlaytimeSeconds = storedPlaytimeSeconds;
        this.dirty = false;
        this.sessionStartSeconds = null;
    }

    public UUID uuid() {
        return uuid;
    }

    public long getStoredPlaytimeSeconds() {
        return storedPlaytimeSeconds;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Marks the data clean after it has been persisted. Phase 3 calls this. */
    public void markClean() {
        this.dirty = false;
    }

    /**
     * Marks clean only if no mutation happened after the given snapshot
     * version. A save of snapshot v10 succeeding after the data moved to v11
     * leaves the data dirty, so the newer change is still persisted later.
     */
    public synchronized void markCleanIfVersion(long snapshotVersion) {
        if (this.version == snapshotVersion) {
            this.dirty = false;
        }
    }

    public synchronized long version() {
        return version;
    }

    /**
     * Consistent immutable persistence snapshot. Must be called holding no
     * other locks; the async storage task then reads only this snapshot, never
     * the live object.
     */
    public synchronized StoredData snapshot() {
        return new StoredData(uuid, storedPlaytimeSeconds, Set.copyOf(claimedRewards), version);
    }

    /**
     * Rebuilds state loaded from durable storage. Starts clean: loaded values
     * are already persisted, so no write is owed until something changes.
     */
    public static PlayerData loaded(UUID uuid, long storedPlaytimeSeconds, Set<String> claimedRewardIds) {
        PlayerData data = new PlayerData(uuid, storedPlaytimeSeconds);
        if (claimedRewardIds != null) {
            data.claimedRewards.addAll(claimedRewardIds);
        }
        return data;
    }

    public synchronized boolean isClaimed(String rewardId) {
        return claimedRewards.contains(rewardId);
    }

    /**
     * Atomic claim reservation: adds the ID only if absent. Returns
     * {@code false} when already claimed. A successful reservation bumps the
     * version and dirties the data (playtime snapshot carries the claim set).
     */
    public synchronized boolean tryClaim(String rewardId) {
        if (!claimedRewards.add(rewardId)) {
            return false;
        }
        dirty = true;
        version++;
        return true;
    }

    /**
     * Compensation for a failed claim (reward never granted or durable insert
     * rejected): removes the reservation so the player can retry. Bumps the
     * version; dirty stays set so persistence converges.
     */
    public synchronized void unclaim(String rewardId) {
        if (claimedRewards.remove(rewardId)) {
            version++;
            dirty = true;
        }
    }

    public synchronized Set<String> claimedRewardIds() {
        return Set.copyOf(claimedRewards);
    }

    /**
     * Removes every claim reservation (admin reset). Returns the removed ids.
     * Bumps the version; dirty stays set so persistence converges.
     */
    public synchronized Set<String> unclaimAll() {
        if (claimedRewards.isEmpty()) {
            return Set.of();
        }
        Set<String> removed = Set.copyOf(claimedRewards);
        claimedRewards.clear();
        version++;
        dirty = true;
        return removed;
    }

    public synchronized boolean hasActiveSession() {
        return sessionStartSeconds != null;
    }

    /**
     * Starts a session at {@code now}. If a session is already active the
     * original start is kept, so a duplicate join can never restart (and
     * later double-count) the clock.
     */
    public synchronized void startSession(long now) {
        if (sessionStartSeconds == null) {
            sessionStartSeconds = now;
        }
    }

    /**
     * Effective playtime: stored total plus the elapsed open session, or the
     * stored total when offline.
     *
     * <p>Read-only: querying never mutates the stored total.
     *
     * @param now current epoch second from the owning {@link TimeSource}
     */
    public synchronized long getEffectivePlaytimeSeconds(long now) {
        if (sessionStartSeconds == null) {
            return storedPlaytimeSeconds;
        }
        return storedPlaytimeSeconds + elapsed(now, sessionStartSeconds);
    }

    /**
     * Finalizes the open session: its elapsed time is added to the stored
     * total exactly once, the session is closed, and the data is marked dirty
     * if anything was added.
     *
     * <p>Calling this with no open session is a no-op returning {@code 0}.
     *
     * @param now current epoch second from the owning {@link TimeSource}
     * @return seconds added to the stored total
     */
    public synchronized long endSession(long now) {
        if (sessionStartSeconds == null) {
            return 0;
        }
        long elapsed = elapsed(now, sessionStartSeconds);
        sessionStartSeconds = null;
        if (elapsed > 0) {
            storedPlaytimeSeconds += elapsed;
            dirty = true;
            version++;
        }
        return elapsed;
    }

    /**
     * Elapsed seconds between two clock readings. A clock that moved backward
     * yields {@code 0}: playtime is never subtracted because of a clock anomaly.
     */
    static long elapsed(long now, long start) {
        return Math.max(0L, now - start);
    }
}
