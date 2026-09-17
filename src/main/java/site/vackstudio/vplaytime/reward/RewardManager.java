package site.vackstudio.vplaytime.reward;

import site.vackstudio.vplaytime.config.MenuDefinition;
import site.vackstudio.vplaytime.model.RewardDefinition;
import site.vackstudio.vplaytime.model.RewardState;
import site.vackstudio.vplaytime.playtime.PlayerData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Configuration-driven reward content: lookup and per-player state.
 *
 * <p>Rewards are defined inside menus (rewards.yml), but identity is global:
 * the same reward id may appear in several menus at different slots. This
 * class merges every menu's copies into one content registry — copies must
 * agree on everything except the slot, otherwise loading fails with a
 * message naming both menus. Claim state is shared by id in
 * {@code PlayerData} either way, so claiming in one menu shows everywhere.
 *
 * <p>Definitions are immutable and stored in an unmodifiable map.
 * {@link #parseFromMenus} validates without touching active state and
 * {@link #swap} publishes a pre-validated map, so a failed load (or reload)
 * never corrupts the active definitions.
 */
public final class RewardManager {

    private final Logger logger;
    private volatile Map<String, RewardDefinition> rewards = Map.of();
    /**
     * Authoritative reward-system gate. Fresh managers and legacy
     * {@link #swap} loads stay {@code ENABLED} (previous behavior);
     * {@link #activatePlan} publishes a validated plan as {@code ENABLED};
     * {@link #disable} parks the system {@code DISABLED} with the reason.
     * Volatile read on every claim: memory-only, no I/O, no scans.
     */
    private volatile SystemStatus status = SystemStatus.enabled(0);

    public RewardManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Merges and validates all menus' rewards, then atomically swaps them in.
     *
     * @param menus parsed menus in any order
     * @throws IllegalStateException with a human-readable message on any invalid data
     */
    public synchronized void load(Map<String, MenuDefinition> menus) {
        swap(parseFromMenus(menus));
    }

    /**
     * Merges without swapping: lets callers validate everything before
     * publishing anything (transactional reload).
     *
     * @return validated content registry (first copy wins), file order
     */
    public Map<String, RewardDefinition> parseFromMenus(Map<String, MenuDefinition> menus) {
        Map<String, RewardDefinition> merged = new LinkedHashMap<>();
        Map<String, String> definedIn = new LinkedHashMap<>();
        for (MenuDefinition menu : menus.values()) {
            for (RewardDefinition def : menu.rewards().values()) {
                RewardDefinition existing = merged.get(def.id());
                if (existing == null) {
                    merged.put(def.id(), def);
                    definedIn.put(def.id(), menu.id());
                } else if (!existing.sameContent(def)) {
                    throw new IllegalStateException("Reward '" + def.id() + "' is defined differently in menu '"
                            + definedIn.get(def.id()) + "' and menu '" + menu.id()
                            + "'. Keep every copy identical (except 'slot'), or give one copy a new id.");
                }
            }
        }
        if (merged.isEmpty()) {
            throw new IllegalStateException("No rewards defined in any menu in rewards.yml.");
        }
        return merged;
    }

    /** Publishes a pre-validated map from {@link #parseFromMenus}. */
    public synchronized void swap(Map<String, RewardDefinition> parsed) {
        this.rewards = Map.copyOf(parsed);
        this.status = SystemStatus.enabled(parsed.size());
        logger.info("Loaded " + parsed.size() + " reward definition(s).");
    }

    /**
     * Atomically publishes a validated plan: definitions plus its report
     * become the active runtime state together, and the system reads
     * {@code ENABLED}. The only path that activates a new configuration.
     */
    public synchronized void activatePlan(ValidatedRewardPlan plan) {
        if (plan == null || !plan.valid()) {
            throw new IllegalArgumentException("only a valid plan can be activated");
        }
        this.rewards = plan.rewards();
        this.status = new SystemStatus(RewardSystemState.ENABLED, "",
                (int) plan.report().validCount(), (int) plan.report().invalidCount(),
                (int) plan.report().unverifiableCount(), plan.size(), plan);
        logger.info("Loaded " + plan.size() + " reward definition(s).");
    }

    /**
     * Parks the reward system {@code DISABLED} with a human reason. Active
     * definitions are left untouched (on failed reload the previous plan
     * stays active underneath; rendering and claims both consult the gate
     * first, so nothing invalid can execute either way).
     */
    public synchronized void disable(String reason, PreflightReport report) {
        PreflightReport safe = report == null ? PreflightReport.empty() : report;
        this.status = new SystemStatus(RewardSystemState.DISABLED,
                reason == null ? "" : reason,
                (int) safe.validCount(), (int) safe.invalidCount(),
                (int) safe.unverifiableCount(), rewards.size(), null);
    }

    /** Central gate: every execution path must consult this first. */
    public boolean systemEnabled() {
        return status.state() == RewardSystemState.ENABLED;
    }

    /** Current gate snapshot for status reporting (info command, logs). */
    public SystemStatus systemStatus() {
        return status;
    }

    /** Reward ids whose load-time availability could not be proven. */
    public java.util.Set<String> unverifiableRewardIds() {
        ValidatedRewardPlan plan = status.plan();
        if (plan == null) {
            return java.util.Set.of();
        }
        return java.util.Set.copyOf(plan.report().unverifiableRewardIds());
    }

    /**
     * Gate snapshot: state, reason (non-blank exactly when DISABLED),
     * validation counts and the active plan (null on legacy loads).
     */
    public record SystemStatus(
            RewardSystemState state,
            String reason,
            int validated,
            int invalid,
            int unverifiable,
            int total,
            ValidatedRewardPlan plan) {

        public SystemStatus {
            reason = reason == null ? "" : reason;
        }

        static SystemStatus enabled(int total) {
            return new SystemStatus(RewardSystemState.ENABLED, "", total, 0, 0, total, null);
        }
    }

    public Optional<RewardDefinition> find(String id) {
        return Optional.ofNullable(rewards.get(id));
    }

    public Map<String, RewardDefinition> all() {
        return rewards;
    }

    public int count() {
        return rewards.size();
    }

    /**
     * State of one reward for one player. CLAIMED always wins over playtime.
     * Pure memory read: no storage access.
     *
     * @param effectiveSeconds the player's CURRENT playtime from the active
     *                         {@code PlaytimeProvider} (internal timer or
     *                         external source) — compared against the
     *                         reward's {@code required-seconds}, which stays
     *                         the single source of truth for requirements
     */
    public Optional<RewardState> stateFor(PlayerData data, String rewardId, long effectiveSeconds) {
        RewardDefinition def = rewards.get(rewardId);
        if (def == null || data == null) {
            return Optional.empty();
        }
        if (data.isClaimed(rewardId)) {
            return Optional.of(RewardState.CLAIMED);
        }
        if (effectiveSeconds >= def.requiredSeconds()) {
            return Optional.of(RewardState.CLAIMABLE);
        }
        return Optional.of(RewardState.LOCKED);
    }
}
