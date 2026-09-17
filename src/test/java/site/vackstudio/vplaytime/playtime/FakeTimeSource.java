package site.vackstudio.vplaytime.playtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic {@link TimeSource} for tests. No sleeping: tests set or
 * advance the clock explicitly.
 */
public final class FakeTimeSource implements TimeSource {

    private final AtomicLong now;

    public FakeTimeSource(long startEpochSeconds) {
        this.now = new AtomicLong(startEpochSeconds);
    }

    @Override
    public long epochSeconds() {
        return now.get();
    }

    public void advance(long seconds) {
        now.addAndGet(seconds);
    }

    public void set(long epochSeconds) {
        now.set(epochSeconds);
    }
}
