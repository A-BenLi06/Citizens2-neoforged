package net.yuuniverse.interactions;

import java.util.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.citizensnpcs.util.CraftingRecipes;
import net.citizensnpcs.util.CreateProcessingRecipes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid="interactions")
public final class CreateRuntimeAudit {
    private static int passed;
    private static boolean ran;
    private static String oldRevision;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (ran) return;
        var server=event.getServer(); var level=server.overworld();
        level.setChunkForced(0,0,true);
        if(server.getTickCount()<20 || !level.isPositionEntityTicking(new BlockPos(1,-60,1))) return;
        try {
            var id=ResourceLocation.parse("citizens_audit:create_milling");
            if(reload==null) {
                var recipe=CraftingRecipes.find(server,id).orElseThrow();
                check(recipe.processing()!=null && recipe.processing().itemResults().getFirst().stack().getCount()==3,
                        "kubejs_native_create_recipe_loaded");
                oldRevision=recipe.revision();
                var script=server.getServerDirectory().resolve("kubejs/server_scripts/citizens_create_audit.js");
                var original=java.nio.file.Files.readString(script); var updated=original.replace("count: 3","count: 4");
                if(updated.equals(original)) throw new AssertionError("Unexpected Create fixture script");
                java.nio.file.Files.writeString(script,updated);
                reload=server.reloadResources(server.getPackRepository().getSelectedIds()); return;
            }
            if(!reload.isDone()) return;
            reload.join(); ran=true;
            check(!CraftingRecipes.current(server,id,oldRevision) && CraftingRecipes.find(server,id).orElseThrow().output().getCount()==4,
                    "actual_resource_reload_invalidates_create_revision");
            recipes(server);
            LoggerFactory.getLogger("interactions").info("[CREATEAUDIT] COMPLETE {} checks",passed);
        } catch(Throwable failure) {
            ran=true; LoggerFactory.getLogger("interactions").error("[CREATEAUDIT] FAILED",failure);
        } finally { if(ran) server.halt(false); }
    }
    private static void recipes(MinecraftServer server) throws Exception {
        var wheat=CraftingRecipes.find(server,ResourceLocation.parse("create:milling/wheat")).orElseThrow();
        check(wheat.supported() && !wheat.tableEvidenceSupported() && wheat.processing().process()==CreateProcessingRecipes.Process.MILLING
                && wheat.processing().itemResults().size()==3 && wheat.processing().recipeDuration()==150,
                "real_wheat_recipe_has_duration_all_outputs_and_machine_capability");
        check(wheat.processing().itemResults().get(1).chancePerItem()==0.25f
                && wheat.processing().itemResults().get(1).stack().getCount()==2,"probability_is_per_item_not_guaranteed_batch");
        var brass=CraftingRecipes.find(server,ResourceLocation.parse("create:mixing/brass_ingot")).orElseThrow();
        check(brass.processing().heat().equals("heated") && brass.inputs().size()==2 && brass.output().getCount()==2
                && brass.workstation().equals(ResourceLocation.parse("create:mechanical_mixer")),"real_brass_recipe_preserves_heat_tags_workstation");
        check(CraftingRecipes.byOutput(server,new ItemStack(Items.WHEAT_SEEDS),true,100).stream().anyMatch(value->value.id().equals(wheat.id())),
                "output_search_finds_secondary_probabilistic_products");
        check(CraftingRecipes.matchesInput(server,wheat.id(),wheat.revision(),0,new ItemStack(Items.WHEAT))
                && !CraftingRecipes.matchesInput(server,wheat.id(),wheat.revision(),0,new ItemStack(Items.STONE)),"native_item_predicate_controls_matching");
        var copy=wheat.processing().itemResults().getFirst().stack(); copy.setCount(37);
        check(wheat.processing().itemResults().getFirst().stack().getCount()==1,"item_outputs_are_defensive");
        for(var process:CreateProcessingRecipes.Process.values()) {
            var views=CraftingRecipes.byType(server,ResourceLocation.fromNamespaceAndPath("create",process.name().toLowerCase(Locale.ROOT)),100);
            check(!views.isEmpty() && views.stream().allMatch(value->value.processing()!=null && value.processing().process()==process),
                    "native_type_"+process.name().toLowerCase(Locale.ROOT));
        }
        var sequence=CraftingRecipes.byType(server,ResourceLocation.parse("create:sequenced_assembly"),1).getFirst();
        check(!sequence.supported() && sequence.processing()==null,"assembly_is_explicitly_unsupported");
        var mechanical=CraftingRecipes.byType(server,ResourceLocation.parse("create:mechanical_crafting"),1).getFirst();
        check(!mechanical.supported(),"mechanical_grid_is_not_a_vanilla_table");
        var manager=server.getRecipeManager(); var original=List.copyOf(manager.getRecipes());
        var id=ResourceLocation.parse("citizens_audit:fluid_mix");
        var ops=server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        Recipe<?> recipe=Recipe.CODEC.parse(ops,JsonParser.parseString("""
                {"type":"create:mixing","ingredients":[{"item":"minecraft:stone"},{"type":"neoforge:single","fluid":"minecraft:water","amount":250}],
                 "results":[{"id":"minecraft:paper","count":2,"chance":0.5,"components":{"minecraft:custom_name":"\\\"Mixed\\\""}},
                 {"id":"minecraft:lava","amount":100}],"heat_requirement":"heated"}
                """)).getOrThrow();
        var recipes=new ArrayList<RecipeHolder<?>>(original); recipes.add(new RecipeHolder<>(id,recipe)); manager.replaceRecipes(recipes);
        try {
            var view=CraftingRecipes.find(server,id).orElseThrow(); var processing=view.processing();
            check(processing.fluidInputs().getFirst().amount()==250 && processing.fluidResults().getFirst().getAmount()==100,
                    "native_sized_fluid_inputs_and_outputs");
            check(processing.itemResults().getFirst().stack().getHoverName().getString().equals("Mixed"),"full_output_components_preserved");
            check(CreateProcessingRecipes.matchesFluidInput(server,id,view.revision(),0,new FluidStack(Fluids.WATER,250))
                    && !CreateProcessingRecipes.matchesFluidInput(server,id,view.revision(),0,new FluidStack(Fluids.WATER,249))
                    && !CreateProcessingRecipes.matchesFluidInput(server,id,view.revision(),0,new FluidStack(Fluids.LAVA,250)),"native_sized_fluid_predicate");
            processing.fluidInputs().getFirst().alternatives().getFirst().setAmount(1); processing.fluidResults().getFirst().setAmount(1);
            check(processing.fluidInputs().getFirst().alternatives().getFirst().getAmount()==250
                    && processing.fluidResults().getFirst().getAmount()==100,"fluid_snapshots_are_defensive");
            var named=new ItemStack(Items.PAPER); named.set(DataComponents.CUSTOM_NAME,Component.literal("Mixed"));
            check(CraftingRecipes.byOutput(server,named,true,100).stream().anyMatch(value->value.id().equals(id))
                    && CraftingRecipes.byOutput(server,new ItemStack(Items.PAPER),true,100).stream().noneMatch(value->value.id().equals(id)),
                    "machine_output_search_obeys_complete_components");
            var nativeRecipe=(com.simibubi.create.content.kinetics.mixer.MixingRecipe)recipe;
            nativeRecipe.getFluidIngredients().set(0,net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient.of(Fluids.WATER,500));
            check(!CraftingRecipes.current(server,id,view.revision()),"fluid_redefinition_invalidates_revision");
            try { CreateProcessingRecipes.matchesFluidInput(server,id,view.revision(),0,new FluidStack(Fluids.WATER,1000));
                throw new AssertionError("Stale fluid query accepted"); }
            catch(IllegalStateException expected) { check(true,"stale_native_fluid_match_rejected"); }
            try { java.util.concurrent.CompletableFuture.runAsync(()->CraftingRecipes.find(server,id)).join();
                throw new AssertionError("Worker recipe access accepted"); }
            catch(java.util.concurrent.CompletionException expected) { check(expected.getCause() instanceof IllegalStateException,"worker_recipe_access_rejected"); }
            check(server.getCommands().getDispatcher().execute("citizens recipe "+id,server.createCommandSourceStack())==1,"native_machine_recipe_command");
        } finally { manager.replaceRecipes(original); }
    }
    private static void check(boolean condition,String name) {
        if(!condition) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("interactions").info("[CREATEAUDIT] PASS {}",name);
    }
}
