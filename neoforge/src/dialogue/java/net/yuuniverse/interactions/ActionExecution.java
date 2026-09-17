package net.yuuniverse.interactions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** An accepted action batch can remain pending across waits; acceptance is not successful completion. */
public final class ActionExecution {
    public enum Result { PENDING, SUCCEEDED, FAILED, CANCELLED }

    private Result result = Result.PENDING;
    private final List<Consumer<Result>> listeners = new ArrayList<>();

    public Result result() { return result; }
    public boolean pending() { return result == Result.PENDING; }
    public boolean accepted() { return pending() || result == Result.SUCCEEDED; }

    /** Called on the server thread, immediately if this batch has already finished. */
    public void whenComplete(Consumer<Result> listener) {
        if (pending()) listeners.add(listener);
        else notifyListener(listener);
    }

    void finish(Result completed) {
        if (!pending()) return;
        if (completed == Result.PENDING) throw new IllegalArgumentException("A completed batch cannot remain pending");
        result = completed;
        var callbacks = List.copyOf(listeners);
        listeners.clear();
        for (var listener : callbacks) notifyListener(listener);
    }

    private void notifyListener(Consumer<Result> listener) {
        try {
            listener.accept(result);
        } catch (RuntimeException failure) {
            // An observer failure cannot prevent another session/batch from receiving cancellation or completion.
            org.slf4j.LoggerFactory.getLogger("interactions").error("Dialogue action completion listener failed", failure);
        }
    }
}
