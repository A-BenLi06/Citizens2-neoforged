package net.citizensnpcs.npc.ai.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.ai.tree.Behavior;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.expr.ExpressionRegistry;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.api.expr.Memory;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;

/**
 * Parses and runs small behaviour trees. Everything here works without an NPC, which is the point: it checks that the
 * parser, the registry and the Molang expressions in between actually drive a tree, rather than only compiling.
 */
public class BehaviorTreeParserTest {
    private static Behavior parse(Map<String, Object> tree, Memory memory) {
        ExpressionRegistry expressions = new ExpressionRegistry();
        expressions.registerEngine(new MolangEngine());
        CitizensBehaviorRegistry registry = new CitizensBehaviorRegistry(expressions);
        DataKey root = new MemoryDataKey();
        root.setMap("tree", tree);
        DataKey treeKey = root.getRelative("tree").getSubKeys().iterator().next();
        return new BehaviorTreeParser(registry).parse(treeKey, null, new ExpressionScope(), memory);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    public void sequenceRunsEveryChild() {
        Memory memory = new Memory();
        Behavior tree = parse(map("sequence", map("0", "set counter `1`", "1", "set other `2`")), memory);
        assertNotNull(tree);
        assertTrue(tree.shouldExecute());
        BehaviorStatus status = tree.run();
        // a sequence of instant behaviours finishes in one tick
        assertEquals(BehaviorStatus.SUCCESS, status);
        assertEquals(1.0, memory.getNumber("counter", -1), 1e-9);
        assertEquals(2.0, memory.getNumber("other", -1), 1e-9);
    }

    @Test
    public void waitHoldsForItsDuration() {
        Behavior tree = parse(map("sequence", map("0", "wait 2t", "1", "set done `1`")), new Memory());
        assertEquals(BehaviorStatus.RUNNING, tree.run(), "still waiting");
    }

    @Test
    public void selectorReportsItsChildsResult() {
        assertEquals(BehaviorStatus.SUCCESS, parse(map("selector", map("0", "succeed")), new Memory()).run());
        assertEquals(BehaviorStatus.FAILURE, parse(map("selector", map("0", "fail")), new Memory()).run());
    }

    @Test
    public void selectorPicksAChildAtRandom() {
        // "selector" is an alias of "random" in the tree syntax, not the priority selector the term usually means: it
        // picks one child per tick and reports that child's result rather than falling through to the next on failure
        Memory memory = new Memory();
        Behavior tree = parse(map("selector", map("0", "set a `1`", "1", "set b `1`")), memory);
        for (int i = 0; i < 50 && (memory.getNumber("a", 0) == 0 || memory.getNumber("b", 0) == 0); i++) {
            tree.run();
        }
        assertEquals(1.0, memory.getNumber("a", 0), 1e-9, "both children should be reachable");
        assertEquals(1.0, memory.getNumber("b", 0), 1e-9);
    }

    @Test
    public void ifElseChoosesOnAnExpression() {
        Memory memory = new Memory();
        Behavior tree = parse(map("root",
                map("if `1 > 0`", map("0", "set taken `1`"), "else", map("0", "set taken `2`"))), memory);
        // the branch is chosen in shouldExecute, which is what the controller calls before every run
        assertTrue(tree.shouldExecute());
        tree.run();
        assertEquals(1.0, memory.getNumber("taken", -1), 1e-9,
                "the condition is true, so the else branch must not run");
    }

    @Test
    public void ifElseFallsThroughToElse() {
        Memory memory = new Memory();
        Behavior tree = parse(map("root",
                map("if `0 > 1`", map("0", "set taken `1`"), "else", map("0", "set taken `2`"))), memory);
        assertTrue(tree.shouldExecute());
        tree.run();
        assertEquals(2.0, memory.getNumber("taken", -1), 1e-9);
    }

    @Test
    public void memoryIsReadableFromAnExpression() {
        Memory memory = new Memory();
        memory.set("coins", 5);
        Behavior tree = parse(map("sequence", map("0", "set doubled `mem.get('coins') * 2`")), memory);
        tree.run();
        assertEquals(10.0, memory.getNumber("doubled", -1), 1e-9);
    }

    @Test
    public void invertFlipsAFailure() {
        Behavior tree = parse(map("invert", map("0", "fail")), new Memory());
        assertTrue(tree.shouldExecute());
        assertEquals(BehaviorStatus.SUCCESS, tree.run());
    }

    @Test
    public void unknownBehaviourDoesNotBlowUpTheTree() {
        Behavior tree = parse(map("sequence", map("0", "definitely_not_a_behaviour", "1", "succeed")), new Memory());
        // the parser substitutes a no-op for what it cannot build rather than failing the whole tree
        assertNotNull(tree);
        assertTrue(tree.shouldExecute());
        // one child per tick, since a no-op is not an instant behaviour and so is not coalesced with its neighbour
        assertEquals(BehaviorStatus.RUNNING, tree.run());
        assertEquals(BehaviorStatus.SUCCESS, tree.run());
    }
}
