package site.vackstudio.vplaytime.playtime.provider;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * Source of a player's playtime, normalized to whole seconds.
 *
 * <p>The rest of VPlaytime (reward states, claims, GUI text, admin info)
 * only ever calls {@link #playtimeSeconds(UUID)} and compares the result
 * against {@code required-seconds}. It never knows — or cares — which
 * implementation is active:
 *
 * <pre>
 * PlaytimeProvider
 *  ├── InternalPlaytimeProvider      (built-in session timer)
 *  └── PlaceholderPlaytimeProvider   (any PlaceholderAPI placeholder)
 *      ├── HexaCorePlaytimeProvider  (future: same slot, no reward changes)
 *      └── VCorePlaytimeProvider     (future: same slot, no reward changes)
 * </pre>
 *
 * <p>Contract:
 * <ul>
 *   <li>Returns whole seconds. Fractional provider values are floored.</li>
 *   <li>Returns {@link OptionalLong#empty()} when the value is unavailable
 *       (player offline, unknown placeholder, unparseable value, missing
 *       dependency). Callers fail closed: unavailable reads as {@code 0},
 *       so rewards stay LOCKED instead of granting on unknown data.</li>
 *   <li>Never throws, never blocks, never invents playtime.</li>
 *   <li>Implementations are immutable and thread-safe; the active instance
 *       is swapped atomically on reload.</li>
 * </ul>
 */
public interface PlaytimeProvider {

    /** Config id: {@code "internal"}, {@code "placeholder"}, later {@code "hexacore"}/{@code "vcore"}. */
    String id();

    /**
     * Current playtime in whole seconds, or empty when unavailable.
     * Safe to call on any thread; must not throw.
     */
    OptionalLong playtimeSeconds(UUID playerId);

    /**
     * Whether this provider replaces the internal session timer. When an
     * external provider is active VPlaytime stops accumulating its own
     * timer (claims storage keeps working normally).
     */
    default boolean external() {
        return false;
    }
}
