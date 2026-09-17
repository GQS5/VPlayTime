package site.vackstudio.vplaytime.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import site.vackstudio.vplaytime.config.ConfigManager;
import site.vackstudio.vplaytime.config.MenuItem;
import site.vackstudio.vplaytime.model.RewardState;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.playtime.TimeSource;
import site.vackstudio.vplaytime.reward.BukkitClaimTarget;
import site.vackstudio.vplaytime.reward.ClaimManager;
import site.vackstudio.vplaytime.reward.RewardManager;

import java.util.function.Function;

/**
 * Menu event wiring. Resolves the CURRENT core state per click (never the
 * clicked item's looks), delegates claims to {@link ClaimManager}, and
 * refreshes from the actual {@link ClaimResult}. No business logic lives here.
 *
 * <p>Each open menu carries its menu id in its {@link MenuHolder}; clicks are
 * resolved against that menu's own placement map, so several different menus
 * can be open on different players with no cross-talk.
 *
 * <p>All handlers run on the region thread already; ClaimManager continuations
 * return on the player thread, so sounds and refreshes below are Folia-safe.
 */
public final class RewardMenuListener implements Listener {

    private final RewardManager rewards;
    private final PlaytimeManager playtime;
    private final ClaimManager claims;
    private final TimeSource clock;
    private final Function<String, RewardMenu> menus;
    private final ConfigManager config;

    public RewardMenuListener(
            RewardManager rewards,
            PlaytimeManager playtime,
            ClaimManager claims,
            TimeSource clock,
            Function<String, RewardMenu> menus,
            ConfigManager config) {
        this.rewards = rewards;
        this.playtime = playtime;
        this.claims = claims;
        this.clock = clock;
        this.menus = menus;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) {
            return;
        }
        // Our menu: nothing may move in or out, by any click type.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        RewardMenu menu = menus.apply(holder.menuId());
        if (menu == null) {
            return;
        }
        boolean clickedTop = event.getRawSlot() < top.getSize();
        // Buttons first: they never touch player data, claims or storage.
        if (clickedTop) {
            var item = menu.definition().items().values().stream()
                    .filter(candidate -> candidate.slot() == event.getRawSlot())
                    .findFirst()
                    .orElse(null);
            if (item != null) {
                for (MenuItem.Action action : item.actions()) {
                    runItemAction(player, action);
                    if (action instanceof MenuItem.Action.OpenMenu
                            || action instanceof MenuItem.Action.Close) {
                        break;
                    }
                }
                return;
            }
        }
        var data = playtime.find(player.getUniqueId());
        RewardState current = null;
        String resolvedId = menu.definition().slotToReward().get(event.getRawSlot());
        if (resolvedId != null && data.isPresent()) {
            current = rewards.stateFor(data.get(), resolvedId,
                    playtime.effectivePlaytimeSeconds(player.getUniqueId())).orElse(null);
        }
        var decision = MenuClickHandler.decide(
                true, clickedTop, event.getRawSlot(), top.getSize(),
                menu.definition().slotToReward(), current);
        switch (decision.action()) {
            case IGNORE -> {
            }
            case LOCKED_FEEDBACK -> {
                menu.playSound(player, MenuSounds.Kind.LOCKED);
                message(player, config.lockedMessage());
            }
            case CLAIMED_FEEDBACK -> {
                menu.playSound(player, MenuSounds.Kind.ALREADY_CLAIMED);
                message(player, config.alreadyClaimedMessage());
            }
            case CLAIM -> claimAndRefresh(player, menu, decision.rewardId());
        }
    }

    private void runItemAction(Player player, MenuItem.Action action) {
        if (action instanceof MenuItem.Action.Message message) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(message.text()));
        } else if (action instanceof MenuItem.Action.OpenMenu open) {
            RewardMenu target = menus.apply(open.menuId());
            if (target != null) {
                target.open(player);
            }
        } else if (action instanceof MenuItem.Action.Close) {
            player.closeInventory();
        }
    }

    private void claimAndRefresh(Player player, RewardMenu menu, String rewardId) {
        claims.claim(player.getUniqueId(), rewardId, new BukkitClaimTarget(player))
                .thenAccept(result -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    switch (result.status()) {
                        case SUCCESS -> {
                            menu.playSound(player, MenuSounds.Kind.CLAIM);
                            message(player, config.claimSuccessMessage());
                        }
                        case ALREADY_CLAIMED -> {
                            menu.playSound(player, MenuSounds.Kind.ALREADY_CLAIMED);
                            message(player, config.alreadyClaimedMessage());
                        }
                        case LOCKED -> {
                            menu.playSound(player, MenuSounds.Kind.LOCKED);
                            message(player, config.lockedMessage());
                        }
                        case REWARD_FAILED, STORAGE_FAILED -> message(player, config.claimFailedMessage());
                        default -> {
                        }
                    }
                    menu.refresh(player);
                });
    }

    /** Sends non-empty configured messages; empty means sounds-only feedback. */
    private static void message(Player player, String mini) {
        if (mini != null && !mini.isBlank()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(mini));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }
}
