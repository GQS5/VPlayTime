package site.vackstudio.vplaytime.reward;

import site.vackstudio.vplaytime.model.RewardAction;

import java.util.List;

/**
 * Delivers reward actions to a {@link ClaimTarget}. Single-action granularity
 * with an ordered multi-action driver, so failures carry the exact action
 * index and dispatch follows configured order.
 */
public interface RewardExecutor {

    /**
     * Delivers one action. Implementations handle their own action type and
     * throw {@link IllegalArgumentException} for anything else (programmer
     * error — the dispatcher routes by type).
     */
    Result execute(ClaimTarget target, RewardAction action);

    /**
     * Delivers all actions in configured order, stopping at the first
     * failure. The returned index identifies the failed action for logging
     * and operator diagnosis.
     *
     * <p>Threading contract: command actions must run on the global tick
     * thread on Folia (console dispatch is rejected elsewhere). ClaimManager
     * splits delivery accordingly; call this directly only where every action
     * is safe on the calling thread.
     */
    default Result executeAll(ClaimTarget target, List<RewardAction> actions) {
        for (int i = 0; i < actions.size(); i++) {
            Result result = execute(target, actions.get(i));
            if (!result.ok()) {
                return new Result(false, result.detail(), i);
            }
        }
        return Result.success();
    }

    record Result(boolean ok, String detail, int failedActionIndex) {
        public static Result success() {
            return new Result(true, "", -1);
        }

        public static Result failed(String detail) {
            return new Result(false, detail == null ? "" : detail, -1);
        }
    }
}
