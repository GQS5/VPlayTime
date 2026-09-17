package site.vackstudio.vplaytime.config;

import org.bukkit.Material;

import java.util.List;

/**
 * One non-reward button inside a menu: navigation, messages, close, or
 * pure display (no action at all, e.g. a live stats readout).
 *
 * <p>Items never give anything and never touch player data, claims or
 * storage. Click actions run in file order: a message is sent, then an
 * open-menu or close finishes the click.
 */
public record MenuItem(
        String id,
        int slot,
        Material material,
        String name,
        List<String> lore,
        boolean glow,
        List<Action> actions,
        HeadData head) {

    /** One thing a click on the item does. */
    public sealed interface Action
            permits MenuItem.Action.OpenMenu, MenuItem.Action.Message, MenuItem.Action.Close,
            MenuItem.Action.NextPage, MenuItem.Action.PreviousPage {
        /** Opens another menu (plays that menu's open sound). */
        record OpenMenu(String menuId) implements Action {
            public OpenMenu {
                if (menuId == null || !menuId.matches("[a-z0-9_]+")) {
                    throw new IllegalArgumentException("invalid target menu id '" + menuId + "'");
                }
            }
        }

        /** Sends the player a message; the menu stays open unless closed after. */
        record Message(String text) implements Action {
            public Message {
                if (text == null || text.isBlank()) {
                    throw new IllegalArgumentException("message action needs non-blank text");
                }
            }
        }

        /** Closes the inventory. */
        record Close() implements Action {
        }

        /** Shortcut: opens the next menu in order (resolved at load). */
        record NextPage() implements Action {
        }

        /** Shortcut: opens the previous menu in order (resolved at load). */
        record PreviousPage() implements Action {
        }
    }

    public MenuItem {
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("invalid item id '" + id + "': use [a-z0-9_]");
        }
        if (material == null) {
            throw new IllegalArgumentException("item '" + id + "' needs a material");
        }
        name = name == null ? "" : name;
        lore = List.copyOf(lore == null ? List.of() : lore);
        actions = List.copyOf(actions == null ? List.of() : actions);
        if (head == null) {
            throw new IllegalArgumentException("item '" + id + "' needs head data (use HeadData.None)");
        }
    }
}
