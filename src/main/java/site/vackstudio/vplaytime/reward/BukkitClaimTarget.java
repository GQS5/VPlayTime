package site.vackstudio.vplaytime.reward;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import site.vackstudio.vplaytime.model.RewardAction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Production {@link ClaimTarget} over a live online player. Short-lived: one
 * instance per claim attempt, never stored. All methods run on the player's
 * entity/region thread.
 *
 * <p>Item capacity uses the {@link ItemGrant} planner over a storage-contents
 * snapshot, so multi-item rewards plan atomically and delivery applies a
 * precomputed plan — no partial grants, no rollback reconstruction.
 */
public final class BukkitClaimTarget implements ClaimTarget {

    private final Player player;

    public BukkitClaimTarget(Player player) {
        this.player = player;
    }

    @Override
    public UUID uuid() {
        return player.getUniqueId();
    }

    @Override
    public String name() {
        return player.getName();
    }

    @Override
    public boolean canAccept(List<RewardAction> actions) {
        List<RewardAction.ItemAction> items = itemActions(actions);
        if (items.isEmpty()) {
            return true;
        }
        return ItemGrant.plan(snapshot(), items, Material::getMaxStackSize).isPresent();
    }

    @Override
    public boolean giveItem(Material material, int amount) {
        return giveItems(List.of(new RewardAction.ItemAction(material, amount))) == -1;
    }

    @Override
    public int giveItems(List<RewardAction.ItemAction> items) {
        if (items.isEmpty()) {
            return -1;
        }
        // ONE snapshot, ONE combined plan, ONE apply for the whole batch:
        // a 10-item reward costs the same inventory work as a 1-item reward.
        var plan = ItemGrant.plan(
                snapshot(), List.copyOf(items), Material::getMaxStackSize);
        if (plan.isEmpty()) {
            return 0;
        }
        PlayerInventory inventory = player.getInventory();
        for (ItemGrant.Placement placement : plan.get()) {
            ItemStack current = inventory.getItem(placement.slot());
            if (current == null || current.getType().isAir() || current.getAmount() == 0) {
                inventory.setItem(placement.slot(), new ItemStack(placement.material(), placement.newAmount()));
            } else {
                current.setAmount(placement.newAmount());
            }
        }
        return -1;
    }

    @Override
    public boolean runCommand(String command) {
        // Always invoked on the global tick thread (see ClaimManager): the
        // only context Folia accepts for console-sender dispatch.
        boolean ok;
        try {
            ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Exception ex) {
            Bukkit.getLogger().warning("[VPlaytime] Console command threw: '" + command
                    + "': " + ex.getMessage());
            return false;
        }
        if (!ok) {
            // dispatchCommand collapses "unknown command" and "executor said
            // no" into one false — probe existence so the console names the
            // actual failure class (production-only path, never unit-tested).
            Bukkit.getLogger().warning("[VPlaytime] "
                    + CommandFailure.describe(command, commandKnown(command)));
        }
        return ok;
    }

    /** Whether any enabled plugin currently provides the command's root label. Never throws. */
    private static boolean commandKnown(String command) {
        try {
            String root = CommandFailure.rootLabel(command);
            if (root.isEmpty()) {
                return false;
            }
            return Bukkit.getCommandMap().getCommand(root) != null;
        } catch (Exception ex) {
            return true; // probe failed: don't misreport "unknown", executor line still logs
        }
    }

    private List<ItemGrant.SlotView> snapshot() {
        ItemStack[] contents = player.getInventory().getStorageContents();
        List<ItemGrant.SlotView> views = new ArrayList<>(contents.length);
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                views.add(new ItemGrant.SlotView(null, 0, 64));
            } else {
                views.add(new ItemGrant.SlotView(stack.getType(), stack.getAmount(), stack.getMaxStackSize()));
            }
        }
        return views;
    }

    private static List<RewardAction.ItemAction> itemActions(List<RewardAction> actions) {
        List<RewardAction.ItemAction> items = new ArrayList<>();
        for (RewardAction action : actions) {
            if (action instanceof RewardAction.ItemAction item) {
                items.add(item);
            }
        }
        return items;
    }
}
