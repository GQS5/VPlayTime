package site.vackstudio.vplaytime.reward;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.model.RewardAction;
import site.vackstudio.vplaytime.model.RewardDefinition;
import site.vackstudio.vplaytime.model.RewardDisplay;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure command-failure wording: no server needed (the Bukkit existence
 * probe lives in the production-only target).
 */
class CommandFailureTest {

    @Test
    void rootLabelParsesFirstToken() {
        assertEquals("addmoney", CommandFailure.rootLabel("addmoney RM7PC 1000"));
        assertEquals("addmoney", CommandFailure.rootLabel("  /addmoney RM7PC 1000  "));
        assertEquals("eco", CommandFailure.rootLabel("eco give RM7PC 100"));
        assertEquals("addmoney", CommandFailure.rootLabel("vcore:addmoney RM7PC 1000"));
        assertEquals("", CommandFailure.rootLabel("   "));
        assertEquals("", CommandFailure.rootLabel(null));
    }

    @Test
    void unknownCommandNamesTheFix() {
        String line = CommandFailure.describe("addmoney RM7PC 1000", false);
        assertTrue(line.contains("unknown command '/addmoney'"), line);
        assertTrue(line.contains("rewards.yml"), line);
    }

    @Test
    void rejectedCommandNamesSenderAndSyntax() {
        String line = CommandFailure.describe("addmoney RM7PC 1000", true);
        assertTrue(line.contains("rejected"), line);
        assertTrue(line.contains("console-sender"), line);
        assertTrue(line.contains("argument"), line);
    }

    @Test
    void unknownRootsCollectsExampleReward() {
        var state = new RewardDisplay.DisplayState(Material.STONE, List.of(), false);
        var display = new RewardDisplay("R", state, state, state);
        var rewards = Map.of(
                "level_1", new RewardDefinition("level_1", 0, 60L, display, List.of(
                        new RewardAction.CommandAction("addmoney %player% 1000"),
                        new RewardAction.CommandAction("exp give %player% 500"))),
                "level_2", new RewardDefinition("level_2", 1, 3600L, display, List.of(
                        new RewardAction.ItemAction(Material.DIAMOND, 1))));
        var unknown = CommandFailure.unknownRoots(rewards, root -> root.equals("addmoney"));
        assertEquals(Map.of("exp", "level_1"), unknown);
        assertEquals(List.of("/exp (e.g. reward 'level_1')"), CommandFailure.describeUnknown(unknown));
    }

    @Test
    void unknownRootsIgnoresItemsAndNulls() {
        assertTrue(CommandFailure.unknownRoots(Map.of(), root -> false).isEmpty());
        assertTrue(CommandFailure.unknownRoots(null, null).isEmpty());
        var state = new RewardDisplay.DisplayState(Material.STONE, List.of(), false);
        var display = new RewardDisplay("R", state, state, state);
        var rewards = Map.of("r", new RewardDefinition("r", 0, 0L, display,
                List.of(new RewardAction.CommandAction("xp give %player% 5"))));
        assertTrue(CommandFailure.unknownRoots(rewards, root -> true).isEmpty());
    }
}
