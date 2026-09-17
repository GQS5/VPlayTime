package site.vackstudio.vplaytime.reward;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.model.RewardAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ItemGrant planner: merging, splitting, atomic multi-action planning.
 */
class ItemGrantTest {

    private static final ToIntFunction<Material> STACK_64 = mat -> 64;

    private static List<ItemGrant.SlotView> empty(int size) {
        List<ItemGrant.SlotView> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(new ItemGrant.SlotView(null, 0, 64));
        }
        return slots;
    }

    private static List<ItemGrant.SlotView> of(Object... spec) {
        // spec: repeating triples (Material|null, amount, maxStack)
        List<ItemGrant.SlotView> slots = new ArrayList<>(spec.length / 3);
        for (int i = 0; i < spec.length; i += 3) {
            slots.add(new ItemGrant.SlotView(
                    (Material) spec[i], (Integer) spec[i + 1], (Integer) spec[i + 2]));
        }
        return slots;
    }

    private static Map<Integer, Integer> appliedAmounts(List<ItemGrant.SlotView> base,
            List<ItemGrant.Placement> placements) {
        java.util.HashMap<Integer, Integer> amounts = new java.util.HashMap<>();
        for (int i = 0; i < base.size(); i++) {
            if (!base.get(i).isEmpty()) {
                amounts.put(i, base.get(i).amount());
            }
        }
        for (ItemGrant.Placement p : placements) {
            if (p.newAmount() > 0) {
                amounts.put(p.slot(), p.newAmount());
            } else {
                amounts.remove(p.slot());
            }
        }
        return amounts;
    }

    @Test
    void emptyInventoryPlacesSingleAction() {
        List<ItemGrant.SlotView> slots = empty(36);
        Optional<List<ItemGrant.Placement>> plan = ItemGrant.plan(
                slots, List.of(new RewardAction.ItemAction(Material.DIAMOND, 10)), STACK_64);

        assertTrue(plan.isPresent());
        assertEquals(1, plan.get().size());
        assertEquals(new ItemGrant.Placement(0, Material.DIAMOND, 10), plan.get().get(0));
        // Input untouched.
        assertTrue(slots.get(0).isEmpty());
    }

    @Test
    void mergesIntoPartialStackFirst() {
        List<ItemGrant.SlotView> slots = of(Material.DIAMOND, 32, 64, null, 0, 64);
        Optional<List<ItemGrant.Placement>> plan = ItemGrant.plan(
                slots, List.of(new RewardAction.ItemAction(Material.DIAMOND, 10)), STACK_64);

        assertTrue(plan.isPresent());
        assertEquals(1, plan.get().size());
        assertEquals(new ItemGrant.Placement(0, Material.DIAMOND, 42), plan.get().get(0));
    }

    @Test
    void splitsAcrossSlotsWhenNeeded() {
        List<ItemGrant.SlotView> slots = of(Material.DIAMOND, 60, 64, null, 0, 64);
        Optional<List<ItemGrant.Placement>> plan = ItemGrant.plan(
                slots, List.of(new RewardAction.ItemAction(Material.DIAMOND, 10)), STACK_64);

        assertTrue(plan.isPresent());
        assertEquals(Map.of(0, 64, 1, 6), appliedAmounts(slots, plan.get()));
    }

    @Test
    void respectsCustomStackSize() {
        ToIntFunction<Material> sixteen = mat -> 16;
        List<ItemGrant.SlotView> slots = empty(36);
        Optional<List<ItemGrant.Placement>> plan = ItemGrant.plan(
                slots, List.of(new RewardAction.ItemAction(Material.SNOWBALL, 20)), sixteen);

        assertTrue(plan.isPresent());
        assertEquals(Map.of(0, 16, 1, 4), appliedAmounts(slots, plan.get()));
    }

    @Test
    void fullInventoryRejects() {
        List<ItemGrant.SlotView> slots = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) {
            slots.add(new ItemGrant.SlotView(Material.STONE, 64, 64));
        }
        Optional<List<ItemGrant.Placement>> plan = ItemGrant.plan(
                slots, List.of(new RewardAction.ItemAction(Material.DIAMOND, 1)), STACK_64);

        assertTrue(plan.isEmpty());
    }

    @Test
    void partialCapacityRejectsWholeAction() {
        // Room for 4 but 10 requested: no partial delivery, no placements.
        List<ItemGrant.SlotView> slots = of(Material.DIAMOND, 60, 64);
        Optional<List<ItemGrant.Placement>> plan = ItemGrant.plan(
                slots, List.of(new RewardAction.ItemAction(Material.DIAMOND, 10)), STACK_64);

        assertTrue(plan.isEmpty());
    }

    @Test
    void multiActionPlansAtomically() {
        List<ItemGrant.SlotView> slots = empty(36);
        Optional<List<ItemGrant.Placement>> plan = ItemGrant.plan(slots, List.of(
                new RewardAction.ItemAction(Material.DIAMOND, 10),
                new RewardAction.ItemAction(Material.GOLD_INGOT, 60)), STACK_64);

        assertTrue(plan.isPresent());
        Map<Integer, Integer> amounts = appliedAmounts(slots, plan.get());
        assertEquals(10, amounts.get(0));
        assertEquals(60, amounts.get(1));
        assertEquals(2, amounts.size());
    }

    @Test
    void multiActionSecondOverflowRejectsAll() {
        // Only one free slot: diamonds fit, gold does not -> everything rejected.
        List<ItemGrant.SlotView> slots = new ArrayList<>(36);
        for (int i = 0; i < 35; i++) {
            slots.add(new ItemGrant.SlotView(Material.STONE, 64, 64));
        }
        slots.add(new ItemGrant.SlotView(null, 0, 64));

        Optional<List<ItemGrant.Placement>> plan = ItemGrant.plan(slots, List.of(
                new RewardAction.ItemAction(Material.DIAMOND, 10),
                new RewardAction.ItemAction(Material.GOLD_INGOT, 60)), STACK_64);

        assertTrue(plan.isEmpty(), "second action overflow must reject the whole plan");
    }

    @Test
    void exactFitSucceeds() {
        List<ItemGrant.SlotView> slots = of(null, 0, 64);
        Optional<List<ItemGrant.Placement>> plan = ItemGrant.plan(
                slots, List.of(new RewardAction.ItemAction(Material.DIAMOND, 64)), STACK_64);
        assertTrue(plan.isPresent());
    }
}
