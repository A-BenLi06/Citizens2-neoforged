package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.*;

class CooldownBehaviorTest {
    private static Behavior cooldown(ExpressionRegistry expressions, Memory memory, String duration) {
        var context = new BehaviorRegistry.BehaviorContext(null, new ExpressionScope(), expressions, memory);
        context.setArgs(new String[]{"gate", duration});
        return new BehaviorRegistry(expressions).createBehavior("cooldown", null, context);
    }

    @Test void firstUseIsAllowedEvenForDurationsLongerThanTheEpochAge() {
        var memory = new Memory(); var behavior = cooldown(new ExpressionRegistry(), memory, "100000d");
        assertEquals(BehaviorStatus.SUCCESS, behavior.run());
        double first = memory.getNumber("cooldown.gate");
        assertTrue(first > 0);
        assertEquals(BehaviorStatus.FAILURE, behavior.run());
        assertEquals(first, memory.getNumber("cooldown.gate"));
    }

    @Test void dynamicNumericMillisecondsCanBeReevaluated() {
        var memory = new Memory(); var duration = new AtomicReference<Object>(86400000D);
        var expressions = expressions(duration); var behavior = cooldown(expressions, memory, "`duration`");
        assertEquals(BehaviorStatus.SUCCESS, behavior.run());
        assertEquals(BehaviorStatus.FAILURE, behavior.run());
        duration.set(0D);
        assertEquals(BehaviorStatus.SUCCESS, behavior.run());
    }

    @Test void invalidDurationsDoNotWriteCooldownState() {
        var duration = new AtomicReference<Object>(); var memory = new Memory();
        var behavior = cooldown(expressions(duration), memory, "`duration`");
        for (Object invalid : new Object[]{Double.NaN, Double.POSITIVE_INFINITY, -1D, 0.5D,
                new BigDecimal("9223372036854775808"), "-0.0001s", "invalid", true}) {
            duration.set(invalid);
            assertThrows(RuntimeException.class, behavior::run);
            assertFalse(memory.has("cooldown.gate"));
        }
    }

    private static ExpressionRegistry expressions(AtomicReference<Object> value) {
        var expressions = new ExpressionRegistry();
        expressions.registerEngine(new ExpressionEngine() {
            public String getName() { return "cooldownprobe"; }
            public CompiledExpression compile(String source) { return scope -> value.get(); }
        });
        expressions.setDefaultEngine("cooldownprobe");
        return expressions;
    }
}
