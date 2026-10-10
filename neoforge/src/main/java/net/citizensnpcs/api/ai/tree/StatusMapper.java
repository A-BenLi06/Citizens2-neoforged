package net.citizensnpcs.api.ai.tree;

import java.util.function.Supplier;

/**
 * Runs a wrapped {@link Behavior}, then returns a supplied {@link BehaviorStatus} instead of the child's status.
 */
public class StatusMapper implements Behavior {
    private final Supplier<BehaviorStatus> to;
    private final Behavior wrapping;

    private StatusMapper(Behavior wrapping, Supplier<BehaviorStatus> to) {
        this.wrapping = wrapping;
        this.to = to;
    }

    @Override
    public void reset() {
        wrapping.reset();
    }

    @Override
    public BehaviorStatus run() {
        wrapping.run();
        return to.get();
    }

    @Override
    public boolean shouldExecute() {
        return wrapping.shouldExecute();
    }

    public static StatusMapper mapping(Behavior wrapping, Supplier<BehaviorStatus> to) {
        return new StatusMapper(wrapping, to);
    }

    public static Behavior singleUse(Behavior base) {
        return new Behavior() {
            @Override
            public void reset() {
                base.reset();
            }

            @Override
            public BehaviorStatus run() {
                BehaviorStatus status = base.run();
                switch (status) {
                    case FAILURE:
                    case SUCCESS:
                        return BehaviorStatus.RESET_AND_REMOVE;
                    default:
                        return status;
                }
            }

            @Override
            public boolean shouldExecute() {
                return base.shouldExecute();
            }
        };
    }
}
