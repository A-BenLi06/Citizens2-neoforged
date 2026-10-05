package net.citizensnpcs.api.ai.tree;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Runs each {@link Behavior} in sequence.
 */
public class Sequence extends Composite {
    private Behavior executing;
    private int executingIndex = -1;

    private Sequence(Behavior... behaviors) {
        this(Arrays.asList(behaviors));
    }

    private Sequence(Collection<Behavior> behaviors) {
        super(behaviors);
    }

    @Override
    public void reset() {
        Behavior previous = executing;
        executing = null;
        executingIndex = -1;
        Throwable failure = null;
        try { super.reset(); }
        catch (RuntimeException | Error ex) { failure = ex; }
        try { stopExecution(previous); }
        catch (RuntimeException | Error ex) {
            if (failure == null) failure = ex;
            else if (failure != ex) failure.addSuppressed(ex);
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
    }

    private void resetCurrent() {
        Behavior previous = executing;
        executing = null;
        stopExecution(previous);
    }

    @Override
    public void removeBehavior(Behavior behavior) {
        int index = getBehaviors().indexOf(behavior);
        if (index >= 0 && index <= executingIndex) executingIndex--;
        super.removeBehavior(behavior);
        if (executing == behavior) resetCurrent();
    }

    @Override
    public BehaviorStatus run() {
        tickParallel();
        List<Behavior> behaviors = getBehaviors();
        if (executing == null) {
            BehaviorStatus next = selectNext(behaviors);
            if (next != BehaviorStatus.RUNNING) {
                resetCurrent();
                return next;
            }
        }
        if (executing == null) return BehaviorStatus.RUNNING;
        Behavior current = executing;
        BehaviorStatus status = current.run();
        if (executing != current) return BehaviorStatus.RUNNING;
        switch (status) {
            case FAILURE:
                resetCurrent();
                return BehaviorStatus.FAILURE;
            case RESET_AND_REMOVE:
                removeBehavior(current);
                return selectNext(behaviors);
            case SUCCESS:
                resetCurrent();
                return selectNext(behaviors);
            default:
                return status;
        }
    }

    private BehaviorStatus selectNext(List<Behavior> behaviors) {
        if (++executingIndex >= behaviors.size())
            return BehaviorStatus.SUCCESS;
        executing = behaviors.get(executingIndex);
        Behavior selected = executing;
        boolean ready = selected.shouldExecute();
        if (executing != selected) return BehaviorStatus.RUNNING;
        if (!ready) {
            resetCurrent();
            return BehaviorStatus.FAILURE;
        }
        return BehaviorStatus.RUNNING;
    }

    @Override
    public String toString() {
        return "Sequence [executing=" + executing + ", executingIndex=" + executingIndex + ", getBehaviors()="
                + getBehaviors() + "]";
    }

    public static Sequence createSequence(Behavior... behaviors) {
        return createSequence(Arrays.asList(behaviors));
    }

    /**
     * Creates sequence that will stop executing if the current {@link Behavior} returns {@link BehaviorStatus#FAILURE}.
     */
    public static Sequence createSequence(Collection<Behavior> behaviors) {
        return new Sequence(behaviors);
    }
}
