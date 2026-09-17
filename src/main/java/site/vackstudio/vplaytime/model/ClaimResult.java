package site.vackstudio.vplaytime.model;

/**
 * Explicit outcome of a claim attempt. Callers can distinguish every rejection
 * reason; SQL exceptions never leak to GUI code.
 */
public record ClaimResult(Status status, String detail) {

    public enum Status {
        SUCCESS,
        NOT_FOUND,
        NOT_LOADED,
        LOCKED,
        ALREADY_CLAIMED,
        REWARD_FAILED,
        STORAGE_FAILED
    }

    public static ClaimResult success() {
        return new ClaimResult(Status.SUCCESS, "");
    }

    public static ClaimResult of(Status status, String detail) {
        return new ClaimResult(status, detail == null ? "" : detail);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
