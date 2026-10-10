package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StatusMapperTest {
    @Test void mappingRunsTheChildBeforeSupplyingAnyReplacementStatus() {
        for (var childStatus : BehaviorStatus.values()) {
            for (var mappedStatus : BehaviorStatus.values()) {
                var calls = new ArrayList<String>();
                Behavior child = new Behavior() {
                    public boolean shouldExecute() { calls.add("start"); return true; }
                    public BehaviorStatus run() { calls.add("run"); return childStatus; }
                    public void reset() { calls.add("reset"); }
                };
                var mapped = StatusMapper.mapping(child, () -> { calls.add("map"); return mappedStatus; });
                assertTrue(mapped.shouldExecute());
                assertEquals(mappedStatus, mapped.run());
                mapped.reset();
                assertEquals(List.of("start", "run", "map", "reset"), calls);
            }
        }
    }

    @Test void replacementSupplierObservesEffectsOfEachChildTick() {
        var ticks = new AtomicInteger();
        var mapped = StatusMapper.mapping((InstantBehavior) () -> {
            ticks.incrementAndGet(); return BehaviorStatus.FAILURE;
        }, () -> ticks.get() < 2 ? BehaviorStatus.RUNNING : BehaviorStatus.SUCCESS);
        assertTrue(mapped.shouldExecute());
        assertEquals(BehaviorStatus.RUNNING, mapped.run());
        assertEquals(BehaviorStatus.SUCCESS, mapped.run());
        assertEquals(2, ticks.get());
    }

    @Test void aChildExceptionPreventsMappingAndMappingErrorsDoNotEraseChildEffects() {
        var mappings = new AtomicInteger();
        var childFailure = new IllegalStateException("child");
        var failedChild = StatusMapper.mapping((InstantBehavior) () -> { throw childFailure; }, () -> {
            mappings.incrementAndGet(); return BehaviorStatus.SUCCESS;
        });
        assertSame(childFailure, assertThrows(IllegalStateException.class, failedChild::run));
        assertEquals(0, mappings.get());

        var effects = new AtomicInteger(); var mappingFailure = new IllegalArgumentException("mapping");
        var failedMapping = StatusMapper.mapping((InstantBehavior) () -> {
            effects.incrementAndGet(); return BehaviorStatus.SUCCESS;
        }, () -> { throw mappingFailure; });
        assertSame(mappingFailure, assertThrows(IllegalArgumentException.class, failedMapping::run));
        assertEquals(1, effects.get());
    }
}
