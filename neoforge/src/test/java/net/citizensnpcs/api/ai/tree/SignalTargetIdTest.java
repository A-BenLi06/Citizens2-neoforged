package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.*;

class SignalTargetIdTest {
    private static Number[] invalidIds() {
        return new Number[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.5D,
                2147483648L, -2147483649L, Long.MAX_VALUE, new BigDecimal("1.0000000000000000000001")};
    }

    @Test void numericTargetsCannotTruncateOverflowOrLoseDecimalPrecision() {
        for (Number value : invalidIds()) assertNull(BehaviorRegistry.signalTargetId(value), value.toString());
    }

    @Test void integralValuesAndIntegerBoundariesRemainSupported() {
        assertEquals(0, BehaviorRegistry.signalTargetId(0));
        assertEquals(7, BehaviorRegistry.signalTargetId(7D));
        assertEquals(7, BehaviorRegistry.signalTargetId(new BigDecimal("7.000")));
        assertEquals(Integer.MIN_VALUE, BehaviorRegistry.signalTargetId((long) Integer.MIN_VALUE));
        assertEquals(Integer.MAX_VALUE, BehaviorRegistry.signalTargetId((long) Integer.MAX_VALUE));
    }

    @Test void invalidDynamicTargetsFailBeforeAnyNpcLookup() {
        var value = new AtomicReference<Number>();
        var expressions = new ExpressionRegistry();
        expressions.registerEngine(new ExpressionEngine() {
            public String getName() { return "targetprobe"; }
            public CompiledExpression compile(String source) { return scope -> value.get(); }
        });
        expressions.setDefaultEngine("targetprobe");
        var context = new BehaviorRegistry.BehaviorContext(null, new ExpressionScope(), expressions, new Memory());
        context.setArgs(new String[]{"`target`", "ready"});
        Behavior emit = new BehaviorRegistry(expressions).createBehavior("emit_signal_to", null, context);
        for (Number invalid : invalidIds()) {
            value.set(invalid);
            assertEquals(BehaviorStatus.FAILURE, emit.run());
        }
    }
}
