package site.vackstudio.vplaytime.gui;

import org.bukkit.Material;
import site.vackstudio.vplaytime.config.HeadData;

import java.util.List;

/**
 * Resolved visual of one reward: plain data, no ItemStacks. The Bukkit
 * application ({@code ItemFactory}) stays separate so this resolution is
 * fully unit-testable without a server.
 */
public record RenderedReward(
        Material material, String name, List<String> lore, boolean glow, HeadData head) {

    /** Rewards and filler never carry head data. */
    public RenderedReward(Material material, String name, List<String> lore, boolean glow) {
        this(material, name, lore, glow, new HeadData.None());
    }
}
