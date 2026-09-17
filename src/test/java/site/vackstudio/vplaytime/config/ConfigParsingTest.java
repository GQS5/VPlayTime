package site.vackstudio.vplaytime.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pure config.yml / messages.yml validation (no server needed).
 * Menu/reward structure is covered by MenuRegistryTest.
 */
class ConfigParsingTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    @Test
    void parsesGlobalSettings() throws Exception {
        GlobalConfig global = ConfigManager.parseGlobal(yaml("""
                debug:
                  enabled: true
                storage:
                  type: sqlite
                  autosave-seconds: 45
                playtime:
                  provider: internal
                """));
        assertTrue(global.debugEnabled());
        assertEquals(45, global.autosaveSeconds());
    }

    @Test
    void rejectsBadStorageType() throws Exception {
        try {
            ConfigManager.parseGlobal(yaml("storage:\n  type: mysql\n"));
            fail("expected failure");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("sqlite"));
        }
    }

    @Test
    void rejectsLowAutosave() throws Exception {
        try {
            ConfigManager.parseGlobal(yaml("storage:\n  autosave-seconds: 2\n"));
            fail("expected failure");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("5 or higher"));
        }
    }

    @Test
    void parsesMessages() throws Exception {
        MessageConfig messages = ConfigManager.parseMessages(yaml("""
                prefix: "[P] "
                loading: "wait"
                claim:
                  success: "got it"
                  locked: "locked"
                  already-claimed: "done"
                  failed: "oops"
                admin:
                  reload-success: "ok"
                  reload-failed: "bad"
                """));
        assertEquals("[P] got it", messages.prefixed(messages.claimSuccess()));
        assertEquals("wait", messages.loading());
        assertEquals("bad", messages.reloadFailed());
    }

    @Test
    void rejectsNonSectionClaim() throws Exception {
        try {
            ConfigManager.parseMessages(yaml("claim: \"oops\"\n"));
            fail("expected failure");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("'claim'"));
        }
    }

    @Test
    void missingMessagesDefaultToBlank() throws Exception {
        MessageConfig messages = ConfigManager.parseMessages(yaml("prefix: \"\"\n"));
        assertEquals("", messages.claimSuccess());
        assertEquals("hi", messages.prefixed("hi"));
    }

    @Test
    void legacyMessagesFileStillLoads() throws Exception {
        // Pre-1.9 files (only claim/admin sections, dead count-afk key)
        // load fine; new situations default to silent.
        MessageConfig messages = ConfigManager.parseMessages(yaml("""
                prefix: "[P] "
                loading: "wait"
                playtime:
                  count-afk: true
                claim:
                  success: "got it"
                admin:
                  reload-success: "ok"
                """));
        assertEquals("[P] got it", messages.prefixed(messages.claimSuccess()));
        assertEquals("", messages.usage());
        assertEquals("", messages.unknownPlayer());
        assertEquals("", messages.resetDone());
        // The dead count-afk key is ignored; provider still defaults to internal.
        assertEquals("internal", ConfigManager.parseGlobal(yaml("playtime:\n  count-afk: false\n"))
                .provider().provider());
    }

    @Test
    void legacyColorsCompileInMessages() throws Exception {
        MessageConfig messages = ConfigManager.parseMessages(yaml("""
                prefix: "&8[&bVPlaytime&8] "
                loading: "wait"
                claim:
                  success: "&aClaimed!"
                """));
        assertEquals("<dark_gray>[<aqua>VPlaytime<dark_gray>] <green>Claimed!",
                messages.prefixed(messages.claimSuccess()));
    }
}
