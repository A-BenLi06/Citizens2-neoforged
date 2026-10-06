package net.citizensnpcs.api.ai.tree;

/**
 * Decorator that forces FAILURE if child doesn't complete within a timeout.
 */
public class TimeoutDecorator implements Behavior {
    private final Behavior child;
    private final int maxTicks;
    private int ticksElapsed;
    private boolean active;
    private BehaviorStatus terminal;
    private long revision;

    public TimeoutDecorator(Behavior child, int maxTicks) {
        this.child = child;
        this.maxTicks = maxTicks;
    }

    @Override
    public void reset() {
        revision++;
        ticksElapsed = 0;
        terminal = null;
        releaseChild();
    }

    private void releaseChild() {
        boolean release = active;
        active = false;
        if (release) child.reset();
    }

    @Override
    public BehaviorStatus run() {
        if (terminal != null) return terminal;
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
        if (maxTicks <= 0) return true;
        active = true;
        boolean ready;
        try { ready = child.shouldExecute(); }
        catch (RuntimeException | Error failure) {
            if (revision == requested) {
                terminal = BehaviorStatus.FAILURE;
                try { releaseChild(); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
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
