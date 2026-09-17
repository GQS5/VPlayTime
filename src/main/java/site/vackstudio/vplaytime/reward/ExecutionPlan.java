package site.vackstudio.vplaytime.reward;

import site.vackstudio.vplaytime.model.RewardAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure execution planner: groups a reward's actions into the minimal run
 * sequence that preserves configured order.
 *
 * <p>Consecutive actions needing the same execution context (player/entity
 * thread for items, global tick thread for console commands on Folia) form
 * one step, so 10 item actions become ONE item batch (one inventory scan,
 * one capacity plan, one delivery) and 10 commands become ONE global block
 * (10 dispatches, in order). Mixed sequences keep every context boundary:
 * {@code item, command, item} runs as three steps, never reordered.
 *
 * <p>No Bukkit access, no I/O, no allocation beyond the step lists: safe to
 * build per claim from the pre-parsed definitions.
 */
public final class ExecutionPlan {

    /** One context-homogeneous run with global action indexes for reporting. */
    public sealed interface Step permits ExecutionPlan.Items, ExecutionPlan.Commands {
        List<Integer> indexes();
    }

    /** Consecutive item actions: one inventory scan, one plan, one delivery. */
    public record Items(List<RewardAction.ItemAction> items, List<Integer> indexes) implements Step {
        public Items {
            items = List.copyOf(items);
            indexes = List.copyOf(indexes);
        }
    }

    /** Consecutive command actions: one global block, dispatched in order. */
    public record Commands(List<RewardAction.CommandAction> commands, List<Integer> indexes)
            implements Step {
        public Commands {
            commands = List.copyOf(commands);
            indexes = List.copyOf(indexes);
        }
    }

    private ExecutionPlan() {
    }

    /**
     * Groups consecutive same-kind actions. Empty input yields no steps
     * (rewards with no actions are rejected at load, so this only sees
     * programmer-built lists).
     */
    public static List<Step> build(List<RewardAction> actions) {
        List<Step> steps = new ArrayList<>();
        List<RewardAction.ItemAction> items = new ArrayList<>();
        List<RewardAction.CommandAction> commands = new ArrayList<>();
        List<Integer> itemIndexes = new ArrayList<>();
        List<Integer> commandIndexes = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            RewardAction action = actions.get(i);
            if (action instanceof RewardAction.ItemAction item) {
                if (!commands.isEmpty()) {
                    steps.add(new Commands(commands, commandIndexes));
                    commands = new ArrayList<>();
                    commandIndexes = new ArrayList<>();
                }
                items.add(item);
                itemIndexes.add(i);
            } else if (action instanceof RewardAction.CommandAction command) {
                if (!items.isEmpty()) {
                    steps.add(new Items(items, itemIndexes));
                    items = new ArrayList<>();
                    itemIndexes = new ArrayList<>();
                }
                commands.add(command);
                commandIndexes.add(i);
            } else {
                throw new IllegalArgumentException("unknown action type: " + action);
            }
        }
        if (!items.isEmpty()) {
            steps.add(new Items(items, itemIndexes));
        }
        if (!commands.isEmpty()) {
            steps.add(new Commands(commands, commandIndexes));
        }
        return List.copyOf(steps);
    }
}
