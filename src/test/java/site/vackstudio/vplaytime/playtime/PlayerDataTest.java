package site.vackstudio.vplaytime.playtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PlayerData} session semantics.
 */
class PlayerDataTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void newPlayerStartsCleanWithNoSession() {
        PlayerData data = new PlayerData(UUID_A, 0L);

        assertEquals(0L, data.getStoredPlaytimeSeconds());
        assertFalse(data.hasActiveSession());
        assertFalse(data.isDirty());
        assertEquals(0L, data.getEffectivePlaytimeSeconds(1_000L));
    }

    @Test
    void sessionAccumulatesExactly() {
        PlayerData data = new PlayerData(UUID_A, 0L);
        data.startSession(1_000L);

        assertEquals(600L, data.getEffectivePlaytimeSeconds(1_600L));
    }

    @Test
    void sessionEndAddsElapsedToStored() {
        PlayerData data = new PlayerData(UUID_A, 3_600L);
        data.startSession(10_000L);

        long added = data.endSession(11_200L);

        assertEquals(1_200L, added);
        assertEquals(4_800L, data.getStoredPlaytimeSeconds());
        assertFalse(data.hasActiveSession());
        assertTrue(data.isDirty());
    }

    @Test
    void sessionCountedExactlyOnceOnRepeatedEnd() {
        PlayerData data = new PlayerData(UUID_A, 0L);
        data.startSession(1_000L);

        assertEquals(120L, data.endSession(1_120L));
        assertEquals(0L, data.endSession(9_999L));

        assertEquals(120L, data.getStoredPlaytimeSeconds());
    }

    @Test
    void effectiveReadsNeverMutateStored() {
        PlayerData data = new PlayerData(UUID_A, 100L);
        data.startSession(1_000L);

        assertEquals(160L, data.getEffectivePlaytimeSeconds(1_060L));
        assertEquals(160L, data.getEffectivePlaytimeSeconds(1_060L));
        assertEquals(160L, data.getEffectivePlaytimeSeconds(1_060L));

        assertEquals(100L, data.getStoredPlaytimeSeconds());
        assertFalse(data.isDirty());
    }

    @Test
    void offlineEffectiveEqualsStored() {
        PlayerData data = new PlayerData(UUID_A, 500L);
        data.startSession(1_000L);
        data.endSession(1_300L);

        assertEquals(800L, data.getEffectivePlaytimeSeconds(99_999L));
    }

    @Test
    void dirtyLifecycle() {
        PlayerData data = new PlayerData(UUID_A, 0L);
        assertFalse(data.isDirty());

        data.startSession(1_000L);
        assertFalse(data.isDirty(), "starting a session changes nothing persistent");

        data.endSession(1_060L);
        assertTrue(data.isDirty());

        data.markClean();
        assertFalse(data.isDirty());
    }

    @Test
    void zeroElapsedEndIsNoOp() {
        PlayerData data = new PlayerData(UUID_A, 42L);
        data.startSession(5_000L);

        assertEquals(0L, data.endSession(5_000L));
        assertEquals(42L, data.getStoredPlaytimeSeconds());
        assertFalse(data.hasActiveSession());
        assertFalse(data.isDirty(), "zero elapsed adds nothing, nothing to persist");
    }

    @Test
    void backwardClockNeverSubtracts() {
        PlayerData data = new PlayerData(UUID_A, 1_000L);
        data.startSession(5_000L);

        assertEquals(1_000L, data.getEffectivePlaytimeSeconds(4_000L));
        assertEquals(0L, data.endSession(4_000L));
        assertEquals(1_000L, data.getStoredPlaytimeSeconds());
    }

    @Test
    void veryLargePlaytimeStaysExact() {
        long huge = 9_000_000_000L;
        PlayerData data = new PlayerData(UUID_A, huge);
        data.startSession(0L);

        assertEquals(huge + 3_600L, data.getEffectivePlaytimeSeconds(3_600L));
        data.endSession(3_600L);
        assertEquals(huge + 3_600L, data.getStoredPlaytimeSeconds());
    }

    @Test
    void duplicateStartKeepsOriginalClock() {
        PlayerData data = new PlayerData(UUID_A, 0L);
        data.startSession(1_000L);
        data.startSession(1_050L); // duplicate join must not restart the clock

        assertEquals(200L, data.endSession(1_200L));
        assertEquals(200L, data.getStoredPlaytimeSeconds());
    }
}
