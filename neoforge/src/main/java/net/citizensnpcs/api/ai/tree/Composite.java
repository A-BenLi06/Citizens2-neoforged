package net.citizensnpcs.api.ai.tree;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import com.google.common.collect.Lists;

/**
 * The base class for composite {@link Behavior}s, which handle the transition between multiple sub-behaviors.
 */
public abstract class Composite implements Behavior {
    private final List<Behavior> behaviors;
    private final List<Behavior> parallel = Lists.newArrayListWithCapacity(0);
    protected final List<Behavior> parallelExecuting = Lists.newArrayListWithCapacity(0);

    public Composite(Behavior... behaviors) {
        this(Arrays.asList(behaviors));
    }

    public Composite(Collection<Behavior> behaviors) {
        this.behaviors = Lists.newArrayList(behaviors);
        Iterator<Behavior> itr = this.behaviors.iterator();
        while (itr.hasNext()) {
            Behavior b = itr.next();
            if (b instanceof ParallelBehavior) {
                parallel.add(b);
                itr.remove();
            }
        }
    }

    public void addBehavior(Behavior behavior) {
        if (behavior instanceof ParallelBehavior) parallel.add(behavior);
        else behaviors.add(behavior);
    }

    public List<Behavior> getBehaviors() {
        return behaviors;
    }

    public void removeBehavior(Behavior behavior) {
        behaviors.remove(behavior);
        parallel.remove(behavior);
        if (parallelExecuting.remove(behavior)) stopExecution(behavior);
    }

    public void clearBehaviors() {
        behaviors.clear();
        parallel.clear();
        reset();
    }

    @Override
    public void reset() {
        List<Behavior> previous = List.copyOf(parallelExecuting);
        parallelExecuting.clear();
        Throwable failure = null;
        for (Behavior behavior : previous) {
            try { stopExecution(behavior); }
            catch (RuntimeException | Error ex) {
                if (failure == null) failure = ex;
                else if (failure != ex) failure.addSuppressed(ex);
            }
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
    }

    @Override
    public boolean shouldExecute() {
        return !behaviors.isEmpty() || !parallel.isEmpty();
    }

    protected void stopExecution(Behavior behavior) {
        if (behavior == null)
            return;
        behavior.reset();
    }

    protected void tickParallel() {
        for (Behavior b : List.copyOf(parallel)) {
            tryAddParallel(b);
        }
        for (Behavior behavior : List.copyOf(parallelExecuting)) {
            if (!parallelExecuting.contains(behavior)) continue;
            BehaviorStatus status = behavior.run();
            if (!parallelExecuting.contains(behavior)) continue;
            switch (status) {
                case RESET_AND_REMOVE:
                    parallel.remove(behavior);
                case FAILURE:
                case SUCCESS:
                    parallelExecuting.remove(behavior);
                    stopExecution(behavior);
                    break;
                default:
                    break;
            }
        }
    }

    private void tryAddParallel(Behavior behavior) {
        if (!parallel.contains(behavior) || parallelExecuting.contains(behavior)) return;
        if (behavior.shouldExecute() && parallel.contains(behavior) && !parallelExecuting.contains(behavior)) {
            parallelExecuting.add(behavior);
        }
    }
}
