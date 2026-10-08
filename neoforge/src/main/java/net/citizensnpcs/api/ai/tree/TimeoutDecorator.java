package net.citizensnpcs.api.ai.tree;

import java.util.function.IntSupplier;

/**
 * Decorator that forces FAILURE if child doesn't complete within a timeout.
 */
public class TimeoutDecorator implements Behavior {
    private final Behavior child;
    private final IntSupplier budget;
    private int maxTicks;
    private boolean budgetReady;
    private int ticksElapsed;
    private boolean active;
    private boolean running;
    private BehaviorStatus terminal;
    private long revision;

    public TimeoutDecorator(Behavior child, int maxTicks) {
        this(child, () -> maxTicks);
    }

    public TimeoutDecorator(Behavior child, IntSupplier budget) {
        this.child = child;
        this.budget = budget;
    }

    @Override
    public void reset() {
        revision++;
        ticksElapsed = 0;
        terminal = null;
        budgetReady = false;
        releaseChild();
    }

    private boolean prepareBudget() {
        if (budgetReady) return true;
        long before = revision;
        int evaluated;
        try { evaluated = budget.getAsInt(); }
        catch (RuntimeException | Error failure) {
            if (revision == before) terminal = BehaviorStatus.FAILURE;
            throw failure;
        }
        if (revision != before) return false;
        maxTicks = evaluated;
        budgetReady = true;
        return true;
    }

    private void releaseChild() {
        boolean release = active;
        active = false;
        if (release) child.reset();
    }

    @Override
    public BehaviorStatus run() {
        if (running) return BehaviorStatus.RUNNING;
        running = true;
        long before = revision;
        try { return runOnce(); }
        catch (RuntimeException | Error failure) {
            abort(before, failure);
            throw failure;
        } finally { running = false; }
    }

    private void abort(long before, Throwable failure) {
        if (revision != before) return;
        terminal = BehaviorStatus.FAILURE;
        try { releaseChild(); }
        catch (RuntimeException | Error cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }

    private BehaviorStatus runOnce() {
        if (terminal != null) return terminal;
        if (!prepareBudget()) return BehaviorStatus.RUNNING;
        long before = revision;
        if (ticksElapsed >= maxTicks) {
            terminal = BehaviorStatus.FAILURE;
            releaseChild();
            return revision == before ? terminal : BehaviorStatus.RUNNING;
        }
        ticksElapsed++;
        active = true;
        BehaviorStatus status = child.run();
        if (revision != before) return BehaviorStatus.RUNNING;
        if (status != BehaviorStatus.RUNNING) {
            terminal = status;
            releaseChild();
            if (revision != before) return BehaviorStatus.RUNNING;
        }
        return status;
    }

    @Override
    public boolean shouldExecute() {
        long requested = revision + 1;
        reset();
        if (revision != requested) return false;
        if (!prepareBudget()) return false;
        if (maxTicks <= 0) return true;
        active = true;
        boolean ready;
        try { ready = child.shouldExecute(); }
        catch (RuntimeException | Error failure) {
            abort(requested, failure);
            throw failure;
        }
        if (revision != requested) return false;
        if (!ready) {
            terminal = BehaviorStatus.FAILURE;
            releaseChild();
        }
        return ready;
    }
}
