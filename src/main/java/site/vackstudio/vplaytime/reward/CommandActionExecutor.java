package site.vackstudio.vplaytime.reward;

import site.vackstudio.vplaytime.model.RewardAction;

/**
 * Runs {@link RewardAction.CommandAction}s on console. The command arrives
 * already substituted ({@code %player%}, {@code %uuid%}, {@code %claim_id%});
 * this executor only dispatches, exactly once per action.
 */
public final class CommandActionExecutor implements RewardExecutor {

    @Override
    public Result execute(ClaimTarget target, RewardAction action) {
        if (!(action instanceof RewardAction.CommandAction command)) {
            throw new IllegalArgumentException("CommandActionExecutor cannot handle " + action);
        }
        if (!target.runCommand(command.command())) {
            return Result.failed("command dispatch failed: " + command.command());
        }
        return Result.success();
    }
}
