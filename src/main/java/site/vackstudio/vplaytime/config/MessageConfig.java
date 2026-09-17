package site.vackstudio.vplaytime.config;

/**
 * Player/admin messages from {@code messages.yml}. An empty string means
 * silent for that event; senders skip blank messages. Every key maps to a
 * real situation — see {@code docs/MESSAGES.md} for the full list with
 * placeholders.
 */
public record MessageConfig(
        String prefix,
        String loading,
        // general command situations
        String usage,
        String noPermission,
        String playersOnly,
        String unknownMenu,
        String unknownPlayer,
        String noData,
        // claim feedback
        String claimSuccess,
        String claimLocked,
        String claimAlready,
        String claimFailed,
        // admin
        String reloadOk,
        String reloadFailed,
        String reloadDetail,
        String infoHeader,
        String infoUuid,
        String infoPlaytimeOnline,
        String infoPlaytimeOffline,
        String infoClaimsNone,
        String infoClaims,
        String infoProvider,
        String resetDone,
        String resetFailed,
        String resetAllDone,
        // gui (opt-in, silent by default)
        String menuOpened,
        String menuClosed) {

    public MessageConfig {
        prefix = orEmpty(prefix);
        loading = orEmpty(loading);
        usage = orEmpty(usage);
        noPermission = orEmpty(noPermission);
        playersOnly = orEmpty(playersOnly);
        unknownMenu = orEmpty(unknownMenu);
        unknownPlayer = orEmpty(unknownPlayer);
        noData = orEmpty(noData);
        claimSuccess = orEmpty(claimSuccess);
        claimLocked = orEmpty(claimLocked);
        claimAlready = orEmpty(claimAlready);
        claimFailed = orEmpty(claimFailed);
        reloadOk = orEmpty(reloadOk);
        reloadFailed = orEmpty(reloadFailed);
        reloadDetail = orEmpty(reloadDetail);
        infoHeader = orEmpty(infoHeader);
        infoUuid = orEmpty(infoUuid);
        infoPlaytimeOnline = orEmpty(infoPlaytimeOnline);
        infoPlaytimeOffline = orEmpty(infoPlaytimeOffline);
        infoClaimsNone = orEmpty(infoClaimsNone);
        infoClaims = orEmpty(infoClaims);
        infoProvider = orEmpty(infoProvider);
        resetDone = orEmpty(resetDone);
        resetFailed = orEmpty(resetFailed);
        resetAllDone = orEmpty(resetAllDone);
        menuOpened = orEmpty(menuOpened);
        menuClosed = orEmpty(menuClosed);
    }

    /** Backwards-compatible constructor for the pre-1.9 key set. */
    public MessageConfig(
            String prefix,
            String loading,
            String claimSuccess,
            String claimLocked,
            String claimAlready,
            String claimFailed,
            String reloadOk,
            String reloadFailed) {
        this(prefix, loading, "", "", "", "", "", "",
                claimSuccess, claimLocked, claimAlready, claimFailed,
                reloadOk, reloadFailed, "", "", "", "", "", "", "", "", "", "", "", "", "");
    }

    /** Prepends the configured prefix (empty by default: no behavior change). */
    public String prefixed(String message) {
        return prefix + orEmpty(message);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
