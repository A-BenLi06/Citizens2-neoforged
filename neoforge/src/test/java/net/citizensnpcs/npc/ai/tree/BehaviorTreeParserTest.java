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
    @Test public void deferredChildParsingKeepsItsOriginalStrictness() {
        var registry = new CitizensBehaviorRegistry(new ExpressionRegistry());
        registry.registerBehavior("deferred", (params, context) -> (net.citizensnpcs.api.ai.tree.InstantBehavior) () -> {
            context.parseChildren(params); return BehaviorStatus.SUCCESS;
        });
        var parser = new BehaviorTreeParser(registry);
        DataKey root = new MemoryDataKey(); root.setRaw("deferred", map("0", "missing_deferred_child"));
        Behavior strict = parser.parseStrict(root.getRelative("deferred"), null, new ExpressionScope(), new Memory());
        Behavior compatible = parser.parse(root.getRelative("deferred"), null, new ExpressionScope(), new Memory());
        assertEquals(BehaviorStatus.SUCCESS, compatible.run());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, strict::run);
    }

    @Test public void nestedCompatibilityParsingDoesNotInheritAnOuterStrictCall() {
        var registry = new CitizensBehaviorRegistry(new ExpressionRegistry());
        var parser = new BehaviorTreeParser(registry);
        var nestedCalls = new java.util.concurrent.atomic.AtomicInteger();
        registry.registerBehavior("nested_parse", (params, context) -> {
            DataKey nested = new MemoryDataKey(); nested.setString("step", "missing_compatibility_child");
            parser.parse(nested.getRelative("step"), null, new ExpressionScope(), new Memory());
            nestedCalls.incrementAndGet();
            return net.citizensnpcs.api.ai.tree.Sequence.createSequence(context.parseChildren(params));
        });
        DataKey root = new MemoryDataKey(); root.setRaw("nested_parse", map("0", "missing_strict_child"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> parser.parseStrict(root.getRelative("nested_parse"), null, new ExpressionScope(), new Memory()));
        assertEquals(1, nestedCalls.get());
    }
    @Test public void retainedFactoryContextsKeepTheirOwnSiblingArguments() {
        var calls = new java.util.ArrayList<String>();
        var registry = new CitizensBehaviorRegistry(new ExpressionRegistry());
        registry.registerBehavior("capture", (params, context) -> (net.citizensnpcs.api.ai.tree.InstantBehavior) () -> {
            calls.add(context.getArgs() == null ? "none" : context.getArgs()[0]);
            return BehaviorStatus.SUCCESS;
        });
        DataKey root = new MemoryDataKey();
        root.setRaw("sequence", map("0", "capture first", "1", "capture second", "2", "capture"));
        finish(new BehaviorTreeParser(registry).parseStrict(root.getRelative("sequence"), null, new ExpressionScope(), new Memory()));
        assertEquals(java.util.List.of("first", "second", "none"), calls);
    }

    @Test public void parsingChildrenDoesNotOverwriteParentFactoryArguments() {
        var calls = new java.util.ArrayList<String>(); Memory memory = new Memory();
        var registry = new CitizensBehaviorRegistry(new ExpressionRegistry());
        registry.registerBehavior("capture", (params, context) -> (net.citizensnpcs.api.ai.tree.InstantBehavior) () -> {
            org.junit.jupiter.api.Assertions.assertSame(memory, context.getMemory());
            calls.add(context.getArgs()[0]); return BehaviorStatus.SUCCESS;
        });
        registry.registerBehavior("parent", (params, context) -> {
            var children = new java.util.ArrayList<Behavior>(context.parseChildren(params));
            net.citizensnpcs.api.ai.tree.InstantBehavior record = () -> {
                org.junit.jupiter.api.Assertions.assertSame(memory, context.getMemory());
                calls.add(context.getArgs()[0]); return BehaviorStatus.SUCCESS;
            };
            children.add(0, record); children.add(record);
            return net.citizensnpcs.api.ai.tree.Sequence.createSequence(children);
        });
        DataKey root = new MemoryDataKey(); root.setRaw("parent outer", map("0", "capture child"));
        finish(new BehaviorTreeParser(registry).parseStrict(root.getRelative("parent outer"), null, new ExpressionScope(), memory));
        assertEquals(java.util.List.of("outer", "child", "outer"), calls);
    }
    @Test public void strictExpressionLeavesRejectMissingEnginesAndBrokenWrappers() {
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(new ExpressionRegistry()));
        for (String expression : java.util.List.of("`1`", "`unfinished", "unknown`1`")) {
            DataKey root = new MemoryDataKey(); root.setString("step", expression);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> parser.parseStrict(root.getRelative("step"), null, new ExpressionScope(), new Memory()));
        }
    }

    @Test public void strictExpressionLeavesRetainCompilerFailure() {
        var expressions = new ExpressionRegistry();
        var failure = new net.citizensnpcs.api.expr.ExpressionEngine.ExpressionCompileException("bad expression");
        expressions.registerEngine(new net.citizensnpcs.api.expr.ExpressionEngine() {
            public String getName() { return "rejecting"; }
            public net.citizensnpcs.api.expr.CompiledExpression compile(String source)
                    throws net.citizensnpcs.api.expr.ExpressionEngine.ExpressionCompileException { throw failure; }
        });
        expressions.setDefaultEngine("rejecting");
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(expressions));
        DataKey root = new MemoryDataKey(); root.setString("step", "`invalid`");
        var actual = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> parser.parseStrict(root.getRelative("step"), null, new ExpressionScope(), new Memory()));
        org.junit.jupiter.api.Assertions.assertSame(failure, actual.getCause());
    }

    @Test public void strictPrefixedExpressionLeavesCompileWithoutExecutingUntilRun() {
        var expressions = new ExpressionRegistry(); expressions.registerEngine(new MolangEngine());
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(expressions));
        Memory memory = new Memory();
        DataKey root = new MemoryDataKey(); root.setString("step", "molang`mem.set('direct', 9)`");
        Behavior behavior = parser.parseStrict(root.getRelative("step"), null, new ExpressionScope(), memory);
        assertEquals(0D, memory.getNumber("direct"));
        assertEquals(BehaviorStatus.SUCCESS, behavior.run());
        assertEquals(9D, memory.getNumber("direct"));
    }
    @Test public void quotedEmptyArgumentsRemainExplicitValues() {
        Memory memory = new Memory(); memory.set("empty", "old"); memory.set("single", "old");
        finish(parse(map("sequence", map("0", "set empty \"\"", "1", "set single ''",
                "2", "set text \"A \\\"quoted\\\" value\"")), memory));
        assertEquals("", memory.getString("empty"));
        assertEquals("", memory.getString("single"));
        assertEquals("A \"quoted\" value", memory.getString("text"));
    }

    @Test public void strictArgumentsRejectUnfinishedSyntaxBeforeFactoryInvocation() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var registry = new CitizensBehaviorRegistry(new ExpressionRegistry());
        registry.registerBehavior("capture", (params, context) -> {
            calls.incrementAndGet(); return (net.citizensnpcs.api.ai.tree.InstantBehavior) () -> BehaviorStatus.SUCCESS;
        });
        var parser = new BehaviorTreeParser(registry);
        for (String input : java.util.List.of("capture \"unfinished", "capture 'unfinished", "capture `unfinished", "capture tail\\")) {
            DataKey root = new MemoryDataKey(); root.setString("step", input);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> parser.parseStrict(root.getRelative("step"), null, new ExpressionScope(), new Memory()));
        }
        assertEquals(0, calls.get());
    }

    @Test public void compatibilityArgumentsPreserveATrailingBackslash() {
        Memory memory = new Memory();
        finish(parse(map("sequence", map("0", "set path tail\\")), memory));
        assertEquals("tail\\", memory.getString("path"));
    }
    @Test public void strictParsingRejectsOrphanElseInRootAndContainerForms() {
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(new ExpressionRegistry()));
        for (Map<String, Object> definition : java.util.List.of(
                map("else", "set wrong `1`"),
                map("sequence", map("0", map("else", "set wrong `1`"))),
                map("sequence", map("else", "set wrong `1`", "0", "succeed")))) {
            DataKey root = new MemoryDataKey(); root.setRaw("tree", definition);
            var failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> parser.parseStrict(root.getRelative("tree").getSubKeys().iterator().next(),
                            null, new ExpressionScope(), new Memory()));
            assertTrue(failure.getMessage().contains("no matching if"));
        }
    }

    @Test public void compatibilityParsingDoesNotExecuteAnOrphanElseBody() {
        for (Map<String, Object> body : java.util.List.of(
                map("0", map("else", "set wrong `1`"), "1", "set after `2`"),
                map("else", "set wrong `1`", "0", "set after `2`"))) {
            Memory memory = new Memory();
            finish(parse(map("sequence", body), memory));
            assertEquals(0D, memory.getNumber("wrong"));
            assertEquals(2D, memory.getNumber("after"));
        }
    }
    @Test public void groupedConditionalKeepsItsElseAndFollowingSibling() {
        for (boolean condition : new boolean[]{false, true}) {
            Memory memory = new Memory();
            finish(parse(map("sequence", map("0", map("if " + condition, "set chosen `1`",
                    "else", "set chosen `2`"), "1", "set after `3`")), memory));
            assertEquals(condition ? 1D : 2D, memory.getNumber("chosen"));
            assertEquals(3D, memory.getNumber("after"));
        }
    }

    @Test public void groupedConditionalRemainsWholeInsideRepeat() {
        Memory memory = new Memory(); memory.set("counter", 0D);
        finish(parse(map("repeat 2", map("0", map("if false", "set wrong `1`",
                "else", "set counter `mem.get('counter') + 1`"))), memory));
        assertEquals(0D, memory.getNumber("wrong"));
        assertEquals(2D, memory.getNumber("counter"));
    }
    @Test public void nestedConditionsWorkInsideEitherOuterBranch() {
        for (boolean outer : new boolean[]{false, true}) {
            Memory memory = new Memory();
            Map<String, Object> nested = map("0", map("if false", "set wrong `1`"),
                    "1", map("else", "set chosen `2`"), "2", "set after `3`");
            Map<String, Object> unused = map("0", "set outer_wrong `1`");
            finish(parse(map("sequence", map("if " + outer, outer ? nested : unused,
                    "else", outer ? unused : nested)), memory));
            assertEquals(0D, memory.getNumber("wrong"));
            assertEquals(0D, memory.getNumber("outer_wrong"));
            assertEquals(2D, memory.getNumber("chosen"));
            assertEquals(3D, memory.getNumber("after"));
        }
    }

    @Test public void nestedRepeatAndConditionalSiblingsRetainTheirStructure() {
        Memory memory = new Memory(); memory.set("counter", 0D);
        finish(parse(map("sequence", map("if true", map(
                "0", map("repeat 2", map("0", map("if true", "set counter `mem.get('counter') + 1`"))),
                "1", map("if true", "set selected `1`"), "2", "set after `2`"))), memory));
        assertEquals(2D, memory.getNumber("counter"));
        assertEquals(1D, memory.getNumber("selected"));
        assertEquals(2D, memory.getNumber("after"));
    }
    @Test public void repeatRunsItsConfiguredBodyTheRequestedNumberOfTimes() {
        Memory memory = new Memory(); memory.set("counter", 0D);
        finish(parse(map("repeat 3", map("0", "set counter `mem.get('counter') + 1`")), memory));
        assertEquals(3D, memory.getNumber("counter"));
    }

    @Test public void repeatSupportsDynamicNamedCountsAndZeroIterations() {
        Memory memory = new Memory(); memory.set("counter", 0D); memory.set("iterations", 2D);
        Behavior tree = parse(map("repeat", map("count", "`mem.get('iterations')`",
                "0", "set counter `mem.get('counter') + 1`")), memory);
        finish(tree); assertEquals(2D, memory.getNumber("counter"));
        memory.set("iterations", 0D); tree.reset(); finish(tree);
        assertEquals(2D, memory.getNumber("counter"));
    }

    @Test public void repeatRejectsInvalidLiteralCountsAndUnknownChildren() {
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(new ExpressionRegistry()));
        for (String count : java.util.List.of("-1", "0.5", "2147483648", "NaN")) {
            DataKey root = new MemoryDataKey(); root.setRaw("repeat " + count, map("0", "succeed"));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> parser.parseStrict(root.getRelative("repeat " + count), null, new ExpressionScope(), new Memory()));
        }
        DataKey root = new MemoryDataKey(); root.setRaw("repeat 2", map("0", "unknown_repeat_child"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> parser.parseStrict(root.getRelative("repeat 2"), null, new ExpressionScope(), new Memory()));
    }
    private static void finish(Behavior tree) {
        assertTrue(tree.shouldExecute());
        for (int i = 0; i < 20; i++) {
            BehaviorStatus status = tree.run();
            if (status == BehaviorStatus.SUCCESS) return;
            assertEquals(BehaviorStatus.RUNNING, status);
        }
        org.junit.jupiter.api.Assertions.fail("Tree did not finish");
    }

    @Test public void conditionalWithoutElseKeepsTheFollowingSibling() {
        Memory memory = new Memory();
        Behavior tree = parse(map("sequence", map("0", map("if true", "set first `1`"),
                "1", "set after `2`")), memory);
        finish(tree);
        assertEquals(1D, memory.getNumber("first"));
        assertEquals(2D, memory.getNumber("after"));
    }

    @Test public void consecutiveConditionalsKeepBothBranchesAndTheFollowingNode() {
        Memory memory = new Memory();
        Behavior tree = parse(map("sequence", map("0", map("if true", "set first `1`"),
                "1", map("if true", "set second `2`"), "2", "set after `3`")), memory);
        finish(tree);
        assertEquals(1D, memory.getNumber("first"));
        assertEquals(2D, memory.getNumber("second"));
        assertEquals(3D, memory.getNumber("after"));
    }

    @Test public void elseIsConsumedOnlyAsItsConditionalsAlternate() {
        Memory memory = new Memory();
        Behavior tree = parse(map("sequence", map("0", map("if false", "set wrong `1`"),
                "1", map("else", "set selected `2`"), "2", "set after `3`")), memory);
        finish(tree);
        assertEquals(0D, memory.getNumber("wrong"));
        assertEquals(2D, memory.getNumber("selected"));
        assertEquals(3D, memory.getNumber("after"));
    }
    @Test public void parsedWaitUsesItsShortFormMolangDuration() {
        Memory memory = new Memory();
        Behavior tree = parse(map("sequence", map("0", "wait `2`", "1", "set waited `1`")), memory);
        assertTrue(tree.shouldExecute());
        assertEquals(BehaviorStatus.RUNNING, tree.run());
        assertEquals(0D, memory.getNumber("waited"));
        for (int i = 0; i < 3; i++) tree.run();
        assertEquals(1D, memory.getNumber("waited"));
    }
    @Test
    public void strictTimeoutsRejectInvalidLiteralTickCounts() {
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(new ExpressionRegistry()));
        for (String value : java.util.List.of("-1", "0.5", "2147483648", "NaN", "Infinity", "not-a-number")) {
            DataKey root = new MemoryDataKey();
            String name = "timeout " + value;
            root.setRaw("", map(name, map("0", "succeed")));
            DataKey timeout = root.getSubKeys().iterator().next();
            var failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> parser.parseStrict(timeout, null, new ExpressionScope(), new Memory()));
            assertTrue(failure.getMessage().toLowerCase().contains("timeout"));
        }
    }

    @Test
    public void strictTimeoutsAllowZeroAndOrdinaryIntegerBudgets() {
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(new ExpressionRegistry()));
        DataKey root = new MemoryDataKey();
        root.setRaw("timeout 0", map("0", "succeed"));
        assertEquals(BehaviorStatus.FAILURE,
                parser.parseStrict(root.getRelative("timeout 0"), null, new ExpressionScope(), new Memory()).run());
        root.setRaw("timeout 2", map("0", "succeed"));
        assertEquals(BehaviorStatus.SUCCESS,
                parser.parseStrict(root.getRelative("timeout 2"), null, new ExpressionScope(), new Memory()).run());
    }

    @Test
    public void parsedParallelContainerExecutesItsChildren() {
        Memory memory = new Memory();
        Behavior tree = parse(map("parallel", map("0", "set first `1`", "1", "set second `2`")), memory);
        assertTrue(tree.shouldExecute());
        assertEquals(BehaviorStatus.SUCCESS, tree.run());
        assertEquals(1D, memory.getNumber("first"));
        assertEquals(2D, memory.getNumber("second"));
    }

    @Test
    public void strictParsingAcceptsExplicitEmptyContainers() {
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(new ExpressionRegistry()));
        DataKey root = new MemoryDataKey();
        root.setRaw("sequence", java.util.List.of());
        assertNotNull(parser.parseStrict(root.getRelative("sequence"), null, new ExpressionScope(), new Memory()));
    }

    @Test
    public void strictParsingRejectsUnknownChildrenWithoutChangingCompatibilityMode() {
        ExpressionRegistry expressions = new ExpressionRegistry();
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(expressions));
        DataKey root = new MemoryDataKey();
        root.setRaw("sequence", map("0", "succeed", "1", "missing_behavior_for_test"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> parser.parseStrict(root.getRelative("sequence"), null, new ExpressionScope(), new Memory()));
        assertNotNull(parser.parse(root.getRelative("sequence"), null, new ExpressionScope(), new Memory()));
    }

    @Test
    public void strictParsingRejectsUnavailableExpressionLanguages() {
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(new ExpressionRegistry()));
        DataKey root = new MemoryDataKey();
        root.setRaw("eval missing_language", map("0", "1"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> parser.parseStrict(root.getRelative("eval missing_language"), null, new ExpressionScope(), new Memory()));
    }

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
