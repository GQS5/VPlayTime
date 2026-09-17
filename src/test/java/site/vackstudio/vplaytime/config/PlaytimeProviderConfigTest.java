package site.vackstudio.vplaytime.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.playtime.provider.PlaytimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provider selection and validation in {@code config.yml}: pure parsing
 * (file + dotted path diagnostics) plus the PlaceholderAPI runtime check
 * that keeps reloads transactional — all without needing a server.
 */
class PlaytimeProviderConfigTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    private static PlaytimeProviderConfig global(String playtimeBlock) throws Exception {
        return ConfigManager.parseGlobal(yaml(playtimeBlock)).provider();
    }

    @Test
    void missingPlaytimeSectionDefaultsToInternal() throws Exception {
        PlaytimeProviderConfig cfg = global("debug:\n  enabled: false\n");
        assertEquals("internal", cfg.provider());
        assertFalse(cfg.external());
        assertEquals(PlaytimeUnit.SECONDS, cfg.unit());
    }

    @Test
    void explicitInternalConfig() throws Exception {
        PlaytimeProviderConfig cfg = global("playtime:\n  provider: internal\n");
        assertEquals("internal", cfg.provider());
    }

    @Test
    void fullPlaceholderConfig() throws Exception {
        PlaytimeProviderConfig cfg = global("""
                playtime:
                  provider: placeholder
                  placeholder:
                    value: '%statistic_seconds_played%'
                    unit: seconds
                """);
        assertEquals("placeholder", cfg.provider());
        assertTrue(cfg.external());
        assertEquals("%statistic_seconds_played%", cfg.placeholder());
        assertEquals(PlaytimeUnit.SECONDS, cfg.unit());
    }

    @Test
    void barePlaceholderNameGetsWrapped() throws Exception {
        PlaytimeProviderConfig cfg = global("""
                playtime:
                  provider: placeholder
                  placeholder:
                    value: statistic_minutes_played
                    unit: minutes
                """);
        assertEquals("%statistic_minutes_played%", cfg.placeholder());
        assertEquals(PlaytimeUnit.MINUTES, cfg.unit());
    }

    @Test
    void placeholderValueMustBeConfigured() throws Exception {
        try {
            global("playtime:\n  provider: placeholder\n");
            fail("expected ConfigError");
        } catch (ConfigError error) {
            assertEquals("config.yml", error.file());
            assertEquals("playtime.placeholder.value", error.path());
        }
    }

    @Test
    void unknownProviderRejected() throws Exception {
        try {
            global("playtime:\n  provider: hexacore\n");
            fail("expected ConfigError");
        } catch (ConfigError error) {
            assertEquals("config.yml", error.file());
            assertEquals("playtime.provider", error.path());
            assertTrue(error.reason().contains("hexacore"));
        }
    }

    @Test
    void unknownUnitRejected() throws Exception {
        try {
            global("""
                    playtime:
                      provider: placeholder
                      placeholder:
                        value: '%x%'
                        unit: fortnights
                    """);
            fail("expected ConfigError");
        } catch (ConfigError error) {
            assertEquals("config.yml", error.file());
            assertEquals("playtime.placeholder.unit", error.path());
        }
    }

    @Test
    void nonSectionPlaceholderRejected() throws Exception {
        try {
            global("playtime:\n  provider: placeholder\n  placeholder: oops\n");
            fail("expected ConfigError");
        } catch (ConfigError error) {
            assertEquals("playtime.placeholder", error.path());
        }
    }

    @Test
    void internalIgnoresPlaceholderBlock() throws Exception {
        // Admins can prepare the placeholder block before switching over.
        PlaytimeProviderConfig cfg = global("""
                playtime:
                  provider: internal
                  placeholder:
                    value: ''
                    unit: bogus
                """);
        assertEquals("internal", cfg.provider());
    }

    @Test
    void placeholderWithoutPlaceholderApiFails() throws Exception {
        PlaytimeProviderConfig cfg = global("""
                playtime:
                  provider: placeholder
                  placeholder:
                    value: '%statistic_seconds_played%'
                    unit: seconds
                """);
        try {
            ConfigManager.checkProviderRuntime(cfg, false);
            fail("expected ConfigError");
        } catch (ConfigError error) {
            assertEquals("config.yml", error.file());
            assertEquals("playtime.provider", error.path());
            assertTrue(error.reason().contains("PlaceholderAPI"), error.reason());
            assertTrue(error.reason().contains("%statistic_seconds_played%"), error.reason());
        }
    }

    @Test
    void placeholderWithPlaceholderApiPasses() throws Exception {
        PlaytimeProviderConfig cfg = global("""
                playtime:
                  provider: placeholder
                  placeholder:
                    value: '%statistic_seconds_played%'
                    unit: seconds
                """);
        ConfigManager.checkProviderRuntime(cfg, true);
    }

    @Test
    void internalWithoutPlaceholderApiPasses() throws Exception {
        PlaytimeProviderConfig cfg = global("playtime:\n  provider: internal\n");
        ConfigManager.checkProviderRuntime(cfg, false);
    }

    @Test
    void placeholderNormalizesWrapping() {
        assertEquals("%foo%", PlaytimeProviderConfig.normalize("foo"));
        assertEquals("%foo%", PlaytimeProviderConfig.normalize("  %foo%  "));
        assertEquals("", PlaytimeProviderConfig.normalize("   "));
        assertEquals("", PlaytimeProviderConfig.normalize(null));
    }
}
