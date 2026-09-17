package site.vackstudio.vplaytime.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Optional per-state display defaults that remove repetition: when a reward
 * omits a state's material (or glow), the menu-level defaults apply, then
 * the file-level defaults. Anything the reward sets explicitly always wins.
 *
 * <p>Both levels are optional and partial: a defaults block may define any
 * subset of states and fields.
 */
public record RewardDefaults(DisplayDefault locked, DisplayDefault claimable, DisplayDefault claimed) {

    /** One state's default look: null fields mean "no default, keep looking". */
    public record DisplayDefault(String materialName, Boolean glow) {
    }

    public static RewardDefaults empty() {
        return new RewardDefaults(null, null, null);
    }

    /**
     * Parses a {@code defaults:} block ({@code defaults.reward.display.*}).
     *
     * @param sec null or missing means no defaults
     * @param path dotted location for diagnostics ({@code defaults} or
     *        {@code menus.<id>.defaults})
     */
    public static RewardDefaults parse(ConfigurationSection sec, String path) {
        if (sec == null) {
            return empty();
        }
        ConfigurationSection reward = sec.getConfigurationSection("reward");
        ConfigurationSection display = reward == null ? null : reward.getConfigurationSection("display");
        return new RewardDefaults(
                parseState(display, "locked", path),
                parseState(display, "claimable", path),
                parseState(display, "claimed", path));
    }

    private static DisplayDefault parseState(ConfigurationSection display, String state, String path) {
        if (display == null) {
            return null;
        }
        String matName = display.getString(state + ".material", null);
        if (matName != null && Material.matchMaterial(matName) == null) {
            throw new ConfigError(MenuRegistry.FILE,
                    path + ".reward.display." + state + ".material",
                    "Defaults use unknown material '" + matName + "' for the " + state + " look."
                    + " Use a Bukkit material name such as DIAMOND.");
        }
        String glowKey = state + ".glow";
        Boolean glow = display.isSet(glowKey) ? display.getBoolean(glowKey) : null;
        if (matName == null && glow == null) {
            return null;
        }
        return new DisplayDefault(matName, glow);
    }

    /** Merges {@code over} on top of this: set fields in {@code over} win. */
    public RewardDefaults mergedOver(RewardDefaults base) {
        return new RewardDefaults(
                mergeOne(locked, base.locked()),
                mergeOne(claimable, base.claimable()),
                mergeOne(claimed, base.claimed()));
    }

    private static DisplayDefault mergeOne(DisplayDefault over, DisplayDefault base) {
        if (over == null) {
            return base;
        }
        if (base == null) {
            return over;
        }
        return new DisplayDefault(
                over.materialName() != null ? over.materialName() : base.materialName(),
                over.glow() != null ? over.glow() : base.glow());
    }

    public DisplayDefault forState(String state) {
        return switch (state) {
            case "locked" -> locked;
            case "claimable" -> claimable;
            case "claimed" -> claimed;
            default -> null;
        };
    }
}
