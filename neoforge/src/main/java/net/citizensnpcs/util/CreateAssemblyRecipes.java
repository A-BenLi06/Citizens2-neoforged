package net.citizensnpcs.util;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

/** Separate native assembly contract: one weighted final result, ordered steps and loops. Never rolls. */
public final class CreateAssemblyRecipes {
    private static final String API = "com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe";
    private CreateAssemblyRecipes() { }
    public record WeightedResult(ItemStack stack, float weight) {
        public WeightedResult {
            if (stack == null || stack.isEmpty() || !Float.isFinite(weight) || weight <= 0)
                throw new IllegalArgumentException("Invalid assembly result");
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }
    public record Step(String definition, List<CraftingRecipes.Input> inputs, CreateProcessingRecipes.Snapshot processing) {
        public Step { inputs = List.copyOf(inputs); }
    }
    public record Snapshot(int loops, ItemStack transitionalItem, CraftingRecipes.Input ingredient,
            List<Step> steps, List<WeightedResult> results) {
        public Snapshot {
            if (loops <= 0 || steps.isEmpty() || results.isEmpty() || transitionalItem.isEmpty())
                throw new IllegalArgumentException("Incomplete assembly recipe");
            Math.multiplyExact(loops, steps.size());
            transitionalItem = transitionalItem.copy(); steps = List.copyOf(steps); results = List.copyOf(results);
        }
        @Override public ItemStack transitionalItem() { return transitionalItem.copy(); }
        public boolean evidenceSupported() { return steps.stream().allMatch(step -> step.processing() != null
                && CreateMachineEvidence.supports(step.processing().process())); }
    }
    static Optional<Snapshot> snapshot(MinecraftServer server, Recipe<?> recipe) {
        if (!recipe.getClass().getName().equals(API)) return Optional.empty();
        try {
            var api = recipe.getClass(); var type = ResourceLocation.parse("create:sequenced_assembly");
            if (!type.equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                    || !type.equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer())))
                throw new IllegalStateException("Create assembly type changed");
            var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            var steps = new java.util.ArrayList<Step>();
            for (var entry : (List<?>) api.getMethod("getSequence").invoke(recipe)) {
                var wrapped = (Recipe<?>) entry.getClass().getMethod("getRecipe").invoke(entry);
                var contract = Class.forName("com.simibubi.create.content.processing.sequenced.IAssemblyRecipe");
                if (!contract.isInstance(wrapped) || !(boolean) contract.getMethod("supportsAssembly").invoke(wrapped))
                    throw new IllegalStateException("Unsupported native assembly step");
                var inputs = new java.util.ArrayList<CraftingRecipes.Input>();
                for (var ingredient : wrapped.getIngredients()) inputs.add(input(server, ingredient));
                steps.add(new Step(CraftingRecipes.canonical(Recipe.CODEC.encodeStart(ops, wrapped).getOrThrow()).toString(),
                        inputs, CreateProcessingRecipes.snapshot(server, wrapped).orElse(null)));
            }
            var results = new java.util.ArrayList<WeightedResult>(); double total = 0;
            for (var output : (List<?>) api.getField("resultPool").get(recipe)) {
                var value = new WeightedResult((ItemStack) output.getClass().getMethod("getStack").invoke(output),
                        (float) output.getClass().getMethod("getChance").invoke(output));
                results.add(value); total += value.weight();
            }
            if (!Double.isFinite(total) || total > Float.MAX_VALUE) throw new IllegalStateException("Assembly result weight overflow");
            return Optional.of(new Snapshot((int) api.getMethod("getLoops").invoke(recipe),
                    (ItemStack) api.getMethod("getTransitionalItem").invoke(recipe),
                    input(server, (Ingredient) api.getMethod("getIngredient").invoke(recipe)), steps, results));
        } catch (InvocationTargetException failure) { throw new IllegalStateException("Create assembly provider failed", failure.getCause()); }
        catch (ReflectiveOperationException | LinkageError failure) { throw new IllegalStateException("Create assembly API unavailable", failure); }
    }
    private static CraftingRecipes.Input input(MinecraftServer server, Ingredient ingredient) {
        if (ingredient.isEmpty() || ingredient.hasNoItems()) throw new IllegalStateException("Assembly ingredient unavailable");
        var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return new CraftingRecipes.Input(CraftingRecipes.canonical(Ingredient.CODEC.encodeStart(ops, ingredient).getOrThrow()).toString(),
                ingredient.isCustom(), java.util.Arrays.asList(ingredient.getItems()));
    }
    static JsonObject encode(Snapshot snapshot, HolderLookup.Provider registries) {
        var ops = registries.createSerializationContext(JsonOps.INSTANCE); var root = new JsonObject();
        root.addProperty("loops", snapshot.loops()); root.add("transitionalItem", ItemStack.CODEC.encodeStart(ops, snapshot.transitionalItem()).getOrThrow());
        root.add("ingredient", encodeInput(snapshot.ingredient(), registries)); var steps = new JsonArray();
        for (var step : snapshot.steps()) {
            var value = new JsonObject(); value.add("definition", com.google.gson.JsonParser.parseString(step.definition()));
            var inputs = new JsonArray(); step.inputs().forEach(input -> inputs.add(encodeInput(input, registries))); value.add("inputs", inputs);
            if (step.processing() != null) value.add("processing", CreateProcessingRecipes.encode(step.processing(), registries));
            steps.add(value);
        }
        root.add("steps", steps); var results = new JsonArray();
        for (var result : snapshot.results()) {
            var value = new JsonObject(); value.add("stack", ItemStack.CODEC.encodeStart(ops, result.stack()).getOrThrow());
            value.addProperty("weight", result.weight()); results.add(value);
        }
        root.add("weightedResults", results); return root;
    }
    private static JsonObject encodeInput(CraftingRecipes.Input input, HolderLookup.Provider registries) {
        var root = new JsonObject(); root.add("definition", com.google.gson.JsonParser.parseString(input.definition()));
        root.addProperty("custom", input.custom()); var candidates = new JsonArray();
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        input.alternatives().forEach(stack -> candidates.add(ItemStack.CODEC.encodeStart(ops, stack).getOrThrow()));
        root.add("alternatives", candidates); return root;
    }
}
