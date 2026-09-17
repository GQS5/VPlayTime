package site.vackstudio.vplaytime.reward;

import java.util.UUID;

/**
 * Runs work in a player's entity-thread context (Folia-safe return path for
 * async claim continuations). Production uses the Folia entity scheduler;
 * tests run inline.
 */
@FunctionalInterface
public interface PlayerScheduler {

    void run(UUID playerId, Runnable task);
}
