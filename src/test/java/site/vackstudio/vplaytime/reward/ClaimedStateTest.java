package site.vackstudio.vplaytime.reward;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import site.vackstudio.vplaytime.gui.RewardStateRenderer;
import site.vackstudio.vplaytime.model.ClaimResult;
import site.vackstudio.vplaytime.model.RewardState;
import site.vackstudio.vplaytime.reward.ClaimManagerTest.FakeTarget;
import site.vackstudio.vplaytime.reward.ClaimManagerTest.Harness;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.9 claimed-state contract: LOCKED → CLAIMABLE → SUCCESS → CLAIMED,
 * rendered immediately and stable across reopen, page changes, reload,
 * reconnect and restart. The persistent claim set is the single source of
 * truth; every render path re-reads it.
 *
 * <p>Reuses the SQLite-backed {@link Harness} (real durability, inline
 * schedulers) so these tests prove the full flow, not just the math.
 */
class ClaimedStateTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void successReadsBackClaimedImmediately(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "A");

            // Pre-state: enough playtime (100s), unclaimed -> CLAIMABLE.
            var before = h.rewards.stateFor(h.playtime.find(UUID_A).orElseThrow(), "reward_3",
                    h.playtime.effectivePlaytimeSeconds(UUID_A));
            assertEquals(Optional.of(RewardState.CLAIMABLE), before);

            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_3", target).status());

            // Post-state: the same objects re-read CLAIMED with no refresh step.
            var data = h.playtime.find(UUID_A).orElseThrow();
            assertTrue(data.isClaimed("reward_3"));
            assertEquals(Optional.of(RewardState.CLAIMED),
                    h.rewards.stateFor(data, "reward_3",
                            h.playtime.effectivePlaytimeSeconds(UUID_A)));

            // Renderer paints the claimed look from that state (this fixture
            // defines no glow; shipped-default glow is covered separately).
            var def = h.rewards.find("reward_3").orElseThrow();
            var rendered = RewardStateRenderer.resolve(def, RewardState.CLAIMED, 200L);
            assertEquals(def.display().forState(RewardState.CLAIMED).material(), rendered.material());
            assertFalse(rendered.glow());
        }
    }

    @Test
    void duplicateClaimGrantsOnce(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "A");

            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_3", target).status());
            assertEquals(ClaimResult.Status.ALREADY_CLAIMED,
                    h.claim(UUID_A, "reward_3", target).status());
            assertEquals(1, target.commands.size());
            assertEquals(Optional.of(RewardState.CLAIMED),
                    h.rewards.stateFor(h.playtime.find(UUID_A).orElseThrow(), "reward_3",
                            h.playtime.effectivePlaytimeSeconds(UUID_A)));
        }
    }

    @Test
    void failedExecutionDoesNotBecomeClaimed(@TempDir Path dir) throws Exception {        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.failCommand = true;

            assertEquals(ClaimResult.Status.REWARD_FAILED,
                    h.claim(UUID_A, "reward_3", target).status());
            var data = h.playtime.find(UUID_A).orElseThrow();
            assertFalse(data.isClaimed("reward_3"));
            // Still claimable (playtime unchanged), never claimed.
            assertEquals(Optional.of(RewardState.CLAIMABLE),
                    h.rewards.stateFor(data, "reward_3",
                            h.playtime.effectivePlaytimeSeconds(UUID_A)));
        }
    }

    @Test
    void claimedSurvivesReloadSwap(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_3", target).status());

            // Simulate /vplaytime reload: fresh parse + swap, same memory.
            org.bukkit.configuration.file.YamlConfiguration config =
                    new org.bukkit.configuration.file.YamlConfiguration();
            config.loadFromString(ClaimManagerTest.CONFIG);
            h.rewards.load(site.vackstudio.vplaytime.config.MenuRegistry.parse(
                    config.getConfigurationSection("menus")));

            assertEquals(Optional.of(RewardState.CLAIMED),
                    h.rewards.stateFor(h.playtime.find(UUID_A).orElseThrow(), "reward_3",
                            h.playtime.effectivePlaytimeSeconds(UUID_A)));
        }
    }

    @Test
    void claimedSurvivesReconnect(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            assertEquals(ClaimResult.Status.SUCCESS, h.claim(UUID_A, "reward_3", target).status());
            h.playtime.handleQuit(UUID_A);

            // Reconnect reloads claims from the durable row.
            h.join(UUID_A);
            var data = h.playtime.find(UUID_A).orElseThrow();
            assertTrue(data.isClaimed("reward_3"));
            assertEquals(Optional.of(RewardState.CLAIMED),
                    h.rewards.stateFor(data, "reward_3",
                            h.playtime.effectivePlaytimeSeconds(UUID_A)));
        }
    }

    @Test
    void repeatedCommandFailuresSuspendReward(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(200L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.failCommand = true;

            // Three failures: attempted every time (revoked for retry).
            for (int i = 0; i < ClaimManager.FAIL_ALARM_THRESHOLD; i++) {
                assertEquals(ClaimResult.Status.REWARD_FAILED,
                        h.claim(UUID_A, "reward_3", target).status());
                assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_3"));
            }
            assertTrue(h.claims.isSuspended("reward_3"));

            // Fourth attempt: refused BEFORE anything runs — no dispatch,
            // no reservation, no grant. The failing command never executes.
            assertEquals(ClaimResult.Status.SUSPENDED,
                    h.claim(UUID_A, "reward_3", target).status());
            assertTrue(target.commands.isEmpty());
            assertFalse(h.playtime.find(UUID_A).orElseThrow().isClaimed("reward_3"));

            // A successful reload (admin fixed the command) re-enables it.
            assertEquals(1, h.claims.clearSuspended());
            assertFalse(h.claims.isSuspended("reward_3"));
            assertEquals(ClaimResult.Status.REWARD_FAILED,
                    h.claim(UUID_A, "reward_3", target).status());

            // Other rewards are unaffected by one reward's suspension.
            assertFalse(h.claims.isSuspended("reward_1"));
        }
    }

    @Test
    void itemFailuresDoNotSuspend(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir.resolve("t.db"))) {
            h.join(UUID_A);
            h.clock.advance(2_000L);
            FakeTarget target = new FakeTarget(UUID_A, "A");
            target.failGive = true;

            for (int i = 0; i < ClaimManager.FAIL_ALARM_THRESHOLD + 1; i++) {
                assertEquals(ClaimResult.Status.REWARD_FAILED,
                        h.claim(UUID_A, "reward_1", target).status());
            }
            // Transient delivery failures keep retrying; only deterministic
            // command rejections suspend.
            assertFalse(h.claims.isSuspended("reward_1"));
        }
    }
}
