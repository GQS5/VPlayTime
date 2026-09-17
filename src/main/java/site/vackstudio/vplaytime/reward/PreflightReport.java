package site.vackstudio.vplaytime.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete preflight outcome for one configuration load: one verdict per
 * reward plus file-level structural issues (duplicate menu order, missing
 * {@code main} menu, cross-menu reward mismatches, empty registry).
 *
 * <p>A configuration may be activated only when {@link #valid()} holds:
 * zero INVALID results and zero INVALID structural issues. UNVERIFIABLE
 * entries do not block activation but are always reported.
 *
 * <p>Immutable. {@link #consoleReport()} renders the exact diagnostic block
 * the console log shows on failure (file, path, reward, type, value, reason,
 * fix per issue, counts on top).
 */
public record PreflightReport(
        Map<String, PreflightResult> results,
        List<PreflightIssue> structuralIssues,
        int scanned) {

    public PreflightReport {
        results = Map.copyOf(results == null ? Map.of() : results);
        structuralIssues = List.copyOf(structuralIssues == null ? List.of() : structuralIssues);
    }

    public static PreflightReport empty() {
        return new PreflightReport(Map.of(), List.of(), 0);
    }

    public long validCount() {
        return results.values().stream().filter(r -> r.status() == PreflightResult.Status.VALID).count();
    }

    public long invalidCount() {
        return results.values().stream().filter(r -> r.status() == PreflightResult.Status.INVALID).count()
                + structuralIssues.stream().filter(i -> !i.reason().isBlank()).count();
    }

    public long unverifiableCount() {
        return results.values().stream().filter(r -> r.status() == PreflightResult.Status.UNVERIFIABLE).count();
    }

    /** Activation gate: zero INVALID anywhere (per-reward or structural). */
    public boolean valid() {
        if (!structuralIssues.isEmpty()) {
            return false;
        }
        return results.values().stream().noneMatch(r -> r.status() == PreflightResult.Status.INVALID);
    }

    /** Every issue in deterministic order: structural first, then per-reward file order. */
    public List<PreflightIssue> allIssues() {
        List<PreflightIssue> all = new ArrayList<>(structuralIssues);
        for (PreflightResult result : results.values()) {
            all.addAll(result.issues());
        }
        return List.copyOf(all);
    }

    /** Reward ids whose commands could not be proven available at load. */
    public List<String> unverifiableRewardIds() {
        List<String> ids = new ArrayList<>();
        for (PreflightResult result : results.values()) {
            if (result.status() == PreflightResult.Status.UNVERIFIABLE) {
                ids.add(result.rewardId());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Console diagnostic block in the documented shape: header, counts, one
     * numbered entry per issue (file, path, reward, level, type, value,
     * reason, fix), closing retention line.
     */
    public List<String> consoleReport() {
        List<String> lines = new ArrayList<>();
        lines.add("VPlayTime Reward Preflight Validation FAILED");
        lines.add("");
        lines.add("Rewards scanned: " + scanned);
        lines.add("Valid: " + validCount());
        lines.add("Invalid: " + invalidCount());
        lines.add("Unverifiable: " + unverifiableCount());
        List<PreflightIssue> issues = allIssues();
        for (int index = 0; index < issues.size(); index++) {
            PreflightIssue issue = issues.get(index);
            lines.add("");
            lines.add("[" + (index + 1) + "]");
            lines.add("File: " + issue.file());
            lines.add("Path: " + issue.path());
            lines.add("Reward: " + (issue.rewardId().isEmpty() ? "-" : issue.rewardId()));
            if (!issue.level().isEmpty()) {
                lines.add("Level: " + issue.level());
            }
            lines.add("Type: " + issue.type());
            lines.add("Value: " + (issue.value().isEmpty() ? "-" : issue.value()));
            lines.add("Reason: " + issue.reason());
            lines.add("Fix: " + issue.fix());
        }
        lines.add("");
        lines.add("Previous configuration remains active.");
        return List.copyOf(lines);
    }

    /** One-line player/admin summary (used in {@code reload-detail}). */
    public String summary() {
        return "Preflight failed: " + invalidCount() + " invalid, "
                + unverifiableCount() + " unverifiable of " + scanned + " scanned (see console).";
    }

    /** Combines structural issues with per-reward results into one report. */
    public static PreflightReport combine(
            int scanned, List<PreflightIssue> structural, Map<String, PreflightResult> results) {
        Map<String, PreflightResult> ordered = new LinkedHashMap<>(results);
        return new PreflightReport(ordered, structural, scanned);
    }
}
