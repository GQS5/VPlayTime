package site.vackstudio.vplaytime.config;

import site.vackstudio.vplaytime.model.RewardDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable definition of one menu: its presentation plus the rewards it
 * shows, each with its own slot.
 *
 * <p>Concepts for server owners: the menu <b>id</b> (for example
 * {@code long_playtime}) is used in configuration and commands; the
 * <b>name</b> is the human-readable label; the <b>title</b> is the actual
 * inventory window title. The <b>order</b> decides page sequence when
 * menus are listed (1, 2, 3, ...); removing a menu never forces
 * renumbering the rest.
 *
 * <p>No Bukkit Inventory objects are stored here; the GUI renders from
 * this definition with plain memory reads.
 */
public record MenuDefinition(
        String id,
        String name,
        int order,
        String title,
        int rows,
        Map<String, SoundConfig> sounds,
        Map<String, RewardDefinition> rewards,
        Map<String, MenuItem> items,
        MenuFill fill) {

    public MenuDefinition {
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("invalid menu id '" + id + "': use [a-z0-9_]");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("menu '" + id + "' needs a non-blank name");
        }
        if (order < 1) {
            throw new IllegalArgumentException("menu '" + id + "' needs order >= 1");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("menu '" + id + "' needs a non-blank title");
        }
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("menu '" + id + "' rows must be 1..6");
        }
        sounds = Map.copyOf(sounds);
        rewards = Map.copyOf(rewards);
        items = Map.copyOf(items);
    }

    /** Inventory size in slots ({@code rows * 9}). */
    public int size() {
        return rows * 9;
    }

    /** {@code rewardId → slot} placements of this menu. */
    public Map<String, Integer> placements() {
        Map<String, Integer> slots = new LinkedHashMap<>();
        for (var entry : rewards.entrySet()) {
            slots.put(entry.getKey(), entry.getValue().slot());
        }
        return Map.copyOf(slots);
    }

    /** Derived {@code slot → rewardId} view for click/render resolution. */
    public Map<Integer, String> slotToReward() {
        Map<Integer, String> inverted = new LinkedHashMap<>();
        for (var entry : rewards.entrySet()) {
            inverted.put(entry.getValue().slot(), entry.getKey());
        }
        return Map.copyOf(inverted);
    }

    /** Derived {@code slot → itemId} view for button click/render resolution. */
    public Map<Integer, String> slotToItem() {
        Map<Integer, String> inverted = new LinkedHashMap<>();
        for (var entry : items.entrySet()) {
            inverted.put(entry.getValue().slot(), entry.getKey());
        }
        return Map.copyOf(inverted);
    }
}
