package com.moveinsync.rostersync.roster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/**
 * Releases tasks together through a start gate and returns each task's result or exception. With as
 * many threads as tasks they all start at the same instant; with more tasks than threads, the first
 * {@code threads} start together and the rest follow as threads free up. No sleeps: contention comes
 * from simultaneous release, not from timing.
 */
public final class Race implements AutoCloseable {

    /** One task's outcome: a value or the exception it threw. */
    public record Outcome<T>(T value, Throwable error) {
        public boolean failed() {
            return error != null;
        }
    }

    private final ExecutorService pool;
    private final int threads;

    public Race(int threads) {
        this.threads = threads;
        this.pool = Executors.newFixedThreadPool(threads);
    }

    public <T> List<Outcome<T>> run(IntFunction<Callable<T>> tasks) {
        return run(threads, tasks);
    }

    public <T> List<Outcome<T>> run(int parties, IntFunction<Callable<T>> tasks) {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < parties; i++) {
            Callable<T> task = tasks.apply(i);
            futures.add(pool.submit(() -> {
                startGate.await();
                return task.call();
            }));
        }
        startGate.countDown();
        List<Outcome<T>> outcomes = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                outcomes.add(new Outcome<>(future.get(), null));
            } catch (ExecutionException e) {
                outcomes.add(new Outcome<>(null, e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return outcomes;
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
