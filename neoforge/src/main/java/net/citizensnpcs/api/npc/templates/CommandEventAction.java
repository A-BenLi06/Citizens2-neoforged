package net.citizensnpcs.api.npc.templates;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

import net.citizensnpcs.api.event.NPCEvent;
import net.citizensnpcs.api.npc.NPC;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Runs a template's command list every time one particular event fires for an NPC the template was applied to.
 * <p>
 * Upstream has to reach into Bukkit's internals for this — reflect out the event class's {@code HandlerList}, build a
 * synthetic {@code RegisteredListener}, and pull the NPC back off the event through a {@code MethodHandle}, because the
 * event class is only known at runtime. NeoForge's bus takes the class directly, so the whole construction collapses into
 * one {@code addListener} call and {@code getNPC()} is a plain virtual call.
 * <p>
 * The set of UUIDs is the same design as upstream: one listener is registered per template action, and it filters to the
 * NPCs the template has actually been applied to.
 */
public class CommandEventAction implements Consumer<NPC>, AutoCloseable {
    private static final Set<CommandEventAction> LIVE = new java.util.LinkedHashSet<>();
    private static volatile boolean accepting = true;
    private final Set<UUID> uuids = new HashSet<>();
    private final Runnable register;
    private final Runnable unregister;
    private boolean registered;
    private boolean closed;

    public <T extends NPCEvent> CommandEventAction(Class<T> clazz, Consumer<NPC> commands) {
        this(clazz, commands, (type, listener) -> NeoForge.EVENT_BUS.addListener(type, listener),
                listener -> NeoForge.EVENT_BUS.unregister(listener));
    }

    <T extends NPCEvent> CommandEventAction(Class<T> clazz, Consumer<NPC> commands,
            BiConsumer<Class<T>, Consumer<T>> registrar) {
        this(clazz, commands, registrar, listener -> { });
    }

    <T extends NPCEvent> CommandEventAction(Class<T> clazz, Consumer<NPC> commands,
            BiConsumer<Class<T>, Consumer<T>> registrar, Consumer<Consumer<T>> unregistrar) {
        Consumer<T> listener = event -> {
            // exact class only, so a subclass event does not trigger its parent's template commands as well
            if (event.getClass() != clazz)
                return;
            NPC npc = event.getNPC();
            if (npc != null && accepts(npc)) {
                commands.accept(npc);
            }
        };
        register = () -> registrar.accept(clazz, listener);
        unregister = () -> unregistrar.accept(listener);
    }

    @Override
    public synchronized void accept(NPC npc) {
        if (closed) throw new IllegalStateException("Template event action is closed");
        UUID uuid = npc.getUniqueId();
        synchronized (LIVE) {
            if (!accepting) throw new IllegalStateException("Template event actions are unavailable during shutdown");
            if (!registered) {
                register.run();
                registered = true;
                LIVE.add(this);
            }
            uuids.add(uuid);
        }
    }

    private synchronized boolean accepts(NPC npc) {
        return !closed && uuids.contains(npc.getUniqueId());
    }

    @Override
    public synchronized void close() {
        closed = true;
        uuids.clear();
        if (registered) {
            unregister.run();
            registered = false;
        }
        synchronized (LIVE) { LIVE.remove(this); }
    }

    /** Includes actions from superseded template registries, which still own active event subscriptions. */
    public static void shutdown() {
        java.util.List<CommandEventAction> retiring;
        synchronized (LIVE) {
            accepting = false;
            retiring = java.util.List.copyOf(LIVE);
        }
        Throwable failure = null;
        for (CommandEventAction action : retiring) {
            try { action.close(); }
            catch (RuntimeException | Error ex) {
                if (failure == null) failure = ex;
                else if (failure != ex) failure.addSuppressed(ex);
            }
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
    }

    /** Begin a new server lifetime; old action objects remain closed. */
    public static void start() {
        shutdown();
        accepting = true;
    }
}
