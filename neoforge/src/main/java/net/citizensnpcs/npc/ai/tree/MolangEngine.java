package net.citizensnpcs.npc.ai.tree;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.google.common.primitives.Doubles;

import net.citizensnpcs.api.expr.CompiledExpression;
import net.citizensnpcs.api.expr.ExpressionEngine;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.api.expr.Memory;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.api.util.ItemStorage;
import net.citizensnpcs.api.util.Placeholders;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import team.unnamed.mocha.MochaEngine;
import team.unnamed.mocha.runtime.MochaFunction;
import team.unnamed.mocha.runtime.value.Function;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.NumberValue;
import team.unnamed.mocha.runtime.value.ObjectProperty;
import team.unnamed.mocha.runtime.value.ObjectValue;
import team.unnamed.mocha.runtime.value.StringValue;
import team.unnamed.mocha.runtime.value.Value;

/**
 * Molang expression engine, implemented with the Mocha library — the same one the Bukkit plugin uses, so expressions
 * written for it evaluate identically here.
 */
public class MolangEngine implements ExpressionEngine {
    private final MochaEngine<?> engine;

    public MolangEngine() {
        this.engine = MochaEngine.createStandard();
    }

    @Override
    public CompiledExpression compile(String expression) throws ExpressionCompileException {
        try {
            return new MolangCompiledExpression(engine.prepareEval(expression), engine);
        } catch (Exception e) {
            throw new ExpressionCompileException("Failed to parse Molang expression: " + expression, e);
        }
    }

    @Override
    public String getName() {
        return "molang";
    }

    public static class ItemStackValue implements Value {
        private final ItemStack itemStack;

        public ItemStackValue(ItemStack itemStack) {
            this.itemStack = itemStack;
        }

        public ItemStack getItemStack() {
            return itemStack;
        }
    }

    private static class LazyObjectBinding implements ObjectValue {
        private final Map<String, Value> eagerProperties = new HashMap<>();
        private final Map<String, Supplier<?>> lazyProperties = new HashMap<>();

        @Override
        public Value get(String property) {
            Value eagerValue = eagerProperties.get(property);
            if (eagerValue != null)
                return eagerValue;

            Supplier<?> supplier = lazyProperties.get(property);
            if (supplier != null)
                return toMochaValue(supplier.get());
            return NumberValue.zero();
        }

        @Override
        public ObjectProperty getProperty(String property) {
            return ObjectProperty.property(get(property), false);
        }

        @Override
        public boolean set(String property, Value value) {
            lazyProperties.remove(property);
            eagerProperties.put(property, value);
            return true;
        }

        public void setEager(String name, Value value) {
            eagerProperties.put(name, value);
        }

        public void setLazy(String name, Supplier<?> supplier) {
            lazyProperties.put(name, supplier);
        }

        @Override
        public String toString() {
            return "LazyObjectBinding [eagerProperties=" + eagerProperties + ", lazyProperties=" + lazyProperties + "]";
        }
    }

    private static class MolangCompiledExpression implements CompiledExpression {
        private final MochaEngine<?> baseEngine;
        private final MochaFunction function;

        MolangCompiledExpression(MochaFunction function, MochaEngine<?> baseEngine) {
            this.function = function;
            this.baseEngine = baseEngine;
        }

        private void bindCustomFunctions(MochaEngine<?> evalEngine, ExpressionScope scope) {
            Memory memory = scope.getMemory();
            NPC npc = scope.getNPC();

            evalEngine.scope().set("list", createListBinding(memory));
            evalEngine.scope().set("mem", createMemBinding(memory));
            if (npc != null) {
                evalEngine.scope().set("inv", createInvBinding(npc));
            }
            evalEngine.scope().set("item", createItemBinding());

            // papi('placeholder_name')
            evalEngine.scope().set("papi", (Function<?>) (context, args) -> {
                if (args.length() < 1)
                    return StringValue.of("");
                String placeholderName = args.next().eval().getAsString();
                return new NumberParseableValue(Placeholders.replace(placeholderName, scope.getPlayer()));
            });
        }

        private void bindScopeVariables(MochaEngine<?> evalEngine, ExpressionScope scope) {
            Map<String, LazyObjectBinding> topLevelObjects = new HashMap<>();
            for (String name : scope.getVariableNames()) {
                String[] parts = name.split("[.]", 2);
                if (parts.length == 1) {
                    if (scope.isConstant(name)) {
                        Object value = scope.get(name);
                        if (value != null) {
                            evalEngine.scope().set(name, toMochaValue(value));
                        }
                    } else {
                        Supplier<?> supplier = scope.getSupplier(name);
                        if (supplier != null) {
                            evalEngine.scope().set(name, (Function<?>) (ctx, args) -> toMochaValue(supplier.get()));
                        }
                    }
                    continue;
                }
                LazyObjectBinding top = topLevelObjects.get(parts[0]);
                if (top == null) {
                    top = new LazyObjectBinding();
                    topLevelObjects.put(parts[0], top);
                    evalEngine.scope().set(parts[0], top);
                }
                setNestedProperty(top, parts[1], scope, name);
            }
        }

        @Override
        public Object evaluate(ExpressionScope scope) {
            bindScopeVariables(baseEngine, scope);
            bindCustomFunctions(baseEngine, scope);
            try {
                return function.evaluate();
            } catch (Exception e) {
                e.printStackTrace();
                return 0.0;
            }
        }

        @Override
        public boolean evaluateAsBoolean(ExpressionScope scope) {
            Object result = evaluate(scope);
            if (result instanceof Boolean bool)
                return bool;
            if (result instanceof Number number)
                return number.doubleValue() != 0;
            return result != null;
        }

        @Override
        public double evaluateAsNumber(ExpressionScope scope) {
            Object result = evaluate(scope);
            if (result instanceof Number number)
                return number.doubleValue();
            if (result instanceof Boolean bool)
                return bool ? 1.0 : 0.0;
            return 0.0;
        }

        @Override
        public String evaluateAsString(ExpressionScope scope) {
            Object result = evaluate(scope);
            return result == null ? "" : result.toString();
        }

        private void setNestedProperty(LazyObjectBinding parent, String path, ExpressionScope scope, String fullName) {
            String[] parts = path.split("[.]", 2);
            String currentPart = parts[0];
            if (parts.length == 1) {
                if (scope.isConstant(fullName)) {
                    Object value = scope.get(fullName);
                    if (value != null) {
                        parent.setEager(currentPart, toMochaValue(value));
                    }
                } else {
                    Supplier<?> supplier = scope.getSupplier(fullName);
                    if (supplier != null) {
                        parent.setLazy(currentPart, supplier);
                    }
                }
                return;
            }
            Value existing = parent.get(currentPart);
            LazyObjectBinding nested;
            if (existing instanceof LazyObjectBinding binding) {
                nested = binding;
            } else {
                nested = new LazyObjectBinding();
                parent.setEager(currentPart, nested);
            }
            setNestedProperty(nested, parts[1], scope, fullName);
        }

        @Override
        public String toString() {
            return "MolangCompiledExpression [function=" + function + "]";
        }
    }

    /** A string that Molang may want to use as a number, e.g. a placeholder that expanded to "12". */
    private static class NumberParseableValue implements Value {
        private Double cache;
        private final String string;

        NumberParseableValue(String value) {
            this.string = value;
        }

        @Override
        public boolean getAsBoolean() {
            try {
                return Double.parseDouble(string) != 0;
            } catch (NumberFormatException e) {
                return "true".equalsIgnoreCase(string.trim()) || !string.isEmpty();
            }
        }

        @Override
        public double getAsNumber() {
            if (cache == null) {
                Double parsed = Doubles.tryParse(string.trim());
                // upstream unboxes tryParse straight away, so an unparseable placeholder throws instead of reading as 0
                cache = parsed == null ? 0 : parsed;
            }
            return cache;
        }

        @Override
        public String getAsString() {
            return string;
        }
    }

    private static ObjectValue createInvBinding(NPC npc) {
        MutableObjectBinding binding = new MutableObjectBinding();

        // inv.has(item)
        binding.set("has", (Function<?>) (context, args) -> {
            if (!npc.isSpawned() || args.length() < 1)
                return NumberValue.zero();
            ItemStack item = resolveItemStack(args.next().eval(), 1);
            if (item.isEmpty())
                return NumberValue.zero();
            for (ItemStack stack : npc.getOrAddTrait(Inventory.class).getContents()) {
                if (stack != null && stack.is(item.getItem()))
                    return NumberValue.of(1);
            }
            return NumberValue.zero();
        });

        // inv.count(item)
        binding.set("count", (Function<?>) (context, args) -> {
            if (!npc.isSpawned() || args.length() < 1)
                return NumberValue.zero();
            ItemStack item = resolveItemStack(args.next().eval(), 1);
            if (item.isEmpty())
                return NumberValue.zero();
            int count = 0;
            for (ItemStack stack : npc.getOrAddTrait(Inventory.class).getContents()) {
                if (stack != null && stack.is(item.getItem())) {
                    count += stack.getCount();
                }
            }
            return NumberValue.of(count);
        });

        // inv.add(item) or inv.add(item, amount)
        binding.set("add", (Function<?>) (context, args) -> {
            if (!npc.isSpawned() || args.length() < 1)
                return NumberValue.zero();
            // upstream reads the amount first and the item second, which is the opposite of the documented argument
            // order - inv.add('stone', 5) there parses "stone" as the amount and 5 as the item, and adds nothing
            ItemStack item = resolveItemStack(args.next().eval(),
                    args.length() > 1 ? (int) args.next().eval().getAsNumber() : 1);
            if (item.isEmpty())
                return NumberValue.zero();
            Inventory inv = npc.getOrAddTrait(Inventory.class);
            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < contents.length; i++) {
                if (contents[i] == null || contents[i].isEmpty()) {
                    contents[i] = item;
                    inv.setContents(contents);
                    return NumberValue.of(1);
                }
            }
            return NumberValue.zero();
        });

        // inv.remove(item) or inv.remove(item, amount)
        binding.set("remove", (Function<?>) (context, args) -> {
            if (!npc.isSpawned() || args.length() < 1)
                return NumberValue.zero();
            ItemStack item = resolveItemStack(args.next().eval(), 1);
            int amount = args.length() > 1 ? (int) args.next().eval().getAsNumber() : 1;
            if (item.isEmpty())
                return NumberValue.zero();
            int remaining = amount;
            Inventory inv = npc.getOrAddTrait(Inventory.class);
            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < contents.length && remaining > 0; i++) {
                if (contents[i] == null || !contents[i].is(item.getItem())) {
                    continue;
                }
                int stackAmount = contents[i].getCount();
                if (stackAmount <= remaining) {
                    contents[i] = null;
                    remaining -= stackAmount;
                } else {
                    contents[i].setCount(stackAmount - remaining);
                    remaining = 0;
                }
            }
            inv.setContents(contents);
            return remaining == 0 ? NumberValue.of(1) : NumberValue.zero();
        });

        // inv.clear()
        binding.set("clear", (Function<?>) (context, args) -> {
            if (!npc.isSpawned())
                return NumberValue.zero();
            Inventory inv = npc.getOrAddTrait(Inventory.class);
            inv.setContents(new ItemStack[inv.getContents().length]);
            return NumberValue.of(1);
        });

        // inv.set_hand(item)
        binding.set("set_hand", (Function<?>) (context, args) -> setHand(npc, args, InteractionHand.MAIN_HAND));

        // inv.set_offhand(item)
        binding.set("set_offhand", (Function<?>) (context, args) -> setHand(npc, args, InteractionHand.OFF_HAND));

        // inv.hand_is(item)
        binding.set("hand_is", (Function<?>) (context, args) -> {
            if (!npc.isSpawned() || args.length() < 1 || !(npc.getEntity() instanceof LivingEntity living))
                return NumberValue.zero();
            ItemStack item = resolveItemStack(args.next().eval(), 1);
            if (item.isEmpty())
                return NumberValue.zero();
            return living.getMainHandItem().is(item.getItem()) ? NumberValue.of(1) : NumberValue.zero();
        });

        // inv.equip(slot, item)
        binding.set("equip", (Function<?>) (context, args) -> {
            if (!npc.isSpawned() || args.length() < 2 || !(npc.getEntity() instanceof LivingEntity living))
                return NumberValue.zero();
            String slotName = args.next().eval().getAsString();
            ItemStack item = resolveItemStack(args.next().eval(), 1);
            EquipmentSlot slot = matchSlot(slotName);
            if (item.isEmpty() || slot == null)
                return NumberValue.zero();
            living.setItemSlot(slot, item);
            return NumberValue.of(1);
        });

        return binding;
    }

    private static Value setHand(NPC npc, Function.Arguments args, InteractionHand hand) {
        if (!npc.isSpawned() || args.length() < 1 || !(npc.getEntity() instanceof LivingEntity living))
            return NumberValue.zero();
        ItemStack item = resolveItemStack(args.next().eval(), 1);
        if (item.isEmpty())
            return NumberValue.zero();
        living.setItemInHand(hand, item);
        return NumberValue.of(1);
    }

    /**
     * Accepts vanilla's slot names ({@code mainhand}, {@code head}) as well as the Bukkit names upstream expects
     * ({@code hand}, {@code off_hand}), so an expression written for the plugin still equips the right slot.
     */
    private static EquipmentSlot matchSlot(String name) {
        switch (name == null ? "" : name.trim().toLowerCase(Locale.ROOT)) {
            case "hand":
            case "mainhand":
            case "main_hand":
                return EquipmentSlot.MAINHAND;
            case "off_hand":
            case "offhand":
                return EquipmentSlot.OFFHAND;
            case "head":
            case "helmet":
                return EquipmentSlot.HEAD;
            case "chest":
            case "chestplate":
                return EquipmentSlot.CHEST;
            case "legs":
            case "leggings":
                return EquipmentSlot.LEGS;
            case "feet":
            case "boots":
                return EquipmentSlot.FEET;
            case "body":
                return EquipmentSlot.BODY;
            default:
                return null;
        }
    }

    private static ObjectValue createItemBinding() {
        MutableObjectBinding binding = new MutableObjectBinding();

        // item.from_component(itemString) or item.from_component(itemString, amount)
        binding.set("from_component", (Function<?>) (context, args) -> {
            if (args.length() < 1)
                return NumberValue.zero();
            String itemString = args.next().eval().getAsString();
            int amount = args.length() > 1 ? (int) args.next().eval().getAsNumber() : 1;
            return new ItemStackValue(ItemStorage.parseItemStack(itemString, amount));
        });

        return binding;
    }

    private static ObjectValue createListBinding(Memory memory) {
        MutableObjectBinding binding = new MutableObjectBinding();

        // list.add(key, value)
        binding.set("add", (Function<?>) (context, args) -> {
            if (memory == null || args.length() < 2)
                return NumberValue.zero();
            memory.listAdd(args.next().eval().getAsString(), args.next().eval().getAsNumber());
            return NumberValue.of(1);
        });

        // list.remove(key, value)
        binding.set("remove", (Function<?>) (context, args) -> {
            if (memory == null || args.length() < 2)
                return NumberValue.zero();
            return memory.listRemove(args.next().eval().getAsString(), args.next().eval().getAsNumber())
                    ? NumberValue.of(1)
                    : NumberValue.zero();
        });

        // list.remove_at(key, index)
        binding.set("remove_at", (Function<?>) (context, args) -> {
            if (memory == null || args.length() < 2)
                return NumberValue.zero();
            return memory.listRemoveAt(args.next().eval().getAsString(),
                    (int) args.next().eval().getAsNumber()) != null ? NumberValue.of(1) : NumberValue.zero();
        });

        // list.clear(key)
        binding.set("clear", (Function<?>) (context, args) -> {
            if (memory == null || args.length() < 1)
                return NumberValue.zero();
            memory.listClear(args.next().eval().getAsString());
            return NumberValue.of(1);
        });

        // list.size(key)
        binding.set("size", (Function<?>) (context, args) -> {
            if (memory == null || args.length() < 1)
                return NumberValue.zero();
            return NumberValue.of(memory.listSize(args.next().eval().getAsString()));
        });

        // list.get(key, index)
        binding.set("get", (Function<?>) (context, args) -> {
            if (memory == null || args.length() < 2)
                return NumberValue.zero();
            Object value = memory.listGet(args.next().eval().getAsString(), (int) args.next().eval().getAsNumber());
            return value instanceof Number number ? NumberValue.of(number.doubleValue()) : NumberValue.zero();
        });

        // list.contains(key, value)
        binding.set("contains", (Function<?>) (context, args) -> {
            if (memory == null || args.length() < 2)
                return NumberValue.zero();
            return memory.listContains(args.next().eval().getAsString(), args.next().eval().getAsNumber())
                    ? NumberValue.of(1)
                    : NumberValue.zero();
        });

        return binding;
    }

    private static ObjectValue createMemBinding(Memory memory) {
        MutableObjectBinding binding = new MutableObjectBinding();

        // mem.set(key, value)
        binding.set("set", (Function<?>) (context, args) -> {
            if (memory == null || args.length() < 2)
                return NumberValue.zero();
            String key = args.next().eval().getAsString();
            double value = args.next().eval().getAsNumber();
            memory.set(key, value);
            return NumberValue.of(value);
        });

        // mem.get(key) or mem.get(key, default)
        binding.set("get", (Function<?>) (context, args) -> {
            if (memory == null || args.length() < 1)
                return NumberValue.zero();
            String key = args.next().eval().getAsString();
            double defaultValue = args.length() > 1 ? args.next().eval().getAsNumber() : 0;
            return NumberValue.of(memory.getNumber(key, defaultValue));
        });

        // mem.has(key)
        binding.set("has", (Function<?>) (context, args) -> {
            if (memory == null || args.length() < 1)
                return NumberValue.zero();
            return memory.has(args.next().eval().getAsString()) ? NumberValue.of(1) : NumberValue.zero();
        });

        // mem.remove(key)
        binding.set("remove", (Function<?>) (context, args) -> {
            if (memory == null || args.length() < 1)
                return NumberValue.zero();
            memory.remove(args.next().eval().getAsString());
            return NumberValue.of(1);
        });

        return binding;
    }

    private static ItemStack resolveItemStack(Value value, int amount) {
        if (value instanceof ItemStackValue itemValue) {
            ItemStack stack = itemValue.getItemStack();
            if (amount != stack.getCount()) {
                stack = stack.copy();
                stack.setCount(amount);
            }
            return stack;
        }
        return ItemStorage.parseItemStack(value.getAsString(), amount);
    }

    /** Converts a Java object to a Mocha value. */
    private static Value toMochaValue(Object value) {
        if (value == null)
            return NumberValue.zero();
        if (value instanceof Value mocha)
            return mocha;
        if (value instanceof Number number)
            return NumberValue.of(number.doubleValue());
        if (value instanceof Boolean bool)
            return NumberValue.of(bool ? 1.0 : 0.0);
        if (value instanceof String string)
            return StringValue.of(string);
        if (value instanceof ItemStack stack)
            return new ItemStackValue(stack);
        return NumberValue.zero();
    }
}
