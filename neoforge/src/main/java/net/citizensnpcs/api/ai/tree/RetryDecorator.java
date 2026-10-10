package net.citizensnpcs.api.ai.tree;

import java.util.Objects;

/**
 * Retries a failing child, admitting it again on the next tick after each failed attempt.
 * A positive limit bounds the total attempts per activation; zero allows one attempt and negative limits are unlimited.
 */
public class RetryDecorator implements Behavior {
    private final int limit;
    private final Behavior wrapping;
    private int remaining;
    private boolean active, admitting, running;
    private boolean finished = true;
    private BehaviorStatus terminal = BehaviorStatus.FAILURE;
    private long revision;

    private RetryDecorator(Behavior wrapping, int limit) {
        this.limit = limit;
        this.wrapping = Objects.requireNonNull(wrapping);
    }

    @Override
    public void reset() {
        revision++;
        remaining = 0;
        finished = true;
        terminal = BehaviorStatus.FAILURE;
        releaseChild();
    }

    private void releaseChild() {
        boolean release = active;
        active = false;
        if (release) wrapping.reset();
    }

    private void abort(long before, Throwable failure) {
        if (revision != before) return;
        finished = true;
        terminal = BehaviorStatus.FAILURE;
        try { releaseChild(); }
        catch (RuntimeException | Error cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }

    private boolean startChild() {
        long before = revision;
        active = true;
        admitting = true;
        boolean ready;
        try { ready = wrapping.shouldExecute(); }
        finally { admitting = false; }
        if (revision != before) return false;
        if (!ready) {
            finished = true;
            terminal = BehaviorStatus.FAILURE;
            releaseChild();
        }
        return revision == before && ready;
    }

    @Override
    public BehaviorStatus run() {
        if (running || admitting) return BehaviorStatus.RUNNING;
        if (finished) return terminal;
        long before = revision;
        running = true;
        try {
            if (!active && !startChild()) return revision == before ? terminal : BehaviorStatus.RUNNING;
            if (revision != before) return BehaviorStatus.RUNNING;
            BehaviorStatus status = wrapping.run();
            if (revision != before) return BehaviorStatus.RUNNING;
            if (status == BehaviorStatus.RUNNING) return status;
            terminal = status;
            finished = status != BehaviorStatus.FAILURE || !(limit < 0 || --remaining > 0);
            releaseChild();
            if (revision != before) return BehaviorStatus.RUNNING;
            return finished ? terminal : BehaviorStatus.RUNNING;
        } catch (RuntimeException | Error failure) {
            abort(before, failure);
            throw failure;
        } finally { running = false; }
    }

    @Override
    public boolean shouldExecute() {
        if (admitting) return false;
        long requested = revision + 1;
        reset();
        if (revision != requested) return false;
        remaining = Math.max(1, limit);
        finished = false;
        try { return startChild(); }
        catch (RuntimeException | Error failure) {
            abort(requested, failure);
            throw failure;
        }
    }

    public static RetryDecorator retry(Behavior wrapping, int n) {
        return new RetryDecorator(wrapping, n);
    }

    public static RetryDecorator unlimited(Behavior wrapping) {
        return retry(wrapping, -1);
    }
}
