package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.*;
import net.citizensnpcs.api.npc.NPC;

class WaitForSignalTest {
    private static class Signals extends BehaviorSignals {
        final List<SignalListener> active = new ArrayList<>();
        SignalListener latest;
        @Override public void listen(NPC npc, String signal, SignalListener listener) {
            super.listen(npc, signal, listener); active.add(listener); latest = listener;
        }
        @Override public void unlisten(NPC npc, String signal, SignalListener listener) {
            super.unlisten(npc, signal, listener); active.remove(listener);
        }
    }
    private static class Fixture {
        final Signals signals = new Signals();
        final AtomicReference<String> name = new AtomicReference<>("first");
        Runnable duringEvaluation = () -> { };
        final NPC npc;
        final Behavior wait;
        Fixture() {
            UUID uuid = UUID.randomUUID();
            npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                    (proxy, method, args) -> { if (method.getName().equals("getUniqueId")) return uuid; throw new AssertionError(method.getName()); });
            var expressions = new ExpressionRegistry();
            expressions.registerEngine(new ExpressionEngine() {
                public String getName() { return "signalprobe"; }
                public CompiledExpression compile(String source) {
                    return scope -> {
                        String value = name.get();
                        if (value == null) throw new IllegalStateException("evaluation failed");
                        duringEvaluation.run();
                        return value;
                    };
                }
            });
            expressions.setDefaultEngine("signalprobe");
            var context = new BehaviorRegistry.BehaviorContext(npc, new ExpressionScope(), expressions, new Memory());
            context.setArgs(new String[]{"`signal`"});
            wait = new BehaviorRegistry(expressions, signals).createBehavior("wait_for_signal", null, context);
        }
    }

    @Test void repeatedActivationReplacesItsListenerAndResetReleasesIt() {
        var f = new Fixture(); assertTrue(f.wait.shouldExecute()); var old = f.signals.latest;
        f.name.set("second"); assertTrue(f.wait.shouldExecute());
        assertEquals(1, f.signals.active.size());
        f.signals.emit(f.npc, "first"); old.signal();
        assertEquals(BehaviorStatus.RUNNING, f.wait.run());
        f.wait.reset(); assertTrue(f.signals.active.isEmpty());
        f.signals.latest.signal(); assertEquals(BehaviorStatus.RUNNING, f.wait.run());
    }

    @Test void completedActivationCannotCompleteItsSuccessor() {
        var f = new Fixture(); f.wait.shouldExecute(); var old = f.signals.latest;
        f.signals.emit(f.npc, "first"); assertEquals(BehaviorStatus.SUCCESS, f.wait.run());
        assertTrue(f.signals.active.isEmpty());
        f.name.set("second"); f.wait.shouldExecute(); old.signal();
        assertEquals(BehaviorStatus.RUNNING, f.wait.run());
        f.signals.emit(f.npc, "second"); assertEquals(BehaviorStatus.SUCCESS, f.wait.run());
        assertTrue(f.signals.active.isEmpty());
    }

    @Test void evaluationFailureReleasesPreviousListenerAndAllowsRetry() {
        var f = new Fixture(); f.wait.shouldExecute(); var old = f.signals.latest;
        f.name.set(null); assertThrows(IllegalStateException.class, f.wait::shouldExecute);
        assertTrue(f.signals.active.isEmpty()); old.signal();
        assertEquals(BehaviorStatus.RUNNING, f.wait.run());
        f.name.set("repaired"); assertTrue(f.wait.shouldExecute());
        f.signals.emit(f.npc, "repaired"); assertEquals(BehaviorStatus.SUCCESS, f.wait.run());
    }

    @Test void reentrantEvaluationCannotOverwriteTheNewerSubscription() {
        var f = new Fixture();
        f.name.set("outer");
        f.duringEvaluation = () -> {
            f.duringEvaluation = () -> { };
            f.name.set("inner");
            assertTrue(f.wait.shouldExecute());
        };
        assertFalse(f.wait.shouldExecute());
        assertEquals(1, f.signals.active.size());
        f.wait.reset();
        assertTrue(f.signals.active.isEmpty());
    }
}
