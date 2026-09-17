package site.vackstudio.vplaytime.reward;

import site.vackstudio.vplaytime.model.RewardDefinition;

import java.util.Map;

/**
 * Immutable validated reward plan: the only reward content the runtime may
 * use. Built once per successful load/reload from fully validated
 * definitions; published atomically; never mutated afterwards. GUI
 * rendering, click handling and claims all read from here (via
 * {@link RewardManager}), so no YAML parsing, filesystem access or
 * environment probing ever happens on the runtime path.
 */
public record ValidatedRewardPlan(Map<String, RewardDefinition> rewards, PreflightReport report) {

    public ValidatedRewardPlan {
        rewards = Map.copyOf(rewards == null ? Map.of() : rewards);
        report = report == null ? PreflightReport.empty() : report;
    }

    /** True when the embedded report passes the activation gate. */
    public boolean valid() {
        return !rewards.isEmpty() && report.valid();
    }

    public int size() {
        return rewards.size();
    }
}
