package net.citizensnpcs.api.ai.tree;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import net.citizensnpcs.api.npc.NPC;

/**
 * Manages signals for behavior trees. Signals allow behavior trees to communicate and wait for events.
 */
public class BehaviorSignals {
    private final Map<UUID, Multimap<String, SignalListener>> listeners = new HashMap<>();

    /**
     * Clears all listeners for a specific NPC.
     *
     * @param npc
     *            the NPC
     */
    public synchronized void clearListeners(NPC npc) {
        listeners.remove(npc.getUniqueId());
    }

    /**
     * Emits a signal for a specific NPC. All behaviors waiting for this signal will be notified.
     *
     * @param npc
     *            the NPC emitting the signal
     * @param signal
     *            the signal name
     */
    public void emit(NPC npc, String signal) {
        emitToNPC(npc.getUniqueId(), signal);
    }

    /**
     * Emits a global signal that all NPCs can receive.
     *
     * @param signal
     *            the signal name
     */
    public void emitGlobal(String signal) {
        List<SignalListener> snapshot = new ArrayList<>();
        synchronized (this) {
            for (Multimap<String, SignalListener> bySignal : listeners.values()) snapshot.addAll(bySignal.get(signal));
        }
        dispatch(snapshot);
    }

    /**
     * Emits a signal to a specific NPC by ID. All behaviors waiting for this signal will be notified.
     *
     * @param npcId
     *            the NPC ID
     * @param signal
     *            the signal name
     */
    public void emitToNPC(UUID npcId, String signal) {
        List<SignalListener> snapshot;
        synchronized (this) {
            Multimap<String, SignalListener> npcListeners = listeners.get(npcId);
            if (npcListeners == null) return;
            snapshot = List.copyOf(npcListeners.get(signal));
        }
        dispatch(snapshot);
    }

    /**
     * Registers a listener for a signal on a specific NPC.
     *
     * @param npc
     *            the NPC to listen on
     * @param signal
     *            the signal name
     * @param listener
     *            the listener to notify
     */
    public synchronized void listen(NPC npc, String signal, SignalListener listener) {
        java.util.Objects.requireNonNull(listener, "Signal listener");
        listeners.computeIfAbsent(npc.getUniqueId(), k -> HashMultimap.create()).put(signal, listener);
    }

    /**
     * Removes a listener.
     *
     * @param npc
     *            the NPC
     * @param signal
     *            the signal name
     * @param listener
     *            the listener to remove
     */
    public synchronized void unlisten(NPC npc, String signal, SignalListener listener) {
        Multimap<String, SignalListener> bySignal = listeners.get(npc.getUniqueId());
        if (bySignal == null)
            return;

        Collection<SignalListener> signals = bySignal.get(signal);
        if (signals != null) {
            signals.remove(listener);
        }
        if (bySignal.isEmpty()) {
            listeners.remove(npc.getUniqueId());
        }
    }

    /** Notifications use the entry snapshot; callback registrations take effect on the next emission. */
    private static void dispatch(List<SignalListener> snapshot) {
        Throwable failure = null;
        for (SignalListener listener : snapshot) {
            try { listener.signal(); }
            catch (RuntimeException | Error ex) {
                if (failure == null) failure = ex;
                else if (failure != ex) failure.addSuppressed(ex);
            }
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
    }

    /**
     * Listener interface for signal notifications.
     */
    @FunctionalInterface
    public interface SignalListener {
        void signal();
    }
}
