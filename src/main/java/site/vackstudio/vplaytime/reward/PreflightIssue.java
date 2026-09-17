package site.vackstudio.vplaytime.reward;

import java.util.Objects;

/**
 * One machine-usable preflight finding. Every issue names the file, the
 * dotted config path, the reward, the offending value and its kind, plus a
 * human reason and a suggested fix — the same shape the console diagnostics
 * and the {@code reload-detail} player message are built from.
 *
 * <p>Pure data: no Bukkit, no I/O, safe to construct and inspect in unit
 * tests. {@code actionIndex} is the 0-based action position, or -1 when the
 * issue is not about a single action.
 */
public record PreflightIssue(
        String file,
        String path,
        String rewardId,
        String level,
        int actionIndex,
        Type type,
        String value,
        String reason,
        String fix) {

    /** What kind of check produced the issue. */
    public enum Type {
        REWARD_ID,
        LEVEL,
        REQUIRED_PLAYTIME,
        SLOT,
        MATERIAL,
        DISPLAY,
        ACTION,
        COMMAND,
        EXTERNAL_PROVIDER,
        PLACEHOLDER,
        ITEM,
        AMOUNT,
        STRUCTURE
    }

    public PreflightIssue {
        file = file == null || file.isBlank() ? "rewards.yml" : file;
        path = path == null ? "" : path;
        rewardId = rewardId == null ? "" : rewardId;
        level = level == null ? "" : level;
        type = Objects.requireNonNull(type, "type");
        value = value == null ? "" : value;
        reason = reason == null ? "" : reason;
        fix = fix == null ? "" : fix;
    }

    /** Display label for the action position ({@code actions[2]}), or "". */
    public String actionLabel() {
        return actionIndex < 0 ? "" : "actions[" + actionIndex + "]";
    }
}
