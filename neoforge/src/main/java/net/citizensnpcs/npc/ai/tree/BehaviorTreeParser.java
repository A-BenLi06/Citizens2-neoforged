package net.citizensnpcs.npc.ai.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.server.level.ServerPlayer;

import net.citizensnpcs.api.ai.tree.Behavior;
import net.citizensnpcs.api.ai.tree.BehaviorRegistry;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.ai.tree.CoalescedBehavior;
import net.citizensnpcs.api.ai.tree.IfElse;
import net.citizensnpcs.api.ai.tree.InstantBehavior;
import net.citizensnpcs.api.ai.tree.InverterDecorator;
import net.citizensnpcs.api.ai.tree.Loop;
import net.citizensnpcs.api.ai.tree.ParallelBehaviorWrapper;
import net.citizensnpcs.api.ai.tree.ParallelComposite;
import net.citizensnpcs.api.ai.tree.Selector;
import net.citizensnpcs.api.ai.tree.Sequence;
import net.citizensnpcs.api.ai.tree.TimeoutDecorator;
import net.citizensnpcs.api.expr.CompiledExpression;
import net.citizensnpcs.api.expr.ExpressionEngine;
import net.citizensnpcs.api.expr.ExpressionEngine.ExpressionCompileException;
import net.citizensnpcs.api.expr.ExpressionRegistry;
import net.citizensnpcs.api.expr.ExpressionRegistry.ExpressionValue;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.api.expr.Memory;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Util;

/**
 * Parses DataKey structures into Behavior trees.
 *
 * The eval block allows evaluating expressions without backtick wrappers. It takes the expression language as an
 * argument (e.g., "eval molang:"). If no language is specified, the default engine is used.
 *
 * Lines starting with ` are also auto-evaluated as expressions:
 *
 * <pre>
 *   - `inv.add(item.json('{"material":"DIAMOND","amount":64}'))`
 * </pre>
 */
public class BehaviorTreeParser {
    private final BehaviorRegistry registry;
    private final boolean strict;

    public BehaviorTreeParser(BehaviorRegistry registry) {
        this(registry, false);
    }

    private BehaviorTreeParser(BehaviorRegistry registry, boolean strict) {
        this.registry = registry;
        this.strict = strict;
    }

    private List<Behavior> coalesceInstantBehaviors(List<Behavior> behaviors) {
        List<Behavior> result = new ArrayList<>();
        List<Behavior> instantGroup = new ArrayList<>();

        for (Behavior behavior : behaviors) {
            if (behavior instanceof InstantBehavior) {
                instantGroup.add(behavior);
            } else {
                if (!instantGroup.isEmpty()) {
                    result.add(instantGroup.size() == 1 ? instantGroup.get(0) : new CoalescedBehavior(instantGroup));
                    instantGroup = new ArrayList<>();
                }
                result.add(behavior);
            }
        }
        if (!instantGroup.isEmpty()) {
            result.add(instantGroup.size() == 1 ? instantGroup.get(0) : new CoalescedBehavior(instantGroup));
        }
        return result;
    }

    private Behavior createCommandBehavior(String command, BehaviorRegistry.BehaviorContext context) {
        ExpressionRegistry expressions = registry.getExpressionRegistry();
        ExpressionValue commandHolder = expressions.parseValue(command);

        return new InstantBehavior() {
            @Override
            public BehaviorStatus run() {
                String command = commandHolder.evaluateAsString(context.getScope());
                ExecutionFlags flags = executionFlags(command);
                Util.runCommand(context.getNPC(),
                        context.getNPC().getEntity() instanceof ServerPlayer player ? player : null,
                        command, flags.operator(), flags.player());
                return BehaviorStatus.SUCCESS;
            }

            @Override
            public String toString() {
                return "CommandBehavior[" + command + "]";
            }
        };
    }

    record ExecutionFlags(boolean operator, boolean player) { }

    static ExecutionFlags executionFlags(String command) {
        java.util.Set<String> tokens = new java.util.HashSet<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) {
                token.append(c); escaped = false; continue;
            }
            if (c == '\\') {
                token.append(c); escaped = true; continue;
            }
            if (quote != 0) {
                token.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c; token.append(c); continue;
            }
            if (Character.isWhitespace(c)) {
                if (token.length() > 0) { tokens.add(token.toString()); token.setLength(0); }
            } else token.append(c);
        }
        if (token.length() > 0) tokens.add(token.toString());
        return new ExecutionFlags(tokens.contains("-o"), tokens.contains("-p"));
    }

    /**
     * Creates a behavior that evaluates an expression for its side effects. This allows lines like "-
     * `inv.add(item.create('DIAMOND', 64))`" without needing an explicit "eval" behavior.
     */
    private Behavior createExpressionBehavior(String expression, BehaviorRegistry.BehaviorContext context) {
        ExpressionRegistry exprRegistry = registry.getExpressionRegistry();
        java.util.function.Function<ExpressionScope, Object> evaluate;
        if (strict) {
            try { evaluate = exprRegistry.compile(expression)::evaluate; }
            catch (ExpressionCompileException failure) {
                throw new IllegalArgumentException("Invalid behavior expression: " + expression, failure);
            }
        } else {
            ExpressionValue exprHolder = exprRegistry.parseValue(expression);
            evaluate = exprHolder::evaluate;
        }

        return new InstantBehavior() {
            @Override
            public BehaviorStatus run() {
                evaluate.apply(context.getScope());
                return BehaviorStatus.SUCCESS;
            }

            @Override
            public String toString() {
                return "ExpressionBehavior[" + expression + "]";
            }
        };
    }

    /**
     * Parses a behavior tree from a DataKey.
     */
    public Behavior parse(DataKey root, NPC npc, ExpressionScope scope, Memory blackboard) {
        scope.setMemory(blackboard);
        scope.setNPC(npc);
        BehaviorRegistry.BehaviorContext context = new BehaviorRegistry.BehaviorContext(npc, scope,
                registry.getExpressionRegistry(), blackboard);
        context.setChildParser(key -> parseContainerChildren(key, context));
        return parseNode(root, context);
    }

    public Behavior parseStrict(DataKey root, NPC npc, ExpressionScope scope, Memory blackboard) {
        return new BehaviorTreeParser(registry, true).parse(root, npc, scope, blackboard);
    }

    private Supplier<Boolean> parseCondition(String conditionStr, BehaviorRegistry.BehaviorContext context) {
        ExpressionRegistry exprRegistry = registry.getExpressionRegistry();

        if (exprRegistry.isPossiblyExpression(conditionStr)) {
            try {
                CompiledExpression expr = exprRegistry.compile(conditionStr);
                return () -> expr.evaluateAsBoolean(context.getScope());
            } catch (ExpressionCompileException e) {
                if (strict) throw new IllegalArgumentException("Invalid behavior condition: " + conditionStr, e);
                e.printStackTrace();
                return () -> false;
            }
        }
        if (conditionStr.equalsIgnoreCase("true") || conditionStr.equals("1"))
            return () -> true;

        if (conditionStr.equalsIgnoreCase("false") || conditionStr.equals("0"))
            return () -> false;

        return () -> {
            Object value = context.getScope().get(conditionStr);
            if (value instanceof Boolean)
                return (Boolean) value;

            if (value instanceof Number)
                return ((Number) value).doubleValue() != 0;

            return value != null;
        };
    }

    private Behavior parseContainer(String name, DataKey key, BehaviorRegistry.BehaviorContext context) {
        if (name.startsWith("timeout ")) {
            String timeoutStr = name.substring(8).trim();
            ExpressionRegistry exprRegistry = registry.getExpressionRegistry();
            ExpressionValue timeoutHolder = exprRegistry.parseValue(timeoutStr);
            double evaluated;
            try { evaluated = timeoutHolder.evaluateAsNumber(context.getScope()); }
            catch (RuntimeException failure) {
                if (strict) throw new IllegalArgumentException("Invalid behavior timeout: " + timeoutStr, failure);
                throw failure;
            }
            if (strict && (!Double.isFinite(evaluated) || evaluated < 0 || evaluated > Integer.MAX_VALUE
                    || evaluated != Math.rint(evaluated)))
                throw new IllegalArgumentException("Behavior timeout must be a non-negative integer tick count: " + timeoutStr);
            int ticks = (int) evaluated;

            List<Behavior> children = parseIndexedChildren(key, context);
            Behavior body = children.size() == 1 ? children.get(0)
                    : Sequence.createSequence(coalesceInstantBehaviors(children));
            return new TimeoutDecorator(body, ticks);
        }
        if (name.equalsIgnoreCase("invert")) {
            List<Behavior> children = parseIndexedChildren(key, context);
            Behavior body = children.size() == 1 ? children.get(0)
                    : Sequence.createSequence(coalesceInstantBehaviors(children));
            return new InverterDecorator(body);
        }
        if (name.startsWith("loop ")) {
            String conditionStr = name.substring(5).trim();
            Supplier<Boolean> condition = parseCondition(conditionStr, context);

            List<Behavior> children = parseIndexedChildren(key, context);
            Behavior body = children.size() == 1 ? children.get(0)
                    : Sequence.createSequence(coalesceInstantBehaviors(children));
            return Loop.createWithCondition(body, condition);
        }
        if (name.equalsIgnoreCase("sequence") || name.equalsIgnoreCase("seq")) {
            List<Behavior> children = parseIndexedChildren(key, context);
            return Sequence.createSequence(coalesceInstantBehaviors(children));
        }
        if (name.equalsIgnoreCase("random") || name.equalsIgnoreCase("selector")) {
            List<Behavior> children = parseIndexedChildren(key, context);
            return Selector.selecting(children).build();
        }
        if (name.equalsIgnoreCase("parallel")) {
            List<Behavior> parallelWrapped = new ArrayList<>();
            for (Behavior child : parseIndexedChildren(key, context)) {
                parallelWrapped.add(new ParallelBehaviorWrapper(child));
            }
            return new ParallelComposite(parallelWrapped);
        }
        if (name.equalsIgnoreCase("eval") || name.toLowerCase().startsWith("eval ")) {
            String language = null;
            if (name.length() > 5) {
                language = name.substring(5).trim();
            }
            return parseEvalBlock(key, language, context);
        }
        // A factory owns its parameters and must decide how to parse its children.
        if (com.google.common.primitives.Ints.tryParse(name) == null
                && !name.isBlank() && registry.hasBehavior(parseInlineArgs(name)[0]))
            return parseLeaf(name, key, context);
        Behavior conditional = parseConditionalGroup(key, context, false);
        return conditional != null ? conditional : parseLeaf(name, key, context);
    }

    private void validateIndexedChildren(DataKey key) {
        if (!strict) return;
        for (DataKey child : key.getSubKeys()) {
            if (com.google.common.primitives.Ints.tryParse(child.name()) == null)
                throw new IllegalArgumentException("Expected indexed behavior child at " + child.getPath());
        }
    }

    private List<Behavior> parseIndexedChildren(DataKey key, BehaviorRegistry.BehaviorContext context) {
        Behavior conditional = parseConditionalGroup(key, context, false);
        if (conditional != null) return List.of(conditional);
        validateIndexedChildren(key);
        return parseIntegerChildren(key, context);
    }

    private List<Behavior> parseContainerChildren(DataKey key, BehaviorRegistry.BehaviorContext context) {
        Behavior conditional = parseConditionalGroup(key, context, true);
        return conditional != null ? List.of(conditional) : parseIntegerChildren(key, context);
    }

    private List<Behavior> parseIntegerChildren(DataKey key, BehaviorRegistry.BehaviorContext context) {
        List<Behavior> children = new ArrayList<>();
        DataKey ifKey = null;
        DataKey elseKey = null;
        for (DataKey sub : key.getIntegerSubKeys()) {
            if (sub.hasSubKeys()) {
                var entries = sub.getSubKeys().iterator();
                DataKey first = entries.next();
                // A grouped if/else is one child node; only unwrap single-entry wrappers.
                if (!entries.hasNext()) sub = first;
            }
            if (ifKey != null) {
                boolean isElse = sub.name().equals("else");
                if (isElse) {
                    elseKey = sub;
                }
                Behavior child = parseIfElse(ifKey, elseKey, context);
                if (child != null) {
                    children.add(child);
                }
                ifKey = null;
                elseKey = null;
                if (isElse) continue;
            }
            if (sub.name().startsWith("if ")) {
                ifKey = sub;
                continue;
            }
            Behavior child = parseNode(sub, context);
            if (child != null) {
                children.add(child);
            }
        }
        if (ifKey != null) {
            Behavior child = parseIfElse(ifKey, elseKey, context);
            if (child != null) {
                children.add(child);
            }
        }
        return children;
    }

    /**
     * Parses an eval block where each child line is evaluated as a raw expression. This allows multiple expressions to
     * be evaluated without needing expression wrappers.
     *
     * @param key
     *            the DataKey containing expressions
     * @param language
     *            the expression language (e.g., "molang"), or null for default
     * @param context
     *            the behavior context
     */
    private Behavior parseEvalBlock(DataKey key, String language, BehaviorRegistry.BehaviorContext context) {
        validateIndexedChildren(key);
        ExpressionRegistry exprRegistry = registry.getExpressionRegistry();
        List<CompiledExpression> expressions = new ArrayList<>();

        String engineName = language != null ? language : exprRegistry.getDefaultEngineName();
        ExpressionEngine engine = exprRegistry.getEngine(engineName);
        if (engine == null) {
            Messaging.severe("Unknown expression language", engineName);
            if (strict) throw new IllegalArgumentException("Unknown expression language: " + engineName);
            return EMPTY;
        }
        for (DataKey sub : key.getIntegerSubKeys()) {
            String expr = sub.getString("");
            if (expr != null && !expr.isEmpty()) {
                try {
                    expressions.add(engine.compile(expr));
                } catch (ExpressionCompileException e) {
                    if (strict) throw new IllegalArgumentException("Invalid behavior expression: " + expr, e);
                    e.printStackTrace();
                }
            }
        }
        return (InstantBehavior) () -> {
            for (CompiledExpression expr : expressions) {
                expr.evaluate(context.getScope());
            }
            return BehaviorStatus.SUCCESS;
        };
    }

    private Behavior parseIfElse(DataKey ifKey, DataKey elseKey, BehaviorRegistry.BehaviorContext context) {
        String conditionStr = ifKey.name().substring(3).trim();
        Supplier<Boolean> condition = parseCondition(conditionStr, context);

        Behavior ifBehavior;
        if (isBranchContainer(ifKey)) {
            List<Behavior> children = parseIndexedChildren(ifKey, context);
            ifBehavior = children.isEmpty() ? EMPTY
                    : children.size() == 1 ? children.get(0) : Sequence.createSequence(children);
        } else {
            String value = ifKey.getString("");
            ifBehavior = parseLeaf(value, null, context);
        }
        Behavior elseBehavior = null;
        if (elseKey != null) {
            if (isBranchContainer(elseKey)) {
                List<Behavior> children = parseIndexedChildren(elseKey, context);
                elseBehavior = children.isEmpty() ? EMPTY
                        : children.size() == 1 ? children.get(0) : Sequence.createSequence(children);
            } else {
                String value = elseKey.getString("");
                if (value != null && !value.isEmpty()) {
                    elseBehavior = parseLeaf(value, null, context);
                }
            }
        }
        return IfElse.create(condition, ifBehavior, elseBehavior);
    }

    /**
     * Parses inline arguments with support for quoted strings. Supports both single and double quotes. Examples: -
     * behavior arg1 arg2 arg3 - behavior "arg with spaces" key="value with spaces" - behavior key='single quoted'
     * other=normal
     *
     * @param input
     *            the input string to parse
     * @return array of parsed arguments
     */
    private String[] parseInlineArgs(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        boolean escaped = false;
        boolean tokenStarted = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                tokenStarted = true;
                escaped = true;
                continue;
            }
            if (!inQuotes && (c == '"' || c == '\'' || c == '`')) {
                tokenStarted = true;
                inQuotes = true;
                quoteChar = c;
                if (c == '`') {
                    current.append(c);
                }
                continue;
            }
            if (inQuotes && c == quoteChar) {
                inQuotes = false;
                quoteChar = 0;
                if (c == '`') {
                    current.append(c);
                }
                continue;
            }
            if (!inQuotes && Character.isWhitespace(c)) {
                if (tokenStarted) {
                    args.add(current.toString());
                    current = new StringBuilder();
                    tokenStarted = false;
                }
                continue;
            }
            current.append(c);
            tokenStarted = true;
        }
        if (strict && (inQuotes || escaped))
            throw new IllegalArgumentException("Unterminated quote or escape in behavior arguments: " + input);
        if (escaped) current.append('\\');
        if (tokenStarted) {
            args.add(current.toString());
        }
        return args.toArray(new String[0]);
    }

    private Behavior parseLeaf(String name, DataKey params, BehaviorRegistry.BehaviorContext context) {
        if (name == null || name.isBlank())
            return EMPTY;

        if (name.startsWith("/"))
            return createCommandBehavior(name.substring(1), context);

        if (name.startsWith("`") || registry.getExpressionRegistry().isPossiblyExpression(name))
            return createExpressionBehavior(name, context);

        String[] parts = parseInlineArgs(name);
        String behaviorName = parts[0];
        BehaviorRegistry.BehaviorContext nodeContext = new BehaviorRegistry.BehaviorContext(context.getNPC(),
                context.getScope(), context.getExpressionRegistry(), context.getMemory());
        nodeContext.setChildParser(key -> parseContainerChildren(key, nodeContext));

        if (parts.length > 1) {
            String[] args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, parts.length - 1);
            nodeContext.setArgs(args);
        } else {
            nodeContext.setArgs(null);
        }
        Behavior behavior = registry.createBehavior(behaviorName, params, nodeContext);
        if (behavior == null) {
            if (strict) throw new IllegalArgumentException("Unknown behavior: " + behaviorName);
            Messaging.severe("Unknown behavior", behaviorName);
            return EMPTY;
        }
        return behavior;
    }

    private Behavior parseNode(DataKey key, BehaviorRegistry.BehaviorContext context) {
        if (key.name().equals("else")) return orphanElse(key);
        if (key.hasSubKeys() || key.getRaw("") instanceof java.util.Map
                || key.getRaw("") instanceof java.util.Collection) {
            return parseContainer(key.name(), key, context);
        } else {
            String value = key.getString("");
            if (value != null && !value.isEmpty()) {
                if (com.google.common.primitives.Ints.tryParse(key.name()) == null && registry.hasBehavior(key.name()))
                    return parseLeaf(key.name(), key, context);
                return parseLeaf(value, null, context);
            }
            return parseLeaf(key.name(), key, context);
        }
    }

    private boolean isBranchContainer(DataKey key) {
        return key.hasSubKeys() || key.getRaw("") instanceof java.util.Map
                || key.getRaw("") instanceof java.util.Collection<?> values && values.isEmpty();
    }

    private Behavior parseConditionalGroup(DataKey key, BehaviorRegistry.BehaviorContext context,
            boolean allowNamedParameters) {
        DataKey ifKey = null;
        DataKey elseKey = null;
        String unexpectedConditionalField = null;
        for (DataKey sub : key.getSubKeys()) {
            String name = sub.name();
            if (name.startsWith("if ")) {
                if (strict && ifKey != null)
                    throw new IllegalArgumentException("Multiple if branches in behavior node at " + key.getPath());
                ifKey = sub;
            } else if (name.equals("else")) {
                elseKey = sub;
            } else if (!allowNamedParameters || com.google.common.primitives.Ints.tryParse(name) != null) {
                unexpectedConditionalField = sub.getPath();
            }
        }
        if (ifKey != null) {
            if (strict && unexpectedConditionalField != null)
                throw new IllegalArgumentException("Unexpected field in conditional behavior: " + unexpectedConditionalField);
            return parseIfElse(ifKey, elseKey, context);
        }
        if (elseKey != null) orphanElse(elseKey);

        return null;
    }

    private Behavior orphanElse(DataKey key) {
        String message = "Behavior else has no matching if at " + key.getPath();
        if (strict) throw new IllegalArgumentException(message);
        Messaging.severe(message);
        return EMPTY;
    }

    private static final Behavior EMPTY = new Behavior() {
        @Override
        public void reset() {
        }

        @Override
        public BehaviorStatus run() {
            return BehaviorStatus.SUCCESS;
        }

        @Override
        public boolean shouldExecute() {
            return true;
        }

        @Override
        public String toString() {
            return "EmptyBehavior";
        }
    };
}
