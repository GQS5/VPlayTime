package site.vackstudio.vplaytime.playtime.provider;

import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.config.PlaytimeProviderConfig;
import site.vackstudio.vplaytime.gui.Placeholders;
import site.vackstudio.vplaytime.model.RewardState;
import site.vackstudio.vplaytime.playtime.FakeTimeSource;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Playtime provider system: units, placeholder parsing, config selection,
 * runtime validation, reload swap semantics, session gating, GUI math and
 * the expansion core — all without needing a server.
 */
class PlaytimeProviderTest {

    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");
    private static final UUID UUID_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static PlaceholderPlaytimeProvider stub(String value, PlaytimeUnit unit) {
        Function<UUID, Optional<String>> lookup = uuid -> Optional.ofNullable(value);
        return new PlaceholderPlaytimeProvider("%test_value%", unit, lookup, LOG);
    }

    // ---- units ----

    @Test
    void secondsPassThrough() {
        assertEquals(OptionalLong.of(3_600L),
                PlaceholderPlaytimeProvider.convert("3600", PlaytimeUnit.SECONDS));
    }

    @Test
    void minutesScaleToSeconds() {
        assertEquals(OptionalLong.of(3_600L),
                PlaceholderPlaytimeProvider.convert("60", PlaytimeUnit.MINUTES));
    }

    @Test
    void hoursScaleToSeconds() {
        assertEquals(OptionalLong.of(7_200L),
                PlaceholderPlaytimeProvider.convert("2", PlaytimeUnit.HOURS));
    }

    @Test
    void millisecondsFloorToSeconds() {
        assertEquals(OptionalLong.of(3_600L),
                PlaceholderPlaytimeProvider.convert("3600000", PlaytimeUnit.MILLISECONDS));
        assertEquals(OptionalLong.of(1L),
                PlaceholderPlaytimeProvider.convert("1500", PlaytimeUnit.MILLISECONDS));
    }

    @Test
    void decimalValuesFloor() {
        assertEquals(OptionalLong.of(90L),
                PlaceholderPlaytimeProvider.convert("1.5", PlaytimeUnit.MINUTES));
    }

    @Test
    void rejectsInvalidValues() {
        assertEquals(OptionalLong.empty(), PlaceholderPlaytimeProvider.convert("", PlaytimeUnit.SECONDS));
        assertEquals(OptionalLong.empty(), PlaceholderPlaytimeProvider.convert("abc", PlaytimeUnit.SECONDS));
        assertEquals(OptionalLong.empty(),
                PlaceholderPlaytimeProvider.convert("%statistic_seconds_played%", PlaytimeUnit.SECONDS));
        assertEquals(OptionalLong.empty(), PlaceholderPlaytimeProvider.convert("-5", PlaytimeUnit.SECONDS));
        assertEquals(OptionalLong.empty(), PlaceholderPlaytimeProvider.convert("NaN", PlaytimeUnit.SECONDS));
        assertEquals(OptionalLong.empty(), PlaceholderPlaytimeProvider.convert("Infinity", PlaytimeUnit.SECONDS));
        assertEquals(OptionalLong.empty(), PlaceholderPlaytimeProvider.convert("12h30m", PlaytimeUnit.SECONDS));
        assertEquals(OptionalLong.empty(), PlaceholderPlaytimeProvider.convert(null, PlaytimeUnit.SECONDS));
        assertEquals(OptionalLong.empty(), PlaceholderPlaytimeProvider.convert("42", null));
    }

    @Test
    void unitOverflowSaturates() {
        assertEquals(Long.MAX_VALUE, PlaytimeUnit.HOURS.toSeconds(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, PlaytimeUnit.MINUTES.toSeconds(Long.MAX_VALUE));
    }

    @Test
    void unitAliasesParse() {
        assertEquals(PlaytimeUnit.SECONDS, PlaytimeUnit.parse("seconds"));
        assertEquals(PlaytimeUnit.MINUTES, PlaytimeUnit.parse("Minute"));
        assertEquals(PlaytimeUnit.HOURS, PlaytimeUnit.parse("h"));
        assertEquals(PlaytimeUnit.MILLISECONDS, PlaytimeUnit.parse("ms"));
        assertEquals(null, PlaytimeUnit.parse("fortnights"));
        assertEquals(null, PlaytimeUnit.parse(null));
    }

    // ---- provider reads ----

    @Test
    void validNumericPlaceholderResolves() {
        assertEquals(OptionalLong.of(7_200L), stub("120", PlaytimeUnit.MINUTES).playtimeSeconds(UUID_A));
        assertEquals(OptionalLong.of(3_600L), stub("3600", PlaytimeUnit.SECONDS).playtimeSeconds(UUID_A));
    }

    @Test
    void invalidPlaceholderReturnsEmpty() {
        assertEquals(OptionalLong.empty(), stub("not-a-number", PlaytimeUnit.SECONDS).playtimeSeconds(UUID_A));
        assertEquals(OptionalLong.empty(),
                stub("%statistic_seconds_played%", PlaytimeUnit.SECONDS).playtimeSeconds(UUID_A));
    }

    @Test
    void missingLookupReturnsEmpty() {
        var provider = new PlaceholderPlaytimeProvider(
                "%test_value%", PlaytimeUnit.SECONDS, uuid -> Optional.empty(), LOG);
        assertEquals(OptionalLong.empty(), provider.playtimeSeconds(UUID_A));
    }

    @Test
    void throwingLookupReturnsEmpty() {
        var provider = new PlaceholderPlaytimeProvider(
                "%test_value%", PlaytimeUnit.SECONDS,
                uuid -> { throw new IllegalStateException("boom"); }, LOG);
        assertEquals(OptionalLong.empty(), provider.playtimeSeconds(UUID_A));
    }

    @Test
    void nullPlayerReturnsEmpty() {
        assertEquals(OptionalLong.empty(), stub("10", PlaytimeUnit.SECONDS).playtimeSeconds(null));
        assertEquals(OptionalLong.empty(),
                new InternalPlaytimeProvider(uuid -> 10L).playtimeSeconds(null));
    }

    @Test
    void internalProviderDelegatesToRead() {
        var provider = new InternalPlaytimeProvider(uuid -> 4_200L);
        assertEquals("internal", provider.id());
        assertFalse(provider.external());
        assertEquals(OptionalLong.of(4_200L), provider.playtimeSeconds(UUID_A));
        assertEquals(OptionalLong.empty(),
                new InternalPlaytimeProvider(uuid -> { throw new IllegalStateException("x"); })
                        .playtimeSeconds(UUID_A));
    }

    @Test
    void invalidReloadKeepsPreviousProvider() throws Exception {
        FakeTimeSource clock = new FakeTimeSource(100_000L);
        PlaytimeManager manager = new PlaytimeManager(clock);
        var first = new PlaceholderPlaytimeProvider(
                "%one%", PlaytimeUnit.SECONDS, uuid -> Optional.of("600"), LOG);
        manager.setProvider(first);
        assertEquals(600L, manager.effectivePlaytimeSeconds(UUID_A));

        // The invalid candidate never validates, so the swap never happens:
        // exactly what ConfigManager.load() guarantees (throw before publish).
        var bad = new org.bukkit.configuration.file.YamlConfiguration();
        bad.loadFromString("provider: hexacore\n");
        try {
            PlaytimeProviderConfig.parse(bad);
            fail("expected ConfigError");
        } catch (site.vackstudio.vplaytime.config.ConfigError expected) {
            assertEquals("playtime.provider", expected.path());
        }
        assertEquals("placeholder", manager.providerId());
        assertEquals(600L, manager.effectivePlaytimeSeconds(UUID_A));
    }

    @Test
    void fixedReloadSwapsProvider() throws Exception {
        FakeTimeSource clock = new FakeTimeSource(100_000L);
        PlaytimeManager manager = new PlaytimeManager(clock);
        manager.setProvider(new PlaceholderPlaytimeProvider(
                "%broken%", PlaytimeUnit.SECONDS, uuid -> Optional.of("nope"), LOG));
        assertEquals(0L, manager.effectivePlaytimeSeconds(UUID_A));

        var fixedYaml = new org.bukkit.configuration.file.YamlConfiguration();
        fixedYaml.loadFromString("""
                provider: placeholder
                placeholder:
                  value: '%statistic_seconds_played%'
                  unit: minutes
                """);
        var fixed = PlaytimeProviderConfig.parse(fixedYaml);
        manager.setProvider(new PlaceholderPlaytimeProvider(
                fixed.placeholder(), fixed.unit(), uuid -> Optional.of("60"), LOG));
        assertEquals("placeholder", manager.providerId());
        assertEquals(3_600L, manager.effectivePlaytimeSeconds(UUID_A));
    }

    @Test
    void providerSelectionSwitchesSource() {
        FakeTimeSource clock = new FakeTimeSource(50_000L);
        PlaytimeManager manager = new PlaytimeManager(clock);
        manager.handleJoin(UUID_A);
        clock.advance(1_000L);
        // No provider wired (unit-test default): internal math.
        assertEquals(1_000L, manager.effectivePlaytimeSeconds(UUID_A));

        manager.setProvider(new PlaceholderPlaytimeProvider(
                "%ext%", PlaytimeUnit.SECONDS, uuid -> Optional.of("9_999".replace("_", "")), LOG));
        assertEquals(9_999L, manager.effectivePlaytimeSeconds(UUID_A));

        manager.setProvider(new InternalPlaytimeProvider(manager::internalEffectiveSeconds));
        assertEquals("internal", manager.providerId());
        assertEquals(1_000L, manager.effectivePlaytimeSeconds(UUID_A));
    }

    // ---- external mode stops the internal timer, claims keep working ----

    @Test
    void externalModeStopsSessionTimer() {
        FakeTimeSource clock = new FakeTimeSource(70_000L);
        PlaytimeManager manager = new PlaytimeManager(clock);
        manager.setTrackSessions(false);
        assertFalse(manager.tracksSessions());
        manager.handleJoin(UUID_A);
        clock.advance(5_000L);
        var data = manager.find(UUID_A).orElseThrow();
        assertFalse(data.hasActiveSession());
        assertEquals(0L, manager.internalEffectiveSeconds(UUID_A));
        // Claims state still fully functional in external mode.
        assertTrue(data.tryClaim("reward_1"));
        assertTrue(data.isClaimed("reward_1"));
        manager.handleQuit(UUID_A);
    }

    @Test
    void internalModeStillTracksSessions() {
        FakeTimeSource clock = new FakeTimeSource(70_000L);
        PlaytimeManager manager = new PlaytimeManager(clock);
        manager.handleJoin(UUID_A);
        clock.advance(120L);
        assertEquals(120L, manager.effectivePlaytimeSeconds(UUID_A));
    }

    // ---- GUI + levels read the provider; required-seconds stays truth ----

    @Test
    void guiPlaceholdersReflectProviderValue() {
        // A 2-hour external value renders exactly like 2 internal hours.
        var ctx = new Placeholders.Context(7_200L, 3_600L, RewardState.CLAIMABLE);
        assertEquals("2", Placeholders.resolve("%playtime_hours%", ctx));
        assertEquals("120", Placeholders.resolve("%playtime_minutes%", ctx));
        assertEquals("7200", Placeholders.resolve("%playtime_seconds%", ctx));
    }

    @Test
    void levelThresholdStillUsesRequiredSeconds() throws Exception {
        // required-seconds is the source of truth; the provider only feeds
        // the "have" side of the comparison.
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString("""
                menus:
                  main:
                    name: M
                    order: 1
                    title: T
                    rows: 1
                    sounds: {}
                    rewards:
                      reward_1:
                        slot: 0
                        required-seconds: 3600
                        display:
                          name: R
                          locked: {material: STONE, lore: []}
                          claimable: {material: STONE, lore: []}
                          claimed: {material: STONE, lore: []}
                        actions:
                          - type: item
                            material: DIAMOND
                            amount: 1
                """);
        var rewards = new site.vackstudio.vplaytime.reward.RewardManager(LOG);
        rewards.load(site.vackstudio.vplaytime.config.MenuRegistry.parse(
                yaml.getConfigurationSection("menus")));
        long required = rewards.find("reward_1").orElseThrow().requiredSeconds();
        assertEquals(3_600L, required);
        var data = new site.vackstudio.vplaytime.playtime.PlayerData(UUID_A, 0L);
        assertEquals(Optional.of(RewardState.LOCKED),
                rewards.stateFor(data, "reward_1", required - 1));
        assertEquals(Optional.of(RewardState.CLAIMABLE),
                rewards.stateFor(data, "reward_1", required));
    }

    // ---- expansion core ----

    @Test
    void expansionReflectsActiveProvider() {
        FakeTimeSource clock = new FakeTimeSource(10_000L);
        PlaytimeManager manager = new PlaytimeManager(clock);
        manager.setProvider(new PlaceholderPlaytimeProvider(
                "%ext%", PlaytimeUnit.MINUTES, uuid -> Optional.of("120"), LOG));
        assertEquals("7200",
                site.vackstudio.vplaytime.papi.VPlaytimeExpansion.resolve(manager, UUID_A, "seconds"));
        assertEquals("120",
                site.vackstudio.vplaytime.papi.VPlaytimeExpansion.resolve(manager, UUID_A, "minutes"));
        assertEquals("2",
                site.vackstudio.vplaytime.papi.VPlaytimeExpansion.resolve(manager, UUID_A, "hours"));
        assertEquals(null,
                site.vackstudio.vplaytime.papi.VPlaytimeExpansion.resolve(manager, UUID_A, "bogus"));
        assertEquals(null,
                site.vackstudio.vplaytime.papi.VPlaytimeExpansion.resolve(manager, null, "seconds"));
    }

    // ---- multi-provider map: future ids slot in without reward changes ----

    @Test
    void providersAreInterchangeableById() {
        Map<String, PlaytimeProvider> byId = Map.of(
                "internal", new InternalPlaytimeProvider(uuid -> 100L),
                "placeholder", stub("200", PlaytimeUnit.SECONDS));
        assertEquals(100L, byId.get("internal").playtimeSeconds(UUID_A).orElseThrow());
        assertEquals(200L, byId.get("placeholder").playtimeSeconds(UUID_A).orElseThrow());
    }
}
