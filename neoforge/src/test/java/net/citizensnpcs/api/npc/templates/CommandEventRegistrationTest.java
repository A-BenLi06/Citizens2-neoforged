package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.event.NPCEvent;
import net.citizensnpcs.api.npc.NPC;

class CommandEventRegistrationTest {
    @org.junit.jupiter.api.BeforeEach void start() { CommandEventAction.start(); }
    @org.junit.jupiter.api.AfterEach void cleanup() { CommandEventAction.shutdown(); }
    private static class Event extends NPCEvent { Event(NPC npc) { super(npc); } }
    private static class SubEvent extends Event { SubEvent(NPC npc) { super(npc); } }
    private static NPC npc() {
        UUID uuid = UUID.randomUUID();
        return (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                (proxy, method, args) -> { if (method.getName().equals("getUniqueId")) return uuid; throw new AssertionError(method.getName()); });
    }

    @Test void constructionDoesNotRegisterAndRepeatedApplicationsRegisterOnlyOnce() {
        var registrations = new AtomicInteger(); var calls = new AtomicInteger();
        var listener = new AtomicReference<Consumer<Event>>();
        var action = new CommandEventAction(Event.class, npc -> calls.incrementAndGet(),
                (type, consumer) -> { registrations.incrementAndGet(); listener.set(consumer); });
        assertEquals(0, registrations.get());
        NPC first = npc(), second = npc();
        action.accept(first); action.accept(first); action.accept(second);
        assertEquals(1, registrations.get());
        listener.get().accept(new Event(first)); listener.get().accept(new Event(second));
        listener.get().accept(new Event(npc())); listener.get().accept(new SubEvent(first));
        assertEquals(2, calls.get());
    }

    @Test void rejectedRegistrationCanBeRetried() {
        var attempts = new AtomicInteger(); var calls = new AtomicInteger();
        var listener = new AtomicReference<Consumer<Event>>();
        var action = new CommandEventAction(Event.class, npc -> calls.incrementAndGet(), (type, consumer) -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("registration failure");
            listener.set(consumer);
        });
        NPC target = npc();
        assertThrows(IllegalStateException.class, () -> action.accept(target));
        action.accept(target);
        listener.get().accept(new Event(target));
        assertEquals(2, attempts.get()); assertEquals(1, calls.get());
    }

    @Test void closeUnregistersTheExactListenerAndPreventsReapplication() {
        var listener = new AtomicReference<Consumer<Event>>();
        var removed = new AtomicReference<Consumer<Event>>();
        var calls = new AtomicInteger();
        var action = new CommandEventAction(Event.class, npc -> calls.incrementAndGet(),
                (type, consumer) -> listener.set(consumer), removed::set);
        NPC target = npc(); action.accept(target);
        action.close(); action.close();
        assertSame(listener.get(), removed.get());
        listener.get().accept(new Event(target));
        assertEquals(0, calls.get());
        assertThrows(IllegalStateException.class, () -> action.accept(target));
    }

    @Test void shutdownContinuesAfterUnregistrationFailureAndAllowsRetry() {
        var firstListener = new AtomicReference<Consumer<Event>>();
        var firstAttempts = new AtomicInteger(); var secondRemovals = new AtomicInteger();
        var calls = new AtomicInteger();
        var first = new CommandEventAction(Event.class, npc -> calls.incrementAndGet(),
                (type, consumer) -> firstListener.set(consumer), consumer -> {
                    if (firstAttempts.incrementAndGet() == 1) throw new IllegalStateException("unregister failed");
                });
        var second = new CommandEventAction(Event.class, npc -> calls.incrementAndGet(),
                (type, consumer) -> { }, consumer -> secondRemovals.incrementAndGet());
        NPC target = npc(); first.accept(target); second.accept(target);
        assertThrows(IllegalStateException.class, CommandEventAction::shutdown);
        firstListener.get().accept(new Event(target));
        assertEquals(0, calls.get());
        assertEquals(1, secondRemovals.get());
        CommandEventAction.shutdown();
        assertEquals(2, firstAttempts.get());
        assertEquals(1, secondRemovals.get());
        assertThrows(IllegalStateException.class, () -> second.accept(target));
    }

    @Test void newLifetimeAllowsNewActionsButNeverRevivesOldOnes() {
        var registrations = new AtomicInteger();
        var old = new CommandEventAction(Event.class, npc -> { },
                (type, listener) -> registrations.incrementAndGet());
        NPC target = npc(); old.accept(target);
        CommandEventAction.shutdown();
        var fresh = new CommandEventAction(Event.class, npc -> { },
                (type, listener) -> registrations.incrementAndGet());
        assertThrows(IllegalStateException.class, () -> fresh.accept(target));
        CommandEventAction.start();
        assertThrows(IllegalStateException.class, () -> old.accept(target));
        fresh.accept(target);
        assertEquals(2, registrations.get());
    }
}
