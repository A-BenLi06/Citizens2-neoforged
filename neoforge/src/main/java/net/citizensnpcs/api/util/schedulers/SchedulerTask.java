package net.citizensnpcs.api.util.schedulers;

public interface SchedulerTask {
    void cancel();

    /** The underlying implementation task object. */
    Object getOriginalTask();

    boolean isCancelled();

    boolean isRepeating();
}
