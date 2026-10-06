package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.npc.NPC;

class BehaviorSignalsTest {
    private static NPC npc() {
        UUID id = UUID.randomUUID();
        return (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                (proxy, method, args) -> { if (method.getName().equals("getUniqueId")) return id; throw new AssertionError(method.getName()); });
    }

    @Test void listenersCanRemoveThemselvesAndAddTheirSuccessor() {
        var signals = new BehaviorSignals(); NPC npc = npc();
        var calls = new AtomicInteger(); var laterCalls = new AtomicInteger();
        BehaviorSignals.SignalListener[] self = new BehaviorSignals.SignalListener[1];
        self[0] = () -> { calls.incrementAndGet(); signals.unlisten(npc, "ready", self[0]); signals.listen(npc, "ready", laterCalls::incrementAndGet); };
        signals.listen(npc, "ready", self[0]);
        signals.emit(npc, "ready");
        assertEquals(1, calls.get()); assertEquals(0, laterCalls.get());
        signals.emit(npc, "ready");
        assertEquals(1, calls.get()); assertEquals(1, laterCalls.get());
    }

    @Test void globalEmissionFreezesAllRecipientsBeforeCallbacks() {
        var signals = new BehaviorSignals(); NPC first = npc(), second = npc();
        var late = new AtomicInteger();
        BehaviorSignals.SignalListener next = late::incrementAndGet;
        signals.listen(first, "ready", () -> signals.listen(second, "ready", next));
        signals.emitGlobal("ready"); assertEquals(0, late.get());
        signals.emitGlobal("ready"); assertEquals(1, late.get());
        signals.clearListeners(second);
        signals.emitToNPC(second.getUniqueId(), "ready"); assertEquals(1, late.get());
    }

    @Test void failingListenerCannotPreventOtherNotifications() {
        var signals = new BehaviorSignals(); NPC npc = npc(); var calls = new AtomicInteger();
        var failure = new IllegalStateException("listener failed");
        signals.listen(npc, "ready", () -> { throw failure; });
        signals.listen(npc, "ready", calls::incrementAndGet);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> signals.emit(npc, "ready")));
        assertEquals(1, calls.get());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> signals.emitGlobal("ready")));
        assertEquals(2, calls.get());
    }
}
