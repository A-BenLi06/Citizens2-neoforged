package net.citizensnpcs.api.ai.tree;

/**
 * Allows a child up to the configured number of ticks, accepting completion on the final permitted tick.
 * An unfinished child is reset on timeout; nonpositive budgets fail without starting the child.
 */
public class TimerDecorator implements Behavior {
    private final TimeoutDecorator timer;

    private TimerDecorator(Behavior wrapping, int tickLimit) {
        this.timer = new TimeoutDecorator(wrapping, tickLimit);
    }

    @Override
    public void reset() {
        timer.reset();
    }

    @Override
    public BehaviorStatus run() {
        return timer.run();
    }

    @Override
    public boolean shouldExecute() {
        return timer.shouldExecute();
    }

    public static TimerDecorator tickLimiter(Behavior wrapping, int tickLimit) {
        return new TimerDecorator(wrapping, tickLimit);
    }
}
