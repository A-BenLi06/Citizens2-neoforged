package net.citizensnpcs.api.ai.tree;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.goals.WanderGoal;
import net.citizensnpcs.api.expr.ExpressionRegistry;
import net.citizensnpcs.api.expr.ExpressionRegistry.ExpressionValue;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.api.expr.Memory;
import net.citizensnpcs.api.npc.BlockBreaker;
import net.citizensnpcs.api.npc.BlockBreaker.BlockBreakerConfiguration;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Durations;

/**
 * Registry for behavior factories that can be instantiated from {@link DataKey} configurations.
 */
public class BehaviorRegistry {
    private final Map<String, BehaviorFactory> behaviors = new HashMap<>();
    private final ExpressionRegistry expressions;
    private final BehaviorSignals signals;

    public BehaviorRegistry(ExpressionRegistry expressions) {
        this.expressions = expressions;
        this.signals = new BehaviorSignals();
        registerDefaults();
    }

    public BehaviorRegistry(ExpressionRegistry expressionRegistry, BehaviorSignals signals) {
        this.expressions = expressionRegistry;
        this.signals = signals;
        registerDefaults();
    }

    /**
     * Creates a behavior instance from the registry.
     *
     * @param name
     *            the behavior name
     * @param params
     *            the DataKey containing parameters
     * @param context
     *            the creation context
     * @return the created behavior, or null if not found
     */
    public Behavior createBehavior(String name, DataKey params, BehaviorContext context) {
        BehaviorFactory factory = behaviors.get(name.toLowerCase(Locale.ROOT));
        if (factory == null)
            return null;

        return factory.create(params, context);
    }

    public ExpressionRegistry getExpressionRegistry() {
        return expressions;
    }

    public BehaviorSignals getSignalManager() {
        return signals;
    }

    /**
     * Checks if a behavior is registered.
     *
     * @param name
     *            the behavior name
     * @return true if registered
     */
    public boolean hasBehavior(String name) {
        return behaviors.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Parses literal durations or expressions producing an integer tick count or a duration string.
     */
    protected int parseDuration(String durationStr, ExpressionScope scope) {
        if (durationStr == null || durationStr.isEmpty())
            return 20;

        return durationTicks(expressions.parseValue(durationStr).evaluate(scope));
    }

    private static int durationTicks(Object value) {
        if (value instanceof Number number) {
            double ticks = number.doubleValue();
            if (!Double.isFinite(ticks) || ticks < 0 || ticks > Integer.MAX_VALUE || ticks != Math.rint(ticks))
                throw new IllegalArgumentException("Wait duration must be a non-negative integer tick count");
            return (int) ticks;
        }
        if (!(value instanceof String text)) throw new IllegalArgumentException("Invalid wait duration: " + value);
        Duration duration = Durations.parse(text);
        if (duration.isNegative()) throw new IllegalArgumentException("Wait duration must not be negative");
        return Durations.toTicks(duration);
    }

    static Integer signalTargetId(Number value) {
        try { return new java.math.BigDecimal(value.toString()).intValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { return null; }
    }

    private static int repetitionCount(Object value) {
        try {
            int count = new java.math.BigDecimal(String.valueOf(value)).intValueExact();
            if (count < 0) throw new IllegalArgumentException("Repeat count must not be negative");
            return count;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Repeat count must be a non-negative integer", invalid);
        }
    }

    private static long cooldownMillis(Object value) {
        Duration duration;
        if (value instanceof Number number)
            duration = Duration.ofMillis(new java.math.BigDecimal(number.toString()).longValueExact());
        else if (value instanceof String text)
            duration = Durations.parse(text, TimeUnit.MILLISECONDS);
        else throw new IllegalArgumentException("Invalid cooldown duration: " + value);
        if (duration.isNegative()) throw new IllegalArgumentException("Cooldown duration must not be negative");
        return duration.toMillis();
    }

    private static String readParameter(DataKey params, String name, String fallback) {
        return params != null && (params.getRaw(name) != null || params.keyExists(name)) ? params.getString(name) : fallback;
    }

    /**
     * Registers a behavior factory.
     *
     * @param name
     *            the behavior name
     * @param factory
     *            the factory to create behavior instances
     */
    public void registerBehavior(String name, BehaviorFactory factory) {
        behaviors.put(name.toLowerCase(Locale.ROOT), factory);
    }

    /**
     * Registers default built-in behaviors.
     */
    protected void registerDefaults() {
        // Wait behavior - waits for a number of ticks
        registerBehavior("wait", (params, context) -> {
            String durationStr = context.getArgOrParam(0, "", params, "20");
            ExpressionRegistry.ExpressionValue durationHolder = expressions.parseValue(
                    durationStr == null || durationStr.isBlank() ? "20" : durationStr);

            return new Behavior() {
                private int ticksRemaining;

                @Override
                public void reset() {
                    ticksRemaining = 0;
                }

                @Override
                public BehaviorStatus run() {
                    if (ticksRemaining > 0) ticksRemaining--;
                    if (ticksRemaining == 0)
                        return BehaviorStatus.SUCCESS;

                    return BehaviorStatus.RUNNING;
                }

                @Override
                public boolean shouldExecute() {
                    ticksRemaining = 0;
                    ticksRemaining = durationTicks(durationHolder.evaluate(context.getScope()));
                    return true;
                }
            };
        });

        // Emits a signal for other behaviors to receive
        registerBehavior("emit_signal", (params, context) -> {
            String signalName = context.getArgOrParam(0, "", params, "");
            if (signalName.isEmpty() && params != null) {
                signalName = params.getString("");
            }
            ExpressionValue value = expressions.parseValue(signalName);

            return (InstantBehavior) () -> {
                String signal = value.evaluateAsString(context.getScope());
                signals.emit(context.getNPC(), signal);
                return BehaviorStatus.SUCCESS;
            };
        });

        // Emits a signal to a specific NPC by ID or UUID
        registerBehavior("emit_signal_to", (params, context) -> {
            String npcIdStr = context.getArgOrParam(0, "npc", params, null);
            String signalName = context.getArgOrParam(1, "signal", params, null);
            if (npcIdStr == null || signalName == null)
                return null;

            ExpressionRegistry.ExpressionValue npcIdHolder = expressions.parseValue(npcIdStr);
            ExpressionRegistry.ExpressionValue signalHolder = expressions.parseValue(signalName);

            return (InstantBehavior) () -> {
                Object idValue = npcIdHolder.evaluate(context.getScope());
                NPC npc = null;

                if (idValue instanceof String) {
                    String idString = (String) idValue;
                    try {
                        UUID uuid = UUID.fromString(idString);
                        npc = CitizensAPI.getNPCRegistry().getByUniqueIdGlobal(uuid);
                    } catch (IllegalArgumentException e) {
                        try {
                            int npcId = Integer.parseInt(idString);
                            npc = CitizensAPI.getNPCRegistry().getById(npcId);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } else if (idValue instanceof Number) {
                    Integer npcId = signalTargetId((Number) idValue);
                    if (npcId == null) return BehaviorStatus.FAILURE;
                    npc = CitizensAPI.getNPCRegistry().getById(npcId);
                }
                if (npc == null)
                    return BehaviorStatus.FAILURE;
                signals.emit(npc, signalHolder.evaluateAsString(context.getScope()));
                return BehaviorStatus.SUCCESS;
            };
        });

        // Emits a signal that all NPCs can receive
        registerBehavior("emit_global_signal", (params, context) -> {
            String signalName = context.getArgOrParam(0, "", params, "");
            if (signalName.isEmpty() && params != null) {
                signalName = params.getString("");
            }
            ExpressionRegistry.ExpressionValue signalHolder = expressions.parseValue(signalName);

            return (InstantBehavior) () -> {
                String signal = signalHolder.evaluateAsString(context.getScope());
                signals.emitGlobal(signal);
                return BehaviorStatus.SUCCESS;
            };
        });

        // Blocks until a signal is received
        registerBehavior("wait_for_signal", (params, context) -> {
            String signalName = context.getArgOrParam(0, "", params, "");
            if (signalName.isEmpty() && params != null) {
                signalName = params.getString("");
            }
            ExpressionRegistry.ExpressionValue signalHolder = expressions.parseValue(signalName);

            return new Behavior() {
                private String currentSignal;
                private BehaviorSignals.SignalListener listener;
                private boolean signalReceived = false;
                private long generation;

                @Override
                public synchronized void reset() {
                    signalReceived = false;
                    detach();
                }

                private void detach() {
                    generation++;
                    BehaviorSignals.SignalListener previous = listener;
                    String previousSignal = currentSignal;
                    listener = null;
                    currentSignal = null;
                    if (previous != null) signals.unlisten(context.getNPC(), previousSignal, previous);
                }

                @Override
                public synchronized BehaviorStatus run() {
                    if (signalReceived) {
                        detach();
                        return BehaviorStatus.SUCCESS;
                    }
                    return BehaviorStatus.RUNNING;
                }

                @Override
                public synchronized boolean shouldExecute() {
                    reset();
                    long activation = generation;
                    String evaluatedSignal = signalHolder.evaluateAsString(context.getScope());
                    if (generation != activation) return false;
                    currentSignal = evaluatedSignal;
                    listener = () -> {
                        synchronized (this) {
                            if (generation == activation) signalReceived = true;
                        }
                    };
                    try { signals.listen(context.getNPC(), currentSignal, listener); }
                    catch (RuntimeException | Error failure) {
                        try { reset(); }
                        catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                        throw failure;
                    }
                    return true;
                }
            };
        });

        // Always succeeds
        registerBehavior("succeed", (params, context) -> (InstantBehavior) () -> BehaviorStatus.SUCCESS);

        // Repeats x times
        registerBehavior("repeat", (params, context) -> {
            String countStr = context.getArgOrParam(0, "count", params, "1");
            ExpressionValue countHolder = expressions.parseValue(countStr);

            if (params == null)
                return null;
            if (!countHolder.isExpression()) repetitionCount(countHolder.evaluate(context.getScope()));
            java.util.List<Behavior> children = context.parseChildren(params);
            Behavior body = children.size() == 1 ? children.get(0) : Sequence.createSequence(children);
            return new Repeat(body, () -> repetitionCount(countHolder.evaluate(context.getScope())));
        });

        // Always fails
        registerBehavior("fail", (params, context) -> (InstantBehavior) () -> BehaviorStatus.FAILURE);

        // Sets a memory variable
        registerBehavior("set", (params, context) -> {
            String key = context.getArgOrParam(0, "key", params, null);
            String valueStr = context.getArgOrParam(1, "value", params, null);
            if (key == null || valueStr == null)
                return null;

            ExpressionRegistry.ExpressionValue valueHolder = expressions.parseValue(valueStr);

            return (InstantBehavior) () -> {
                Object value = valueHolder.evaluate(context.getScope());
                context.getMemory().set(key, value);
                return BehaviorStatus.SUCCESS;
            };
        });

        // Waits until a condition becomes true
        registerBehavior("wait_until", (params, context) -> {
            String conditionStr = context.getArgOrParam(0, "condition", params, null);
            if (conditionStr == null)
                return null;

            ExpressionRegistry.ExpressionValue conditionHolder = expressions.parseValue(conditionStr);

            return new Behavior() {
                @Override
                public void reset() {
                }

                @Override
                public BehaviorStatus run() {
                    if (conditionHolder.evaluateAsBoolean(context.getScope()))
                        return BehaviorStatus.SUCCESS;

                    return BehaviorStatus.RUNNING;
                }

                @Override
                public boolean shouldExecute() {
                    return true;
                }
            };
        });

        // Rate limits execution using memory
        // Usage: cooldown key 5s, cooldown key 10m, cooldown key {expression}
        registerBehavior("cooldown", (params, context) -> {
            String key = context.getArgOrParam(0, "key", params, null);
            String durationStr = context.getArgOrParam(1, "duration", params, "1s");
            if (key == null)
                return null;
            ExpressionRegistry.ExpressionValue durationHolder = expressions.parseValue(durationStr);
            String cooldownKey = "cooldown." + key;

            return (InstantBehavior) () -> {
                long duration = cooldownMillis(durationHolder.evaluate(context.getScope()));
                long now = System.currentTimeMillis();
                boolean used = context.getMemory().has(cooldownKey);
                long lastUsed = (long) context.getMemory().getNumber(cooldownKey, 0);
                if (!used || now - lastUsed >= duration) {
                    context.getMemory().set(cooldownKey, now);
                    return BehaviorStatus.SUCCESS;
                }
                return BehaviorStatus.FAILURE;
            };
        });

        // Forgets a memory variable
        registerBehavior("forget", (params, context) -> {
            String key = context.getArgOrParam(0, "key", params, null);
            if (key == null)
                return null;

            return (InstantBehavior) () -> {
                context.getMemory().remove(key);
                return BehaviorStatus.SUCCESS;
            };
        });

        // Clears all memory
        registerBehavior("clear_memory", (params, context) -> {
            return (InstantBehavior) () -> {
                context.getMemory().clear();
                return BehaviorStatus.SUCCESS;
            };
        });

        // Walkto behavior - walks to coordinates
        // Inline usage: walkto 100 64 200 speed=1.5 range=50
        // Named usage:
        // walkto:
        // x: 100
        // y: 64
        // z: 200
        // speed: 1.5
        // range: 50
        // distance_margin: 2
        registerBehavior("walkto", (params, context) -> {
            String xStr = null;
            String yStr = null;
            String zStr = null;
            String speedStr = null;
            String rangeStr = null;
            String distanceMarginStr = null;

            String[] args = context.getArgs();
            if (args != null && args.length >= 3) {
                xStr = args[0];
                yStr = args[1];
                zStr = args[2];

                for (int i = 3; i < args.length; i++) {
                    String arg = args[i];
                    if (arg.contains("=")) {
                        String[] parts = arg.split("=", 2);
                        String key = parts[0].toLowerCase(Locale.ROOT);
                        String value = parts[1];

                        switch (key) {
                            case "speed":
                                speedStr = value;
                                break;
                            case "range":
                                rangeStr = value;
                                break;
                            case "distance_margin":
                            case "margin":
                                distanceMarginStr = value;
                                break;
                        }
                    }
                }
            } else if (params != null) {
                xStr = readParameter(params, "x", null);
                yStr = readParameter(params, "y", null);
                zStr = readParameter(params, "z", null);
                speedStr = readParameter(params, "speed", null);
                rangeStr = readParameter(params, "range", null);
                distanceMarginStr = readParameter(params, "distance_margin", null);
            }
            if (xStr == null || yStr == null || zStr == null)
                return null;

            ExpressionRegistry.ExpressionValue xHolder = expressions.parseValue(xStr);
            ExpressionRegistry.ExpressionValue yHolder = expressions.parseValue(yStr);
            ExpressionRegistry.ExpressionValue zHolder = expressions.parseValue(zStr);
            ExpressionRegistry.ExpressionValue speedHolder = speedStr != null ? expressions.parseValue(speedStr) : null;
            ExpressionRegistry.ExpressionValue rangeHolder = rangeStr != null ? expressions.parseValue(rangeStr) : null;
            ExpressionRegistry.ExpressionValue distanceMarginHolder = distanceMarginStr != null
                    ? expressions.parseValue(distanceMarginStr)
                    : null;

            return new Behavior() {
                private boolean started = false;
                private net.citizensnpcs.api.ai.NavigatorParameters ownedParameters;
                private BehaviorStatus result;
                private long generation;

                @Override
                public void reset() {
                    generation++;
                    started = false;
                    result = null;
                    var previous = ownedParameters;
                    ownedParameters = null;
                    if (previous != null && context.getNPC().getNavigator().getLocalParameters() == previous)
                        context.getNPC().getNavigator().cancelNavigation();
                }

                @Override
                public BehaviorStatus run() {
                    NPC npc = context.getNPC();
                    if (!npc.isSpawned()) return BehaviorStatus.FAILURE;
                    if (!started) {
                        long activation = generation;
                        double x = xHolder.evaluateAsNumber(context.getScope());
                        double y = yHolder.evaluateAsNumber(context.getScope());
                        double z = zHolder.evaluateAsNumber(context.getScope());
                        Float speed = speedHolder == null ? null : (float) speedHolder.evaluateAsNumber(context.getScope());
                        Float range = rangeHolder == null ? null : (float) rangeHolder.evaluateAsNumber(context.getScope());
                        Double margin = distanceMarginHolder == null ? null : distanceMarginHolder.evaluateAsNumber(context.getScope());
                        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                                || speed != null && (!Float.isFinite(speed) || speed < 0)
                                || range != null && (!Float.isFinite(range) || range < 0)
                                || margin != null && (!Double.isFinite(margin) || margin < 0)) return BehaviorStatus.FAILURE;
                        Location current = npc.getStoredLocation();
                        if (current == null || current.getWorld() == null) return BehaviorStatus.FAILURE;
                        if (generation != activation || !npc.isSpawned()) return BehaviorStatus.FAILURE;
                        var navigator = npc.getNavigator();
                        if (navigator.isNavigating()) return BehaviorStatus.FAILURE;
                        Location target = new Location(current.getWorld(), x, y, z);
                        started = true;
                        try {
                            navigator.setTarget(target, parameters -> {
                                ownedParameters = parameters;
                                if (speed != null) parameters.speedModifier(speed);
                                if (range != null) parameters.range(range);
                                if (margin != null) parameters.distanceMargin(margin);
                                parameters.addSingleUseCallback(reason -> {
                                    if (generation == activation && ownedParameters == parameters)
                                        result = reason == null ? BehaviorStatus.SUCCESS : BehaviorStatus.FAILURE;
                                });
                            });
                        } catch (RuntimeException | Error failure) {
                            if (generation == activation) {
                                try { reset(); }
                                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                            }
                            throw failure;
                        }
                        if (generation != activation) return BehaviorStatus.RUNNING;
                    }
                    if (result != null) return result;
                    if (ownedParameters == null || npc.getNavigator().getLocalParameters() != ownedParameters
                            || !npc.getNavigator().isNavigating()) return result = BehaviorStatus.FAILURE;
                    return BehaviorStatus.RUNNING;
                }

                @Override
                public boolean shouldExecute() {
                    if (!context.getNPC().isSpawned() || context.getNPC().getNavigator().isNavigating()) return false;
                    reset();
                    return true;
                }
            };
        });

        // Wander behavior - random walk within radius
        registerBehavior("wander", (params, context) -> {
            Map<String, String> parsedArgs = context.parseArgs(1, params);
            String radiusStr = context.getFromParsedArgs(parsedArgs, "0", "radius");
            String pathfindStr = context.getFromParsedArgs(parsedArgs, "1", "pathfind");
            if (radiusStr == null) {
                radiusStr = "10";
            }
            ExpressionRegistry.ExpressionValue radiusHolder = expressions.parseValue(radiusStr);
            ExpressionRegistry.ExpressionValue pathfindHolder = expressions.parseValue(pathfindStr);

            return new Behavior() {
                private WanderGoal wander = null;

                @Override
                public void reset() {
                    wander = null;
                }

                @Override
                public BehaviorStatus run() {
                    NPC npc = context.getNPC();
                    if (!npc.isSpawned())
                        return BehaviorStatus.FAILURE;

                    if (wander == null) {
                        int radius = (int) radiusHolder.evaluateAsNumber(context.getScope());
                        wander = WanderGoal.builder(npc).xrange(radius).yrange(radius)
                                .pathfind(pathfindHolder.evaluateAsBoolean(context.getScope())).build();
                    }
                    return wander.run();
                }

                @Override
                public boolean shouldExecute() {
                    return context.getNPC().isSpawned();
                }
            };
        });

        // Break-block behavior - break a block at coordinates using BlockBreaker
        registerBehavior("break_block", (params, context) -> {
            Map<String, String> parsedArgs = context.parseArgs(3, params);
            String xStr = context.getFromParsedArgs(parsedArgs, "0", "x");
            String yStr = context.getFromParsedArgs(parsedArgs, "1", "y");
            String zStr = context.getFromParsedArgs(parsedArgs, "2", "z");
            String radiusStr = context.getFromParsedArgs(parsedArgs, "3", "radius");

            if (xStr == null || yStr == null || zStr == null) {
                return null;
            }
            if (radiusStr == null) {
                radiusStr = "3";
            }
            ExpressionRegistry.ExpressionValue xHolder = expressions.parseValue(xStr);
            ExpressionRegistry.ExpressionValue yHolder = expressions.parseValue(yStr);
            ExpressionRegistry.ExpressionValue zHolder = expressions.parseValue(zStr);
            ExpressionRegistry.ExpressionValue radiusHolder = expressions.parseValue(radiusStr);

            return new Behavior() {
                private BlockBreaker breaker = null;

                @Override
                public void reset() {
                    if (breaker != null) {
                        breaker.reset();
                        breaker = null;
                    }
                }

                @Override
                public BehaviorStatus run() {
                    NPC npc = context.getNPC();
                    if (!npc.isSpawned())
                        return BehaviorStatus.FAILURE;

                    if (breaker == null) {
                        int x = (int) xHolder.evaluateAsNumber(context.getScope());
                        int y = (int) yHolder.evaluateAsNumber(context.getScope());
                        int z = (int) zHolder.evaluateAsNumber(context.getScope());
                        double radius = radiusHolder.evaluateAsNumber(context.getScope());

                        BlockPos target = new BlockPos(x, y, z);
                        ServerLevel level = (ServerLevel) npc.getEntity().level();
                        if (level.getBlockState(target).isAir())
                            return BehaviorStatus.SUCCESS;

                        BlockBreakerConfiguration cfg = new BlockBreakerConfiguration();
                        if (radius > 0) {
                            cfg.radius(radius);
                        }
                        breaker = npc.getBlockBreaker(target, cfg);
                    }
                    return breaker.run();
                }

                @Override
                public boolean shouldExecute() {
                    return context.getNPC().isSpawned();
                }
            };
        });
    }

    /**
     * Context passed to behavior factories during creation. Provides access to NPC, expression scope, memory, and
     * argument parsing utilities.
     */
    public static class BehaviorContext {
        private String[] args;
        private java.util.function.Function<DataKey, java.util.List<Behavior>> childParser;
        private final ExpressionRegistry expressionRegistry;
        private final Memory memory;
        private final NPC npc;
        private final ExpressionScope scope;

        public BehaviorContext(NPC npc, ExpressionScope scope, ExpressionRegistry expressionRegistry, Memory memory) {
            this.npc = npc;
            this.scope = scope;
            this.expressionRegistry = expressionRegistry;
            this.memory = memory;
        }

        public String getArgOrParam(int index, String name, DataKey params, String defaultValue) {
            if (args != null && index < args.length)
                return args[index];

            return readParameter(params, name, defaultValue);
        }

        public void setChildParser(java.util.function.Function<DataKey, java.util.List<Behavior>> parser) {
            childParser = parser;
        }

        public java.util.List<Behavior> parseChildren(DataKey key) {
            if (childParser == null) throw new IllegalStateException("No child behavior parser is configured");
            return childParser.apply(key);
        }

        public String[] getArgs() {
            return args;
        }

        public ExpressionRegistry getExpressionRegistry() {
            return expressionRegistry;
        }

        public String getFromParsedArgs(Map<String, String> parsedArgs, String... keys) {
            for (String key : keys) {
                String value = parsedArgs.get(key);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        public Memory getMemory() {
            return memory;
        }

        public NPC getNPC() {
            return npc;
        }

        public ExpressionScope getScope() {
            return scope;
        }

        public Map<String, String> parseArgs(int positionalCount, DataKey params) {
            Map<String, String> result = new HashMap<>();

            if (args != null && args.length >= positionalCount) {
                for (int i = 0; i < positionalCount && i < args.length; i++) {
                    result.put(String.valueOf(i), args[i]);
                }
                for (int i = positionalCount; i < args.length; i++) {
                    String arg = args[i];
                    if (arg.contains("=")) {
                        String[] parts = arg.split("=", 2);
                        result.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
                    }
                }
            } else if (params != null) {
                for (DataKey sub : params.getSubKeys()) {
                    result.put(sub.name(), params.getString(sub.name()));
                }
            }
            return result;
        }

        public void setArgs(String[] args) {
            this.args = args;
        }
    }

    @FunctionalInterface
    public interface BehaviorFactory {
        /**
         * Creates a behavior instance.
         *
         * @param params
         *            the DataKey containing parameters (may be null)
         * @param context
         *            the creation context with NPC and scope
         * @return the created behavior, or null if creation fails
         */
        Behavior create(DataKey params, BehaviorContext context);
    }
}
