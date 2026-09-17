package site.vackstudio.vplaytime.reward;

/**
 * Runs work on the global tick thread. Required for console-sender command
 * dispatch: Folia rejects {@code Bukkit.dispatchCommand} from anywhere else
 * ({@code RegionizedServer.ensureGlobalTickThread}). Production uses the
 * global region scheduler; tests run inline.
 */
@FunctionalInterface
public interface GlobalScheduler {

    void run(Runnable task);
}
