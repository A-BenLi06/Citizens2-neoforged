package net.citizensnpcs.api.ai.tree;

import java.util.function.Supplier;

/**
 * A simple if-then-else {@link Behavior} which will execute a different {@link Behavior} depending on an
 * {@link Condition} function.
 */
public class IfElse implements Behavior {
    private final Supplier<Boolean> condition;
    private Behavior executing;
    private final Behavior ifBehavior, elseBehavior;
    private long revision;

    public IfElse(Supplier<Boolean> condition, Behavior ifBehavior, Behavior elseBehavior) {
        this.condition = condition;
        this.ifBehavior = ifBehavior;
        this.elseBehavior = elseBehavior;
    }

    @Override
    public void reset() {
        revision++;
        Behavior previous = executing;
        executing = null;
        if (previous != null) previous.reset();
    }

    @Override
    public BehaviorStatus run() {
        Behavior current = executing;
        if (current == null) return BehaviorStatus.FAILURE;
        long before = revision;
        BehaviorStatus status = current.run();
        return revision == before && executing == current ? status : BehaviorStatus.RUNNING;
    }

    @Override
    public boolean shouldExecute() {
        long requested = revision + 1;
        reset();
        if (revision != requested) return false;
        boolean cond = condition.get();
        if (revision != requested) return false;
        if (cond) {
            executing = ifBehavior;
        } else {
            executing = elseBehavior;
        }
        Behavior selected = executing;
        if (selected == null) return false;
        boolean ready;
        try { ready = selected.shouldExecute(); }
        catch (RuntimeException | Error failure) {
            if (revision == requested && executing == selected) {
                try { reset(); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        if (revision != requested || executing != selected) return false;
        if (!ready) {
            reset();
            return false;
        }
        return true;
    }

    public static IfElse create(Supplier<Boolean> condition, Behavior ifBehavior, Behavior elseBehavior) {
        return new IfElse(condition, ifBehavior, elseBehavior);
    }
}
