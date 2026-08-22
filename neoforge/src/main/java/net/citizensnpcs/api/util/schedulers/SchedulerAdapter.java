package net.citizensnpcs.api.util.schedulers;

import net.citizensnpcs.api.util.Location;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Task scheduling abstraction.
 * <p>
 * Upstream Citizens2 uses this to paper over Folia's region threading vs Spigot's single main thread. NeoForge has no
 * region threading, so every {@code runEntityTask*} / {@code runRegionTask*} variant collapses onto the main server
 * thread. The overloads are kept so that ported call sites read unchanged and so that the distinction survives if a
 * threaded server implementation ever appears.
 */
public interface SchedulerAdapter {
    default void checkedRunEntityTask(Entity entity, Runnable runnable) {
        if (isOnOwnerThread(entity)) {
            runnable.run();
        } else {
            runEntityTask(entity, runnable);
        }
    }

    default void checkedRunRegionTask(Location location, Runnable runnable) {
        if (isOnOwnerThread(location)) {
            runnable.run();
        } else {
            runRegionTask(location, runnable);
        }
    }

    default void checkedRunTask(Runnable runnable) {
        if (isOnOwnerThread()) {
            runnable.run();
        } else {
            runTask(runnable);
        }
    }

    /**
     * @return whether the current thread may safely touch game state — on NeoForge, the main server thread
     */
    boolean isOnOwnerThread();

    default boolean isOnOwnerThread(Entity entity) {
        return isOnOwnerThread();
    }

    default boolean isOnOwnerThread(Location location) {
        return isOnOwnerThread();
    }

    default boolean isOnOwnerThread(ServerLevel level, int chunkX, int chunkZ) {
        return isOnOwnerThread();
    }

    /** Runs on the next tick, on the main server thread. */
    SchedulerTask runEntityTask(Entity entity, Runnable runnable);

    SchedulerTask runEntityTaskLater(Entity entity, Runnable runnable, long delayTicks);

    SchedulerTask runEntityTaskTimer(Entity entity, Runnable runnable, long delayTicks, long periodTicks);

    SchedulerTask runRegionTask(Location location, Runnable runnable);

    SchedulerTask runRegionTask(ServerLevel level, int chunkX, int chunkZ, Runnable runnable);

    SchedulerTask runRegionTaskLater(Location location, Runnable runnable, long delayTicks);

    SchedulerTask runRegionTaskLater(ServerLevel level, int chunkX, int chunkZ, Runnable runnable, long delayTicks);

    SchedulerTask runRegionTaskTimer(Location location, Runnable runnable, long delayTicks, long periodTicks);

    SchedulerTask runRegionTaskTimer(ServerLevel level, int chunkX, int chunkZ, Runnable runnable, long delayTicks,
            long periodTicks);

    /** Runs on the next tick, on the main server thread. */
    SchedulerTask runTask(Runnable runnable);

    /** Runs immediately on a worker thread. */
    SchedulerTask runTaskAsynchronously(Runnable runnable);

    /**
     * @param delayTicks
     *            delay in server ticks (1 tick = 50ms)
     */
    SchedulerTask runTaskLater(Runnable runnable, long delayTicks);

    SchedulerTask runTaskLaterAsynchronously(Runnable runnable, long delayTicks);

    SchedulerTask runTaskTimer(Runnable runnable, long delayTicks, long periodTicks);

    SchedulerTask runTaskTimerAsynchronously(Runnable runnable, long delayTicks, long periodTicks);
}
