package site.vackstudio.vplaytime.config;

import java.util.Map;

/**
 * Coherent runtime configuration snapshot: everything the GUI and Core read
 * comes from one immutable object, so a reload can never expose partially
 * mutated structures (no scattered {@code getConfig()} reads at render or
 * click time).
 *
 * <p>Reward <i>content</i> itself stays owned by RewardManager; this snapshot
 * carries layout (menus) plus globals and messages validated together with
 * those rewards in a single transaction.
 */
public record ConfigSnapshot(
        GlobalConfig global,
        MessageConfig messages,
        Map<String, MenuDefinition> menus) {

    public ConfigSnapshot {
        menus = Map.copyOf(menus);
    }

    /** Menu opened by bare {@code /vplaytime}; always present (validated). */
    public MenuDefinition mainMenu() {
        return menus.get(MenuRegistry.DEFAULT_MENU_ID);
    }
}
