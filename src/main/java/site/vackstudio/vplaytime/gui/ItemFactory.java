package site.vackstudio.vplaytime.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import site.vackstudio.vplaytime.config.HeadData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import java.util.ArrayList;
import java.util.List;

/**
 * MiniMessage text and ItemStack assembly. Thin Bukkit application over the
 * pure {@link RenderedReward} resolution (which is unit-tested); this class
 * only runs on the server.
 *
 * <p>Glow uses a hidden enchantment: the paper-api version pinned here
 * exposes no glint-override API, so one level of an unused enchant plus
 * {@code HIDE_ENCHANTS} is the isolated, compatible mechanism.
 */
public final class ItemFactory {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private ItemFactory() {
    }

    public static Component title(String raw) {
        return MINI_MESSAGE.deserialize(raw == null ? "" : raw);
    }

    public static ItemStack build(RenderedReward rendered) {
        return build(rendered, null);
    }

    /**
     * @param viewer owning player for {@code %player%} heads; null when the
     *        visual carries no head data (rewards, filler)
     */
    public static ItemStack build(RenderedReward rendered, Player viewer) {
        ItemStack item = new ItemStack(rendered.material());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MINI_MESSAGE.deserialize(rendered.name()));
            List<Component> lore = new ArrayList<>(rendered.lore().size());
            for (String line : rendered.lore()) {
                lore.add(MINI_MESSAGE.deserialize(line));
            }
            meta.lore(lore);
            if (rendered.glow()) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            if (rendered.material() == Material.PLAYER_HEAD
                    && !(rendered.head() instanceof HeadData.None)
                    && meta instanceof SkullMeta skull) {
                applyHead(skull, rendered.head(), viewer);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Best-effort head identity: any failure leaves a plain Steve head. */
    private static void applyHead(SkullMeta skull, HeadData head, Player viewer) {
        try {
            if (head instanceof HeadData.Self && viewer != null) {
                skull.setOwningPlayer(viewer);
            } else if (head instanceof HeadData.Name named) {
                @SuppressWarnings("deprecation")
                var owner = Bukkit.getOfflinePlayer(named.name());
                skull.setOwningPlayer(owner);
            } else if (head instanceof HeadData.Texture texture) {
                com.destroystokyo.paper.profile.PlayerProfile profile =
                        Bukkit.createProfile(UUID.randomUUID(), "VPlaytimeHead");
                profile.getProperties().add(new com.destroystokyo.paper.profile.ProfileProperty(
                        "textures", texture.base64()));
                skull.setPlayerProfile(profile);
            }
        } catch (RuntimeException ignored) {
            // Invalid names/profiles fall back to the plain head behind them.
        }
    }
}
