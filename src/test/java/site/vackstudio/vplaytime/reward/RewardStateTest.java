package site.vackstudio.vplaytime.reward;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import site.vackstudio.vplaytime.model.RewardState;
import site.vackstudio.vplaytime.playtime.PlayerData;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RewardState calculation: claimed always wins over playtime.
 */
class RewardStateTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static RewardManager manager() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(RewardManagerTest.validConfig());
        RewardManager manager = new RewardManager(Logger.getLogger("VPlaytimeTest"));
        manager.load(site.vackstudio.vplaytime.config.MenuRegistry.parse(
                config.getConfigurationSection("menus")));
        return manager;
    }

    private static PlayerData player(long stored, long sessionStart, boolean claimed) {
        PlayerData data = new PlayerData(UUID_A, stored);
        if (sessionStart >= 0) {
            data.startSession(sessionStart);
        } else {
            data.startSession(0L);
            data.endSession(0L);
        }
        if (claimed) {
            data.tryClaim("reward_1");
            data.markClean();
        }
        return data;
    }

    @Test
    void unclaimedInsufficientIsLocked() throws Exception {
        PlayerData data = player(100L, 1_000L, false);
        Optional<RewardState> state = manager().stateFor(
                data, "reward_1", data.getEffectivePlaytimeSeconds(1_100L));
        assertEquals(Optional.of(RewardState.LOCKED), state);
    }

    @Test
    void unclaimedSufficientIsClaimable() throws Exception {
        PlayerData data = player(1_000L, 1_000L, false);
        Optional<RewardState> state = manager().stateFor(
                data, "reward_1", data.getEffectivePlaytimeSeconds(1_900L));
        assertEquals(1_900L, data.getEffectivePlaytimeSeconds(1_900L));
        assertEquals(Optional.of(RewardState.CLAIMABLE), state);
    }

    @Test
    void claimedSufficientIsStillClaimed() throws Exception {
        PlayerData data = player(10_000L, 1_000L, true);
        assertEquals(Optional.of(RewardState.CLAIMED),
                manager().stateFor(data, "reward_1", data.getEffectivePlaytimeSeconds(2_000L)));
    }

    @Test
    void claimedInsufficientIsStillClaimed() throws Exception {
        PlayerData data = player(0L, 1_000L, true);
        assertEquals(Optional.of(RewardState.CLAIMED),
                manager().stateFor(data, "reward_1", data.getEffectivePlaytimeSeconds(1_001L)));
    }

    @Test
    void unknownRewardIsEmpty() throws Exception {
        PlayerData data = player(10_000L, 1_000L, false);
        assertTrue(manager().stateFor(data, "missing", data.getEffectivePlaytimeSeconds(2_000L)).isEmpty());
    }
}
