package site.vackstudio.vplaytime.playtime.provider;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * The built-in provider: VPlaytime's own session timer
 * (stored total + open session). Always available, no dependencies.
 *
 * <p>Takes the read as a function so there is no construction cycle with
 * the owning manager, and so tests can wire any clock.
 */
public final class InternalPlaytimeProvider implements PlaytimeProvider {

    private final ToLongFunction<UUID> read;

    public InternalPlaytimeProvider(ToLongFunction<UUID> read) {
        if (read == null) {
            throw new IllegalArgumentException("read must not be null");
        }
        this.read = read;
    }

    @Override
    public String id() {
        return "internal";
    }

    @Override
    public OptionalLong playtimeSeconds(UUID playerId) {
        if (playerId == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Math.max(0L, read.applyAsLong(playerId)));
        } catch (Exception ex) {
            return OptionalLong.empty();
        }
    }
}
