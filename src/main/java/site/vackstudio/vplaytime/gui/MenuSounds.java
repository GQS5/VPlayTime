package site.vackstudio.vplaytime.gui;

import net.kyori.adventure.key.Key;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Registry-based menu sounds, resolved once at startup/reload — never per
 * click, never throwing.
 *
 * <p>Every valid registered sound works: legacy enum form
 * ({@code BLOCK_CHEST_OPEN}), key form ({@code block.chest.open}) and
 * namespaced form ({@code minecraft:block.chest.open}, or any other
 * registered namespace) all normalize to the same canonical key. Unknown
 * sounds warn once (naming menu, field and key) and stay silent.
 * Volume and pitch come from the configuration; playback reuses the
 * stored immutable {@link ResolvedSound} with no parsing or lookup.
 *
 * <p>This class touches no Bukkit registry types on purpose: the caller
 * walks the live registry once (server side) into a plain spelling map,
 * so every spelling rule below is unit-testable without a server.
 */
public final class MenuSounds {

    public enum Kind {
        OPEN("open"),
        CLAIM("claim"),
        LOCKED("locked"),
        ALREADY_CLAIMED("already-claimed");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        /** Config key (e.g. {@code already-claimed}) back to its event. */
        public static java.util.Optional<Kind> byKey(String key) {
            for (Kind kind : values()) {
                if (kind.key.equals(key)) {
                    return java.util.Optional.of(kind);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** Immutable resolved playback instruction: canonical key + volume + pitch. */
    public record ResolvedSound(Key key, float volume, float pitch) {
    }

    private final Map<Kind, ResolvedSound> sounds;

    private MenuSounds(Map<Kind, ResolvedSound> sounds) {
        this.sounds = Map.copyOf(sounds);
    }

    public ResolvedSound get(Kind kind) {
        return sounds.get(kind);
    }

    /**
     * Canonical namespaced key for dotted key forms, without a server:
     * {@code block.chest.open} and {@code minecraft:block.chest.open} both
     * become {@code minecraft:block.chest.open}. Everything is lowercased:
     * registry keys are case-insensitive in practice.
     */
    static String canonicalKey(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        int namespace = text.indexOf(':');
        if (namespace >= 0) {
            return text;
        }
        return "minecraft:" + text;
    }

    /**
     * Builds the spelling map from live registry keys (server side, once per
     * load): canonical, bare-key and legacy-enum spellings, all lowercased,
     * each pointing at its canonical key. Custom namespaces ride along free.
     */
    public static Map<String, String> spellings(Iterable<String> canonicalKeys) {
        Map<String, String> map = new java.util.HashMap<>();
        for (String canon : canonicalKeys) {
            String lower = canon.toLowerCase(Locale.ROOT);
            int namespace = lower.indexOf(':');
            String path = namespace >= 0 ? lower.substring(namespace + 1) : lower;
            map.put(lower, lower);
            map.put(path, lower);
            map.put(path.replace('.', '_'), lower);
        }
        return Map.copyOf(map);
    }

    /**
     * Resolves configured sounds through a spelling map.
     *
     * @param canonicalBySpelling lowercased spelling to canonical key
     * @param menuId owning menu, named in warnings
     * @param configured parsed per-event configuration
     */
    public static MenuSounds load(Map<String, String> canonicalBySpelling, String menuId,
            Map<Kind, site.vackstudio.vplaytime.config.SoundConfig> configured, Logger logger) {
        Map<Kind, ResolvedSound> resolved = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            var config = configured.get(kind);
            if (config == null || config.silent()) {
                continue;
            }
            String canonical = canonicalBySpelling.get(config.key().trim().toLowerCase(Locale.ROOT));
            if (canonical == null) {
                logger.warning("Menu '" + menuId + "' sound '" + kind.key + "' uses unknown sound '"
                        + config.key() + "'; it will stay silent.");
                continue;
            }
            resolved.put(kind, new ResolvedSound(Key.key(canonical), config.volume(), config.pitch()));
        }
        return new MenuSounds(resolved);
    }
}
