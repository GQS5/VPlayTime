package site.vackstudio.vplaytime.config;

import org.bukkit.Material;

import java.util.List;

/**
 * Background filler for the empty slots of one menu (the glass panes
 * around the content). Pure decoration: never clickable, never a reward.
 */
public record MenuFill(Material material, String name, List<String> lore) {

    public MenuFill {
        if (material == null) {
            throw new IllegalArgumentException("fill needs a material");
        }
        name = name == null ? "" : name;
        lore = List.copyOf(lore == null ? List.of() : lore);
    }
}
