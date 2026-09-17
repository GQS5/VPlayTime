package site.vackstudio.vplaytime.reward;

import site.vackstudio.vplaytime.model.RewardAction;

/**
 * Routes each action to its typed executor in configured order. New action
 * types plug in as a new branch plus an executor — ClaimManager is untouched.
 */
public final class DelegatingRewardExecutor implements RewardExecutor {

    private final ItemActionExecutor items = new ItemActionExecutor();
    private final CommandActionExecutor commands = new CommandActionExecutor();

    @Override
    public Result execute(ClaimTarget target, RewardAction action) {
        return switch (action) {
            case RewardAction.ItemAction item -> items.execute(target, item);
            case RewardAction.CommandAction command -> commands.execute(target, command);
        };
    }
}
