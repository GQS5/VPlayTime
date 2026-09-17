package site.vackstudio.vplaytime.playtime;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim-state operations on {@link PlayerData}.
 */
class PlayerDataClaimTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void freshPlayerHasNoClaims() {
        PlayerData data = new PlayerData(UUID_A, 0L);
        assertFalse(data.isClaimed("reward_1"));
        assertTrue(data.claimedRewardIds().isEmpty());
    }

    @Test
    void tryClaimReservesOnce() {
        PlayerData data = new PlayerData(UUID_A, 0L);
        assertTrue(data.tryClaim("reward_1"));
        assertFalse(data.tryClaim("reward_1"));
        assertTrue(data.isClaimed("reward_1"));
    }

    @Test
    void tryClaimDirtiesAndBumpsVersion() {
        PlayerData data = new PlayerData(UUID_A, 0L);
        long before = data.version();
        assertTrue(data.tryClaim("reward_1"));
        assertTrue(data.isDirty());
        assertTrue(data.version() > before);

        data.markClean();
        assertFalse(data.tryClaim("reward_1"), "duplicate reserve changes nothing");
        assertFalse(data.isDirty());
    }

    @Test
    void unclaimReleasesForRetry() {
        PlayerData data = new PlayerData(UUID_A, 0L);
        data.tryClaim("reward_1");
        data.markClean();

        data.unclaim("reward_1");
        assertFalse(data.isClaimed("reward_1"));
        assertTrue(data.isDirty());
        assertTrue(data.tryClaim("reward_1"), "retry after revoke succeeds");
    }

    @Test
    void unclaimUnknownIsNoOp() {
        PlayerData data = new PlayerData(UUID_A, 0L);
        data.unclaim("reward_9");
        assertFalse(data.isDirty());
    }

    @Test
    void snapshotCarriesClaims() {
        PlayerData data = new PlayerData(UUID_A, 100L);
        data.tryClaim("reward_1");
        data.tryClaim("reward_2");

        assertEquals(Set.of("reward_1", "reward_2"), data.snapshot().claimedRewardIds());
        assertEquals(100L, data.snapshot().playtimeSeconds());
    }

    @Test
    void loadedFactoryRestoresClaimsClean() {
        PlayerData data = PlayerData.loaded(UUID_A, 500L, Set.of("reward_1"));
        assertTrue(data.isClaimed("reward_1"));
        assertFalse(data.isClaimed("reward_2"));
        assertEquals(500L, data.getStoredPlaytimeSeconds());
        assertFalse(data.isDirty());
    }

    @Test
    void staleVersionGuardAppliesToClaims() {
        PlayerData data = new PlayerData(UUID_A, 0L);
        data.tryClaim("reward_1"); // v1
        long staleVersion = data.version();
        data.tryClaim("reward_2"); // v2

        data.markCleanIfVersion(staleVersion);
        assertTrue(data.isDirty(), "newer claim must survive an older save");
    }

    @Test
    void unclaimAllClearsAndReports() {
        PlayerData data = new PlayerData(UUID_A, 0L);
        assertTrue(data.unclaimAll().isEmpty());
        assertFalse(data.isDirty());

        data.tryClaim("reward_1");
        data.tryClaim("reward_2");
        data.markClean();
        assertEquals(Set.of("reward_1", "reward_2"), data.unclaimAll());
        assertTrue(data.claimedRewardIds().isEmpty());
        assertTrue(data.isDirty());
        assertTrue(data.tryClaim("reward_1"), "claim again after reset-all works");
    }

    @Test
    void concurrentReservesGrantExactlyOne() throws InterruptedException {
        PlayerData data = new PlayerData(UUID_A, 0L);
        int threads = 32;
        boolean[] won = new boolean[threads];
        Thread[] runners = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            runners[i] = new Thread(() -> won[idx] = data.tryClaim("reward_1"));
            runners[i].start();
        }
        for (Thread t : runners) {
            t.join();
        }
        int wins = 0;
        for (boolean w : won) {
            if (w) wins++;
        }
        assertEquals(1, wins);
        assertTrue(data.isClaimed("reward_1"));
    }
}
