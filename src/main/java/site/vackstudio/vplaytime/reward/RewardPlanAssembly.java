package site.vackstudio.vplaytime.reward;

import site.vackstudio.vplaytime.config.ConfigError;
import site.vackstudio.vplaytime.config.MenuDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure assembly of a candidate configuration into either a committable
 * {@link ValidatedRewardPlan} or a rejection carrying every collected
 * issue. No Bukkit, no I/O, no mutation of live state: callers
 * ({@code ConfigManager}) commit or reject atomically based on the outcome.
 *
 * <p>Assembly order: structural issues (from the collecting parser) first,
 * then the cross-menu content merge, then semantic preflight over the
 * merged model. Every phase collects instead of throwing, so one broken
 * reward never hides the next one.
 */
public final class RewardPlanAssembly {

    private RewardPlanAssembly() {
    }

    /** Either a committable plan or a full rejection. Never partial. */
    public sealed interface Outcome
            permits Outcome.Ready, Outcome.Rejected {

        /** Validated and committable: plan plus the menus owning it. */
        record Ready(ValidatedRewardPlan plan, Map<String, MenuDefinition> menus) implements Outcome {
            public Ready {
                menus = Map.copyOf(menus);
            }
        }

        /** Rejected: every issue plus the partial menus (for GUI diagnostics). */
        record Rejected(PreflightReport report, Map<String, MenuDefinition> menus) implements Outcome {
            public Rejected {
                menus = Map.copyOf(menus);
            }
        }
    }

    /**
     * @param menus      successfully parsed menus (possibly partial)
     * @param structural file-level and per-unit structural issues collected
     *                   while parsing (duplicates, bad slots/materials, ...)
     * @param merger     content registry owner (used only for its stateless
     *                   merge; active state is never touched here)
     * @param env        load-time environment answers for semantic checks
     */
    public static Outcome assemble(
            Map<String, MenuDefinition> menus,
            List<ConfigError> structural,
            RewardManager merger,
            PreflightEnv env) {
        List<ConfigError> structuralErrors = structural == null ? List.of() : structural;
        Map<String, MenuDefinition> safeMenus = menus == null ? Map.of() : menus;

        List<PreflightIssue> structuralIssues = new ArrayList<>();
        for (ConfigError error : structuralErrors) {
            structuralIssues.add(new PreflightIssue(
                    error.file(), error.path(), "", "", -1,
                    PreflightIssue.Type.STRUCTURE, "", error.reason(),
                    "Fix the value at the reported path, then reload."));
        }

        Map<String, site.vackstudio.vplaytime.model.RewardDefinition> merged = Map.of();
        if (!safeMenus.isEmpty()) {
            try {
                merged = merger.parseFromMenus(safeMenus);
            } catch (IllegalStateException ex) {
                String reason = ex instanceof ConfigError error ? error.reason() : ex.getMessage();
                String path = ex instanceof ConfigError error ? error.path() : "menus";
                structuralIssues.add(new PreflightIssue(
                        RewardPreflightValidator.FILE, path, "", "", -1,
                        PreflightIssue.Type.STRUCTURE, "",
                        reason == null ? "Invalid reward definitions." : reason,
                        "Keep every copy of a reward identical (except 'slot'), then reload."));
            }
        } else if (structuralIssues.isEmpty()) {
            structuralIssues.add(new PreflightIssue(
                    RewardPreflightValidator.FILE, "menus", "", "", -1,
                    PreflightIssue.Type.STRUCTURE, "",
                    "No menus with rewards were loaded.",
                    "Restore rewards.yml from backup or reinstall the default, then reload."));
        }

        Map<String, PreflightResult> results = new LinkedHashMap<>();
        if (!merged.isEmpty()) {
            results.putAll(RewardPreflightValidator.validate(safeMenus, env));
        }
        PreflightReport report = PreflightReport.combine(merged.size(), structuralIssues, results);
        if (report.valid() && !merged.isEmpty()) {
            return new Outcome.Ready(new ValidatedRewardPlan(merged, report), safeMenus);
        }
        return new Outcome.Rejected(report, safeMenus);
    }
}
