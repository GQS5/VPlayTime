package site.vackstudio.vplaytime.api;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vplaytime.config.MenuRegistry;
import site.vackstudio.vplaytime.model.ClaimResult;
import site.vackstudio.vplaytime.model.RewardAction;
import site.vackstudio.vplaytime.model.RewardState;
import site.vackstudio.vplaytime.playtime.FakeTimeSource;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.reward.ClaimManager;
import site.vackstudio.vplaytime.reward.ClaimTarget;
import site.vackstudio.vplaytime.reward.RewardManager;
import site.vackstudio.vplaytime.storage.SQLiteStorage;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Public API: read-only views and the single protected claim path.
 */
class VPlaytimeApiTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Logger LOG = Logger.getLogger("VPlaytimeTest");

    static final class AcceptAllTarget implements ClaimTarget {
        int grants;
        @Override public UUID uuid() { return UUID_A; }
        @Override public String name() { return "A"; }
        @Override public boolean canAccept(List<RewardAction> actions) { return true; }
        @Override public boolean giveItem(Material material, int amount) { grants++; return true; }
        @Override public int giveItems(List<RewardAction.ItemAction> items) {
            grants += items.size();
            return -1;
        }
        @Override public boolean runCommand(String command) { return true; }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return fail("future did not complete: " + ex.getMessage());
        }
    }

    private static RewardManager rewards() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                menus:
                  main:
                    name: M
                    order: 1
                    title: T
                    rows: 3
                    sounds: {}
                    rewards:
                      reward_1:
                        slot: 11
                        required-seconds: 1800
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
        RewardManager manager = new RewardManager(LOG);
        manager.load(MenuRegistry.parse(config.getConfigurationSection("menus")));
        return manager;
    }

    @Test
    void readsAndClaimGoThroughProtectedPath(@TempDir Path dir) throws Exception {
        FakeTimeSource clock = new FakeTimeSource(1_000L);
        SQLiteStorage storage = new SQLiteStorage(dir.resolve("t.db"), LOG);
        try {
            PlaytimeManager playtime = new PlaytimeManager(clock, storage);
            RewardManager manager = rewards();
            ClaimManager claims = new ClaimManager(manager, playtime, storage,
                    (id, task) -> task.run(), clock, LOG);
            VPlaytimeAPI api = new VPlaytimeAPI(playtime, manager, claims, clock);

            assertEquals(Set.of("reward_1"), api.rewardIds());
            assertEquals(0L, api.getStoredPlaytime(UUID_A));
            assertFalse(api.isClaimed(UUID_A, "reward_1"));
            assertTrue(api.getRewardState(UUID_A, "reward_1").isEmpty());

            await(playtime.handleJoin(UUID_A));
            clock.advance(2_000L);
            assertEquals(2_000L, api.getEffectivePlaytime(UUID_A));
            assertEquals(Optional.of(RewardState.CLAIMABLE), api.getRewardState(UUID_A, "reward_1"));

            AcceptAllTarget target = new AcceptAllTarget();
            assertEquals(ClaimResult.Status.SUCCESS, await(api.claim(UUID_A, "reward_1", target)).status());
            assertEquals(1, target.grants);
            assertTrue(api.isClaimed(UUID_A, "reward_1"));
            assertEquals(Optional.of(RewardState.CLAIMED), api.getRewardState(UUID_A, "reward_1"));

            // Second claim through the API is rejected, not duplicated.
            assertEquals(ClaimResult.Status.ALREADY_CLAIMED,
                    await(api.claim(UUID_A, "reward_1", target)).status());
            assertEquals(1, target.grants);
            playtime.shutdown();
        } finally {
            storage.close();
        }
    }

    @Test
    void staticAccessorSetAndCleared() throws Exception {
        assertTrue(VPlaytimeAPI.get().isEmpty());
        FakeTimeSource clock = new FakeTimeSource(0L);
        PlaytimeManager playtime = new PlaytimeManager(clock);
        VPlaytimeAPI api = new VPlaytimeAPI(playtime, rewards(),
                new ClaimManager(rewards(), playtime, null,
                        (id, task) -> task.run(), clock, LOG),
                clock);
        VPlaytimeAPI.setInstance(api);
        try {
            assertTrue(VPlaytimeAPI.get().isPresent());
        } finally {
            VPlaytimeAPI.setInstance(null);
        }
        assertTrue(VPlaytimeAPI.get().isEmpty());
    }
}
