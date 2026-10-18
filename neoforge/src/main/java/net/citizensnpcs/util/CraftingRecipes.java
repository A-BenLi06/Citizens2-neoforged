package net.citizensnpcs.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/** Registry-backed recipe discovery. Queries never craft items or advance tasks. */
public final class CraftingRecipes {
    private CraftingRecipes() { }
    public enum Kind { SHAPED, SHAPELESS, CREATE_PROCESSING, UNSUPPORTED }

    /** Alternatives are display candidates. The current provider's predicate remains authoritative. */
    public record Input(String definition, boolean custom, List<ItemStack> alternatives) {
        public Input { alternatives = copies(alternatives); }
        @Override public List<ItemStack> alternatives() { return copies(alternatives); }
    }

    public record Snapshot(ResourceLocation id, ResourceLocation type, ResourceLocation serializer, Kind kind,
            String revision, String definition, ResourceLocation workstation, int width, int height, List<Input> inputs, ItemStack output,
            CreateProcessingRecipes.Snapshot processing) {
        public Snapshot(ResourceLocation id, ResourceLocation type, ResourceLocation serializer, Kind kind, String revision,
                String definition, ResourceLocation workstation, int width, int height, List<Input> inputs, ItemStack output) {
            this(id, type, serializer, kind, revision, definition, workstation, width, height, inputs, output, null);
        }
        public Snapshot {
            inputs = List.copyOf(inputs);
            output = output.copy();
        }
        @Override public ItemStack output() { return output.copy(); }
        public boolean supported() { return kind != Kind.UNSUPPORTED; }
        public boolean tableEvidenceSupported() { return kind == Kind.SHAPED || kind == Kind.SHAPELESS; }
    }

    public static Optional<Snapshot> find(MinecraftServer server, ResourceLocation id) {
        active(server);
        return server.getRecipeManager().byKey(java.util.Objects.requireNonNull(id, "id"))
                .map(holder -> snapshot(server, holder));
    }

    public static List<Snapshot> byType(MinecraftServer server, ResourceLocation typeId, int limit) {
        active(server);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Recipe query limit must be 1..100");
        var type = BuiltInRegistries.RECIPE_TYPE.getOptional(java.util.Objects.requireNonNull(typeId, "typeId"))
                .orElseThrow(() -> new IllegalArgumentException("Recipe type is unavailable"));
        return server.getRecipeManager().getRecipes().stream().filter(holder -> holder.value().getType() == type)
                .sorted(java.util.Comparator.comparing(holder -> holder.id().toString())).limit(limit)
                .map(holder -> snapshot(server, holder)).toList();
    }

    /** Output count is not a filter; optional component matching uses the complete native component map. */
    public static List<Snapshot> byOutput(MinecraftServer server, ItemStack output, boolean components, int limit) {
        active(server);
        if (output == null || output.isEmpty()) throw new IllegalArgumentException("Recipe output is required");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Recipe query limit must be 1..100");
        List<Snapshot> found = new ArrayList<>();
        for (var holder : server.getRecipeManager().getRecipes().stream()
                .sorted(java.util.Comparator.comparing(recipe -> recipe.id().toString())).toList()) {
            var processing = CreateProcessingRecipes.snapshot(server, holder.value());
            var results = processing.map(value -> value.itemResults().stream().map(CreateProcessingRecipes.ItemResult::stack).toList())
                    .orElseGet(() -> List.of(holder.value().getResultItem(server.registryAccess())));
            if (results.stream().noneMatch(result -> !result.isEmpty() && (components ? ItemStack.isSameItemSameComponents(output, result) : result.is(output.getItem())))) continue;
            found.add(snapshot(server, holder));
            if (found.size() == limit) break;
        }
        return List.copyOf(found);
    }

    public static boolean current(MinecraftServer server, ResourceLocation id, String revision) {
        return find(server, id).map(snapshot -> snapshot.revision().equals(revision)).orElse(false);
    }

    /** A custom ingredient's candidate list is never substituted for its actual matching logic. */
    public static boolean matchesInput(MinecraftServer server, ResourceLocation id, String revision, int slot, ItemStack stack) {
        Snapshot current = find(server, id).orElseThrow(() -> new IllegalArgumentException("Recipe is unavailable"));
        if (!current.supported() || !current.revision().equals(revision)) throw new IllegalStateException("Recipe changed or is unsupported");
        var recipe = server.getRecipeManager().byKey(id).orElseThrow().value();
        if (slot < 0 || slot >= recipe.getIngredients().size()) throw new IllegalArgumentException("Recipe slot is out of range");
        return recipe.getIngredients().get(slot).test(java.util.Objects.requireNonNull(stack, "stack"));
    }

    private static Snapshot snapshot(MinecraftServer server, RecipeHolder<?> holder) {
        Recipe<?> recipe = holder.value();
        var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        JsonElement definition = Recipe.CODEC.encodeStart(ops, recipe).getOrThrow();
        var processing = CreateProcessingRecipes.snapshot(server, recipe).orElse(null);
        Kind kind = processing == null ? kind(recipe) : Kind.CREATE_PROCESSING;
        if (kind != Kind.UNSUPPORTED && !recipe.canCraftInDimensions(3, 3)) kind = Kind.UNSUPPORTED;
        ItemStack output = recipe.getResultItem(server.registryAccess()).copy();
        if (kind != Kind.UNSUPPORTED && (processing == null ? output.isEmpty() || recipe.isIncomplete()
                : recipe.getIngredients().stream().anyMatch(ingredient -> ingredient.isEmpty() || ingredient.hasNoItems())))
            throw new IllegalStateException("Recipe output or ingredient provider is unavailable");
        int width = kind == Kind.SHAPED ? ((ShapedRecipe) recipe).getWidth() : 0;
        int height = kind == Kind.SHAPED ? ((ShapedRecipe) recipe).getHeight() : 0;
        List<Input> inputs = new ArrayList<>();
        JsonArray expanded = new JsonArray();
        if (kind != Kind.UNSUPPORTED) {
            for (Ingredient ingredient : recipe.getIngredients()) {
                JsonElement nativeDefinition = Ingredient.CODEC.encodeStart(ops, ingredient).getOrThrow();
                var alternatives = java.util.Arrays.asList(ingredient.getItems());
                inputs.add(new Input(canonical(nativeDefinition).toString(), ingredient.isCustom(), alternatives));
                JsonArray candidates = new JsonArray();
                for (ItemStack candidate : alternatives) candidates.add(ItemStack.CODEC.encodeStart(ops, candidate).getOrThrow());
                expanded.add(candidates);
            }
        }
        JsonObject content = new JsonObject();
        content.addProperty("id", holder.id().toString());
        content.add("recipe", definition);
        content.add("resolvedIngredients", expanded);
        if (processing != null) content.add("createProcessing", CreateProcessingRecipes.encode(processing, server.registryAccess()));
        content.add("output", output.isEmpty() ? com.google.gson.JsonNull.INSTANCE : ItemStack.CODEC.encodeStart(ops, output).getOrThrow());
        String revision;
        try { revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical(content).toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        return new Snapshot(holder.id(), BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()),
                BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()), kind, revision,
                canonical(definition).toString(), kind == Kind.UNSUPPORTED ? null : processing != null ? processing.workstation() : BuiltInRegistries.BLOCK.getKey(
                        net.minecraft.world.level.block.Blocks.CRAFTING_TABLE), width, height, inputs, output, processing);
    }

    static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted().forEach(key -> result.add(key, canonical(value.getAsJsonObject().get(key))));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(element -> result.add(canonical(element)));
            return result;
        }
        return value.deepCopy();
    }

    private static Kind kind(Recipe<?> recipe) {
        if (recipe.isSpecial()) return Kind.UNSUPPORTED;
        if (recipe.getClass() == ShapedRecipe.class) return Kind.SHAPED;
        if (recipe.getClass() == ShapelessRecipe.class) return Kind.SHAPELESS;
        // Only the pinned public wrappers with static output and no ingredient actions have this contract.
        String name = recipe.getClass().getName();
        Kind supported = switch (name) {
            case "dev.latvian.mods.kubejs.recipe.special.ShapedKubeJSRecipe" -> Kind.SHAPED;
            case "dev.latvian.mods.kubejs.recipe.special.ShapelessKubeJSRecipe" -> Kind.SHAPELESS;
            default -> Kind.UNSUPPORTED;
        };
        if (supported == Kind.UNSUPPORTED) return supported;
        try {
            Class<?> api = Class.forName("dev.latvian.mods.kubejs.recipe.special.KubeJSCraftingRecipe");
            Object actions = api.getMethod("kjs$getIngredientActions").invoke(recipe);
            Object modifier = api.getMethod("kjs$getModifyResult").invoke(recipe);
            return actions instanceof List<?> list && list.isEmpty() && modifier instanceof String key && key.isEmpty()
                    ? supported : Kind.UNSUPPORTED;
        } catch (ReflectiveOperationException | LinkageError unavailable) { return Kind.UNSUPPORTED; }
    }

    private static List<ItemStack> copies(List<ItemStack> stacks) { return stacks.stream().map(ItemStack::copy).toList(); }
    private static void active(MinecraftServer server) {
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Recipe access requires the server thread");
    }
}
