package site.vackstudio.vplaytime.gui;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.config.SoundConfig;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sound resolution without a server: the spelling map proves every form
 * maps to one canonical key, volume/pitch survive, and unknown keys warn
 * once with menu context instead of crashing.
 */
class MenuSoundsTest {

    /** Mirrors production's registry walk over fixed 1.21.11 keys. */
    static Map<String, String> spellings() {
        return MenuSounds.spellings(List.of(
                "minecraft:block.chest.open",
                "minecraft:entity.player.levelup",
                "minecraft:block.note_block.bass",
                "minecraft:block.note_block.pling",
                "someplugin:custom.sound"));
    }

    static final class WarningTrap extends Handler {
        final List<String> warnings = new java.util.ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                warnings.add(record.getMessage());
            }
        }

        @Override public void flush() {
        }

        @Override public void close() {
        }
    }

    private static Map<MenuSounds.Kind, SoundConfig> configured(String open, String claim) {
        Map<MenuSounds.Kind, SoundConfig> map = new EnumMap<>(MenuSounds.Kind.class);
        map.put(MenuSounds.Kind.OPEN, new SoundConfig(open, 1.0f, 1.0f));
        map.put(MenuSounds.Kind.CLAIM, new SoundConfig(claim, 0.8f, 1.2f));
        return map;
    }

    @Test
    void allSpellingsResolveToOneCanonicalKey() {
        Logger logger = Logger.getLogger("VPlaytimeSoundTest");
        Key expected = Key.key("minecraft:block.chest.open");
        for (String spelling : List.of(
                "BLOCK_CHEST_OPEN", "block.chest.open", "minecraft:block.chest.open",
                "Minecraft:Block.Chest.Open", "  block.chest.open  ")) {
            MenuSounds sounds = MenuSounds.load(spellings(), "main",
                    Map.of(MenuSounds.Kind.OPEN, new SoundConfig(spelling, 1.0f, 1.0f)), logger);
            assertEquals(expected, sounds.get(MenuSounds.Kind.OPEN).key(),
                    "spelling: " + spelling);
        }
    }

    @Test
    void customNamespaceResolves() {
        Logger logger = Logger.getLogger("VPlaytimeSoundTest");
        MenuSounds sounds = MenuSounds.load(spellings(), "main",
                Map.of(MenuSounds.Kind.OPEN, new SoundConfig("someplugin:custom.sound", 1.0f, 1.0f)),
                logger);
        assertEquals(Key.key("someplugin:custom.sound"), sounds.get(MenuSounds.Kind.OPEN).key());
    }

    @Test
    void volumeAndPitchSurvive() {
        Logger logger = Logger.getLogger("VPlaytimeSoundTest");
        MenuSounds sounds = MenuSounds.load(spellings(), "main",
                configured("block.chest.open", "entity.player.levelup"), logger);
        var open = sounds.get(MenuSounds.Kind.OPEN);
        assertEquals(1.0f, open.volume());
        assertEquals(1.0f, open.pitch());
        var claim = sounds.get(MenuSounds.Kind.CLAIM);
        assertEquals(Key.key("minecraft:entity.player.levelup"), claim.key());
        assertEquals(0.8f, claim.volume());
        assertEquals(1.2f, claim.pitch());
    }

    @Test
    void unknownSoundWarnsOnceWithContext() {
        Logger logger = Logger.getLogger("VPlaytimeSoundTest-" + System.nanoTime());
        WarningTrap trap = new WarningTrap();
        logger.addHandler(trap);
        MenuSounds sounds = MenuSounds.load(spellings(), "menu_2",
                Map.of(MenuSounds.Kind.CLAIM, new SoundConfig("this.sound.does.not.exist", 1.0f, 1.0f)),
                logger);
        assertNull(sounds.get(MenuSounds.Kind.CLAIM), "unknown stays silent");
        assertEquals(1, trap.warnings.size());
        String warning = trap.warnings.get(0);
        assertTrue(warning.contains("menu_2") && warning.contains("claim")
                && warning.contains("this.sound.does.not.exist"), warning);
    }

    @Test
    void emptyStaysSilentWithoutWarning() {
        Logger logger = Logger.getLogger("VPlaytimeSoundTest-" + System.nanoTime());
        WarningTrap trap = new WarningTrap();
        logger.addHandler(trap);
        MenuSounds sounds = MenuSounds.load(spellings(), "main",
                Map.of(MenuSounds.Kind.OPEN, new SoundConfig("", 1.0f, 1.0f)), logger);
        assertNull(sounds.get(MenuSounds.Kind.OPEN));
        assertTrue(trap.warnings.isEmpty());
    }

    @Test
    void resolvedInstanceIsReused() {
        Logger logger = Logger.getLogger("VPlaytimeSoundTest");
        MenuSounds sounds = MenuSounds.load(spellings(), "main",
                configured("block.chest.open", "entity.player.levelup"), logger);
        assertSame(sounds.get(MenuSounds.Kind.OPEN), sounds.get(MenuSounds.Kind.OPEN),
                "hot path reuses the stored sound, no reparse");
    }
}
