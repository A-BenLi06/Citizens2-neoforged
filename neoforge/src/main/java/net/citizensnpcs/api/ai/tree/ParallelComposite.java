package net.citizensnpcs.api.ai.tree;

import java.util.Collection;

/**
 * A composite that runs all parallel children. Returns SUCCESS when all parallel children complete. Individual child
 * status doesn't affect the composite's return value.
 */
public class ParallelComposite extends Composite {
    private boolean started;
    public ParallelComposite(Collection<Behavior> behaviors) {
        super(behaviors);
    }

    @Override
    public BehaviorStatus run() {
        boolean admit = !started;
        started = true;
        tickParallel(admit);
        return parallelExecuting.isEmpty() ? BehaviorStatus.SUCCESS : BehaviorStatus.RUNNING;
    }

    @Override
    public void reset() {
        started = false;
        super.reset();
    }

    @Override
    public boolean shouldExecute() {
        return true;
    }
}
