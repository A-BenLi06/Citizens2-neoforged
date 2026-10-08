package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecoratorTest {
    private static class Child implements Behavior {
        final List<String> events;
        Child(List<String> events) { this.events = events; }
        public boolean shouldExecute() { events.add("start"); return true; }
        public BehaviorStatus run() { events.add("run"); return BehaviorStatus.SUCCESS; }
        public void reset() { events.add("reset"); }
    }

    @Test void builtDecoratorsRetainTheirCallbacksWhenTheBuilderIsExtended() {
        var events = new ArrayList<String>();
        var builder = Decorator.wrapping(new Child(events))
                .withPreRunCallback(() -> events.add("before"))
                .withResetCallback(() -> events.add("cleanup"))
                .withShouldExecutePredicate(ready -> ready)
                .withStatusTransformer(status -> status);
        var first = builder.build();
        builder.withPreRunCallback(() -> events.add("later-before"))
                .withResetCallback(() -> events.add("later-cleanup"))
                .withShouldExecutePredicate(ready -> false)
                .withStatusTransformer(status -> BehaviorStatus.FAILURE);
        assertTrue(first.shouldExecute()); assertEquals(BehaviorStatus.SUCCESS, first.run()); first.reset();
        assertEquals(List.of("start", "before", "run", "cleanup", "reset"), events);
        events.clear();
        var second = builder.build();
        assertFalse(second.shouldExecute()); assertEquals(BehaviorStatus.FAILURE, second.run()); second.reset();
        assertEquals(List.of("start", "before", "later-before", "run", "cleanup", "later-cleanup", "reset"), events);
    }

    @Test void builderMutationDuringACallbackDoesNotChangeTheCurrentDecorator() {
        var events = new ArrayList<String>(); var builder = Decorator.wrapping(new Child(events));
        builder.withPreRunCallback(() -> builder.withPreRunCallback(() -> events.add("added")));
        var decorator = builder.build();
        assertEquals(BehaviorStatus.SUCCESS, decorator.run());
        assertEquals(BehaviorStatus.SUCCESS, decorator.run());
        assertEquals(List.of("run", "run"), events);
    }

    @Test void resetInvokesEveryCleanupAndRetainsAllFailuresInOrder() {
        var events = new ArrayList<String>();
        var first = new IllegalStateException("first");
        var second = new AssertionError("second");
        var childFailure = new IllegalArgumentException("child");
        var child = new Child(events) {
            @Override public void reset() { super.reset(); throw childFailure; }
        };
        var decorator = Decorator.wrapping(child)
                .withResetCallback(() -> { events.add("first"); throw first; })
                .withResetCallback(() -> { events.add("second"); throw second; })
                .withResetCallback(() -> events.add("last"))
                .build();
        assertSame(first, assertThrows(IllegalStateException.class, decorator::reset));
        assertArrayEquals(new Throwable[] { second, childFailure }, first.getSuppressed());
        assertEquals(List.of("first", "second", "last", "reset"), events);
    }

    @Test void repeatedExceptionIdentityDoesNotPreventChildCleanup() {
        var events = new ArrayList<String>(); var failure = new AssertionError("shared");
        var decorator = Decorator.wrapping(new Child(events))
                .withResetCallback(() -> { throw failure; })
                .withResetCallback(() -> { throw failure; }).build();
        assertSame(failure, assertThrows(AssertionError.class, decorator::reset));
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(List.of("reset"), events);
    }
}
