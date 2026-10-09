package net.citizensnpcs.api.ai.tree;

import java.util.List;

/**
 * A behavior that executes multiple behaviors in a single tick.
 */
public class CoalescedBehavior implements Behavior {
    private final List<Behavior> behaviors;
    private long revision;
    private boolean running;

    public CoalescedBehavior(List<Behavior> behaviors) {
        this.behaviors = List.copyOf(behaviors);
    }

    @Override
    public void reset() {
        revision++;
        Throwable failure = null;
        for (Behavior behavior : behaviors) {
            try { behavior.reset(); }
            catch (RuntimeException | Error ex) {
                if (failure == null) failure = ex;
                else if (failure != ex) failure.addSuppressed(ex);
            }
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
    }

    @Override
    public BehaviorStatus run() {
        if (running) return BehaviorStatus.RUNNING;
        running = true;
        long before = revision;
        try {
            for (Behavior behavior : behaviors) {
                BehaviorStatus status = behavior.run();
                if (revision != before) return BehaviorStatus.RUNNING;
                if (status == BehaviorStatus.FAILURE || status == BehaviorStatus.RESET_AND_REMOVE)
                    return status;
            }
            return BehaviorStatus.SUCCESS;
        } finally { running = false; }
    }

    @Override
    public boolean shouldExecute() {
        long before = revision;
        for (Behavior behavior : behaviors) {
            boolean ready = behavior.shouldExecute();
            if (revision != before) return false;
            if (ready)
                return true;
        }
        return false;
    }
}
