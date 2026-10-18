package net.citizensnpcs.util;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/** Optional read-only adapter to Create 6's public processing recipe contract. Never rolls results. */
public final class CreateProcessingRecipes {
    private static final String CONTENT = "com.simibubi.create.content.";
    private static final String API = CONTENT + "processing.recipe.ProcessingRecipe";
    private CreateProcessingRecipes() { }

    /** Provider-specific workstation and implementation contracts, not a pack's recipe or item allowlist. */
    public enum Process {
        MILLING("kinetics.millstone.MillingRecipe", "millstone"),
        CRUSHING("kinetics.crusher.CrushingRecipe", "crushing_wheel"),
        MIXING("kinetics.mixer.MixingRecipe", "mechanical_mixer"),
        COMPACTING("kinetics.mixer.CompactingRecipe", "mechanical_press"),
        PRESSING("kinetics.press.PressingRecipe", "mechanical_press"),
        CUTTING("kinetics.saw.CuttingRecipe", "mechanical_saw"),
        SPLASHING("kinetics.fan.processing.SplashingRecipe", "encased_fan"),
        HAUNTING("kinetics.fan.processing.HauntingRecipe", "encased_fan"),
        DEPLOYING("kinetics.deployer.DeployerApplicationRecipe", "deployer"),
        FILLING("fluids.transfer.FillingRecipe", "spout"),
        EMPTYING("fluids.transfer.EmptyingRecipe", "item_drain");

        private final String implementation;
        private final ResourceLocation workstation;
        Process(String implementation, String workstation) {
            this.implementation = CONTENT + implementation;
            this.workstation = ResourceLocation.fromNamespaceAndPath("create", workstation);
        }
        public ResourceLocation workstation() { return workstation; }
    }

    /** Create rolls each item in a result stack independently with this probability. */
    public record ItemResult(ItemStack stack, float chancePerItem) {
        public ItemResult {
            if (stack == null || stack.isEmpty() || !Float.isFinite(chancePerItem) || chancePerItem < 0 || chancePerItem > 1)
                throw new IllegalArgumentException("Invalid processing result");
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    public record FluidInput(String definition, int amount, List<FluidStack> alternatives) {
        public FluidInput {
            if (amount <= 0) throw new IllegalArgumentException("Fluid amount must be positive");
            alternatives = fluidCopies(alternatives);
        }
        @Override public List<FluidStack> alternatives() { return fluidCopies(alternatives); }
    }

    /** Zero recipe duration means unspecified; native machinery determines elapsed time/speed. No energy is inferred. */
    public record Snapshot(Process process, int recipeDuration, String heat, String heatTranslationKey,
            List<FluidInput> fluidInputs, List<ItemResult> itemResults, List<FluidStack> fluidResults,
            List<Integer> retainedItemSlots) {
        public Snapshot {
            if (recipeDuration < 0) throw new IllegalArgumentException("Invalid processing duration");
            fluidInputs = List.copyOf(fluidInputs); itemResults = List.copyOf(itemResults);
            fluidResults = fluidCopies(fluidResults); retainedItemSlots = List.copyOf(retainedItemSlots);
        }
        @Override public List<FluidStack> fluidResults() { return fluidCopies(fluidResults); }
        public ResourceLocation workstation() { return process.workstation(); }
    }

    static Optional<Snapshot> snapshot(MinecraftServer server, Recipe<?> recipe) {
        Process process = Arrays.stream(Process.values()).filter(value -> value.implementation.equals(recipe.getClass().getName()))
                .findFirst().orElse(null);
        if (process == null) return Optional.empty();
        try {
            Class<?> api = Class.forName(API);
            if (!api.isInstance(recipe)) throw new IllegalStateException("Create processing contract changed");
            if (!((List<?>) api.getMethod("validate").invoke(recipe)).isEmpty())
                throw new IllegalStateException("Create recipe failed native validation");
            var typeInfo = api.getMethod("getTypeInfo").invoke(recipe);
            var typeId = (ResourceLocation) Class.forName("com.simibubi.create.foundation.recipe.IRecipeTypeInfo")
                    .getMethod("getId").invoke(typeInfo);
            if (!ResourceLocation.fromNamespaceAndPath("create", process.name().toLowerCase(java.util.Locale.ROOT)).equals(typeId)
                    || !typeId.equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                    || !typeId.equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer())))
                throw new IllegalStateException("Create processing type changed");
            if (BuiltInRegistries.BLOCK.getOptional(process.workstation()).filter(block -> block != Blocks.AIR).isEmpty())
                throw new IllegalStateException("Create workstation is unavailable");
            Class<?> outputApi = Class.forName(CONTENT + "processing.recipe.ProcessingOutput");
            var results = new java.util.ArrayList<ItemResult>();
            for (Object result : (List<?>) api.getMethod("getRollableResults").invoke(recipe))
                results.add(new ItemResult((ItemStack) outputApi.getMethod("getStack").invoke(result),
                        (float) outputApi.getMethod("getChance").invoke(result)));
            var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            var fluids = new java.util.ArrayList<FluidInput>();
            for (Object input : (List<?>) api.getMethod("getFluidIngredients").invoke(recipe)) {
                var sized = (SizedFluidIngredient) input;
                var alternatives = Arrays.asList(sized.getFluids());
                if (alternatives.isEmpty()) throw new IllegalStateException("Create fluid ingredient is unavailable");
                fluids.add(new FluidInput(CraftingRecipes.canonical(SizedFluidIngredient.FLAT_CODEC.encodeStart(ops, sized).getOrThrow())
                        .toString(), sized.amount(), alternatives));
            }
            var fluidResults = new java.util.ArrayList<FluidStack>();
            for (Object output : (List<?>) api.getMethod("getFluidResults").invoke(recipe)) {
                var stack = (FluidStack) output;
                if (stack.isEmpty()) throw new IllegalStateException("Create fluid result is unavailable");
                fluidResults.add(stack);
            }
            if (results.isEmpty() && fluidResults.isEmpty()) throw new IllegalStateException("Create output is unavailable");
            Object heat = api.getMethod("getRequiredHeat").invoke(recipe);
            Class<?> heatApi = Class.forName(CONTENT + "processing.recipe.HeatCondition");
            List<Integer> retained = process == Process.DEPLOYING && (boolean) Class.forName(CONTENT + "kinetics.deployer.ItemApplicationRecipe")
                    .getMethod("shouldKeepHeldItem").invoke(recipe) ? List.of(1) : List.of();
            return Optional.of(new Snapshot(process, (int) api.getMethod("getProcessingDuration").invoke(recipe),
                    (String) heatApi.getMethod("getSerializedName").invoke(heat),
                    (String) heatApi.getMethod("getTranslationKey").invoke(heat), fluids, results, fluidResults, retained));
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Create processing provider failed", failure.getCause());
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            throw new IllegalStateException("Create processing API is unavailable", unavailable);
        }
    }

    /** Uses the current sized native predicate, including amount/components; candidates are for guidance only. */
    public static boolean matchesFluidInput(MinecraftServer server, ResourceLocation id, String revision, int slot, FluidStack stack) {
        var current = CraftingRecipes.find(server, id).orElseThrow(() -> new IllegalArgumentException("Recipe is unavailable"));
        if (current.processing() == null || !current.revision().equals(revision)) throw new IllegalStateException("Recipe changed or is unsupported");
        try {
            var recipe = server.getRecipeManager().byKey(id).orElseThrow().value();
            var inputs = (List<?>) Class.forName(API).getMethod("getFluidIngredients").invoke(recipe);
            if (slot < 0 || slot >= inputs.size()) throw new IllegalArgumentException("Fluid slot is out of range");
            return ((SizedFluidIngredient) inputs.get(slot)).test(java.util.Objects.requireNonNull(stack));
        } catch (ReflectiveOperationException | LinkageError unavailable) { throw new IllegalStateException("Create fluid predicate is unavailable", unavailable); }
    }

    static JsonObject encode(Snapshot snapshot, net.minecraft.core.HolderLookup.Provider registries) {
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        var root = new JsonObject();
        root.addProperty("process", snapshot.process().name()); root.addProperty("recipeDuration", snapshot.recipeDuration());
        root.addProperty("heat", snapshot.heat()); root.addProperty("heatTranslationKey", snapshot.heatTranslationKey());
        var fluids = new JsonArray();
        for (var input : snapshot.fluidInputs()) {
            var value = new JsonObject(); value.add("definition", com.google.gson.JsonParser.parseString(input.definition()));
            value.addProperty("amount", input.amount()); var alternatives = new JsonArray();
            for (var stack : input.alternatives()) alternatives.add(FluidStack.CODEC.encodeStart(ops, stack).getOrThrow());
            value.add("alternatives", alternatives); fluids.add(value);
        }
        root.add("fluidInputs", fluids); var results = new JsonArray();
        for (var output : snapshot.itemResults()) {
            var value = new JsonObject(); value.add("stack", ItemStack.CODEC.encodeStart(ops, output.stack()).getOrThrow());
            value.addProperty("chancePerItem", output.chancePerItem()); results.add(value);
        }
        root.add("itemResults", results); var fluidResults = new JsonArray();
        for (var stack : snapshot.fluidResults()) fluidResults.add(FluidStack.CODEC.encodeStart(ops, stack).getOrThrow());
        root.add("fluidResults", fluidResults); var retained = new JsonArray(); snapshot.retainedItemSlots().forEach(retained::add);
        root.add("retainedItemSlots", retained); return root;
    }

    private static List<FluidStack> fluidCopies(List<FluidStack> stacks) { return stacks.stream().map(FluidStack::copy).toList(); }
}
