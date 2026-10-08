package net.citizensnpcs.api.ai.tree;

import java.util.function.Supplier;
import java.util.Objects;

/**
 * A decorator {@link Behavior} that continues to execute its child behavior as long as a condition returns
 * <code>true</code> and the behavior returns {@link BehaviorStatus#SUCCESS}.
 */
public class Loop implements Behavior {
    private final Supplier<Boolean> condition;
    private final Behavior wrapping;
    private boolean active;
    private boolean finished = true;
    private boolean running;
    private BehaviorStatus terminal = BehaviorStatus.SUCCESS;
    private long revision;

    public Loop(Behavior wrapping, Supplier<Boolean> condition) {
        this.wrapping = Objects.requireNonNull(wrapping);
        this.condition = Objects.requireNonNull(condition);
    }

    @Override
    public void reset() {
        revision++;
        finished = true;
        terminal = BehaviorStatus.SUCCESS;
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
        boolean ready = wrapping.shouldExecute();
        if (revision != before) return false;
        finished = !ready;
        if (!ready) releaseChild();
        return revision == before && ready;
    }

    @Override
    public BehaviorStatus run() {
        if (running) return BehaviorStatus.RUNNING;
        if (finished) return terminal;
        running = true;
        long before = revision;
        try {
            BehaviorStatus status = wrapping.run();
            if (revision != before) return BehaviorStatus.RUNNING;
            if (status == BehaviorStatus.RUNNING) return status;
            finished = true;
            terminal = status;
            releaseChild();
            if (revision != before) return BehaviorStatus.RUNNING;
            // This is a do-while loop: evaluate only after a successful iteration.
            if (status == BehaviorStatus.SUCCESS) {
                boolean again = condition.get();
                if (revision != before) return BehaviorStatus.RUNNING;
                if (again && startChild()) return BehaviorStatus.RUNNING;
                if (revision != before) return BehaviorStatus.RUNNING;
            }
            return terminal;
        } catch (RuntimeException | Error failure) {
            abort(before, failure);
            throw failure;
        } finally {
            running = false;
        }
    }

    @Override
    public boolean shouldExecute() {
        long requested = revision + 1;
        reset();
        if (revision != requested) return false;
        terminal = BehaviorStatus.FAILURE;
        try { return startChild(); }
        catch (RuntimeException | Error failure) {
            abort(requested, failure);
            throw failure;
        }
    }

    public static Loop createWithCondition(Behavior wrapping, Supplier<Boolean> condition) {
        return new Loop(wrapping, condition);
    }
}
