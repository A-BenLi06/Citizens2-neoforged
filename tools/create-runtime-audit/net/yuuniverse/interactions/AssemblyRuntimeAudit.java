package net.yuuniverse.interactions;

import java.util.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid="interactions")
public final class AssemblyRuntimeAudit {
    private static int passed;
    private static boolean recording, reject, rejectBucket, throwOutput, mutate, changeTool, changeFluid;
    private static ItemEntity activeInput;
    private static com.simibubi.create.content.logistics.depot.DepotBlockEntity activeDepot;
    private static com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity activeDeployer;
    private static net.neoforged.neoforge.fluids.capability.IFluidHandler activeTank;
    private static Runnable revise;
    private static final List<CreateMachineEvidence.Observation> observations=new ArrayList<>();
    private static final List<MachineOutcomeEvent> outcomes=new ArrayList<>();
    @SubscribeEvent public static void result(MachineResultEvent event){if(recording&&event.getObservation().assembly()!=null){observations.add(event.getObservation());if(mutate){if(activeInput!=null)activeInput.getItem().grow(1);else activeDepot.getHeldItem().grow(1);}if(changeTool)activeDeployer.getPlayer().getMainHandItem().grow(1);if(changeFluid)activeTank.drain(1,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);if(revise!=null)revise.run();}}
    @SubscribeEvent public static void outcome(MachineOutcomeEvent event){if(recording&&event.getObservation().assembly()!=null)outcomes.add(event);}
    @SubscribeEvent public static void insert(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event){
        if(!recording||!(event.getEntity() instanceof ItemEntity item))return;
        if(throwOutput&&item.getItem().is(Items.PAPER))throw new IllegalStateException("Expected native assembly storage failure");
        if(reject&&item.getItem().is(Items.PAPER)||rejectBucket&&item.getItem().is(Items.BUCKET))event.setCanceled(true);
    }
    public static void run(MinecraftServer server)throws Exception{
        var level=server.overworld();var manager=server.getRecipeManager();var original=List.copyOf(manager.getRecipes());
        var id=ResourceLocation.parse("citizens_audit:assembly_press");var randomId=ResourceLocation.parse("citizens_audit:assembly_random");
        var recipe=recipe(server,"""
            {"type":"create:sequenced_assembly","ingredient":{"item":"minecraft:snowball"},"transitional_item":{"id":"minecraft:prismarine_shard"},"loops":2,
             "sequence":[{"type":"create:pressing","ingredients":[{"item":"minecraft:prismarine_shard"}],"results":[{"id":"minecraft:prismarine_shard"}]}],
             "results":[{"id":"minecraft:paper","count":2,"components":{"minecraft:custom_name":"\\\"Assembled result\\\""},"chance":120.0}]}
            """);
        var randomRecipe=recipe(server,"""
            {"type":"create:sequenced_assembly","ingredient":{"item":"minecraft:chorus_fruit"},"transitional_item":{"id":"minecraft:echo_shard"},"loops":2,
             "sequence":[{"type":"create:pressing","ingredients":[{"item":"minecraft:echo_shard"}],"results":[{"id":"minecraft:echo_shard"}]}],
             "results":[{"id":"minecraft:paper","chance":1.0},{"id":"minecraft:diamond","chance":3.0}]}
            """);
        var expanded=new ArrayList<RecipeHolder<?>>(original);expanded.add(new RecipeHolder<>(id,recipe));expanded.add(new RecipeHolder<>(randomId,randomRecipe));manager.replaceRecipes(expanded);
        var file=dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();var chapter=new dev.ftb.mods.ftbquests.quest.Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();var quest=new dev.ftb.mods.ftbquests.quest.Quest(file.newID(),chapter);quest.onCreated();
        var task=task(file,quest);var randomTask=task(file,quest);file.clearCachedData();file.clearCachedProgress();
        var actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"AssemblyActor"),ClientInformation.createDefault());actor.setPos(1,-60,1);var channel=CreateRuntimeAudit.connect(actor);var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();
        var path=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"assembly-evidence-");var service=CraftQuestEvidence.open(server,path);
        var pos=new BlockPos(12,-58,13);var motorPos=pos.west();var ground=pos.below(2);var oldBulk=AllConfigs.server().recipes.bulkPressing.get();recording=true;
        try{
            clear(level,pos);level.setBlockAndUpdate(ground,Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(pos,AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST));
            var press=(MechanicalPressBlockEntity)level.getBlockEntity(pos);press.initialize();var motor=motor(level,motorPos);for(int tick=0;tick<10;tick++){motor.tick();press.tick();}AllConfigs.server().recipes.bulkPressing.set(true);
            var snapshot=CraftingRecipes.find(server,id).orElseThrow();
            check(snapshot.kind()==CraftingRecipes.Kind.CREATE_ASSEMBLY&&snapshot.processing()==null&&snapshot.assembly().loops()==2&&snapshot.assembly().steps().size()==1&&snapshot.workstation()==null,"assembly_discovery_preserves_separate_loop_step_and_workstation_contract");
            check(snapshot.assembly().results().getFirst().weight()==120&&snapshot.assembly().results().getFirst().stack().getHoverName().getString().equals("Assembled result"),"assembly_results_preserve_weights_above_one_and_components");
            check(CraftingRecipes.byOutput(server,new ItemStack(Items.DIAMOND),false,100).stream().anyMatch(s->s.id().equals(randomId)),"assembly_secondary_weighted_results_are_discoverable");
            check(CraftingRecipes.matchesInput(server,id,snapshot.revision(),0,new ItemStack(Items.SNOWBALL))&&!CraftingRecipes.matchesInput(server,id,snapshot.revision(),0,new ItemStack(Items.STONE)),"assembly_native_initial_ingredient_predicate_is_authoritative");
            snapshot.assembly().transitionalItem().grow(3);snapshot.assembly().results().getFirst().stack().grow(5);
            check(snapshot.assembly().transitionalItem().getCount()==1&&snapshot.assembly().results().getFirst().stack().getCount()==2,"assembly_recipe_snapshots_are_defensive");
            var binding=service.bind(source,task.getCodeString(),id);service.bind(source,randomTask.getCodeString(),randomId);
            var first=runPress(press,motor,new ItemStack(Items.SNOWBALL,3));
            check(first.getItem().has(AllDataComponents.SEQUENCED_ASSEMBLY)&&first.getItem().get(AllDataComponents.SEQUENCED_ASSEMBLY).step()==1&&observations.isEmpty(),"native_intermediate_assembly_step_has_no_completed_evidence");
            var intermediate=first.getItem().copy();clear(level,pos);var unassigned=runPress(press,motor,intermediate);
            check(unassigned.getItem().is(Items.PAPER)&&observations.isEmpty()&&team.getProgress(task)==0,"unassigned_final_assembly_process_has_no_credit");clear(level,pos);
            CreateMachineEvidence.assign(source,actor.getUUID(),pos);var finalEntity=runPress(press,motor,intermediate);var observation=observations.getLast();var outcome=outcomes.getLast();
            check(observation.recipe().id().equals(id)&&observation.recipe().revision().equals(snapshot.revision())&&observation.assembly().step()==1&&observation.assembly().totalSteps()==2&&outcome.getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==6,"native_bulk_final_assembly_confirms_parent_recipe_and_actual_primary_output");
            check(observation.assembly().finalOutputs().size()==3&&finalEntity.getItem().getCount()==6&&!finalEntity.getItem().has(AllDataComponents.SEQUENCED_ASSEMBLY),"assembly_captures_each_actual_final_roll_and_stored_components");
            var encoded=JsonParser.parseString(CreateMachineEvidence.encode(observation,server.registryAccess())).getAsJsonObject();
            check(encoded.has("assemblyRecipe")&&encoded.getAsJsonObject("assembly").getAsJsonArray("finalOutputs").size()==3&&!encoded.has("processing"),"assembly_evidence_serializes_final_step_and_weighted_definition");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new MachineResultEvent(actor,observation));net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outcome);check(team.getProgress(task)==6,"assembly_duplicate_notifications_do_not_repeat_progress");clear(level,pos);
            int before=observations.size();var selected=SequencedAssemblyRecipe.getRecipe(level,intermediate,com.simibubi.create.AllRecipeTypes.PRESSING.getType(),com.simibubi.create.content.kinetics.press.PressingRecipe.class).orElseThrow();
            com.simibubi.create.foundation.recipe.RecipeApplier.applyRecipeOn(level,intermediate,selected.value(),true);check(observations.size()==before,"unrelated_native_assembly_utility_does_not_acquire_machine_evidence");
            AllConfigs.server().recipes.bulkPressing.set(false);reject=true;runPress(press,motor,intermediate);reject=false;check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(task)==6,"rejected_native_final_assembly_entity_stays_in_review");resolve(service,source);clear(level,pos);AllConfigs.server().recipes.bulkPressing.set(true);
            mutate=true;runPress(press,motor,intermediate);mutate=false;check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(task)==6,"changed_final_assembly_input_cannot_confirm_output");resolve(service,source);clear(level,pos);
            AllConfigs.server().recipes.bulkPressing.set(false);finalEntity=runPress(press,motor,intermediate);
            check(finalEntity.getItem().getCount()==2&&finalEntity.getItem().has(AllDataComponents.SEQUENCED_ASSEMBLY)&&observation.consumedInput().getCount()==3&&observations.getLast().consumedInput().getCount()==1&&team.getProgress(task)==8,"single_assembly_final_roll_retains_unprocessed_intermediates");clear(level,pos);AllConfigs.server().recipes.bulkPressing.set(true);
            first=runPress(press,motor,new ItemStack(Items.CHORUS_FRUIT,8));var randomIntermediate=first.getItem().copy();clear(level,pos);level.random.setSeed(42);runPress(press,motor,randomIntermediate);var randomObservation=observations.getLast();
            long primary=randomObservation.assembly().finalOutputs().stream().filter(s->s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum();
            check(randomObservation.assembly().finalOutputs().size()==8&&randomObservation.assembly().finalOutputs().stream().anyMatch(s->s.is(Items.DIAMOND))&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(randomTask)==primary,"native_weighted_assembly_scrap_does_not_count_as_primary_progress");clear(level,pos);
            server.getPlayerList().remove(actor);channel.finishAndReleaseAll();before=observations.size();runPress(press,motor,intermediate);check(observations.size()==before&&team.getProgress(task)==8,"offline_final_assembly_beneficiary_has_no_credit");channel=CreateRuntimeAudit.connect(actor);clear(level,pos);
            level.setBlockAndUpdate(ground,AllBlocks.DEPOT.getDefaultState());activeDepot=(com.simibubi.create.content.logistics.depot.DepotBlockEntity)level.getBlockEntity(ground);activeDepot.initialize();
            before=observations.size();activeDepot.setHeldItem(intermediate.copy());for(int tick=0;tick<600&&observations.size()==before;tick++){motor.tick();press.tick();activeDepot.tick();}
            check(observations.size()==before+1&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==14&&countDepot(activeDepot,Items.PAPER)==6,"native_depot_final_assembly_press_confirms_complete_storage");activeDepot.clearContent();
            mutate=true;before=observations.size();activeDepot.setHeldItem(intermediate.copy());for(int tick=0;tick<600&&observations.size()==before;tick++){motor.tick();press.tick();activeDepot.tick();}mutate=false;
            check(observations.size()==before+1&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(task)==14,"changed_final_assembly_depot_input_stays_in_review");resolve(service,source);activeDepot.clearContent();level.removeBlock(ground,false);activeDepot=null;
            mixedMachines(server,service,source,actor,file,quest,team,expanded);
            var beltStart=new BlockPos(12,-60,11);var beltEnd=new BlockPos(12,-60,16);var beltMotorPos=beltStart.west();
            try{
                level.setBlockAndUpdate(beltStart,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));level.setBlockAndUpdate(beltEnd,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));
                com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem.createBelts(level,beltStart,beltEnd);com.simibubi.create.content.kinetics.belt.BeltBlock.initBelt(level,beltStart);
                var belt=(com.simibubi.create.content.kinetics.belt.BeltBlockEntity)level.getBlockEntity(beltStart);belt.initialize();var beltMotor=motor(level,beltMotorPos);
                for(int tick=0;tick<10;tick++){motor.tick();beltMotor.tick();press.tick();belt.tick();}
                var transported=new com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack(intermediate.copy());transported.beltPosition=0.5f;transported.prevBeltPosition=0.5f;belt.getInventory().addItem(transported);before=observations.size();
                for(int tick=0;tick<1000&&observations.size()==before;tick++){motor.tick();beltMotor.tick();press.tick();belt.tick();}
                check(observations.size()==before+1&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==20,"native_connected_belt_final_assembly_press_confirms_complete_pending_storage");
                check(((CreateTransportInventory)belt.getInventory()).citizens$transportSnapshot().stream().filter(s->s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum()==6,"final_assembly_belt_snapshot_keeps_outputs_without_serializing_queues");
            }finally{for(int z=beltStart.getZ();z<=beltEnd.getZ();z++)level.removeBlock(new BlockPos(beltStart.getX(),beltStart.getY(),z),false);level.removeBlock(beltMotorPos,false);clear(level,pos);}
            for(int tick=0;tick<600&&press.pressingBehaviour.running;tick++){motor.tick();press.tick();}
            check(!press.pressingBehaviour.running,"native_belt_press_cycle_finishes_before_recipe_replacement");
            var revised=recipe(server,"""
                {"type":"create:sequenced_assembly","ingredient":{"item":"minecraft:snowball"},"transitional_item":{"id":"minecraft:prismarine_shard"},"loops":2,
                 "sequence":[{"type":"create:pressing","ingredients":[{"item":"minecraft:prismarine_shard"}],"results":[{"id":"minecraft:prismarine_shard"}]}],"results":[{"id":"minecraft:paper","count":3}]}
                """);var replaced=new ArrayList<RecipeHolder<?>>(expanded);replaced.removeIf(h->h.id().equals(id));replaced.add(new RecipeHolder<>(id,revised));manager.replaceRecipes(replaced);level.setBlockAndUpdate(ground,Blocks.STONE.defaultBlockState());runPress(press,motor,intermediate);
            check(observations.getLast().recipe().revision().equals(CraftingRecipes.find(server,id).orElseThrow().revision())&&!observations.getLast().recipe().revision().equals(snapshot.revision())&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==20,"replaced_assembly_parent_uses_actual_current_revision_without_old_binding_credit");manager.replaceRecipes(expanded);clear(level,pos);
            revise=()->manager.replaceRecipes(replaced);runPress(press,motor,intermediate);revise=null;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(task)==20,"assembly_parent_revision_change_during_admission_requires_review");manager.replaceRecipes(expanded);resolve(service,source);clear(level,pos);
            var sameDefinition=recipe(server,Recipe.CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),recipe).getOrThrow().toString());
            var replacedIdentity=new ArrayList<RecipeHolder<?>>(expanded);replacedIdentity.removeIf(h->h.id().equals(id));replacedIdentity.add(new RecipeHolder<>(id,sameDefinition));
            revise=()->manager.replaceRecipes(replacedIdentity);runPress(press,motor,intermediate);revise=null;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&CraftingRecipes.current(server,id,snapshot.revision())&&team.getProgress(task)==20,"same_revision_parent_identity_replacement_during_storage_requires_review");manager.replaceRecipes(expanded);resolve(service,source);clear(level,pos);
            edgeCases(server,service,source,file,quest,team,expanded,press,motor);
            service.close();service=CraftQuestEvidence.open(server,path);check(service.bindings(source).stream().anyMatch(b->b.epoch().equals(binding.epoch()))&&service.reconcile(source)==0&&team.getProgress(task)==20&&team.getProgress(randomTask)==primary,"reopened_assembly_journal_preserves_absolute_final_progress");
            org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEASSEMBLYAUDIT] COMPLETE {} checks",passed);
        }finally{recording=false;reject=false;rejectBucket=false;throwOutput=false;MachineRemainderItem.fail=false;MachineRemainderItem.distinctRemainders=false;mutate=false;changeTool=false;changeFluid=false;revise=null;activeDeployer=null;activeTank=null;activeInput=null;activeDepot=null;service.close();AllConfigs.server().recipes.bulkPressing.set(oldBulk);clear(level,pos);for(var p:List.of(pos,motorPos,ground))level.removeBlock(p,false);server.getPlayerList().remove(actor);channel.finishAndReleaseAll();manager.replaceRecipes(original);}
    }
    private static void edgeCases(MinecraftServer server,CraftQuestEvidence service,net.minecraft.commands.CommandSourceStack source,
            dev.ftb.mods.ftbquests.quest.ServerQuestFile file,dev.ftb.mods.ftbquests.quest.Quest quest,dev.ftb.mods.ftbquests.quest.TeamData team,
            List<RecipeHolder<?>> original,MechanicalPressBlockEntity press,CreativeMotorBlockEntity motor)throws Exception{
        var level=server.overworld();var pos=press.getBlockPos();var manager=server.getRecipeManager();var id=ResourceLocation.parse("citizens_audit:assembly_remainder");
        var recipe=recipe(server,"""
            {"type":"create:sequenced_assembly","ingredient":{"item":"interactions:audit_machine_remainder"},"transitional_item":{"id":"interactions:audit_machine_remainder"},"loops":1,
             "sequence":[{"type":"create:pressing","ingredients":[{"item":"interactions:audit_machine_remainder"}],"results":[{"id":"interactions:audit_machine_remainder"}]}],"results":[{"id":"minecraft:paper","count":2}]}
            """);var recipes=new ArrayList<RecipeHolder<?>>(original);recipes.add(new RecipeHolder<>(id,recipe));manager.replaceRecipes(recipes);
        var task=task(file,quest);file.clearCachedData();file.clearCachedProgress();service.bind(source,task.getCodeString(),id);
        try{
            MachineRemainderItem.calls=0;MachineRemainderItem.distinctRemainders=true;runPress(press,motor,new ItemStack(MachineRemainderItem.item,3));var observation=observations.getLast();
            check(MachineRemainderItem.calls==3&&observation.nativeRemainder().is(Items.BUCKET)&&observation.additionalRemainders().size()==2&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==6,
                    "final_assembly_native_remainders_execute_once_without_primary_credit");
            check(level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(3)).stream().filter(e->e.getItem().is(Items.BUCKET)).map(e->e.getItem().getHoverName().getString()).distinct().count()==3,
                    "final_assembly_storage_preserves_distinct_native_remainder_components");clear(level,pos);
            rejectBucket=true;runPress(press,motor,new ItemStack(MachineRemainderItem.item,2));rejectBucket=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(task)==6,"rejected_final_assembly_remainder_storage_requires_review");resolve(service,source);clear(level,pos);
            var duplicate=new ArrayList<RecipeHolder<?>>(recipes);duplicate.add(new RecipeHolder<>(ResourceLocation.parse("citizens_audit:ambiguous_assembly"),recipe));manager.replaceRecipes(duplicate);int before=observations.size();runPress(press,motor,new ItemStack(MachineRemainderItem.item));
            check(observations.size()==before&&team.getProgress(task)==6,"ambiguous_registered_assembly_parent_has_no_attributed_evidence");manager.replaceRecipes(recipes);clear(level,pos);
            MachineRemainderItem.calls=0;MachineRemainderItem.fail=true;before=observations.size();
            try{runPress(press,motor,new ItemStack(MachineRemainderItem.item,2));throw new AssertionError("Native remainder exception hidden");}
            catch(IllegalStateException expected){check(MachineRemainderItem.calls==1&&observations.size()==before&&team.getProgress(task)==6,"assembly_remainder_exception_before_admission_preserves_native_failure");}
            finally{MachineRemainderItem.fail=false;clear(level,pos);}
            for(int tick=0;tick<600&&press.pressingBehaviour.running;tick++){motor.tick();press.tick();}
            AllConfigs.server().recipes.bulkPressing.set(false);throwOutput=true;
            try{runPress(press,motor,new ItemStack(MachineRemainderItem.item,2));throw new AssertionError("Native storage exception hidden");}
            catch(IllegalStateException expected){check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.INTERRUPTED&&team.getProgress(task)==6,"assembly_native_storage_exception_after_admission_is_interrupted");}
            finally{throwOutput=false;AllConfigs.server().recipes.bulkPressing.set(true);clear(level,pos);}resolve(service,source);
        }finally{rejectBucket=false;throwOutput=false;MachineRemainderItem.fail=false;MachineRemainderItem.distinctRemainders=false;manager.replaceRecipes(original);clear(level,pos);}
    }
    private static void mixedMachines(MinecraftServer server,CraftQuestEvidence service,net.minecraft.commands.CommandSourceStack source,ServerPlayer actor,
            dev.ftb.mods.ftbquests.quest.ServerQuestFile file,dev.ftb.mods.ftbquests.quest.Quest quest,dev.ftb.mods.ftbquests.quest.TeamData team,List<RecipeHolder<?>> original)throws Exception{
        var level=server.overworld();var id=ResourceLocation.parse("citizens_audit:assembly_mixed");var finalDeployId=ResourceLocation.parse("citizens_audit:assembly_final_deploy");var finalFillId=ResourceLocation.parse("citizens_audit:assembly_final_fill");
        var mixed=recipe(server,"""
            {"type":"create:sequenced_assembly","ingredient":{"item":"minecraft:magma_cream"},"transitional_item":{"id":"minecraft:prismarine_crystals"},"loops":1,
             "sequence":[{"type":"create:pressing","ingredients":[{"item":"minecraft:prismarine_crystals"}],"results":[{"id":"minecraft:prismarine_crystals"}]},
              {"type":"create:deploying","ingredients":[{"item":"minecraft:prismarine_crystals"},{"item":"minecraft:flint"}],"results":[{"id":"minecraft:prismarine_crystals"}]},
              {"type":"create:filling","ingredients":[{"item":"minecraft:prismarine_crystals"},{"type":"fluid_stack","fluid":"minecraft:water","amount":250}],"results":[{"id":"minecraft:prismarine_crystals"}]},
              {"type":"create:cutting","ingredients":[{"item":"minecraft:prismarine_crystals"}],"results":[{"id":"minecraft:prismarine_crystals"},{"id":"minecraft:paper","count":4}],"processing_time":16}],
             "results":[{"id":"minecraft:paper","count":2}]}
            """);
        var finalDeploy=recipe(server,"""
            {"type":"create:sequenced_assembly","ingredient":{"item":"minecraft:amethyst_shard"},"transitional_item":{"id":"minecraft:nether_star"},"loops":1,
             "sequence":[{"type":"create:deploying","ingredients":[{"item":"minecraft:nether_star"},{"item":"minecraft:flint"}],"results":[{"id":"minecraft:nether_star"}]}],"results":[{"id":"minecraft:paper","count":3}]}
            """);
        var finalFill=recipe(server,"""
            {"type":"create:sequenced_assembly","ingredient":{"item":"minecraft:nautilus_shell"},"transitional_item":{"id":"minecraft:heart_of_the_sea"},"loops":1,
             "sequence":[{"type":"create:filling","ingredients":[{"item":"minecraft:heart_of_the_sea"},{"type":"fluid_stack","fluid":"minecraft:water","amount":100}],"results":[{"id":"minecraft:heart_of_the_sea"}]}],"results":[{"id":"minecraft:paper","count":4}]}
            """);
        var expanded=new ArrayList<RecipeHolder<?>>(original);expanded.add(new RecipeHolder<>(id,mixed));expanded.add(new RecipeHolder<>(finalDeployId,finalDeploy));expanded.add(new RecipeHolder<>(finalFillId,finalFill));server.getRecipeManager().replaceRecipes(expanded);
        var mixedTask=task(file,quest);var deployTask=task(file,quest);var fillTask=task(file,quest);file.clearCachedData();file.clearCachedProgress();service.bind(source,mixedTask.getCodeString(),id);service.bind(source,deployTask.getCodeString(),finalDeployId);service.bind(source,fillTask.getCodeString(),finalFillId);
        var pressPos=new BlockPos(12,-58,6);var deployPos=new BlockPos(12,-58,9);var spoutPos=new BlockPos(12,-58,12);var sawPos=new BlockPos(8,-58,4);var placed=new ArrayList<BlockPos>();
        try{
            level.setBlockAndUpdate(pressPos,AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST));placed.add(pressPos);var press=(MechanicalPressBlockEntity)level.getBlockEntity(pressPos);press.initialize();var pressMotor=motor(level,pressPos.west());placed.add(pressPos.west());level.setBlockAndUpdate(pressPos.below(2),Blocks.STONE.defaultBlockState());placed.add(pressPos.below(2));CreateMachineEvidence.assign(source,actor.getUUID(),pressPos);
            int before=observations.size();var first=runPress(press,pressMotor,new ItemStack(Items.MAGMA_CREAM));var stage=first.getItem().copy();clear(level,pressPos);
            check(stage.get(AllDataComponents.SEQUENCED_ASSEMBLY).step()==1&&observations.size()==before&&team.getProgress(mixedTask)==0,"mixed_assembly_native_press_advances_without_final_credit");
            level.setBlockAndUpdate(deployPos,AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING,Direction.DOWN).setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,true));placed.add(deployPos);
            var deployer=(com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity)level.getBlockEntity(deployPos);deployer.initialize();var deployMotor=motor(level,deployPos.west());placed.add(deployPos.west());
            level.setBlockAndUpdate(deployPos.below(2),AllBlocks.DEPOT.getDefaultState());placed.add(deployPos.below(2));var deployDepot=(com.simibubi.create.content.logistics.depot.DepotBlockEntity)level.getBlockEntity(deployPos.below(2));deployDepot.initialize();CreateMachineEvidence.assign(source,actor.getUUID(),deployPos);
            for(int tick=0;tick<10;tick++){deployMotor.tick();deployer.tick();deployDepot.tick();}deployer.getPlayer().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.FLINT,4));deployDepot.setHeldItem(stage);
            for(int tick=0;tick<600&&deployDepot.getHeldItem().get(AllDataComponents.SEQUENCED_ASSEMBLY).step()==1;tick++){deployMotor.tick();deployer.tick();deployDepot.tick();}
            stage=deployDepot.getHeldItem().copy();check(stage.get(AllDataComponents.SEQUENCED_ASSEMBLY).step()==2&&deployer.getPlayer().getMainHandItem().getCount()==3&&observations.size()==before,"mixed_assembly_native_deployer_consumes_tool_without_final_credit");deployDepot.clearContent();
            level.setBlockAndUpdate(spoutPos,AllBlocks.SPOUT.getDefaultState());placed.add(spoutPos);var spout=(com.simibubi.create.content.fluids.spout.SpoutBlockEntity)level.getBlockEntity(spoutPos);spout.initialize();level.setBlockAndUpdate(spoutPos.below(2),AllBlocks.DEPOT.getDefaultState());placed.add(spoutPos.below(2));var spoutDepot=(com.simibubi.create.content.logistics.depot.DepotBlockEntity)level.getBlockEntity(spoutPos.below(2));spoutDepot.initialize();CreateMachineEvidence.assign(source,actor.getUUID(),spoutPos);
            var tank=level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,spoutPos,null);tank.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,250),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);spoutDepot.setHeldItem(stage);
            for(int tick=0;tick<100&&spoutDepot.getHeldItem().get(AllDataComponents.SEQUENCED_ASSEMBLY).step()==2;tick++){spout.tick();spoutDepot.tick();}
            stage=spoutDepot.getHeldItem().copy();check(stage.get(AllDataComponents.SEQUENCED_ASSEMBLY).step()==3&&tank.getFluidInTank(0).isEmpty()&&observations.size()==before,"mixed_assembly_native_spout_consumes_fluid_without_final_credit");spoutDepot.clearContent();
            level.setBlockAndUpdate(sawPos,AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(com.simibubi.create.content.kinetics.saw.SawBlock.FACING,Direction.UP).setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,true));placed.add(sawPos);var saw=(com.simibubi.create.content.kinetics.saw.SawBlockEntity)level.getBlockEntity(sawPos);saw.initialize();var sawMotor=motor(level,sawPos.west());placed.add(sawPos.west());CreateMachineEvidence.assign(source,actor.getUUID(),sawPos);
            saw.inventory.setStackInSlot(0,stage);saw.inventory.appliedRecipe=false;saw.inventory.remainingTime=-1;for(int tick=0;tick<1000&&!saw.inventory.appliedRecipe;tick++){sawMotor.tick();saw.tick();}
            check(observations.size()==before+1&&observations.getLast().source()==CreateMachineEvidence.Source.CREATE_SAW&&observations.getLast().assembly().step()==3&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(mixedTask)==2,"mixed_four_machine_assembly_confirms_only_native_final_saw_storage");
            check(observations.getLast().rolledOutputs().stream().filter(s->s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum()==6&&observations.getLast().assembly().finalOutputs().getFirst().getCount()==2&&team.getProgress(mixedTask)==2,"incidental_matching_step_outputs_do_not_count_as_final_assembly_progress");
            for(int tick=0;tick<50;tick++){deployMotor.tick();deployer.tick();deployDepot.tick();}deployer.getPlayer().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.FLINT,3));deployDepot.setHeldItem(new ItemStack(Items.AMETHYST_SHARD,2));before=observations.size();activeDepot=deployDepot;
            for(int tick=0;tick<600&&observations.size()==before;tick++){deployMotor.tick();deployer.tick();deployDepot.tick();}
            check(observations.size()==before+1&&observations.getLast().assembly().process()==CreateProcessingRecipes.Process.DEPLOYING&&observations.getLast().tool()!=null&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(deployTask)==3&&deployer.getPlayer().getMainHandItem().getCount()==2,"final_assembly_deployer_confirms_tool_and_complete_native_storage");deployDepot.clearContent();activeDepot=null;
            for(int tick=0;tick<50;tick++){deployMotor.tick();deployer.tick();deployDepot.tick();}deployer.getPlayer().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.FLINT,3));deployDepot.setHeldItem(new ItemStack(Items.AMETHYST_SHARD,2));before=observations.size();activeDeployer=deployer;changeTool=true;
            for(int tick=0;tick<600&&observations.size()==before;tick++){deployMotor.tick();deployer.tick();deployDepot.tick();}changeTool=false;activeDeployer=null;
            check(observations.size()==before+1&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(deployTask)==3,"changed_final_assembly_tool_prevents_quest_confirmation");resolve(service,source);deployDepot.clearContent();
            spout.processingTicks=-1;tank.drain(Integer.MAX_VALUE,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);tank.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,200),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);spoutDepot.setHeldItem(new ItemStack(Items.NAUTILUS_SHELL,2));before=observations.size();activeDepot=spoutDepot;
            for(int tick=0;tick<100&&observations.size()==before;tick++){spout.tick();spoutDepot.tick();}
            check(observations.size()==before+1&&observations.getLast().assembly().process()==CreateProcessingRecipes.Process.FILLING&&observations.getLast().fluid().consumedFluids().getFirst().getAmount()==100&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(fillTask)==4&&tank.getFluidInTank(0).getAmount()==100,"final_assembly_spout_confirms_fluid_and_complete_native_storage");spoutDepot.clearContent();activeDepot=null;
            spout.processingTicks=-1;tank.drain(Integer.MAX_VALUE,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);tank.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,200),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);spoutDepot.setHeldItem(new ItemStack(Items.NAUTILUS_SHELL,2));before=observations.size();activeTank=tank;changeFluid=true;
            for(int tick=0;tick<100&&observations.size()==before;tick++){spout.tick();spoutDepot.tick();}changeFluid=false;activeTank=null;
            check(observations.size()==before+1&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(fillTask)==4,"changed_final_assembly_fluid_prevents_quest_confirmation");resolve(service,source);spoutDepot.clearContent();
        }finally{activeDepot=null;for(var p:placed){clear(level,p);level.removeBlock(p,false);}server.getRecipeManager().replaceRecipes(original);}
    }
    private static int countDepot(com.simibubi.create.content.logistics.depot.DepotBlockEntity depot,Item item){var inventory=depot.getBehaviour(com.simibubi.create.content.logistics.depot.DepotBehaviour.TYPE).itemHandler;int count=0;for(int slot=0;slot<inventory.getSlots();slot++)if(inventory.getStackInSlot(slot).is(item))count+=inventory.getStackInSlot(slot).getCount();return count;}
    private static SequencedAssemblyRecipe recipe(MinecraftServer server,String json){return (SequencedAssemblyRecipe)Recipe.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString(json)).getOrThrow();}
    private static ItemEntity runPress(MechanicalPressBlockEntity press,CreativeMotorBlockEntity motor,ItemStack stack){var level=(ServerLevel)press.getLevel();var pos=press.getBlockPos();var input=new ItemEntity(level,pos.getX()+0.5,pos.getY()-1,pos.getZ()+0.5,stack.copy());input.setOnGround(true);input.setNoGravity(true);input.setDeltaMovement(Vec3.ZERO);if(!level.addFreshEntity(input))throw new AssertionError("Input rejected");activeInput=input;try{boolean started=false;for(int tick=0;tick<600;tick++){motor.tick();press.tick();started|=press.pressingBehaviour.running;if(started&&!press.pressingBehaviour.running)return input;}throw new AssertionError("Native assembly press did not finish");}finally{activeInput=null;}}
    private static void clear(ServerLevel level,BlockPos pos){for(var entity:level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(4)))entity.discard();}
    private static CreativeMotorBlockEntity motor(ServerLevel level,BlockPos pos){level.setBlockAndUpdate(pos,AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,Direction.EAST));var motor=(CreativeMotorBlockEntity)level.getBlockEntity(pos);motor.initialize();motor.generatedSpeed.setValue(256);motor.updateGeneratedRotation();return motor;}
    private static dev.ftb.mods.ftbquests.quest.task.CustomTask task(dev.ftb.mods.ftbquests.quest.ServerQuestFile file,dev.ftb.mods.ftbquests.quest.Quest quest){var task=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);task.setMaxProgress(1000);task.onCreated();return task;}
    private static void resolve(CraftQuestEvidence service,net.minecraft.commands.CommandSourceStack source)throws java.io.IOException{service.resolve(source,service.entries(source).getLast().id(),false,"Fixture changed final assembly storage");}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEASSEMBLYAUDIT] PASS {}",name);}
}
