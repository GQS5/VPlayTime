package site.vackstudio.vplaytime.playtime;

/**
 * Production {@link TimeSource} backed by the system clock.
 */
public final class SystemTimeSource implements TimeSource {

    @Override
    public long epochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
