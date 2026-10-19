package net.yuuniverse.interactions;

import java.util.*;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.kinetics.crusher.*;
import com.simibubi.create.content.processing.recipe.ProcessingInventory;
import net.citizensnpcs.util.*;
import net.citizensnpcs.api.event.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Powered native ticks, batch RNG, inventory outcomes and exact task evidence. Never invokes private processing. */
@EventBusSubscriber(modid="interactions")
public final class BatchMachineRuntimeAudit {
    private static int passed;
    private static boolean recording;
    private static final List<CreateMachineEvidence.Observation> observations=new ArrayList<>();
    private static final List<MachineOutcomeEvent> outcomes=new ArrayList<>();
    @SubscribeEvent public static void result(MachineResultEvent event) {if(recording)observations.add(event.getObservation());}
    @SubscribeEvent public static void outcome(MachineOutcomeEvent event) {if(recording)outcomes.add(event);}
    public static void run(MinecraftServer server) throws Exception {
        var level=server.overworld();var manager=server.getRecipeManager();var original=List.copyOf(manager.getRecipes());
        var cuttingId=ResourceLocation.parse("citizens_audit:batch_cutting");var crushingId=ResourceLocation.parse("citizens_audit:batch_crushing");
        var cutting=Recipe.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString("""
            {"type":"create:cutting","ingredients":[{"item":"minecraft:snowball"}],"processing_time":40,
             "results":[{"id":"minecraft:paper","count":3,"components":{"minecraft:custom_name":"\\\"Cut batch\\\""}},
                        {"id":"minecraft:diamond","chance":0.5}]}
            """)).getOrThrow();
        var crushing=Recipe.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString("""
            {"type":"create:crushing","ingredients":[{"item":"minecraft:egg"}],"processing_time":40,
             "results":[{"id":"minecraft:paper","count":2,"components":{"minecraft:custom_name":"\\\"Crushed batch\\\""}},
                        {"id":"minecraft:diamond","chance":0.5}]}
            """)).getOrThrow();
        var recipes=new ArrayList<RecipeHolder<?>>(original);recipes.add(new RecipeHolder<>(cuttingId,cutting));recipes.add(new RecipeHolder<>(crushingId,crushing));manager.replaceRecipes(recipes);
        var file=dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();
        var chapter=new dev.ftb.mods.ftbquests.quest.Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
        var quest=new dev.ftb.mods.ftbquests.quest.Quest(file.newID(),chapter);quest.onCreated();
        var cutTask=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);cutTask.setMaxProgress(1000);cutTask.onCreated();
        var crushTask=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);crushTask.setMaxProgress(1000);crushTask.onCreated();
        var millTask=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);millTask.setMaxProgress(1000);millTask.onCreated();
        var remainderTask=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);remainderTask.setMaxProgress(1000);remainderTask.onCreated();
        file.clearCachedData();file.clearCachedProgress();
        var actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"BatchActor"),ClientInformation.createDefault());actor.setPos(1,-60,1);
        var channel=CreateRuntimeAudit.connect(actor);var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();
        var path=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"batch-evidence-");var service=CraftQuestEvidence.open(server,path);
        var placed=new ArrayList<BlockPos>();recording=true;
        try {
            var sawPos=new BlockPos(8,-58,4);var sawMotorPos=sawPos.west();
            level.setBlockAndUpdate(sawPos,AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(SawBlock.FACING,Direction.UP)
                    .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,true));placed.add(sawPos);
            var saw=(SawBlockEntity)level.getBlockEntity(sawPos);saw.initialize();var sawMotor=motor(level,sawMotorPos,Direction.EAST,256);placed.add(sawMotorPos);
            for(int tick=0;tick<10;tick++){sawMotor.tick();saw.tick();}
            check(saw.getSpeed()!=0,"actual_saw_rotation_propagates_from_creative_motor");
            var cutBinding=service.bind(source,cutTask.getCodeString(),cuttingId);service.bind(source,crushTask.getCodeString(),crushingId);service.bind(source,millTask.getCodeString(),ResourceLocation.parse("create:milling/wheat"));
            runSaw(saw,sawMotor,new ItemStack(Items.SNOWBALL,3));check(observations.isEmpty()&&team.getProgress(cutTask)==0,"unassigned_batch_cutting_gives_no_task_credit");clear(saw.inventory);
            CreateMachineEvidence.assign(source,actor.getUUID(),sawPos);
            runSaw(saw,sawMotor,new ItemStack(Items.SNOWBALL,3));
            var cut=observations.getLast();var cutOutcome=outcomes.getLast();
            check(cut.source()==CreateMachineEvidence.Source.CREATE_SAW&&cut.consumedInput().getCount()==3&&cut.rolledOutputs().stream().filter(s->s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum()==9
                    &&cutOutcome.getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(cutTask)==9,"native_saw_batch_counts_three_actual_rolls_and_primary_components_once");
            check(cut.rolledOutputs().stream().filter(s->s.is(Items.PAPER)).allMatch(s->s.has(DataComponents.CUSTOM_NAME))&&service.entries(source).getLast().admission().units()==9,"batch_task_evidence_keeps_exact_output_components_and_quantity");
            cut.rolledOutputs().getFirst().setCount(99);cut.consumedInput().setCount(99);
            check(cut.consumedInput().getCount()==3&&cut.rolledOutputs().stream().filter(s->s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum()==9,"batch_observation_copies_before_native_merging_and_external_mutation");
            var before=team.getProgress(cutTask);net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new MachineResultEvent(actor,cut));net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(cutOutcome);
            check(team.getProgress(cutTask)==before,"duplicate_batch_result_and_outcome_keep_absolute_progress");
            clear(saw.inventory);check(team.getProgress(cutTask)==before,"extracting_batch_outputs_does_not_create_new_evidence");
            var saved=saw.saveWithFullMetadata(server.registryAccess());var copy=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(sawPos,saw.getBlockState(),saved,server.registryAccess());copy.setLevel(level);
            check(CreateMachineEvidence.attribution(copy).equals(cut.attribution()),"saw_assignment_survives_native_serialization");
            var center=new BlockPos(8,-58,10);var left=center.west();var right=center.east();
            level.setBlockAndUpdate(left,AllBlocks.CRUSHING_WHEEL.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.Z));placed.add(left);
            level.setBlockAndUpdate(right,AllBlocks.CRUSHING_WHEEL.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.Z));placed.add(right);
            var leftWheel=(CrushingWheelBlockEntity)level.getBlockEntity(left);var rightWheel=(CrushingWheelBlockEntity)level.getBlockEntity(right);leftWheel.initialize();rightWheel.initialize();
            var leftMotor=motor(level,left.north(),Direction.SOUTH,256);placed.add(left.north());var rightMotor=motor(level,right.north(),Direction.SOUTH,-256);placed.add(right.north());
            for(int tick=0;tick<10;tick++){leftMotor.tick();rightMotor.tick();leftWheel.tick();rightWheel.tick();}
            AllBlocks.CRUSHING_WHEEL.get().updateControllers(level.getBlockState(left),level,left,Direction.EAST);
            var controller=(CrushingWheelControllerBlockEntity)level.getBlockEntity(center);if(controller==null)throw new AssertionError("Native wheels created no controller");controller.initialize();placed.add(center);
            AllBlocks.CRUSHING_WHEEL_CONTROLLER.get().updateSpeed(controller.getBlockState(),level,center);
            check(leftWheel.getSpeed()!=0&&rightWheel.getSpeed()!=0&&Math.signum(leftWheel.getSpeed())!=Math.signum(rightWheel.getSpeed())&&controller.crushingspeed>0
                    &&controller.getBlockState().getValue(CrushingWheelControllerBlock.VALID),"opposing_powered_crushing_wheels_create_valid_native_controller");
            CreateMachineEvidence.assign(source,actor.getUUID(),center);
            runCrusher(controller,leftMotor,rightMotor,leftWheel,rightWheel,new ItemStack(Items.EGG,4));
            var crushed=observations.getLast();
            check(crushed.source()==CreateMachineEvidence.Source.CREATE_CRUSHING_WHEELS&&crushed.consumedInput().getCount()==4&&crushed.recipe().id().equals(crushingId)
                    &&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(crushTask)==8,"native_crushing_batch_records_four_rolls_and_exact_stored_outputs");
            clear(controller.inventory);long millingBefore=team.getProgress(millTask);
            runCrusher(controller,leftMotor,rightMotor,leftWheel,rightWheel,new ItemStack(Items.WHEAT,2));
            var milled=observations.getLast();int flour=milled.rolledOutputs().stream().filter(s->ItemStack.isSameItemSameComponents(s,milled.recipe().output())).mapToInt(ItemStack::getCount).sum();
            check(milled.source()==CreateMachineEvidence.Source.CREATE_CRUSHING_WHEELS&&milled.recipe().id().toString().equals("create:milling/wheat")
                    &&team.getProgress(millTask)==millingBefore+flour&&flour>=2&&flour<=6,"crushing_wheel_native_milling_fallback_retains_actual_recipe_identity");
            clear(controller.inventory);var snapshot=controller.saveWithFullMetadata(server.registryAccess());var restored=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(center,controller.getBlockState(),snapshot,server.registryAccess());restored.setLevel(level);
            check(CreateMachineEvidence.attribution(restored).equals(milled.attribution()),"crushing_controller_assignment_survives_native_serialization");
            // Saw calls native remaining-item once per input, crushing once per original batch. Preserve those distinct contracts.
            var remainderId=ResourceLocation.parse("citizens_audit:batch_remainder");
            var remainderRecipe=new com.simibubi.create.content.processing.recipe.StandardProcessingRecipe.Builder<>(com.simibubi.create.content.kinetics.saw.CuttingRecipe::new,remainderId)
                    .withItemIngredients(NonNullList.of(Ingredient.EMPTY,Ingredient.of(MachineRemainderItem.item))).withSingleItemOutput(new ItemStack(Items.PAPER)).duration(16).build();
            var crusherRemainderId=ResourceLocation.parse("citizens_audit:crusher_remainder");
            var crusherRemainder=new com.simibubi.create.content.processing.recipe.StandardProcessingRecipe.Builder<>(CrushingRecipe::new,crusherRemainderId)
                    .withItemIngredients(NonNullList.of(Ingredient.EMPTY,Ingredient.of(MachineRemainderItem.item))).withSingleItemOutput(new ItemStack(Items.PAPER)).duration(16).build();
            var expanded=new ArrayList<RecipeHolder<?>>(manager.getRecipes());expanded.add(new RecipeHolder<>(remainderId,remainderRecipe));expanded.add(new RecipeHolder<>(crusherRemainderId,crusherRemainder));manager.replaceRecipes(expanded);
            com.simibubi.create.foundation.recipe.RecipeFinder.LISTENER.onResourceManagerReload(server.getResourceManager());
            service.bind(source,remainderTask.getCodeString(),remainderId);
            MachineRemainderItem.calls=0;runSaw(saw,sawMotor,new ItemStack(MachineRemainderItem.item,3));var remainder=observations.getLast();
            check(MachineRemainderItem.calls==3&&remainder.nativeRemainder().is(Items.BUCKET)&&remainder.additionalRemainders().size()==2&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED,"saw_batch_native_remainders_called_once_per_input_and_retained_separately");
            remainder.additionalRemainders().getFirst().setCount(99);
            check(remainder.additionalRemainders().getFirst().getCount()==1&&team.getProgress(remainderTask)==3,"batch_remainders_are_defensive_and_excluded_from_primary_task_units");
            MachineRemainderItem.calls=0;runCrusher(controller,leftMotor,rightMotor,leftWheel,rightWheel,new ItemStack(MachineRemainderItem.item,3));var crusherContainer=observations.getLast();
            check(MachineRemainderItem.calls==1&&crusherContainer.nativeRemainder().is(Items.BUCKET)&&crusherContainer.additionalRemainders().isEmpty()
                    &&crusherContainer.consumedInput().getCount()==3&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED,"crushing_batch_keeps_native_single_remainder_for_entire_input_stack");clear(controller.inventory);
            clear(saw.inventory);MachineRemainderItem.calls=0;MachineRemainderItem.fail=true;var observedBefore=observations.size();
            try{runSaw(saw,sawMotor,new ItemStack(MachineRemainderItem.item,2));throw new AssertionError("Remainder failure hidden");}
            catch(IllegalStateException expected){check(MachineRemainderItem.calls==1&&observations.size()==observedBefore&&saw.inventory.getStackInSlot(0).isEmpty()
                    &&team.getProgress(remainderTask)==3,"native_batch_remainder_failure_keeps_consumed_input_and_emits_no_completed_evidence");}
            finally{MachineRemainderItem.fail=false;}
            clear(saw.inventory);MachineRemainderItem.calls=0;MachineRemainderItem.distinctRemainders=true;
            runSaw(saw,sawMotor,new ItemStack(MachineRemainderItem.item,32));
            check(MachineRemainderItem.calls==32&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED
                    &&team.getProgress(remainderTask)==3&&service.entries(source).getLast().state()==QuestEvidenceJournal.State.REVIEW,
                    "native_batch_output_slot_truncation_requires_review_even_when_primary_output_was_stored");
            service.resolve(source,service.entries(source).getLast().id(),false,"Fixture discarded component-distinct native remainder outputs");MachineRemainderItem.distinctRemainders=false;
            clear(saw.inventory);saw.inventory.setStackInSlot(1,new ItemStack(Items.GOLD_INGOT));runSaw(saw,sawMotor,new ItemStack(Items.SNOWBALL));
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(cutTask)==before
                    &&service.entries(source).getLast().state()==QuestEvidenceJournal.State.REVIEW,"preexisting_batch_output_loss_is_not_confirmed_as_task_completion");
            service.resolve(source,service.entries(source).getLast().id(),false,"Fixture input bypassed normal empty-inventory insertion precondition");
            clear(saw.inventory);long entries=service.entries(source).size();var outcomesBefore=outcomes.size();
            saw.inventory=new ProcessingInventory(stack->{}) {@Override public void setStackInSlot(int slot,ItemStack stack){if(slot>0&&!stack.isEmpty())throw new IllegalStateException("Fixture batch storage fails");super.setStackInSlot(slot,stack);}};
            try{runSaw(saw,sawMotor,new ItemStack(Items.SNOWBALL,2));throw new AssertionError("Insertion failure hidden");}catch(IllegalStateException expected){check(outcomes.size()==outcomesBefore+1&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.INTERRUPTED&&team.getProgress(cutTask)==before,"batch_storage_exception_preserves_native_failure_and_requires_review");}
            check(service.entries(source).size()==entries+1&&service.entries(source).getLast().state()==QuestEvidenceJournal.State.REVIEW,"interrupted_batch_keeps_durable_review_evidence_without_credit");
            service.resolve(source,service.entries(source).getLast().id(),false,"Fixture native storage threw before outputs retained");
            service.close();service=CraftQuestEvidence.open(server,path);
            check(service.bindings(source).stream().anyMatch(b->b.epoch().equals(cutBinding.epoch()))&&service.reconcile(source)==0&&team.getProgress(cutTask)==before,"reopened_batch_journal_deduplicates_confirmed_native_progress");
            check(java.util.concurrent.CompletableFuture.supplyAsync(()->{try{CreateMachineEvidence.attribution(saw);return false;}catch(IllegalStateException expected){return true;}}).join(),"batch_machine_attribution_rejects_worker_thread");
            CreateMachineEvidence.clear(source,sawPos);check(CreateMachineEvidence.attribution(saw)==null,"explicit_saw_assignment_clear_preserves_native_machine");
            org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEBATCHAUDIT] COMPLETE {} checks",passed);
        } finally {recording=false;MachineRemainderItem.fail=false;MachineRemainderItem.distinctRemainders=false;service.close();for(var pos:placed)level.removeBlock(pos,false);server.getPlayerList().remove(actor);channel.finishAndReleaseAll();manager.replaceRecipes(original);}
    }
    private static CreativeMotorBlockEntity motor(ServerLevel level,BlockPos pos,Direction facing,int speed){
        level.setBlockAndUpdate(pos,AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,facing));
        var motor=(CreativeMotorBlockEntity)level.getBlockEntity(pos);motor.initialize();motor.generatedSpeed.setValue(speed);motor.updateGeneratedRotation();return motor;
    }
    private static void runSaw(SawBlockEntity saw,CreativeMotorBlockEntity motor,ItemStack input){
        saw.inventory.setStackInSlot(0,input.copy());saw.inventory.appliedRecipe=false;saw.inventory.remainingTime=-1;
        for(int tick=0;tick<1000&&!saw.inventory.appliedRecipe;tick++){motor.tick();saw.tick();}
        if(!saw.inventory.appliedRecipe)throw new AssertionError("Native saw did not apply recipe: speed="+saw.getSpeed());
    }
    private static void runCrusher(CrushingWheelControllerBlockEntity controller,CreativeMotorBlockEntity leftMotor,CreativeMotorBlockEntity rightMotor,CrushingWheelBlockEntity left,CrushingWheelBlockEntity right,ItemStack input){
        controller.inventory.insertItem(0,input.copy(),false);
        for(int tick=0;tick<1000&&!controller.inventory.appliedRecipe;tick++){leftMotor.tick();rightMotor.tick();left.tick();right.tick();controller.tick();}
        if(!controller.inventory.appliedRecipe)throw new AssertionError("Native crushing wheels did not apply recipe: speed="+controller.crushingspeed+", input="+controller.inventory.getStackInSlot(0));
    }
    private static void clear(ProcessingInventory inventory){inventory.clear();inventory.appliedRecipe=false;inventory.remainingTime=-1;}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEBATCHAUDIT] PASS {}",name);}
}
