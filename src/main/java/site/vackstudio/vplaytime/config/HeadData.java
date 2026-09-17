package site.vackstudio.vplaytime.config;

/**
 * Player-head identity for a menu button ({@code material: PLAYER_HEAD}).
 * Resolved at render time against the viewing player; invalid values fall
 * back to a plain head instead of breaking the menu.
 */
public sealed interface HeadData
        permits HeadData.None, HeadData.Self, HeadData.Name, HeadData.Texture {

    /** No head configured (or material is not a player head). */
    record None() implements HeadData {
    }

    /** The viewing player's own head ({@code head: "%player%"}). */
    record Self() implements HeadData {
    }

    /** A named player's head ({@code head: "Notch"}). */
    record Name(String name) implements HeadData {
        public Name {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("head name must not be blank");
            }
        }
    }

    /** A raw Mojang texture value ({@code head: {texture: "..."}}). */
    record Texture(String base64) implements HeadData {
        public Texture {
            if (base64 == null || base64.isBlank()) {
                throw new IllegalArgumentException("head texture must not be blank");
            }
        }
    }
}
