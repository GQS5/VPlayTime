package site.vackstudio.vplaytime.reward;

import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.model.RewardAction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ExecutionPlan grouping: consecutive same-context actions batch,
 * context boundaries always become separate steps in configured order.
 */
class ExecutionPlanTest {

    private static RewardAction.ItemAction item() {
        return new RewardAction.ItemAction(org.bukkit.Material.DIAMOND, 1);
    }

    private static RewardAction.CommandAction command() {
        return new RewardAction.CommandAction("say hi");
    }

    @Test
    void emptyBuildsNoSteps() {
        assertTrue(ExecutionPlan.build(List.of()).isEmpty());
    }

    @Test
    void singleItemIsOneStep() {
        var steps = ExecutionPlan.build(List.of(item()));
        assertEquals(1, steps.size());
        var only = (ExecutionPlan.Items) steps.get(0);
        assertEquals(1, only.items().size());
        assertEquals(List.of(0), only.indexes());
    }

    @Test
    void tenItemsAreOneBatch() {
        var actions = new java.util.ArrayList<RewardAction>();
        for (int i = 0; i < 10; i++) {
            actions.add(item());
        }
        var steps = ExecutionPlan.build(actions);
        assertEquals(1, steps.size());
        var only = (ExecutionPlan.Items) steps.get(0);
        assertEquals(10, only.items().size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), only.indexes());
    }

    @Test
    void tenCommandsAreOneBlock() {
        var actions = new java.util.ArrayList<RewardAction>();
        for (int i = 0; i < 10; i++) {
            actions.add(command());
        }
        var steps = ExecutionPlan.build(actions);
        assertEquals(1, steps.size());
        var only = (ExecutionPlan.Commands) steps.get(0);
        assertEquals(10, only.commands().size());
    }

    @Test
    void mixedSequenceKeepsEveryBoundary() {
        // item, command, item -> three steps, global indexes preserved.
        var steps = ExecutionPlan.build(List.of(item(), command(), item()));
        assertEquals(3, steps.size());
        assertEquals(List.of(0), steps.get(0).indexes());
        assertEquals(List.of(1), steps.get(1).indexes());
        assertEquals(List.of(2), steps.get(2).indexes());
        assertTrue(steps.get(0) instanceof ExecutionPlan.Items);
        assertTrue(steps.get(1) instanceof ExecutionPlan.Commands);
        assertTrue(steps.get(2) instanceof ExecutionPlan.Items);
    }

    @Test
    void consecutiveRunsGroup() {
        // item, item, command, command, item -> item-batch, command-block, item.
        var steps = ExecutionPlan.build(
                List.of(item(), item(), command(), command(), item()));
        assertEquals(3, steps.size());
        assertEquals(List.of(0, 1), steps.get(0).indexes());
        assertEquals(List.of(2, 3), steps.get(1).indexes());
        assertEquals(List.of(4), steps.get(2).indexes());
    }
}
