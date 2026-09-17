package site.vackstudio.vplaytime.reward;

import site.vackstudio.vplaytime.model.RewardAction;

/**
 * Delivers {@link RewardAction.ItemAction}s natively (no {@code /give}).
 * Single actions route through the same batched path production claims use
 * ({@link ClaimTarget#giveItems}), so capacity semantics never diverge.
 */
public final class ItemActionExecutor implements RewardExecutor {

    @Override
    public Result execute(ClaimTarget target, RewardAction action) {
        if (!(action instanceof RewardAction.ItemAction item)) {
            throw new IllegalArgumentException("ItemActionExecutor cannot handle " + action);
        }
        if (target.giveItems(java.util.List.of(item)) != -1) {
            return Result.failed("could not deliver " + item.amount() + "x " + item.material());
        }
        return Result.success();
    }
}
