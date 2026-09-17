package site.vackstudio.vplaytime.reward;

import java.util.List;
import java.util.Objects;

/**
 * Per-reward preflight verdict.
 *
 * <ul>
 *   <li>{@code VALID} — every check passed; the reward may be activated.</li>
 *   <li>{@code INVALID} — provably unsafe, malformed, unsupported, impossible
 *       or missing a mandatory dependency; blocks activation of the whole
 *       configuration.</li>
 *   <li>{@code UNVERIFIABLE} — structurally sound, but correctness cannot be
 *       fully proven before runtime (e.g. an {@code @p} selector target).
 *       Never used to bypass obvious errors: any INVALID issue wins.</li>
 * </ul>
 */
public record PreflightResult(String rewardId, Status status, List<PreflightIssue> issues) {

    public enum Status {
        VALID,
        INVALID,
        UNVERIFIABLE
    }

    public PreflightResult {
        rewardId = rewardId == null ? "" : rewardId;
        status = Objects.requireNonNull(status, "status");
        issues = List.copyOf(issues == null ? List.of() : issues);
    }

    public static PreflightResult valid(String rewardId) {
        return new PreflightResult(rewardId, Status.VALID, List.of());
    }
}
