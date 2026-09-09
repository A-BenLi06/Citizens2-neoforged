package net.yuuniverse.interactions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Validate every action before executing any; execution exceptions stop the remaining actions. */
final class ActionBatch {
    private ActionBatch() {
    }

    static void run(List<String> actions, Function<String, Runnable> prepare) {
        List<Runnable> ready = new ArrayList<>(actions.size());
        for (String action : actions)
            ready.add(prepare.apply(action));
        for (Runnable action : ready)
            action.run();
    }
}
