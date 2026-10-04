package dev.abu.screener_backend.exchange.mexc;

import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A virtual-time {@link Scheduler}: delayed tasks run only when the test advances the clock, on the
 * test's thread, in due order. Enough for {@code Mono.delay} — the only timer
 * {@link MexcSnapshotFetcher} uses — without pulling in {@code reactor-test}.
 */
final class ManualScheduler implements Scheduler {

    private record Task(long dueMs, long seq, Runnable run, AtomicBoolean cancelled) {}

    private final PriorityQueue<Task> tasks = new PriorityQueue<>(
            Comparator.comparingLong(Task::dueMs).thenComparingLong(Task::seq));
    private volatile long nowMs;
    private long seq;

    ManualScheduler(long startMs) {
        this.nowMs = startMs;
    }

    @Override
    public Disposable schedule(Runnable task) {
        return schedule(task, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized Disposable schedule(Runnable task, long delay, TimeUnit unit) {
        Task t = new Task(nowMs + unit.toMillis(delay), seq++, task, new AtomicBoolean());
        tasks.add(t);
        return () -> t.cancelled.set(true);
    }

    @Override
    public long now(TimeUnit unit) {
        return unit.convert(nowMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public Worker createWorker() {
        throw new UnsupportedOperationException("not needed by Mono.delay");
    }

    /** Runs every task due within {@code ms}, including tasks those tasks schedule. */
    void advanceBy(long ms) {
        long target = nowMs + ms;
        while (true) {
            Task next;
            synchronized (this) {
                next = tasks.peek();
                if (next == null || next.dueMs > target) break;
                tasks.poll();
                nowMs = next.dueMs;
            }
            if (!next.cancelled.get()) next.run.run();
        }
        nowMs = target;
    }

    long nowMs() {
        return nowMs;
    }
}
