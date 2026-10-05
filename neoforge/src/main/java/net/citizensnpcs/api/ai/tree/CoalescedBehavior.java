package net.citizensnpcs.api.ai.tree;

import java.util.List;

/**
 * A behavior that executes multiple behaviors in a single tick.
 */
public class CoalescedBehavior implements Behavior {
    private final List<Behavior> behaviors;

    public CoalescedBehavior(List<Behavior> behaviors) {
        this.behaviors = List.copyOf(behaviors);
    }

    @Override
    public void reset() {
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
        for (Behavior behavior : behaviors) {
            BehaviorStatus status = behavior.run();
            if (status == BehaviorStatus.FAILURE || status == BehaviorStatus.RESET_AND_REMOVE)
                return status;
        }
        return BehaviorStatus.SUCCESS;
    }

    @Override
    public boolean shouldExecute() {
        for (Behavior behavior : behaviors) {
            if (behavior.shouldExecute())
                return true;
        }
        return false;
    }
}
