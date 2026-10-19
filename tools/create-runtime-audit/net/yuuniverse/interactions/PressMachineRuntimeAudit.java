package net.yuuniverse.interactions;

import java.util.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Native powered press cycles and real entity outcomes; no direct invocation of processing methods. */
@EventBusSubscriber(modid="interactions")
public final class PressMachineRuntimeAudit {
    private static int passed;
    private static boolean recording;
    private static boolean rejectBuckets;
    private static final List<CreateMachineEvidence.Observation> observations=new ArrayList<>();
    private static final List<MachineOutcomeEvent> outcomes=new ArrayList<>();
    @SubscribeEvent public static void result(MachineResultEvent event){if(recording)observations.add(event.getObservation());}
    @SubscribeEvent public static void outcome(MachineOutcomeEvent event){if(recording)outcomes.add(event);}
    @SubscribeEvent public static void insertion(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event){
        if(recording&&rejectBuckets&&event.getEntity() instanceof ItemEntity item&&item.getItem().is(Items.BUCKET))event.setCanceled(true);
    }
    public static void run(MinecraftServer server) throws Exception {
        var level=server.overworld();var manager=server.getRecipeManager();var original=List.copyOf(manager.getRecipes());
        var recipeId=ResourceLocation.parse("citizens_audit:world_press");
        var recipe=Recipe.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString("""
            {"type":"create:pressing","ingredients":[{"item":"minecraft:snowball"}],
             "results":[{"id":"minecraft:paper","count":2,"components":{"minecraft:custom_name":"\\\"Native press\\\""}},
                        {"id":"minecraft:diamond","chance":0.5}]}
            """)).getOrThrow();
        var expanded=new ArrayList<RecipeHolder<?>>(original);expanded.add(new RecipeHolder<>(recipeId,recipe));manager.replaceRecipes(expanded);
        var file=dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();
        var chapter=new dev.ftb.mods.ftbquests.quest.Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
        var quest=new dev.ftb.mods.ftbquests.quest.Quest(file.newID(),chapter);quest.onCreated();
        var task=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);task.setMaxProgress(1000);task.onCreated();file.clearCachedData();file.clearCachedProgress();
        var remainderTask=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);remainderTask.setMaxProgress(1000);remainderTask.onCreated();file.clearCachedData();file.clearCachedProgress();
        var actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"PressActor"),ClientInformation.createDefault());actor.setPos(1,-60,1);
        var channel=CreateRuntimeAudit.connect(actor);var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();
        var path=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"press-evidence-");var service=CraftQuestEvidence.open(server,path);
        var pos=new BlockPos(12,-58,13);var motorPos=pos.west();var ground=pos.below(2);
        var oldBulk=AllConfigs.server().recipes.bulkPressing.get();recording=true;
        try {
            level.setBlockAndUpdate(ground,Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos,AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST));
            var press=(MechanicalPressBlockEntity)level.getBlockEntity(pos);press.initialize();
            level.setBlockAndUpdate(motorPos,AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,Direction.EAST));
            var motor=(CreativeMotorBlockEntity)level.getBlockEntity(motorPos);motor.initialize();motor.generatedSpeed.setValue(256);motor.updateGeneratedRotation();
            for(int tick=0;tick<10;tick++){motor.tick();press.tick();}
            check(press.getSpeed()!=0,"native_motor_powers_world_press");
            var binding=service.bind(source,task.getCodeString(),recipeId);
            AllConfigs.server().recipes.bulkPressing.set(true);
            var unassigned=run(press,motor,new ItemStack(Items.SNOWBALL,3));
            check(observations.isEmpty()&&team.getProgress(task)==0,"unassigned_world_press_never_credits_task");clear(level,pos);
            var assignment=CreateMachineEvidence.assign(source,actor.getUUID(),pos);
            var entity=run(press,motor,new ItemStack(Items.SNOWBALL,3));
            var observation=observations.getLast();var outcome=outcomes.getLast();
            check(observation.source()==CreateMachineEvidence.Source.CREATE_PRESS&&observation.inputEntityId().equals(entity.getUUID())
                    &&observation.consumedInput().getCount()==3&&observation.remainingInput().isEmpty()&&observation.recipe().id().equals(recipeId)
                    &&outcome.getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==6,
                    "native_world_press_counts_actual_bulk_outputs_with_input_entity_identity");
            check(observation.rolledOutputs().stream().filter(s->s.is(Items.PAPER)).allMatch(s->s.getHoverName().getString().equals("Native press"))
                    &&observation.rolledOutputs().stream().filter(s->s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum()==6,
                    "world_press_retains_actual_component_sensitive_primary_and_random_results");
            observation.rolledOutputs().getFirst().setCount(99);
            check(observation.rolledOutputs().stream().filter(s->s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum()==6,
                    "world_press_evidence_snapshots_are_defensive");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new MachineResultEvent(actor,observation));net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outcome);
            check(team.getProgress(task)==6,"world_press_duplicate_result_outcome_cannot_repeat_progress");
            check(JsonParser.parseString(CreateMachineEvidence.encode(observation,server.registryAccess())).getAsJsonObject().get("inputEntityId").getAsString().equals(entity.getUUID().toString()),
                    "world_press_durable_payload_preserves_native_input_identity");
            var saved=press.saveWithFullMetadata(server.registryAccess());var restored=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos,press.getBlockState(),saved,server.registryAccess());restored.setLevel(level);
            check(CreateMachineEvidence.attribution(restored).equals(assignment),"world_press_native_serialization_preserves_assignment");
            clear(level,pos);AllConfigs.server().recipes.bulkPressing.set(false);
            var single=run(press,motor,new ItemStack(Items.SNOWBALL,4));var individual=observations.getLast();
            check(individual.consumedInput().getCount()==1&&individual.remainingInput().getCount()==3&&single.getItem().is(Items.SNOWBALL)&&single.getItem().getCount()==3
                    &&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==8,
                    "nonbulk_world_press_retains_three_inputs_and_counts_one_native_roll");
            clear(level,pos);AllConfigs.server().recipes.bulkPressing.set(true);
            var before=observations.size();
            com.simibubi.create.foundation.recipe.RecipeApplier.applyRecipeOn(level,new ItemStack(Items.SNOWBALL),recipe,true);
            check(observations.size()==before&&team.getProgress(task)==8,"unrelated_recipe_applier_call_has_no_machine_evidence");
            var simulated=new ItemEntity(level,pos.getX()+0.5,pos.getY()-1,pos.getZ()+0.5,new ItemStack(Items.SNOWBALL,2));level.addFreshEntity(simulated);
            check(press.tryProcessInWorld(simulated,true)&&observations.size()==before&&simulated.getItem().getCount()==2&&team.getProgress(task)==8,
                    "native_press_simulation_produces_no_consumption_or_evidence");clear(level,pos);
            var remainderId=ResourceLocation.parse("citizens_audit:press_remainder");
            var remainderRecipe=new com.simibubi.create.content.processing.recipe.StandardProcessingRecipe.Builder<>(com.simibubi.create.content.kinetics.press.PressingRecipe::new,remainderId)
                    .withItemIngredients(NonNullList.of(Ingredient.EMPTY,Ingredient.of(MachineRemainderItem.item))).withSingleItemOutput(new ItemStack(Items.PAPER)).build();
            var withRemainder=new ArrayList<RecipeHolder<?>>(manager.getRecipes());withRemainder.add(new RecipeHolder<>(remainderId,remainderRecipe));manager.replaceRecipes(withRemainder);
            service.bind(source,remainderTask.getCodeString(),remainderId);MachineRemainderItem.calls=0;
            run(press,motor,new ItemStack(MachineRemainderItem.item,3));var remainder=observations.getLast();
            check(MachineRemainderItem.calls==3&&remainder.nativeRemainder().is(Items.BUCKET)&&remainder.additionalRemainders().size()==2
                    &&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(remainderTask)==3,
                    "world_press_invokes_each_native_remainder_once_and_excludes_it_from_task_units");clear(level,pos);
            rejectBuckets=true;run(press,motor,new ItemStack(MachineRemainderItem.item,2));rejectBuckets=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(remainderTask)==3
                    &&service.entries(source).getLast().state()==QuestEvidenceJournal.State.REVIEW,
                    "canceled_native_remainder_entity_insertion_retains_review_without_progress");
            service.resolve(source,service.entries(source).getLast().id(),false,"Fixture canceled native remainder entity insertion");clear(level,pos);
            MachineRemainderItem.calls=0;MachineRemainderItem.fail=true;var beforeFailure=observations.size();
            try {run(press,motor,new ItemStack(MachineRemainderItem.item,2));throw new AssertionError("Native remainder failure hidden");}
            catch(IllegalStateException expected){check(MachineRemainderItem.calls==1&&observations.size()==beforeFailure&&team.getProgress(remainderTask)==3,
                    "world_press_preserves_native_remainder_exception_without_inventing_completed_evidence");}
            finally{MachineRemainderItem.fail=false;}
            service.close();service=CraftQuestEvidence.open(server,path);
            check(service.bindings(source).stream().anyMatch(b->b.epoch().equals(binding.epoch()))&&service.reconcile(source)==0&&team.getProgress(task)==8,
                    "reopened_world_press_journal_preserves_absolute_progress");
            CreateMachineEvidence.clear(source,pos);check(CreateMachineEvidence.attribution(press)==null,"world_press_explicit_assignment_clear");
            org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEPRESSAUDIT] COMPLETE {} checks",passed);
        } finally {recording=false;rejectBuckets=false;MachineRemainderItem.fail=false;AllConfigs.server().recipes.bulkPressing.set(oldBulk);service.close();clear(level,pos);level.removeBlock(pos,false);level.removeBlock(motorPos,false);level.removeBlock(ground,false);
            server.getPlayerList().remove(actor);channel.finishAndReleaseAll();manager.replaceRecipes(original);}
    }
    private static ItemEntity run(MechanicalPressBlockEntity press,CreativeMotorBlockEntity motor,ItemStack input){
        var level=(ServerLevel)press.getLevel();var p=press.getBlockPos();
        var entity=new ItemEntity(level,p.getX()+0.5,p.getY()-1,p.getZ()+0.5,input.copy());entity.setOnGround(true);entity.setNoGravity(true);entity.setDeltaMovement(Vec3.ZERO);
        if(!level.addFreshEntity(entity))throw new AssertionError("Fixture input insertion rejected");
        boolean started=false;
        for(int tick=0;tick<500;tick++){
            motor.tick();press.tick();started|=press.pressingBehaviour.running;
            if(started&&!press.pressingBehaviour.running)return entity;
        }
        throw new AssertionError("Native press cycle did not finish: speed="+press.getSpeed());
    }
    private static void clear(ServerLevel level,BlockPos pos){for(var entity:level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(3)))entity.discard();}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEPRESSAUDIT] PASS {}",name);}
}
