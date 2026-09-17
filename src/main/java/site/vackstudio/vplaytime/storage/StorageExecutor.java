package site.vackstudio.vplaytime.storage;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single-threaded database worker.
 *
 * <p>All JDBC runs on one controlled thread against one connection: no
 * connection-per-query, no concurrent access to a non-thread-safe connection,
 * and per-player save submission order is preserved (an older snapshot can
 * never commit after a newer one). Callers get {@link CompletableFuture}s and
 * never block.
 */
public final class StorageExecutor {

    private final ExecutorService worker;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Logger logger;

    public StorageExecutor(Logger logger) {
        this.logger = logger;
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "VPlaytime-storage");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Runs {@code task} on the storage thread.
     *
     * @return failed future if the executor is closed or rejects the task
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        if (!accepting.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Storage executor is closed"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }, worker);
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /**
     * Stops accepting work and waits up to {@code timeout} for queued work.
     * Logs clearly if the wait times out instead of pretending all saves
     * succeeded.
     */
    public void shutdown(long timeout, TimeUnit unit) {
        accepting.set(false);
        worker.shutdown();
        try {
            if (!worker.awaitTermination(timeout, unit)) {
                logger.log(Level.SEVERE,
                        "Storage shutdown timed out after " + timeout + " " + unit
                                + "; some saves may not have persisted. Forcing shutdown.");
                worker.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Storage shutdown interrupted; some saves may not have persisted.");
            worker.shutdownNow();
        }
    }
}
