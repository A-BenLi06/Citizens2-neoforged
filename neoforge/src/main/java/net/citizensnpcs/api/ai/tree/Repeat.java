package net.citizensnpcs.api.ai.tree;

import java.util.Objects;
import java.util.function.IntSupplier;

/** Executes a child a bounded number of times, yielding between iterations. */
public final class Repeat implements Behavior {
    private final Behavior child;
    private final IntSupplier count;
    private int remaining;
    private boolean active;
    private boolean finished = true;
    private boolean running;
    private BehaviorStatus terminal = BehaviorStatus.SUCCESS;
    private long revision;

    public Repeat(Behavior child, IntSupplier count) {
        this.child = Objects.requireNonNull(child);
        this.count = Objects.requireNonNull(count);
    }

    private void releaseChild() {
        boolean release = active;
        active = false;
        if (release) child.reset();
    }

    @Override public void reset() {
        revision++;
        remaining = 0;
        finished = true;
        terminal = BehaviorStatus.SUCCESS;
        releaseChild();
    }

    private boolean startChild() {
        long before = revision;
        active = true;
        boolean ready = child.shouldExecute();
        if (revision != before) return false;
        if (!ready) {
            finished = true;
            terminal = BehaviorStatus.FAILURE;
            releaseChild();
        }
        return ready;
    }

    @Override public boolean shouldExecute() {
        long requested = revision + 1;
        reset();
        if (revision != requested) return false;
        long before = revision;
        int repetitions = count.getAsInt();
        if (revision != before) return false;
        if (repetitions < 0) throw new IllegalArgumentException("Repeat count must not be negative");
        remaining = repetitions;
        finished = remaining == 0;
        return finished || startChild();
    }

    @Override public BehaviorStatus run() {
        if (running) return BehaviorStatus.RUNNING;
        running = true;
        try { return runOnce(); }
        finally { running = false; }
    }

    private BehaviorStatus runOnce() {
        if (finished) return terminal;
        long before = revision;
        if (!active && !startChild()) return revision == before ? terminal : BehaviorStatus.RUNNING;
        if (revision != before) return BehaviorStatus.RUNNING;
        BehaviorStatus status = child.run();
        if (revision != before) return BehaviorStatus.RUNNING;
        if (status == BehaviorStatus.RUNNING) return status;
        if (status == BehaviorStatus.SUCCESS) {
            finished = --remaining == 0;
            terminal = BehaviorStatus.SUCCESS;
        } else {
            finished = true;
            terminal = status;
        }
        releaseChild();
        if (revision != before) return BehaviorStatus.RUNNING;
        return finished ? terminal : BehaviorStatus.RUNNING;
    }
}
