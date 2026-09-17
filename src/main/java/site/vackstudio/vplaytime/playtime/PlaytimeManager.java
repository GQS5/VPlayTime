package site.vackstudio.vplaytime.playtime;

import site.vackstudio.vplaytime.model.StoredData;
import site.vackstudio.vplaytime.playtime.provider.PlaytimeProvider;
import site.vackstudio.vplaytime.storage.Storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runtime playtime system: player-state lifecycle, session tracking, cache,
 * and persistence coordination.
 *
 * <p>Lifecycle (storage mode):
 * <pre>
 * join → async load → cache.put → startSession (only if no quit arrived first)
 * quit → endSession (stored += elapsed, dirty if changed) → cache.remove → async save
 * </pre>
 *
 * <p>No schedulers, no tick polling, no blocking JDBC. Storage reads happen on
 * join only; writes on quit, autosave (dirty only) and shutdown. Hot paths
 * (GUI, placeholders) read memory.
 *
 * <p>Join/quit handoff: activation (load completion) and finalization (quit)
 * are mutually exclusive under {@code lifecycleLock}, both pure in-memory, so
 * a quit racing a load can neither leak a session for an offline player nor
 * lose one for an online player.
 *
 * <p>Provider seam: {@link #effectivePlaytimeSeconds(UUID)} is the single
 * read every consumer (GUI, placeholders, claims, admin info, API) goes
 * through. It delegates to the active {@link PlaytimeProvider} — internal
 * session math by default, any external source after a reload swaps it.
 * Reward logic never touches provider types, so future providers
 * (HexaCore, VCore) plug in without changing a single reward class.
 */
public final class PlaytimeManager {

    private static final long SHUTDOWN_FLUSH_SECONDS = 15;

    private final TimeSource clock;
    private final PlayerCache cache;
    private final Storage storage; // null = memory-only (tests)
    private final Logger logger;

    /**
     * Active playtime source. {@code null} means "internal math" (used by
     * unit tests, which never wire a provider): identical behavior to the
     * built-in provider. Production always sets an explicit provider at
     * enable/reload time; the volatile write makes the swap atomic.
     */
    private volatile PlaytimeProvider provider;

    /**
     * Whether joins/ quits run the internal session timer. Turned off while
     * an external provider is active: no second timer runs, and the frozen
     * stored total is never displayed or compared (claims still load, save
     * and reset normally — only accumulation stops).
     */
    private volatile boolean trackSessions = true;

    /** UUIDs with a storage load in flight. Guarded by {@link #lifecycleLock}. */
    private final Set<UUID> pendingJoins = new HashSet<>();
    private final Object lifecycleLock = new Object();

    public PlaytimeManager(TimeSource clock) {
        this(clock, new PlayerCache(), null, Logger.getLogger("VPlaytime"));
    }

    public PlaytimeManager(TimeSource clock, Storage storage) {
        this(clock, new PlayerCache(), storage, Logger.getLogger("VPlaytime"));
    }

    PlaytimeManager(TimeSource clock, PlayerCache cache, Storage storage, Logger logger) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
        this.cache = cache;
        this.storage = storage;
        this.logger = logger;
    }

    /**
     * Atomically swaps the active playtime source (reload path). A
     * {@code null} argument restores plain internal math.
     */
    public void setProvider(PlaytimeProvider provider) {
        this.provider = provider;
    }

    /** The active playtime source, or empty when running on internal math. */
    public Optional<PlaytimeProvider> provider() {
        return Optional.ofNullable(provider);
    }

    /** Config id of the active source ({@code "internal"} by default). Diagnostics only. */
    public String providerId() {
        PlaytimeProvider active = this.provider;
        return active == null ? "internal" : active.id();
    }

    /** Whether an external source (not the session timer) is currently active. */
    public boolean externalProviderActive() {
        PlaytimeProvider active = this.provider;
        return active != null && active.external();
    }

    /**
     * Stops/starts internal session accumulation. Used when an external
     * provider takes over so no second timer runs. Claims are unaffected:
     * they still load on join and save on quit/autosave/reset either way.
     */
    public void setTrackSessions(boolean track) {
        this.trackSessions = track;
    }

    public boolean tracksSessions() {
        return trackSessions;
    }

    /**
     * Initializes state for a joining player and starts their session.
     *
     * <p>Memory mode: synchronous get-or-create (Phase 2 behavior). Storage
     * mode: loads persisted state asynchronously, then caches and starts the
     * session — unless a quit arrived first, in which case the loaded data is
     * dropped without ever becoming active (it holds no session time, so
     * nothing is lost).
     *
     * <p>With session tracking off (external provider) the claims state
     * still loads and caches exactly the same way; only
     * {@code startSession} is skipped, so no timer runs.
     */
    public CompletableFuture<PlayerData> handleJoin(UUID uuid) {
        Optional<PlayerData> cached = cache.get(uuid);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(cached.get());
        }
        if (storage == null) {
            PlayerData fresh = new PlayerData(uuid, 0L);
            cache.put(uuid, fresh);
            if (trackSessions) {
                fresh.startSession(clock.epochSeconds());
            }
            return CompletableFuture.completedFuture(fresh);
        }
        synchronized (lifecycleLock) {
            pendingJoins.add(uuid);
        }
        final boolean track = this.trackSessions;
        return storage.loadPlayer(uuid).thenApply(stored -> {
            PlayerData data = stored
                    .map(s -> PlayerData.loaded(uuid, s.playtimeSeconds(), s.claimedRewardIds()))
                    .orElseGet(() -> new PlayerData(uuid, 0L));
            data.markClean();
            synchronized (lifecycleLock) {
                if (pendingJoins.remove(uuid)) {
                    cache.put(uuid, data);
                    if (track) {
                        data.startSession(clock.epochSeconds());
                    }
                }
                // Else: quit arrived during load. Data never activated, no
                // session ran, nothing to persist — drop it.
            }
            return data;
        }).exceptionally(ex -> {
            synchronized (lifecycleLock) {
                pendingJoins.remove(uuid);
            }
            logger.log(Level.SEVERE, "Failed to load player " + uuid + ": " + messageOf(ex));
            // Fail open with memory-only state so the player can still play;
            // the error is loud, not silent.
            PlayerData fallback = new PlayerData(uuid, 0L);
            cache.put(uuid, fallback);
            if (trackSessions) {
                fallback.startSession(clock.epochSeconds());
            }
            return fallback;
        });
    }

    /**
     * Finalizes the quitting player's session, untracks them, and submits an
     * async save when anything changed. Returns the finalized data.
     *
     * <p>With session tracking off there is no session to end; claims
     * dirtied by {@code tryClaim} still save exactly as before.
     */
    public Optional<PlayerData> handleQuit(UUID uuid) {
        Optional<PlayerData> data;
        synchronized (lifecycleLock) {
            pendingJoins.remove(uuid);
            data = cache.get(uuid);
            if (trackSessions) {
                data.ifPresent(d -> d.endSession(clock.epochSeconds()));
            }
            cache.remove(uuid);
        }
        data.ifPresent(this::saveIfDirty);
        return data;
    }

    public Optional<PlayerData> find(UUID uuid) {
        return cache.get(uuid);
    }

    public boolean isTracked(UUID uuid) {
        return cache.contains(uuid);
    }

    /**
     * Effective playtime for GUI/placeholder/claim/admin reads — the single
     * seam the whole plugin reads through. Delegates to the active
     * {@link PlaytimeProvider} (internal session math when none is wired).
     * Unavailable external values fail closed to {@code 0} (LOCKED, never
     * granted). Never mutates stored state, never throws.
     */
    public long effectivePlaytimeSeconds(UUID uuid) {
        PlaytimeProvider active = this.provider;
        if (active == null) {
            return internalEffectiveSeconds(uuid);
        }
        try {
            return Math.max(0L, active.playtimeSeconds(uuid).orElse(0L));
        } catch (Exception ex) {
            // Providers promise never to throw; belt and braces so one bad
            // source can never break menus, clicks or claims.
            logger.log(Level.WARNING, "Playtime provider '" + active.id()
                    + "' threw; treating as unavailable: " + messageOf(ex));
            return 0L;
        }
    }

    /**
     * Internal session math: stored total plus the elapsed open session, or
     * {@code 0} for untracked players. Read target for the built-in
     * provider (wired as a method reference, so no construction cycle) and
     * for unit tests. Never mutates stored state.
     */
    public long internalEffectiveSeconds(UUID uuid) {
        if (uuid == null) {
            return 0L;
        }
        return cache.get(uuid)
                .map(d -> d.getEffectivePlaytimeSeconds(clock.epochSeconds()))
                .orElse(0L);
    }

    /**
     * Autosave entry point: snapshots every dirty player and saves
     * asynchronously. Clean players are skipped — no unnecessary writes.
     */
    public CompletableFuture<Void> saveDirtySnapshots() {
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (UUID uuid : snapshotTracked()) {
            cache.get(uuid).ifPresent(data -> {
                StoredData snapshot = data.snapshot();
                if (data.isDirty() && storage != null) {
                    saves.add(saveSnapshot(data, snapshot));
                }
            });
        }
        return CompletableFuture.allOf(saves.toArray(new CompletableFuture[0]));
    }

    /**
     * Shutdown flush (disable thread, never player-facing): ends all open
     * sessions, saves dirty states, waits bounded, then closes storage.
     */
    public void shutdown() {
        List<UUID> tracked = snapshotTracked();
        for (UUID uuid : tracked) {
            cache.get(uuid).ifPresent(d -> d.endSession(clock.epochSeconds()));
        }
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (UUID uuid : tracked) {
            cache.get(uuid).ifPresent(data -> {
                if (data.isDirty() && storage != null) {
                    saves.add(saveSnapshot(data, snapshotOf(data)));
                }
            });
        }
        if (!saves.isEmpty()) {
            try {
                CompletableFuture.allOf(saves.toArray(new CompletableFuture[0]))
                        .get(SHUTDOWN_FLUSH_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Shutdown flush timed out or failed; "
                        + "some playtime may not have persisted: " + messageOf(ex));
            }
        }
        if (storage != null) {
            storage.close();
        }
    }

    /** Number of actively tracked players. Diagnostics only. */
    public int trackedCount() {
        return cache.size();
    }

    private List<UUID> snapshotTracked() {
        List<UUID> uuids = new ArrayList<>();
        cache.forEach((uuid, data) -> uuids.add(uuid));
        return uuids;
    }

    private void saveIfDirty(PlayerData data) {
        if (storage == null || !data.isDirty()) {
            return;
        }
        saveNow(data);
    }

    /**
     * Persists the current snapshot immediately (async). Used by the claim
     * path after a durable grant; the version guard keeps newer changes dirty.
     */
    public void saveNow(PlayerData data) {
        if (storage == null) {
            return;
        }
        saveSnapshot(data, snapshotOf(data));
    }

    private StoredData snapshotOf(PlayerData data) {
        return data.snapshot();
    }

    private CompletableFuture<Void> saveSnapshot(PlayerData data, StoredData snapshot) {
        return storage.savePlayer(snapshot).thenAccept(ignored ->
                data.markCleanIfVersion(snapshot.version())
        ).exceptionally(ex -> {
            // Dirty flag untouched: the change will be retried by autosave/shutdown.
            logger.log(Level.SEVERE, "Failed to save player "
                    + snapshot.uuid() + ": " + messageOf(ex));
            return null;
        });
    }

    private static String messageOf(Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        return String.valueOf(cause.getMessage());
    }
}
