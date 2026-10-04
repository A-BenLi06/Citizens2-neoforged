package net.citizensnpcs.api.util.schedulers.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class NeoForgeSchedulerTest {
    @Test
    void shutdownCancelsActiveAndIncomingHandlesAndRejectsNewWork() {
        NeoForgeScheduler scheduler = new NeoForgeScheduler(null);
        AtomicInteger runs = new AtomicInteger();
        var active = scheduler.runTaskLater(runs::incrementAndGet, 20);
        scheduler.tick();
        var incoming = scheduler.runTask(runs::incrementAndGet);
        var asynchronous = scheduler.runTaskLaterAsynchronously(runs::incrementAndGet, 1200);
        scheduler.shutdown();
        scheduler.shutdown();
        assertTrue(active.isCancelled());
        assertTrue(incoming.isCancelled());
        assertTrue(asynchronous.isCancelled());
        assertThrows(RejectedExecutionException.class, () -> scheduler.runTask(runs::incrementAndGet));
        assertThrows(RejectedExecutionException.class,
                () -> scheduler.runTaskAsynchronously(runs::incrementAndGet));
        scheduler.tick();
        assertEquals(0, runs.get());
    }

    @Test
    void callbackCanStopSchedulerWithoutRunningRemainingTasks() {
        NeoForgeScheduler scheduler = new NeoForgeScheduler(null);
        AtomicInteger runs = new AtomicInteger();
        var stopping = scheduler.runTaskTimer(scheduler::shutdown, 0, 1);
        var later = scheduler.runTask(runs::incrementAndGet);
        assertDoesNotThrow(scheduler::tick);
        assertTrue(stopping.isCancelled());
        assertTrue(later.isCancelled());
        assertEquals(0, runs.get());
    }

    @Test
    void nestedTasksStillWaitUntilNextTick() {
        NeoForgeScheduler scheduler = new NeoForgeScheduler(null);
        try {
            AtomicInteger runs = new AtomicInteger();
            scheduler.runTask(() -> scheduler.runTask(runs::incrementAndGet));
            scheduler.tick();
            assertEquals(0, runs.get());
            scheduler.tick();
            assertEquals(1, runs.get());
        } finally { scheduler.shutdown(); }
    }

    @Test
    void shutdownInterruptsRunningAsynchronousWork() throws Exception {
        NeoForgeScheduler scheduler = new NeoForgeScheduler(null);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var task = scheduler.runTaskAsynchronously(() -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException expected) { interrupted.countDown(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            scheduler.shutdown();
            assertTrue(task.isCancelled());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }
}
