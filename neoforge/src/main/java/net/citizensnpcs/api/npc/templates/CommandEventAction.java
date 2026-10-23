package net.citizensnpcs.api.npc.templates;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

import net.citizensnpcs.api.event.NPCEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Runs a template's command list every time one particular event fires for an NPC the template was applied to.
 * <p>
 * Upstream has to reach into Bukkit's internals for this — reflect out the event class's {@code HandlerList}, build a
 * synthetic {@code RegisteredListener}, and pull the NPC back off the event through a {@code MethodHandle}, because the
 * event class is only known at runtime. NeoForge's bus takes the class directly, so the whole construction collapses into
 * one {@code addListener} call and {@code getNPC()} is a plain virtual call.
 * <p>
 * One listener is registered per template action. UUID subscriptions are scoped to registry identity so independent
 * registries may contain the same UUID without sharing commands.
 */
public class CommandEventAction implements Consumer<NPC>, AutoCloseable {
    private static final Set<CommandEventAction> LIVE = new java.util.LinkedHashSet<>();
    private static final java.util.Map<NPCRegistry, RegistryTransfer> TRANSFERS = new java.util.IdentityHashMap<>();
    private static volatile boolean accepting = true;
    private final java.util.Map<NPCRegistry, Set<UUID>> targets = new com.google.common.collect.MapMaker()
            .weakKeys().makeMap();
    private final Runnable register;
    private final Runnable unregister;
    private boolean registered;
    private boolean closed;
    private net.minecraft.resources.ResourceLocation templateKey;
    private final Class<? extends NPCEvent> eventClass;
    private final java.util.List<String> savedCommands;

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
        eventClass = clazz;
        savedCommands = commands instanceof CommandListExecutor executor ? executor.getCommands() : null;
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
        NPCRegistry registry = java.util.Objects.requireNonNull(npc.getOwningRegistry(), "NPC registry");
        synchronized (LIVE) {
            if (!accepting) throw new IllegalStateException("Template event actions are unavailable during shutdown");
            if (!registered) {
                register.run();
                registered = true;
                LIVE.add(this);
            }
            targets.computeIfAbsent(registry, unused -> new HashSet<>()).add(uuid);
        }
    }

    private synchronized boolean accepts(NPC npc) {
        return accepting && hasTarget(npc);
    }

    private synchronized boolean hasTarget(NPC npc) {
        Set<UUID> uuids = targets.get(npc.getOwningRegistry());
        return !closed && uuids != null && uuids.contains(npc.getUniqueId());
    }

    synchronized void bindTemplate(net.minecraft.resources.ResourceLocation key) {
        if (templateKey != null && !templateKey.equals(key))
            throw new IllegalArgumentException("Event action already belongs to another template");
        templateKey = key;
    }

    /** Publish event bindings only after the template's other actions have succeeded. */
    static void replaceSubscriptions(net.minecraft.resources.ResourceLocation key, NPC npc,
            java.util.List<CommandEventAction> replacements) {
        replaceSubscriptions(java.util.Map.of(key, replacements), npc);
    }

    /** Register the complete replacement before retiring any previous template bindings. */
    static void replaceSubscriptions(
            java.util.Map<net.minecraft.resources.ResourceLocation, java.util.List<CommandEventAction>> replacements,
            NPC npc) {
        var previous = new java.util.ArrayList<CommandEventAction>();
        for (CommandEventAction action : liveActions()) {
            if (action.templateKey != null && replacements.containsKey(action.templateKey) && action.accepts(npc))
                previous.add(action);
        }
        var replacementActions = replacements.values().stream().flatMap(java.util.Collection::stream).toList();
        var added = new java.util.ArrayList<CommandEventAction>();
        try {
            for (CommandEventAction action : replacementActions) {
                boolean alreadyApplied = action.accepts(npc);
                action.accept(npc);
                if (!alreadyApplied) added.add(action);
            }
        } catch (RuntimeException | Error failure) {
            for (CommandEventAction action : added) {
                try { action.removeTarget(npc); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        synchronized (LIVE) {
            RegistryTransfer transfer = TRANSFERS.get(npc.getOwningRegistry());
            if (transfer != null) replacements.keySet().forEach(key ->
                    transfer.reapplied.add(new TemplateTarget(key, npc.getUniqueId())));
        }
        Throwable failure = null;
        for (CommandEventAction action : previous) {
            if (replacementActions.contains(action)) continue;
            try { action.removeTarget(npc); }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
    }

    static java.util.Map<net.minecraft.resources.ResourceLocation, java.util.Map<String, java.util.List<String>>>
            snapshotSubscriptions(NPC npc) {
        var result = new java.util.TreeMap<net.minecraft.resources.ResourceLocation,
                java.util.Map<String, java.util.List<String>>>();
        for (CommandEventAction action : liveActions()) {
            synchronized (action) {
                String event = TemplateEventBindings.eventName(action.eventClass);
                if (action.templateKey == null || action.savedCommands == null || event == null || !action.hasTarget(npc))
                    continue;
                var commands = result.computeIfAbsent(action.templateKey, unused -> new java.util.TreeMap<>());
                commands.computeIfAbsent(event, unused -> new java.util.ArrayList<>()).addAll(action.savedCommands);
            }
        }
        return result;
    }

    private synchronized void removeTarget(NPC npc) {
        NPCRegistry registry = npc.getOwningRegistry();
        Set<UUID> uuids = targets.get(registry);
        if (uuids != null) {
            uuids.remove(npc.getUniqueId());
            if (uuids.isEmpty()) targets.remove(registry);
        }
        if (targets.isEmpty() && registered) {
            unregister.run();
            registered = false;
            synchronized (LIVE) { LIVE.remove(this); }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        targets.clear();
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

    /** Stop command dispatch and new subscriptions while keeping applied definitions available for the shutdown save. */
    public static void beginShutdown() {
        accepting = false;
    }

    /** Begin a new server lifetime; old action objects remain closed. */
    public static void start() {
        shutdown();
        accepting = true;
    }

    /** Copy subscription ownership to a fresh candidate while preserving rollback to the original registry. */
    public static RegistryTransfer prepareRegistryTransfer(NPCRegistry previous, NPCRegistry candidate) {
        if (previous == candidate) throw new IllegalArgumentException("Registry candidate must be distinct");
        var transfer = new RegistryTransfer(previous, candidate);
        synchronized (LIVE) {
            if (TRANSFERS.containsKey(candidate)) throw new IllegalStateException("Registry already has a subscription transfer");
            TRANSFERS.put(candidate, transfer);
        }
        try { transfer.copy(); }
        catch (RuntimeException | Error failure) {
            transfer.close();
            throw failure;
        }
        return transfer;
    }

    private record TemplateTarget(net.minecraft.resources.ResourceLocation key, UUID uuid) { }

    public static final class RegistryTransfer implements AutoCloseable {
        private final NPCRegistry previous, candidate;
        private boolean finished;
        private final Set<TemplateTarget> reapplied = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private RegistryTransfer(NPCRegistry previous, NPCRegistry candidate) {
            this.previous = java.util.Objects.requireNonNull(previous);
            this.candidate = java.util.Objects.requireNonNull(candidate);
        }

        private void copy() {
            for (CommandEventAction action : liveActions()) {
                synchronized (action) {
                    Set<UUID> old = action.targets.get(previous);
                    if (!action.closed && old != null) {
                        for (UUID uuid : old) {
                            if (action.templateKey == null || !reapplied.contains(new TemplateTarget(action.templateKey, uuid)))
                                action.targets.computeIfAbsent(candidate, unused -> new HashSet<>()).add(uuid);
                        }
                    }
                }
            }
        }

        public void commit() {
            if (finished) throw new IllegalStateException("Template subscription transfer is finished");
            copy();
            remove(previous);
            finished = true;
            synchronized (LIVE) { TRANSFERS.remove(candidate, this); }
        }

        @Override public void close() {
            if (!finished) {
                remove(candidate);
                finished = true;
                synchronized (LIVE) { TRANSFERS.remove(candidate, this); }
            }
        }

        private static void remove(NPCRegistry registry) {
            for (CommandEventAction action : liveActions()) {
                synchronized (action) { action.targets.remove(registry); }
            }
        }
    }

    private static java.util.List<CommandEventAction> liveActions() {
        synchronized (LIVE) { return java.util.List.copyOf(LIVE); }
    }
}
