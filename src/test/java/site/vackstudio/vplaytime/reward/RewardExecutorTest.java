package site.vackstudio.vplaytime.reward;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.model.RewardAction;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executor dispatch: typed delivery, configured order, indexed failures.
 */
class RewardExecutorTest {

    private static ClaimManagerTest.FakeTarget target() {
        return new ClaimManagerTest.FakeTarget(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), "T");
    }

    @Test
    void itemExecutorDelivers() {
        ClaimManagerTest.FakeTarget target = target();
        RewardExecutor.Result result = new ItemActionExecutor().execute(
                target, new RewardAction.ItemAction(Material.DIAMOND, 10));

        assertTrue(result.ok());
        assertEquals(10, target.itemCount(Material.DIAMOND));
    }

    @Test
    void executorsRejectForeignTypes() {
        ClaimManagerTest.FakeTarget target = target();
        assertThrows(IllegalArgumentException.class, () -> new ItemActionExecutor().execute(
                target, new RewardAction.CommandAction("say hi")));
        assertThrows(IllegalArgumentException.class, () -> new CommandActionExecutor().execute(
                target, new RewardAction.ItemAction(Material.DIAMOND, 1)));
    }

    @Test
    void delegatingFollowsConfiguredOrder() {
        ClaimManagerTest.FakeTarget target = target();
        DelegatingRewardExecutor executor = new DelegatingRewardExecutor();
        List<RewardAction> actions = List.of(
                new RewardAction.CommandAction("say one"),
                new RewardAction.ItemAction(Material.DIAMOND, 2),
                new RewardAction.CommandAction("say two"));

        RewardExecutor.Result result = executor.executeAll(target, actions);

        assertTrue(result.ok());
        assertEquals(List.of("say one", "say two"), target.commands);
        assertEquals(2, target.itemCount(Material.DIAMOND));
    }

    @Test
    void failureCarriesActionIndex() {
        ClaimManagerTest.FakeTarget target = target();
        target.failGive = true;
        DelegatingRewardExecutor executor = new DelegatingRewardExecutor();
        List<RewardAction> actions = List.of(
                new RewardAction.CommandAction("say one"),
                new RewardAction.ItemAction(Material.DIAMOND, 2));

        RewardExecutor.Result result = executor.executeAll(target, actions);

        assertTrue(!result.ok());
        assertEquals(1, result.failedActionIndex());
        assertEquals(List.of("say one"), target.commands,
                "configured order: the command ran before the failing item");
    }

    @Test
    void commandFailureCarriesIndexZero() {
        ClaimManagerTest.FakeTarget target = target();
        target.failCommand = true;
        RewardExecutor.Result result = new DelegatingRewardExecutor().executeAll(
                target, List.of(new RewardAction.CommandAction("say one")));

        assertTrue(!result.ok());
        assertEquals(0, result.failedActionIndex());
    }

    @Test
    void successIndexIsUnset() {
        ClaimManagerTest.FakeTarget target = target();
        RewardExecutor.Result result = new DelegatingRewardExecutor().executeAll(
                target, List.of(new RewardAction.ItemAction(Material.DIAMOND, 1)));
        assertTrue(result.ok());
        assertEquals(-1, result.failedActionIndex());
    }
}
