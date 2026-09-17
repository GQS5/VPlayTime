package site.vackstudio.vplaytime.reward;

import site.vackstudio.vplaytime.model.ClaimResult;
import site.vackstudio.vplaytime.model.RewardAction;
import site.vackstudio.vplaytime.model.RewardDefinition;
import site.vackstudio.vplaytime.playtime.PlayerData;
import site.vackstudio.vplaytime.playtime.PlaytimeManager;
import site.vackstudio.vplaytime.playtime.TimeSource;
import site.vackstudio.vplaytime.storage.Storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Authoritative claim business logic. The GUI (later) calls here; it never
 * reimplements these checks.
 *
 * <p>Claim ordering (durable-first, deliberate):
 * <pre>
 * player thread:  validate → atomic memory reserve → capacity pre-check
 * storage thread: durable recordClaim (uniqueness arbiter)
 * player thread:  re-check capacity → build ONE execution plan
 * plan steps:     consecutive same-context actions grouped; player/global
 *                 hops only at context boundaries, configured order kept
 * player thread:  SUCCESS
 * </pre>
 *
 * <p>Delivery follows configured order exactly: {@code item, command, item}
 * executes item-batch → command block → item-batch across the minimal
 * thread hops (player → global → player). Consecutive compatible actions
 * batch: 10 item actions cost ONE inventory scan, ONE combined capacity
 * plan ({@link ItemGrant}), ONE delivery; 10 commands cost ONE global
 * block with 10 in-order dispatches. Single-action rewards behave exactly
 * as before.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Duplicate grant is impossible: memory reserve rejects concurrent
 *       attempts; the DB primary key rejects anything memory missed
 *       (conflict self-heals memory back to claimed).</li>
 *   <li>Failed execution never finalizes: the reservation is revoked in
 *       memory and the claim row revoked in the DB, so the player retries.</li>
 *   <li>Failed durability never grants: execution runs only after the insert
 *       ack. The reservation is rolled back and the player retries.</li>
 *   <li>Capacity is checked twice on the player thread (before reserve-durable
 *       and immediately before delivery, with no await between the second
 *       check and delivery), so item delivery cannot half-finish because of
 *       space. Pure-item multi-action rewards are atomic in practice: one
 *       combined plan is computed and applied without interleaving.</li>
  *   <li>Mixed item+command rewards execute in configured order with the failed
  *       action reported as {@code action i/n}. A failure after earlier steps
  *       were delivered revokes the claim for retry, so the retry re-runs
  *       every step INCLUDING already-delivered ones: commands in such
  *       rewards must be idempotent, and a permanently failing later step
  *       would re-grant earlier steps on every attempt. Two guards cover
  *       that: load time warns about unknown command roots, and
  *       {@link #FAIL_ALARM_THRESHOLD} consecutive execution failures raise
  *       one SEVERE alarm naming the reward and the last error.</li>
 *   <li>Offline at execution time: target operations fail safely into the
 *       revoke path — nothing is granted, nothing is finalized, retry works
 *       after reconnect.</li>
 *   <li>Residual crash risk (unchanged from Phase 4, ms-scale): crash after
 *       the DB ack but before execution leaves a durable claim without items.
 *       Every attempt carries a unique execution id in the logs (and as
 *       {@code %claim_id%} in commands) so support can audit and re-grant.
 *       The reverse order would risk duplicates — the worse failure.</li>
 * </ul>
 *
 * <p>Threading: {@link #claim} must be called on the player's entity/region
 * thread (a GUI click already is). Storage runs async; execution returns to
 * the player thread via {@link PlayerScheduler} (Folia entity scheduler in
 * production). No blocking, no global locks — atomicity comes from the
 * per-player {@code PlayerData} monitor plus the DB primary key.
 */
public final class ClaimManager {

    /**
     * Consecutive execution failures per reward id that trigger one SEVERE
     * alarm. A single failure is routine (transient); a streak means the
     * reward is almost certainly misconfigured — and every further attempt
     * re-grants already-delivered earlier actions. Counting resets on the
     * next success. Memory-only; bounded by the reward count.
     */
    static final int FAIL_ALARM_THRESHOLD = 3;

    private final RewardManager rewards;
    private final PlaytimeManager playtime;
    private final Storage storage;
    private final PlayerScheduler scheduler;
    private final GlobalScheduler globalScheduler;
    private final TimeSource clock;
    private final Logger logger;
    private final boolean debugLogging;
    private final ConcurrentMap<String, Integer> execFailStreak = new ConcurrentHashMap<>();

    public ClaimManager(
            RewardManager rewards,
            PlaytimeManager playtime,
            Storage storage,
            PlayerScheduler scheduler,
            GlobalScheduler globalScheduler,
            TimeSource clock,
            Logger logger,
            boolean debugLogging) {
        this.rewards = rewards;
        this.playtime = playtime;
        this.storage = storage;
        this.scheduler = scheduler;
        this.globalScheduler = globalScheduler;
        this.clock = clock;
        this.logger = logger;
        this.debugLogging = debugLogging;
    }

    /** Backwards-compatible constructor with debug logging off. */
    public ClaimManager(
            RewardManager rewards,
            PlaytimeManager playtime,
            Storage storage,
            PlayerScheduler scheduler,
            TimeSource clock,
            Logger logger) {
        this(rewards, playtime, storage, scheduler, task -> task.run(), clock, logger, false);
    }

    public CompletableFuture<ClaimResult> claim(UUID playerId, String rewardId, ClaimTarget target) {
        RewardDefinition def = rewards.find(rewardId).orElse(null);
        if (def == null) {
            return CompletableFuture.completedFuture(
                    ClaimResult.of(ClaimResult.Status.NOT_FOUND, "unknown reward '" + rewardId + "'"));
        }
        Optional<PlayerData> dataOpt = playtime.find(playerId);
        if (dataOpt.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ClaimResult.of(ClaimResult.Status.NOT_LOADED, "player data not loaded"));
        }
        PlayerData data = dataOpt.get();
        long now = clock.epochSeconds();
        UUID executionId = UUID.randomUUID();
        long startedNanos = System.nanoTime();

        synchronized (data) {
            if (data.isClaimed(rewardId)) {
                return completed(ClaimResult.Status.ALREADY_CLAIMED, "already claimed");
            }
            long effective = playtime.effectivePlaytimeSeconds(playerId);
            if (effective < def.requiredSeconds()) {
                return completed(ClaimResult.Status.LOCKED,
                        "requires " + def.requiredSeconds() + "s, have " + effective + "s");
            }
            if (!data.tryClaim(rewardId)) {
                return completed(ClaimResult.Status.ALREADY_CLAIMED, "already claimed");
            }
            // Capacity pre-check before anything durable: a full inventory
            // rejects fast without touching the claim row.
            if (!target.canAccept(def.actions())) {
                data.unclaim(rewardId);
                return completed(ClaimResult.Status.REWARD_FAILED, "not enough inventory space");
            }
        }
        debug("Claim " + executionId + " reserved for " + playerId + " / " + rewardId);

        return storage.recordClaim(data.snapshot(), rewardId, now).thenCompose(inserted -> {
            if (!inserted) {
                // Safety net: the row already existed. Heal memory toward the
                // durable truth and reject — never grant twice.
                data.tryClaim(rewardId);
                logger.log(Level.WARNING, "Claim conflict for " + playerId + " / " + rewardId
                        + ": durable row already existed; treated as already claimed.");
                return completed(ClaimResult.Status.ALREADY_CLAIMED, "already claimed");
            }
            CompletableFuture<ClaimResult> done = new CompletableFuture<>();
            try {
                scheduler.run(playerId, () -> executePhase(data, def, target, executionId, startedNanos, done));
            } catch (Exception ex) {
                // Execution never ran: revoke the durable row too, roll back
                // memory, let the player retry.
                data.unclaim(rewardId);
                storage.revokeClaim(playerId, rewardId);
                logger.log(Level.SEVERE, "Claim scheduler failed for " + playerId + " / " + rewardId
                        + ": " + messageOf(ex));
                return completed(ClaimResult.Status.REWARD_FAILED, "claim could not complete, try again");
            }
            return done;
        }).exceptionally(ex -> {
            // Durability failed before any grant: roll back the reservation so
            // the player can retry. Dirty stays set; nothing is lost.
            data.unclaim(rewardId);
            logger.log(Level.SEVERE, "Claim storage failure for " + playerId + " / " + rewardId
                    + ": " + messageOf(ex));
            return ClaimResult.of(ClaimResult.Status.STORAGE_FAILED, "claim could not complete, try again");
        });
    }

    private void executePhase(
            PlayerData data,
            RewardDefinition def,
            ClaimTarget target,
            UUID executionId,
            long startedNanos,
            CompletableFuture<ClaimResult> done) {
        // Second capacity check on the player thread with no await between it
        // and delivery: the inventory cannot change underneath us here.
        if (!target.canAccept(def.actions())) {
            revoke(data, def, "inventory changed during claim");
            done.complete(ClaimResult.of(ClaimResult.Status.REWARD_FAILED, "not enough inventory space"));
            return;
        }
        List<RewardAction> effective = substitute(def.actions(), target, data.uuid(), executionId);
        runStep(ExecutionPlan.build(effective), 0, data, def, target, executionId, startedNanos, done);
    }

    /**
     * Runs one plan step, then advances. Item steps execute inline (already
     * on the player thread — no hop); command steps hop to the global tick
     * thread once per step and hop back for whatever follows. Configured
     * order is preserved exactly; consecutive compatible actions batch.
     */
    private void runStep(
            List<ExecutionPlan.Step> plan,
            int stepIndex,
            PlayerData data,
            RewardDefinition def,
            ClaimTarget target,
            UUID executionId,
            long startedNanos,
            CompletableFuture<ClaimResult> done) {
        if (stepIndex >= plan.size()) {
            grantComplete(data, def, executionId, startedNanos, done);
            return;
        }
        ExecutionPlan.Step step = plan.get(stepIndex);
        if (step instanceof ExecutionPlan.Items items) {
            int failed = target.giveItems(items.items());
            if (failed != -1) {
                int global = items.indexes().get(Math.min(failed, items.indexes().size() - 1));
                String detail = "item delivery failed at action " + global + "/" + def.actions().size();
                revoke(data, def, detail);
                done.complete(ClaimResult.of(ClaimResult.Status.REWARD_FAILED, detail));
                return;
            }
            runStep(plan, stepIndex + 1, data, def, target, executionId, startedNanos, done);
            return;
        }
        if (step instanceof ExecutionPlan.Commands commands) {
            try {
                globalScheduler.run(() -> runCommands(plan, stepIndex, commands, data, def, target,
                        executionId, startedNanos, done));
            } catch (Exception ex) {
                // Commands never ran (e.g. scheduling on a disabled plugin):
                // revoke the durable row too and let the player retry.
                data.unclaim(def.id());
                storage.revokeClaim(data.uuid(), def.id());
                logger.log(Level.SEVERE, "Claim global scheduler failed for " + data.uuid()
                        + " / " + def.id() + ": " + messageOf(ex));
                done.complete(ClaimResult.of(ClaimResult.Status.REWARD_FAILED,
                        "claim could not complete, try again"));
            }
        }
    }

    /** One global-tick block dispatching every command of the step, in order. */
    private void runCommands(
            List<ExecutionPlan.Step> plan,
            int stepIndex,
            ExecutionPlan.Commands commands,
            PlayerData data,
            RewardDefinition def,
            ClaimTarget target,
            UUID executionId,
            long startedNanos,
            CompletableFuture<ClaimResult> done) {
        for (int j = 0; j < commands.commands().size(); j++) {
            boolean ok;
            try {
                ok = target.runCommand(commands.commands().get(j).command());
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Command threw for " + data.uuid()
                        + " / " + def.id() + ": " + ex.getMessage());
                ok = false;
            }
            if (!ok) {
                int global = commands.indexes().get(j);
                String detail = "command dispatch failed: " + commands.commands().get(j).command()
                        + " (action " + global + "/" + def.actions().size() + ")";
                UUID playerId = data.uuid();
                scheduler.run(playerId, () -> {
                    revoke(data, def, detail);
                    done.complete(ClaimResult.of(ClaimResult.Status.REWARD_FAILED, detail));
                });
                return;
            }
        }
        UUID playerId = data.uuid();
        scheduler.run(playerId, () -> runStep(plan, stepIndex + 1, data, def, target,
                executionId, startedNanos, done));
    }

    private void grantComplete(
            PlayerData data, RewardDefinition def, UUID executionId, long startedNanos,
            CompletableFuture<ClaimResult> done) {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000L;
        debug("Granted reward " + def.id() + " to " + data.uuid()
                + " execution " + executionId + " in " + millis + "ms");
        execFailStreak.remove(def.id());
        // Claims are already durable; persist the latest playtime snapshot.
        // A failed playtime save keeps dirty and is retried by autosave.
        playtime.saveNow(data);
        done.complete(ClaimResult.success());
    }

    private void revoke(PlayerData data, RewardDefinition def, String reason) {
        data.unclaim(def.id());
        storage.revokeClaim(data.uuid(), def.id());
        logger.log(Level.WARNING, "Reward execution failed for " + data.uuid()
                + " / " + def.id() + ": " + reason + "; claim revoked for retry.");
        int streak = execFailStreak.merge(def.id(), 1, Integer::sum);
        if (streak == FAIL_ALARM_THRESHOLD) {
            logger.log(Level.SEVERE, "Reward '" + def.id() + "' execution failed "
                    + streak + " times in a row (last: " + reason + "). This is almost"
                    + " certainly a broken command, not lag: every further attempt re-grants"
                    + " already-delivered earlier actions. Fix the command in rewards.yml"
                    + " (load time warns about unknown commands), then /vplaytime reload.");
        }
    }

    /** Consecutive execution failures for one reward id (0 when clean). Test hook. */
    int failStreak(String rewardId) {
        return execFailStreak.getOrDefault(rewardId, 0);
    }

    /**
     * Placeholder substitution, in one place. {@code %player%} is the username,
     * {@code %uuid%} the player UUID, {@code %claim_id%} the unique id of this
     * attempt (audit hook for external systems). Item actions pass through.
     */
    static List<RewardAction> substitute(
            List<RewardAction> actions, ClaimTarget target, UUID playerId, UUID executionId) {
        List<RewardAction> effective = new ArrayList<>(actions.size());
        for (RewardAction action : actions) {
            if (action instanceof RewardAction.CommandAction command) {
                effective.add(new RewardAction.CommandAction(command.command()
                        .replace("%player%", target.name())
                        .replace("%uuid%", playerId.toString())
                        .replace("%claim_id%", executionId.toString())));
            } else {
                effective.add(action);
            }
        }
        return List.copyOf(effective);
    }

    private void debug(String message) {
        if (debugLogging) {
            logger.info("[debug] " + message);
        }
    }

    private static CompletableFuture<ClaimResult> completed(ClaimResult.Status status, String detail) {
        return CompletableFuture.completedFuture(ClaimResult.of(status, detail));
    }

    private static String messageOf(Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        return String.valueOf(cause.getMessage());
    }
}
