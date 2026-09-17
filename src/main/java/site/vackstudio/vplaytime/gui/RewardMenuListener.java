package site.vackstudio.vplaytime.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import site.vackstudio.vplaytime.config.ConfigManager;
import site.vackstudio.vplaytime.config.MenuItem;
import site.vackstudio.vplaytime.model.ClaimResult;
import site.vackstudio.vplaytime.model.RewardState;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.playtime.TimeSource;
import site.vackstudio.vplaytime.reward.BukkitClaimTarget;
import site.vackstudio.vplaytime.reward.ClaimManager;
import site.vackstudio.vplaytime.reward.RewardManager;

import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    private final Logger logger;

    public RewardMenuListener(
            RewardManager rewards,
            PlaytimeManager playtime,
            ClaimManager claims,
            TimeSource clock,
            Function<String, RewardMenu> menus,
            ConfigManager config,
            Logger logger) {
        this.rewards = rewards;
        this.playtime = playtime;
        this.claims = claims;
        this.clock = clock;
        this.menus = menus;
        this.config = config;
        this.logger = logger;
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
                message(player, config.message(config.messages().claimLocked(),
                        Map.of("required_playtime", requiredPlaytime(decision.rewardId()))));
            }
            case CLAIMED_FEEDBACK -> {
                menu.playSound(player, MenuSounds.Kind.ALREADY_CLAIMED);
                message(player, config.message(config.messages().claimAlready()));
            }
            case CLAIM -> claimAndRefresh(player, menu, holder.menuId(), decision.rewardId());
        }
    }

    /** Formatted requirement for the locked message (empty when unknown). */
    private String requiredPlaytime(String rewardId) {
        return rewards.find(rewardId)
                .map(def -> PlaytimeFormat.format(def.requiredSeconds()))
                .orElse("");
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

    private void claimAndRefresh(Player player, RewardMenu menu, String menuId, String rewardId) {
        claims.claim(player.getUniqueId(), rewardId, new BukkitClaimTarget(player))
                .thenAccept(result -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    // Re-resolve from the LIVE menu map: a reload between the
                    // click and the claim result must not repaint a stale
                    // layout. Falls back to the clicked instance (same content
                    // in the common no-reload case).
                    RewardMenu live = menus.apply(menuId);
                    RewardMenu ui = live != null ? live : menu;
                    switch (result.status()) {
                        case SUCCESS -> {
                            ui.playSound(player, MenuSounds.Kind.CLAIM);
                            message(player, config.claimSuccessMessage());
                        }
                        case ALREADY_CLAIMED -> {
                            ui.playSound(player, MenuSounds.Kind.ALREADY_CLAIMED);
                            message(player, config.alreadyClaimedMessage());
                        }
                        case LOCKED -> {
                            ui.playSound(player, MenuSounds.Kind.LOCKED);
                            message(player, config.lockedMessage());
                        }
                        case REWARD_FAILED, STORAGE_FAILED -> message(player, config.claimFailedMessage());
                        case SUSPENDED -> {
                            ui.playSound(player, MenuSounds.Kind.LOCKED);
                            message(player, config.message(config.messages().claimUnavailable()));
                        }
                        default -> {
                        }
                    }
                    ui.refresh(player);
                    if (result.status() == ClaimResult.Status.SUCCESS) {
                        verifyClaimed(player, rewardId);
                    }
                });
    }

    /**
     * Post-success tripwire: a SUCCESS that does not re-read as CLAIMED
     * from the single source of truth means memory and durable state
     * disagree somewhere upstream. Loud log with full context — the render
     * itself always comes from this same re-read, so the GUI cannot show
     * anything else.
     */
    private void verifyClaimed(Player player, String rewardId) {
        RewardState state = playtime.find(player.getUniqueId())
                .flatMap(data -> rewards.stateFor(data, rewardId,
                        playtime.effectivePlaytimeSeconds(player.getUniqueId())))
                .orElse(null);
        if (state != RewardState.CLAIMED) {
            logger.log(Level.SEVERE, "Claim of '" + rewardId + "' by " + player.getName()
                    + " reported SUCCESS but re-reads as " + state
                    + ". Investigate persistence/state sync before players notice.");
        }
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        // Opt-in "menu closed" notice (silent by default).
        String raw = config.messages().menuClosed();
        if (raw != null && !raw.isBlank()) {
            message(player, config.message(raw, Map.of("menu", holder.menuId())));
        }
    }
}
