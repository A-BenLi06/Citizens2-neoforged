package net.citizensnpcs.api.util.schedulers.adapter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.schedulers.SchedulerAdapter;
import net.citizensnpcs.api.util.schedulers.SchedulerTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * The single {@link SchedulerAdapter} implementation for NeoForge.
 * <p>
 * Synchronous tasks are queued and drained once per server tick — {@link #tick()} must be called from a
 * {@code ServerTickEvent.Post} handler. Asynchronous tasks run on a small daemon thread pool; tick delays are converted
 * at 50ms per tick.
 * <p>
 * Tasks may be submitted from any thread. Newly submitted tasks are staged in a concurrent queue and folded into the
 * active list at the start of the next tick, so a task scheduling another task cannot cause concurrent modification.
 */
public class NeoForgeScheduler implements SchedulerAdapter {
    private final List<NeoForgeSchedulerTask> active = new ArrayList<>();
    private final ScheduledExecutorService async;
    private long currentTick;
    private final Queue<NeoForgeSchedulerTask> incoming = new ConcurrentLinkedQueue<>();
    private final MinecraftServer server;

    public NeoForgeScheduler(MinecraftServer server) {
        this.server = server;
        AtomicInteger counter = new AtomicInteger();
        this.async = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "Citizens Async #" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Cancels all pending tasks and shuts the async pool down. Call on server stop. */
    public void shutdown() {
        active.clear();
        incoming.clear();
        async.shutdownNow();
    }

    @Override
    public boolean isOnOwnerThread() {
        return server.isSameThread();
    }

    private SchedulerTask schedule(Runnable runnable, long delayTicks, long periodTicks) {
        NeoForgeSchedulerTask task = new NeoForgeSchedulerTask(runnable, currentTick + Math.max(0, delayTicks),
                periodTicks);
        incoming.add(task);
        return task;
    }

    private SchedulerTask scheduleAsync(Runnable runnable, long delayTicks, long periodTicks) {
        NeoForgeSchedulerTask task = new NeoForgeSchedulerTask(runnable, 0, periodTicks);
        long delayMs = Math.max(0, delayTicks) * 50L;
        Future<?> future;
        if (periodTicks > 0) {
            future = async.scheduleAtFixedRate(() -> runGuarded(task), delayMs, periodTicks * 50L,
                    TimeUnit.MILLISECONDS);
        } else {
            future = async.schedule(() -> runGuarded(task), delayMs, TimeUnit.MILLISECONDS);
        }
        task.setFuture(future);
        return task;
    }

    private void runGuarded(NeoForgeSchedulerTask task) {
        if (task.isCancelled())
            return;
        try {
            task.getRunnable().run();
        } catch (Throwable t) {
            net.citizensnpcs.Citizens.LOGGER.error("Error in Citizens async task", t);
        }
    }

    /** Drains due tasks. Must be called once per server tick from the main thread. */
    public void tick() {
        currentTick++;
        NeoForgeSchedulerTask staged;
        while ((staged = incoming.poll()) != null) {
            active.add(staged);
        }
        if (active.isEmpty())
            return;

        for (Iterator<NeoForgeSchedulerTask> itr = active.iterator(); itr.hasNext();) {
            NeoForgeSchedulerTask task = itr.next();
            if (task.isCancelled()) {
                itr.remove();
                continue;
            }
            if (task.getNextRunTick() > currentTick) {
                continue;
            }
            try {
                task.getRunnable().run();
            } catch (Throwable t) {
                net.citizensnpcs.Citizens.LOGGER.error("Error in Citizens task", t);
            }
            if (task.isRepeating() && !task.isCancelled()) {
                task.setNextRunTick(currentTick + task.getPeriodTicks());
            } else {
                task.cancel();
                itr.remove();
            }
        }
    }

    @Override
    public SchedulerTask runEntityTask(Entity entity, Runnable runnable) {
        return schedule(runnable, 0, 0);
    }

    @Override
    public SchedulerTask runEntityTaskLater(Entity entity, Runnable runnable, long delayTicks) {
        return schedule(runnable, delayTicks, 0);
    }

    @Override
    public SchedulerTask runEntityTaskTimer(Entity entity, Runnable runnable, long delayTicks, long periodTicks) {
        return schedule(runnable, delayTicks, periodTicks);
    }

    @Override
    public SchedulerTask runRegionTask(Location location, Runnable runnable) {
        return schedule(runnable, 0, 0);
    }

    @Override
    public SchedulerTask runRegionTask(ServerLevel level, int chunkX, int chunkZ, Runnable runnable) {
        return schedule(runnable, 0, 0);
    }

    @Override
    public SchedulerTask runRegionTaskLater(Location location, Runnable runnable, long delayTicks) {
        return schedule(runnable, delayTicks, 0);
    }

    @Override
    public SchedulerTask runRegionTaskLater(ServerLevel level, int chunkX, int chunkZ, Runnable runnable,
            long delayTicks) {
        return schedule(runnable, delayTicks, 0);
    }

    @Override
    public SchedulerTask runRegionTaskTimer(Location location, Runnable runnable, long delayTicks, long periodTicks) {
        return schedule(runnable, delayTicks, periodTicks);
    }

    @Override
    public SchedulerTask runRegionTaskTimer(ServerLevel level, int chunkX, int chunkZ, Runnable runnable,
            long delayTicks, long periodTicks) {
        return schedule(runnable, delayTicks, periodTicks);
    }

    @Override
    public SchedulerTask runTask(Runnable runnable) {
        return schedule(runnable, 0, 0);
    }

    @Override
    public SchedulerTask runTaskAsynchronously(Runnable runnable) {
        return scheduleAsync(runnable, 0, 0);
    }

    @Override
    public SchedulerTask runTaskLater(Runnable runnable, long delayTicks) {
        return schedule(runnable, delayTicks, 0);
    }

    @Override
    public SchedulerTask runTaskLaterAsynchronously(Runnable runnable, long delayTicks) {
        return scheduleAsync(runnable, delayTicks, 0);
    }

    @Override
    public SchedulerTask runTaskTimer(Runnable runnable, long delayTicks, long periodTicks) {
        return schedule(runnable, delayTicks, periodTicks);
    }

    @Override
    public SchedulerTask runTaskTimerAsynchronously(Runnable runnable, long delayTicks, long periodTicks) {
        return scheduleAsync(runnable, delayTicks, periodTicks);
    }

    static class NeoForgeSchedulerTask implements SchedulerTask {
        private volatile boolean cancelled;
        private volatile Future<?> future;
        private long nextRunTick;
        private final long periodTicks;
        private final Runnable runnable;

        NeoForgeSchedulerTask(Runnable runnable, long nextRunTick, long periodTicks) {
            this.runnable = runnable;
            this.nextRunTick = nextRunTick;
            this.periodTicks = periodTicks;
        }

        @Override
        public void cancel() {
            cancelled = true;
            Future<?> f = future;
            if (f != null) {
                f.cancel(false);
            }
        }

        long getNextRunTick() {
            return nextRunTick;
        }

        @Override
        public Object getOriginalTask() {
            return future != null ? future : runnable;
        }

        long getPeriodTicks() {
            return periodTicks;
        }

        Runnable getRunnable() {
            return runnable;
        }

        @Override
        public boolean isCancelled() {
            return cancelled || future instanceof ScheduledFuture && future.isCancelled();
        }

        @Override
        public boolean isRepeating() {
            return periodTicks > 0;
        }

        void setFuture(Future<?> future) {
            this.future = future;
        }

        void setNextRunTick(long tick) {
            this.nextRunTick = tick;
        }
    }
}
