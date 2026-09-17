package site.vackstudio.vplaytime.playtime;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory store of actively tracked players: {@code UUID -> PlayerData}.
 *
 * <p>Backed by a {@link ConcurrentHashMap} because on Folia join/quit events
 * for different players may run on different region threads. The map itself is
 * never exposed; callers use the small {@code get/put/remove/contains} API.
 * The database (Phase 3) remains the source of truth; this cache only avoids
 * per-click storage access.
 */
public final class PlayerCache {

    private final ConcurrentMap<UUID, PlayerData> active = new ConcurrentHashMap<>();

    public Optional<PlayerData> get(UUID uuid) {
        return Optional.ofNullable(active.get(uuid));
    }

    public void put(UUID uuid, PlayerData data) {
        active.put(uuid, data);
    }

    public Optional<PlayerData> remove(UUID uuid) {
        return Optional.ofNullable(active.remove(uuid));
    }

    public boolean contains(UUID uuid) {
        return active.containsKey(uuid);
    }

    /**
     * Applies {@code action} to each tracked entry. Backed by a concurrent map
     * so entries added/removed mid-iteration are handled safely; the map
     * itself is never exposed.
     */
    public void forEach(java.util.function.BiConsumer<UUID, PlayerData> action) {
        active.forEach(action);
    }

    /** Number of actively tracked players. Diagnostics only. */
    public int size() {
        return active.size();
    }
}
