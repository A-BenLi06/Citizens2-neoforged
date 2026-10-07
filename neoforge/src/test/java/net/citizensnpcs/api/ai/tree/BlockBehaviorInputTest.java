package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.*;
import net.citizensnpcs.api.npc.NPC;
import net.minecraft.core.BlockPos;

class BlockBehaviorInputTest {
    @Test void blockCoordinatesUseFlooringIncludingNegativeFractions() {
        assertEquals(new BlockPos(-1, -61, 4), BehaviorRegistry.blockPosition(-0.1, -60.5, 4.9));
        assertEquals(new BlockPos(Integer.MIN_VALUE, 0, Integer.MAX_VALUE),
                BehaviorRegistry.blockPosition(Integer.MIN_VALUE, 0, (double) Integer.MAX_VALUE + 0.5));
    }

    @Test void nonFiniteAndOutOfRangeCoordinatesCannotSelectABlock() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                (double) Integer.MAX_VALUE + 1, (double) Integer.MIN_VALUE - 0.1}) {
            assertNull(BehaviorRegistry.blockPosition(value, 0, 0));
            assertNull(BehaviorRegistry.blockPosition(0, value, 0));
            assertNull(BehaviorRegistry.blockPosition(0, 0, value));
        }
    }

    @Test void invalidCoordinatesAndBothRadiusFormsFailBeforeWorldAccess() {
        NPC npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                (proxy, method, args) -> { if (method.getName().equals("isSpawned")) return true; throw new AssertionError(method.getName()); });
        for (String[] args : new String[][]{{"NaN","0","0"}, {"0","Infinity","0"},
                {"0","0","2147483648"}, {"0","0","0","-1"}, {"0","0","0","radius=NaN"}}) {
            var expressions = new ExpressionRegistry();
            var context = new BehaviorRegistry.BehaviorContext(npc, new ExpressionScope(), expressions, new Memory());
            context.setArgs(args);
            Behavior breaker = new BehaviorRegistry(expressions).createBehavior("break_block", null, context);
            assertEquals(BehaviorStatus.FAILURE, breaker.run());
        }
    }
}
