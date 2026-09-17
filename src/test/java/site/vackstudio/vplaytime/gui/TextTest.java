package site.vackstudio.vplaytime.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Single text path: legacy codes compile once at load, MiniMessage passes
 * through, typos fail fast with a named field.
 */
class TextTest {

    @Test
    void compilesLegacyColors() {
        assertEquals("<light_purple><bold>Playtime Reward", Text.legacyToMini("&d&lPlaytime Reward"));
        assertEquals("<gray>Required: <white>1 Hour", Text.legacyToMini("&7Required: &f1 Hour"));
        assertEquals("<dark_gray>Click", Text.legacyToMini("&8Click"));
        assertEquals("a<green>b<red>c<reset>d", Text.legacyToMini("a&ab&cc&rd"));
    }

    @Test
    void passesMiniMessageThrough() {
        String mini = "<green><bold>Reward</bold></green>";
        assertEquals(mini, Text.legacyToMini(mini));
        assertEquals("<gradient:#55ffff:#aa00ff>Hi", Text.legacyToMini("<gradient:#55ffff:#aa00ff>Hi"));
    }

    @Test
    void mixesBothSystems() {
        assertEquals("<light_purple><bold>Reward <gray>#1",
                Text.normalize("test", "&d&lReward <gray>#1"));
    }

    @Test
    void doubleAmpersandIsLiteral() {
        assertEquals("Fish & Chips", Text.legacyToMini("Fish && Chips"));
    }

    @Test
    void unknownSequenceLeftAlone() {
        assertEquals("&q hi", Text.legacyToMini("&q hi"));
    }

    @Test
    void rejectsInvalidMiniMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Text.normalize("menu 'main' title", "<green"));
        assertTrue(ex.getMessage().contains("menu 'main' title"), ex.getMessage());
    }

    @Test
    void rejectsUnknownTag() {
        assertThrows(IllegalStateException.class, () -> Text.normalize("x", "<gren>Hi"));
    }

    @Test
    void nullAndEmptyPass() {
        assertEquals("", Text.normalize("x", ""));
        assertEquals("", Text.normalize("x", null));
    }
}
