package site.vackstudio.vplaytime.reward;

import org.bukkit.Material;
import site.vackstudio.vplaytime.model.RewardAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * Pure, deterministic item-placement planner.
 *
 * <p>Plans ALL given item actions against an inventory snapshot as one unit:
 * either every action fully fits (a placement is returned) or nothing is
 * planned ({@code Optional.empty()}). Callers apply a returned plan without
 * any further checks, so multi-item rewards never half-deliver — no rollback
 * logic is needed because failure is decided before anything mutates.
 *
 * <p>No Bukkit world/server access: the snapshot is plain data and the
 * per-material stack size comes from {@code maxStackOf}, so this is fully
 * unit-testable. Production passes {@code Material::getMaxStackSize} (valid on
 * the server thread); tests pass fixed functions.
 *
 * <p>Merging order per action is deterministic: top up existing partial stacks
 * first (lowest slot first), then fill empty slots (lowest slot first).
 */
public final class ItemGrant {

    private ItemGrant() {
    }

    /** One inventory slot as plain data. {@code amount <= 0} (or null/AIR) = empty. */
    public record SlotView(Material material, int amount, int maxStack) {
        public boolean isEmpty() {
            return amount <= 0 || material == null || material == Material.AIR;
        }
    }

    /** One slot mutation from applying a plan. */
    public record Placement(int slot, Material material, int newAmount) {
    }

    /**
     * Plans placement for every action or rejects everything.
     *
     * @param slots current inventory snapshot, index = slot id
     * @param actions item actions to place, in order
     * @param maxStackOf stack-size lookup for fresh placements
     * @return placements for the slots that change, or empty when any action
     *         cannot be fully placed. Inputs are never mutated.
     */
    public static Optional<List<Placement>> plan(
            List<SlotView> slots,
            List<RewardAction.ItemAction> actions,
            ToIntFunction<Material> maxStackOf) {
        int size = slots.size();
        Material[] materials = new Material[size];
        int[] amounts = new int[size];
        int[] limits = new int[size];
        for (int i = 0; i < size; i++) {
            SlotView view = slots.get(i);
            materials[i] = view.isEmpty() ? null : view.material();
            amounts[i] = view.isEmpty() ? 0 : view.amount();
            limits[i] = view.isEmpty() ? 0 : Math.max(1, view.maxStack());
        }

        for (RewardAction.ItemAction action : actions) {
            int remaining = action.amount();
            int limit = Math.max(1, maxStackOf.applyAsInt(action.material()));
            // 1) Top up partial stacks of the same material, lowest slot first.
            for (int i = 0; i < size && remaining > 0; i++) {
                if (action.material().equals(materials[i]) && amounts[i] < limits[i]) {
                    int room = limits[i] - amounts[i];
                    int add = Math.min(room, remaining);
                    amounts[i] += add;
                    remaining -= add;
                }
            }
            // 2) Fill empty slots, lowest slot first.
            for (int i = 0; i < size && remaining > 0; i++) {
                if (materials[i] == null) {
                    int add = Math.min(limit, remaining);
                    materials[i] = action.material();
                    limits[i] = limit;
                    amounts[i] = add;
                    remaining -= add;
                }
            }
            if (remaining > 0) {
                return Optional.empty();
            }
        }

        List<Placement> placements = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            SlotView original = slots.get(i);
            int originalAmount = original.isEmpty() ? 0 : original.amount();
            Material originalMaterial = original.isEmpty() ? null : original.material();
            if (amounts[i] != originalAmount || !sameMaterial(materials[i], originalMaterial)) {
                placements.add(new Placement(i, materials[i], amounts[i]));
            }
        }
        return Optional.of(List.copyOf(placements));
    }

    private static boolean sameMaterial(Material a, Material b) {
        if (a == null || a == Material.AIR) {
            return b == null || b == Material.AIR;
        }
        return a.equals(b);
    }
}
