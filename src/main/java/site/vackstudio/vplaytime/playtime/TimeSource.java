package site.vackstudio.vplaytime.playtime;

/**
 * Source of wall-clock time in epoch seconds.
 *
 * <p>Injected into {@link PlayerData} and {@link PlaytimeManager} so tests can
 * use a deterministic fake instead of the system clock. Production uses
 * {@link SystemTimeSource}.
 */
public interface TimeSource {

    /**
     * @return current time in whole epoch seconds
     */
    long epochSeconds();
}
