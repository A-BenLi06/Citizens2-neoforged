package net.citizensnpcs.api.util.schedulers;

import net.citizensnpcs.api.util.Location;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * A self-referencing task, so that {@code run()} can cancel itself.
 * <p>
 * Upstream takes a {@code Plugin} as the first argument of every schedule method and branches between Folia and Spigot
 * implementations; on NeoForge there is one scheduler and no plugin instance, so both go away. The {@code retired}
 * callback (invoked by Folia when the owning entity is removed before the task fires) is accepted for call-site
 * fidelity but never invoked — NeoForge has no region scheduler that can retire a task.
 */
public abstract class SchedulerRunnable implements Runnable {
    private SchedulerTask task;

    public void cancel() {
        checkScheduled();
        task.cancel();
    }

    private void checkScheduled() {
        if (task == null)
            throw new IllegalStateException("Task not yet scheduled");
    }

    public SchedulerTask getTask() {
        checkScheduled();
        return task;
    }

    public int getTaskId() {
        checkScheduled();
        return task.getOriginalTask().hashCode();
    }

    public boolean isCancelled() {
        checkScheduled();
        return task.isCancelled();
    }

    @Override
    public abstract void run();

    public SchedulerTask runEntityTask(Entity entity, Runnable retired) {
        return setupTask(Schedulers.get().runEntityTask(entity, this));
    }

    public SchedulerTask runEntityTaskLater(Entity entity, Runnable retired, long delayTicks) {
        return setupTask(Schedulers.get().runEntityTaskLater(entity, this, delayTicks));
    }

    public SchedulerTask runEntityTaskTimer(Entity entity, Runnable retired, long delayTicks, long periodTicks) {
        return setupTask(Schedulers.get().runEntityTaskTimer(entity, this, delayTicks, periodTicks));
    }

    public SchedulerTask runRegionTask(Location location) {
        return setupTask(Schedulers.get().runRegionTask(location, this));
    }

    public SchedulerTask runRegionTask(ServerLevel level, int chunkX, int chunkZ) {
        return setupTask(Schedulers.get().runRegionTask(level, chunkX, chunkZ, this));
    }

    public SchedulerTask runRegionTaskLater(Location location, long delayTicks) {
        return setupTask(Schedulers.get().runRegionTaskLater(location, this, delayTicks));
    }

    public SchedulerTask runRegionTaskLater(ServerLevel level, int chunkX, int chunkZ, long delayTicks) {
        return setupTask(Schedulers.get().runRegionTaskLater(level, chunkX, chunkZ, this, delayTicks));
    }

    public SchedulerTask runRegionTaskTimer(Location location, long delayTicks, long periodTicks) {
        return setupTask(Schedulers.get().runRegionTaskTimer(location, this, delayTicks, periodTicks));
    }

    public SchedulerTask runRegionTaskTimer(ServerLevel level, int chunkX, int chunkZ, long delayTicks,
            long periodTicks) {
        return setupTask(Schedulers.get().runRegionTaskTimer(level, chunkX, chunkZ, this, delayTicks, periodTicks));
    }

    public SchedulerTask runTask() {
        return setupTask(Schedulers.get().runTask(this));
    }

    public SchedulerTask runTaskAsynchronously() {
        return setupTask(Schedulers.get().runTaskAsynchronously(this));
    }

    public SchedulerTask runTaskLater(long delayTicks) {
        return setupTask(Schedulers.get().runTaskLater(this, delayTicks));
    }

    public SchedulerTask runTaskLaterAsynchronously(long delayTicks) {
        return setupTask(Schedulers.get().runTaskLaterAsynchronously(this, delayTicks));
    }

    public SchedulerTask runTaskTimer(long delayTicks, long periodTicks) {
        return setupTask(Schedulers.get().runTaskTimer(this, delayTicks, periodTicks));
    }

    public SchedulerTask runTaskTimerAsynchronously(long delayTicks, long periodTicks) {
        return setupTask(Schedulers.get().runTaskTimerAsynchronously(this, delayTicks, periodTicks));
    }

    protected SchedulerTask setupTask(SchedulerTask task) {
        this.task = task;
        return task;
    }
}
