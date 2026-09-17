package site.vackstudio.vplaytime.gui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Chat-message substitution: real values in, no raw tokens out, no
 * formatting injection, no exceptions — without needing a server.
 */
class MessageFormatTest {

    @Test
    void substitutesKnownPlaceholders() {
        assertEquals("Hi Steve, need 1 hour.",
                MessageFormat.format("Hi %player%, need %required_playtime%.",
                        Map.of("player", "Steve", "required_playtime", "1 hour")));
    }

    @Test
    void missingValuesAreDropped() {
        assertEquals("Hi , need .",
                MessageFormat.format("Hi %player%, need %required_playtime%.", Map.of()));
        assertEquals("Hi , need .",
                MessageFormat.format("Hi %player%, need %required_playtime%.", null));
    }

    @Test
    void valuesAreEscaped() {
        // A name containing MiniMessage syntax must not restyle the message.
        assertEquals("Hi \\<red>Bob.",
                MessageFormat.format("Hi %player%.", Map.of("player", "<red>Bob")));
    }

    @Test
    void lonePercentsSurvive() {
        assertEquals("100% sure", MessageFormat.format("100% sure", Map.of()));
        assertEquals("%x", MessageFormat.format("%x", Map.of()));
    }

    @Test
    void percentsInsideValuesSurvive() {
        // A reason quoting '%statistic_seconds_played%' must stay intact.
        assertEquals("bad %statistic_seconds_played% value",
                MessageFormat.format("bad %detail% value",
                        Map.of("detail", "%statistic_seconds_played%")));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals("", MessageFormat.format(null, Map.of()));
        assertEquals("", MessageFormat.format("", Map.of("a", "b")));
    }
}
