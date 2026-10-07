package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.*;
import net.citizensnpcs.api.npc.NPC;

class WanderBehaviorTest {
    @Test void invalidPathfindingOptionCannotReachWorldQueries() {
        NPC npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                (proxy, method, args) -> { if (method.getName().equals("isSpawned")) return true; throw new AssertionError(method.getName()); });
        var expressions = new ExpressionRegistry();
        var context = new BehaviorRegistry.BehaviorContext(npc, new ExpressionScope(), expressions, new Memory());
        context.setArgs(new String[]{"0", "pathfind=invalid"});
        assertFalse(new BehaviorRegistry(expressions).createBehavior("wander", null, context).shouldExecute());
    }
    @Test void invalidRadiusCannotReachNavigationOrWorldQueries() {
        NPC npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                (proxy, method, args) -> { if (method.getName().equals("isSpawned")) return true; throw new AssertionError(method.getName()); });
        for (String radius : new String[]{"NaN", "Infinity", "-1", "0.5", "2147483648", "1073741824", "invalid"}) {
            var expressions = new ExpressionRegistry();
            var context = new BehaviorRegistry.BehaviorContext(npc, new ExpressionScope(), expressions, new Memory());
            context.setArgs(new String[]{radius});
            Behavior wander = new BehaviorRegistry(expressions).createBehavior("wander", null, context);
            assertFalse(wander.shouldExecute(), radius);
            assertEquals(BehaviorStatus.FAILURE, wander.run());
        }
    }
}
