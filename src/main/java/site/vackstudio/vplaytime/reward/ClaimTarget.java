package site.vackstudio.vplaytime.reward;

import org.bukkit.Material;
import site.vackstudio.vplaytime.model.RewardAction;

import java.util.List;
import java.util.UUID;

/**
 * Who/what receives a granted reward.
 *
 * <p>Decouples claim business logic from Bukkit: unit tests use a recording
 * fake, production uses a Bukkit-backed target (GUI phase). All methods must
 * be called on the player's entity/region thread.
 */
public interface ClaimTarget {

    UUID uuid();

    String name();

    /**
     * Whether every item action can be fully accepted right now (space check).
     * Checked before the durable insert so a full inventory rejects fast.
     * Plans ALL item actions as one unit: all fit or none do.
     */
    boolean canAccept(List<RewardAction> actions);

    /**
     * Grants items, all-or-nothing per call: fully delivers or rolls back
     * partial inserts and returns {@code false}.
     */
    boolean giveItem(Material material, int amount);

    /**
     * Grants a batch of item actions as ONE unit: a single inventory scan,
     * a single combined capacity plan ({@link ItemGrant}), a single apply.
     * Compatible stacks merge naturally through the planner (same material
     * tops up the same slots); metadata beyond material/amount does not
     * exist on ItemAction, so every merge the planner performs is safe.
     *
     * @return -1 when everything was delivered, otherwise the position
     *         within {@code items} that could not be delivered (nothing
     *         from the batch is kept on failure)
     */
    int giveItems(List<RewardAction.ItemAction> items);

    /**
     * Runs an already-substituted console command.
     *
     * @return {@code true} when the command dispatched successfully
     */
    boolean runCommand(String command);
}
