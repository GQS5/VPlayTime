package site.vackstudio.vplaytime.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import site.vackstudio.vplaytime.config.MenuDefinition;
import site.vackstudio.vplaytime.model.RewardDefinition;
import site.vackstudio.vplaytime.model.RewardState;
import site.vackstudio.vplaytime.playtime.PlayerData;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.playtime.TimeSource;
import site.vackstudio.vplaytime.reward.RewardManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Presentation-only rendering of one {@link MenuDefinition} over Core state.
 * Reads memory (PlayerData + RewardManager), never storage. Rendering is
 * event-driven: open, click, refresh — no background tasks, no schedulers.
 *
 * <p>Placement resolution is {@code menu → slot → reward}: the menu decides
 * where each reward appears; claim state stays shared in PlayerData no
 * matter how many menus show the same reward.
 *
 * <p>All methods must be called on the player's entity/region thread.
 */
public final class RewardMenu {

    private final RewardManager rewards;
    private final PlaytimeManager playtime;
    private final TimeSource clock;
    private final MenuDefinition menu;
    private final MenuSounds sounds;

    public RewardMenu(
            RewardManager rewards,
            PlaytimeManager playtime,
            TimeSource clock,
            MenuDefinition menu,
            MenuSounds sounds) {
        this.rewards = rewards;
        this.playtime = playtime;
        this.clock = clock;
        this.menu = menu;
        this.sounds = sounds;
    }

    public MenuDefinition definition() {
        return menu;
    }

    public int size() {
        return menu.size();
    }

    /** Opens a fresh menu for an already-loaded player. Caller checks loading. */
    public void open(Player player) {
        Inventory inventory = Bukkit.createInventory(
                new MenuHolder(player.getUniqueId(), menu.id()), menu.size(), ItemFactory.title(menu.title()));
        renderInto(player, inventory);
        player.openInventory(inventory);
        playSound(player, MenuSounds.Kind.OPEN);
    }

    /** Re-renders every reward slot of the player's open menu, if it is ours. */
    public void refresh(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        if (!menu.id().equals(holder.menuId())) {
            return;
        }
        renderInto(player, player.getOpenInventory().getTopInventory());
    }

    private void renderInto(Player player, Inventory inventory) {
        Optional<PlayerData> data = playtime.find(player.getUniqueId());
        if (data.isEmpty()) {
            return;
        }
        // ONE provider read per render: with an external (PlaceholderAPI)
        // source every read is a full placeholder resolution, so resolving
        // once here instead of per slot keeps opens/refreshes/clicks cheap.
        // The value is also frozen for the whole render, so every slot and
        // button in one frame agrees with each other.
        long effective = playtime.effectivePlaytimeSeconds(player.getUniqueId());
        for (var placement : menu.placements().entrySet()) {
            int slot = placement.getValue();
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            Optional<RewardDefinition> def = rewards.find(placement.getKey());
            if (def.isEmpty()) {
                continue;
            }
            RewardState state = rewards.stateFor(data.get(), def.get().id(), effective)
                    .orElse(RewardState.LOCKED);
            inventory.setItem(slot, ItemFactory.build(RewardStateRenderer.resolve(def.get(), state, effective)));
        }
        for (var item : menu.items().values()) {
            int slot = item.slot();
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            // Buttons show the viewer's own playtime in their text, so a
            // stats button stays live without any server placeholder plugin.
            // The value comes from the ACTIVE provider (internal or external);
            // reuse this render's frozen value (see above).
            Placeholders.Context context = new Placeholders.Context(
                    effective, 0L, RewardState.LOCKED);
            List<String> lore = new ArrayList<>(item.lore().size());
            for (String line : item.lore()) {
                lore.add(Placeholders.resolve(line, context));
            }
            inventory.setItem(slot, ItemFactory.build(new RenderedReward(
                    item.material(), Placeholders.resolve(item.name(), context), lore, item.glow(),
                    item.head()), player));
        }
        if (menu.fill() != null) {
            RenderedReward fill = new RenderedReward(
                    menu.fill().material(), menu.fill().name(), menu.fill().lore(), false);
            ItemStack fillStack = ItemFactory.build(fill);
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                if (inventory.getItem(slot) == null) {
                    inventory.setItem(slot, fillStack);
                }
            }
        }
    }

    /**
     * Plays a resolved menu sound at the player, with its configured volume
     * and pitch. Same entity thread the click already runs on — no hops,
     * no lookups: the stored immutable sound is reused directly.
     */
    public void playSound(Player player, MenuSounds.Kind kind) {
        MenuSounds.ResolvedSound sound = sounds.get(kind);
        if (sound == null) {
            return;
        }
        var location = player.getLocation();
        player.playSound(
                net.kyori.adventure.sound.Sound.sound(
                        sound.key(),
                        net.kyori.adventure.sound.Sound.Source.MASTER,
                        sound.volume(),
                        sound.pitch()),
                location.getX(), location.getY(), location.getZ());
    }
}
