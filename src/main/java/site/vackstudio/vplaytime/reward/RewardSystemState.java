package site.vackstudio.vplaytime.reward;

/**
 * Authoritative reward-system state: the single gate every execution path
 * must respect. {@code ENABLED} means a fully validated plan is active;
 * {@code DISABLED} means no valid plan exists (invalid startup config or
 * ... in practice only reachable when nothing valid was ever loaded, since
 * failed reloads keep the previous plan) and every claim attempt is refused
 * before touching player data, storage or executors.
 */
public enum RewardSystemState {
    ENABLED,
    DISABLED
}
