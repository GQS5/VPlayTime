package site.vackstudio.vplaytime.config;

/**
 * Player/admin messages from {@code messages.yml}. An empty string means
 * sounds-only feedback for that event; the listener skips blank messages.
 */
public record MessageConfig(
        String prefix,
        String loading,
        String claimSuccess,
        String claimLocked,
        String claimAlready,
        String claimFailed,
        String reloadOk,
        String reloadFailed) {

    public MessageConfig {
        prefix = orEmpty(prefix);
        loading = orEmpty(loading);
        claimSuccess = orEmpty(claimSuccess);
        claimLocked = orEmpty(claimLocked);
        claimAlready = orEmpty(claimAlready);
        claimFailed = orEmpty(claimFailed);
        reloadOk = orEmpty(reloadOk);
        reloadFailed = orEmpty(reloadFailed);
    }

    /** Prepends the configured prefix (empty by default: no behavior change). */
    public String prefixed(String message) {
        return prefix + orEmpty(message);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
