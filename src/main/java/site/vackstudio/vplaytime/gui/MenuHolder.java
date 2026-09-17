package site.vackstudio.vplaytime.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Reliable menu identity marker (never title matching). Holds only the owning
 * player id and the menu id — no Player object, no mutable state, nothing to
 * leak on disconnect.
 */
public final class MenuHolder implements InventoryHolder {

    private final UUID playerId;
    private final String menuId;

    public MenuHolder(UUID playerId, String menuId) {
        this.playerId = playerId;
        this.menuId = menuId;
    }

    public UUID playerId() {
        return playerId;
    }

    public String menuId() {
        return menuId;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
