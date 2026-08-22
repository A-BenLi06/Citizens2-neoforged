package net.citizensnpcs.api.util.schedulers;

import java.util.Objects;

/**
 * Static holder for the active {@link SchedulerAdapter}.
 * <p>
 * Set once when the server starts and cleared when it stops. {@code CitizensAPI.getScheduler()} delegates here.
 */
public class Schedulers {
    private Schedulers() {
    }

    public static SchedulerAdapter get() {
        SchedulerAdapter adapter = instance;
        if (adapter == null)
            throw new IllegalStateException("scheduler not available - server is not running");
        return adapter;
    }

    /** @return the active adapter, or null if the server is not running */
    public static SchedulerAdapter getOrNull() {
        return instance;
    }

    public static void set(SchedulerAdapter adapter) {
        instance = Objects.requireNonNull(adapter, "adapter cannot be null");
    }

    public static void unset() {
        instance = null;
    }

    private static volatile SchedulerAdapter instance;
}
