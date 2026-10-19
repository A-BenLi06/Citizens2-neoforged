package net.yuuniverse.interactions;

import java.util.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.depot.*;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Powered depot and connected belt cycles through the provider's real transport callbacks. */
@EventBusSubscriber(modid="interactions")
public final class TransportPressRuntimeAudit {
    private static int passed;
    private static boolean recording, rejectBuckets, removeInput, damageOutput, changeInput;
    private static DepotBehaviour activeDepot;
    private static final List<CreateMachineEvidence.Observation> observations=new ArrayList<>();
    private static final List<MachineOutcomeEvent> outcomes=new ArrayList<>();
    @SubscribeEvent public static void result(MachineResultEvent event){
        if(!recording)return;observations.add(event.getObservation());
        if(removeInput)activeDepot.removeHeldItem();
        if(changeInput)activeDepot.getHeldItemStack().grow(1);
    }
    @SubscribeEvent public static void outcome(MachineOutcomeEvent event){if(recording)outcomes.add(event);}
    @SubscribeEvent public static void insertion(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event){
        if(!recording||!(event.getEntity() instanceof ItemEntity item)||!item.getItem().is(Items.BUCKET))return;
        if(rejectBuckets)event.setCanceled(true);
        if(damageOutput)item.getItem().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Changed overflow"));
    }
    public static void run(MinecraftServer server) throws Exception {
        var level=server.overworld();var manager=server.getRecipeManager();var original=List.copyOf(manager.getRecipes());
        var id=ResourceLocation.parse("citizens_audit:transport_press");
        var recipe=Recipe.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString("""
            {"type":"create:pressing","ingredients":[{"item":"minecraft:snowball"}],
             "results":[{"id":"minecraft:paper","count":2,"components":{"minecraft:custom_name":"\\\"Transport result\\\""}},
                        {"id":"minecraft:diamond","count":1}]}
            """)).getOrThrow();
        var remainderId=ResourceLocation.parse("citizens_audit:transport_press_remainder");
        var remainderRecipe=new com.simibubi.create.content.processing.recipe.StandardProcessingRecipe.Builder<>(com.simibubi.create.content.kinetics.press.PressingRecipe::new,remainderId)
                .withItemIngredients(NonNullList.of(Ingredient.EMPTY,Ingredient.of(MachineRemainderItem.item))).withSingleItemOutput(new ItemStack(Items.PAPER)).build();
        var unchangedId=ResourceLocation.parse("citizens_audit:unchanged_transport_press");
        var unchangedRecipe=new com.simibubi.create.content.processing.recipe.StandardProcessingRecipe.Builder<>(com.simibubi.create.content.kinetics.press.PressingRecipe::new,unchangedId)
                .withItemIngredients(NonNullList.of(Ingredient.EMPTY,Ingredient.of(Items.RABBIT_HIDE))).withSingleItemOutput(new ItemStack(Items.RABBIT_HIDE)).build();
        var expanded=new ArrayList<RecipeHolder<?>>(original);expanded.add(new RecipeHolder<>(id,recipe));expanded.add(new RecipeHolder<>(remainderId,remainderRecipe));expanded.add(new RecipeHolder<>(unchangedId,unchangedRecipe));manager.replaceRecipes(expanded);
        var file=dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();
        var chapter=new dev.ftb.mods.ftbquests.quest.Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
        var quest=new dev.ftb.mods.ftbquests.quest.Quest(file.newID(),chapter);quest.onCreated();
        var task=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);task.setMaxProgress(1000);task.onCreated();
        var remainderTask=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);remainderTask.setMaxProgress(1000);remainderTask.onCreated();
        var unchangedTask=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);unchangedTask.setMaxProgress(1000);unchangedTask.onCreated();file.clearCachedData();file.clearCachedProgress();
        var actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"TransportActor"),ClientInformation.createDefault());actor.setPos(1,-60,1);
        var channel=CreateRuntimeAudit.connect(actor);var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();
        var path=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"transport-evidence-");var service=CraftQuestEvidence.open(server,path);
        var pos=new BlockPos(8,-58,7);var depotPos=pos.below(2);var motorPos=pos.west();
        var beltStart=new BlockPos(8,-60,5);var beltEnd=new BlockPos(8,-60,10);var beltMotorPos=beltStart.west();
        var oldBulk=AllConfigs.server().recipes.bulkPressing.get();recording=true;
        try {
            clearEntities(level,depotPos);
            level.setBlockAndUpdate(pos,AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST));
            var press=(MechanicalPressBlockEntity)level.getBlockEntity(pos);press.initialize();
            var motor=motor(level,motorPos);
            level.setBlockAndUpdate(depotPos,AllBlocks.DEPOT.getDefaultState());var depot=(DepotBlockEntity)level.getBlockEntity(depotPos);depot.initialize();
            activeDepot=BlockEntityBehaviour.get(depot,DepotBehaviour.TYPE);
            for(int tick=0;tick<10;tick++){motor.tick();press.tick();}
            check(press.getSpeed()!=0,"native_motor_powers_transport_press");
            var binding=service.bind(source,task.getCodeString(),id);service.bind(source,remainderTask.getCodeString(),remainderId);
            AllConfigs.server().recipes.bulkPressing.set(true);
            runDepot(press,motor,depot,new ItemStack(Items.SNOWBALL,3),false);
            check(observations.isEmpty()&&team.getProgress(task)==0,"unassigned_depot_processing_has_no_task_evidence");depot.clearContent();
            CreateMachineEvidence.assign(source,actor.getUUID(),pos);
            int beforeSimulation=observations.size();var simulated=new TransportedItemStack(new ItemStack(Items.SNOWBALL,3));
            check(press.tryProcessOnBelt(simulated,null,true)&&observations.size()==beforeSimulation&&simulated.stack.getCount()==3&&team.getProgress(task)==0,
                    "transport_simulation_preserves_input_and_emits_no_evidence");
            runDepot(press,motor,depot,new ItemStack(Items.SNOWBALL,3),true);
            var observation=observations.getLast();var outcome=outcomes.getLast();
            check(observation.inputEntityId()==null&&observation.consumedInput().getCount()==3&&observation.remainingInput().isEmpty()
                    &&outcome.getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==6,
                    "depot_bulk_press_confirms_native_held_and_buffer_storage");
            check(activeDepot.itemHandler.getStackInSlot(0).getCount()==6&&activeDepot.itemHandler.getStackInSlot(0).getHoverName().getString().equals("Transport result")
                    &&count(activeDepot.itemHandler,Items.DIAMOND)==3,"depot_preserves_component_outputs_and_secondary_buffer");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new MachineResultEvent(actor,observation));net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outcome);
            check(team.getProgress(task)==6,"transport_duplicate_outcomes_do_not_repeat_absolute_progress");
            depot.clearContent();AllConfigs.server().recipes.bulkPressing.set(false);
            runDepot(press,motor,depot,new ItemStack(Items.SNOWBALL,4),true);
            check(observations.getLast().consumedInput().getCount()==1&&observations.getLast().remainingInput().getCount()==3
                    &&activeDepot.getHeldItemStack().is(Items.SNOWBALL)&&activeDepot.getHeldItemStack().getCount()==3
                    &&count(activeDepot.itemHandler,Items.PAPER)==2&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==8,
                    "single_depot_cycle_retains_input_and_stores_actual_outputs");
            depot.clearContent();AllConfigs.server().recipes.bulkPressing.set(true);
            // Saturate the pinned native buffer before the cycle.
            fillBuffer(activeDepot);MachineRemainderItem.calls=0;
            runDepot(press,motor,depot,new ItemStack(MachineRemainderItem.item,3),true);
            check(MachineRemainderItem.calls==3&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(remainderTask)==3
                    &&dropped(level,depotPos,Items.BUCKET)==3,"full_depot_buffer_confirms_exact_native_overflow_remainders");clearEntities(level,depotPos);depot.clearContent();
            fillBuffer(activeDepot);rejectBuckets=true;runDepot(press,motor,depot,new ItemStack(MachineRemainderItem.item,2),true);rejectBuckets=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(remainderTask)==3&&service.entries(source).getLast().state()==QuestEvidenceJournal.State.REVIEW,
                    "rejected_depot_overflow_stays_in_review_without_credit");service.resolve(source,service.entries(source).getLast().id(),false,"Fixture rejected overflow");depot.clearContent();clearEntities(level,depotPos);
            fillBuffer(activeDepot);damageOutput=true;runDepot(press,motor,depot,new ItemStack(MachineRemainderItem.item,2),true);damageOutput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(remainderTask)==3,"changed_overflow_components_prevent_confirmation");
            service.resolve(source,service.entries(source).getLast().id(),false,"Fixture changed overflow components");depot.clearContent();clearEntities(level,depotPos);
            removeInput=true;runDepot(press,motor,depot,new ItemStack(Items.SNOWBALL,2),true);removeInput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(task)==8,"missing_original_depot_input_cannot_confirm_computed_results");
            service.resolve(source,service.entries(source).getLast().id(),false,"Fixture removed native input before storage");depot.clearContent();
            changeInput=true;runDepot(press,motor,depot,new ItemStack(Items.SNOWBALL,2),true);changeInput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(task)==8,"changed_native_input_before_storage_prevents_confirmation");
            service.resolve(source,service.entries(source).getLast().id(),false,"Fixture changed native input before storage");depot.clearContent();
            service.bind(source,unchangedTask.getCodeString(),unchangedId);
            runDepot(press,motor,depot,new ItemStack(Items.RABBIT_HIDE,2),true);
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&activeDepot.getHeldItemStack().is(Items.RABBIT_HIDE)
                    &&activeDepot.getHeldItemStack().getCount()==2&&team.getProgress(unchangedTask)==0,"native_unchanged_transport_result_is_skipped_and_not_credited");
            service.resolve(source,service.entries(source).getLast().id(),false,"Native holder skipped unchanged result");depot.clearContent();
            int beforeFailure=observations.size();MachineRemainderItem.calls=0;MachineRemainderItem.fail=true;
            try{runDepot(press,motor,depot,new ItemStack(MachineRemainderItem.item,2),true);throw new AssertionError("Native remainder failure hidden");}
            catch(IllegalStateException expected){check(MachineRemainderItem.calls==1&&observations.size()==beforeFailure&&team.getProgress(remainderTask)==3,
                    "transport_remainder_exception_is_preserved_without_completed_evidence");}
            finally{MachineRemainderItem.fail=false;depot.clearContent();}
            level.removeBlock(depotPos,false);activeDepot=null;
            level.setBlockAndUpdate(beltStart,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));
            level.setBlockAndUpdate(beltEnd,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));
            BeltConnectorItem.createBelts(level,beltStart,beltEnd);BeltBlock.initBelt(level,beltStart);
            var belt=(BeltBlockEntity)level.getBlockEntity(beltStart);belt.initialize();var beltMotor=motor(level,beltMotorPos);
            for(int tick=0;tick<10;tick++){beltMotor.tick();motor.tick();press.tick();belt.tick();}
            check(belt.getSpeed()!=0&&belt.beltLength==6,"native_connected_belt_is_motor_powered");
            runBelt(press,motor,belt,beltMotor,new ItemStack(Items.SNOWBALL,3));
            var beltObservation=observations.getLast();
            check(beltObservation.consumedInput().getCount()==3&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==14,
                    "native_belt_pending_output_storage_confirms_bulk_press");
            belt.getInventory().tick();
            check(belt.getInventory().getTransportedItems().stream().filter(t->t.stack.is(Items.PAPER)).mapToInt(t->t.stack.getCount()).sum()==6
                    &&belt.getInventory().getTransportedItems().stream().filter(t->t.stack.is(Items.DIAMOND)).mapToInt(t->t.stack.getCount()).sum()==3,
                    "next_native_tick_retains_confirmed_belt_outputs");
            belt.clearContent();AllConfigs.server().recipes.bulkPressing.set(false);
            runBelt(press,motor,belt,beltMotor,new ItemStack(Items.SNOWBALL,4));
            check(observations.getLast().remainingInput().getCount()==3&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==16,
                    "single_belt_press_confirms_queued_held_input_and_outputs");
            belt.getInventory().tick();check(belt.getInventory().getTransportedItems().stream().filter(t->t.stack.is(Items.SNOWBALL)).mapToInt(t->t.stack.getCount()).sum()==3,
                    "single_belt_native_queue_flush_preserves_remaining_input");
            belt.clearContent();AllConfigs.server().recipes.bulkPressing.set(true);MachineRemainderItem.calls=0;
            runBelt(press,motor,belt,beltMotor,new ItemStack(MachineRemainderItem.item,3));
            check(MachineRemainderItem.calls==3&&observations.getLast().additionalRemainders().size()==2&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(remainderTask)==6,
                    "belt_native_remainder_callbacks_run_once_and_are_not_primary_units");
            service.close();service=CraftQuestEvidence.open(server,path);
            check(service.bindings(source).stream().anyMatch(b->b.epoch().equals(binding.epoch()))&&service.reconcile(source)==0&&team.getProgress(task)==16&&team.getProgress(remainderTask)==6,
                    "transport_journal_reopen_keeps_original_absolute_progress");
            org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATETRANSPORTAUDIT] COMPLETE {} checks",passed);
        } finally {
            recording=false;rejectBuckets=false;removeInput=false;damageOutput=false;changeInput=false;MachineRemainderItem.fail=false;activeDepot=null;AllConfigs.server().recipes.bulkPressing.set(oldBulk);service.close();
            level.removeBlock(pos,false);level.removeBlock(motorPos,false);level.removeBlock(depotPos,false);level.removeBlock(beltMotorPos,false);
            for(int z=beltStart.getZ();z<=beltEnd.getZ();z++)level.removeBlock(new BlockPos(8,-60,z),false);
            clearEntities(level,depotPos);server.getPlayerList().remove(actor);channel.finishAndReleaseAll();manager.replaceRecipes(original);
        }
    }
    private static CreativeMotorBlockEntity motor(ServerLevel level,BlockPos pos){
        level.setBlockAndUpdate(pos,AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,Direction.EAST));
        var motor=(CreativeMotorBlockEntity)level.getBlockEntity(pos);motor.initialize();motor.generatedSpeed.setValue(256);motor.updateGeneratedRotation();return motor;
    }
    private static void runDepot(MechanicalPressBlockEntity press,CreativeMotorBlockEntity motor,DepotBlockEntity depot,ItemStack input,boolean evidence){
        depot.setHeldItem(input.copy());int before=observations.size();boolean started=false;
        for(int tick=0;tick<500;tick++){motor.tick();press.tick();depot.tick();started|=press.pressingBehaviour.running;
            if(evidence&&observations.size()>before)return;
            if(!evidence&&started&&!press.pressingBehaviour.running)return;
        }throw new AssertionError("Depot cycle did not process");
    }
    private static void runBelt(MechanicalPressBlockEntity press,CreativeMotorBlockEntity motor,BeltBlockEntity belt,CreativeMotorBlockEntity beltMotor,ItemStack input){
        for(int tick=0;press.pressingBehaviour.running&&tick<500;tick++){motor.tick();press.tick();}
        if(press.pressingBehaviour.running)throw new AssertionError("Previous press cycle did not finish");
        var transported=new TransportedItemStack(input.copy());transported.beltPosition=0.5f;transported.prevBeltPosition=0.5f;belt.getInventory().addItem(transported);
        int before=observations.size();
        for(int tick=0;tick<1000;tick++){motor.tick();beltMotor.tick();press.tick();belt.tick();if(observations.size()>before)return;}
        throw new AssertionError("Connected belt did not process: "+belt.getSpeed()+" length="+belt.beltLength+" items="+belt.getInventory().getTransportedItems());
    }
    private static void fillBuffer(DepotBehaviour depot){
        // Public read contract supplies buffer stacks; fill using the pinned buffer's documented ItemStackHandler in this opt-in fixture.
        try{var field=DepotBehaviour.class.getDeclaredField("processingOutputBuffer");field.setAccessible(true);var handler=(net.neoforged.neoforge.items.ItemStackHandler)field.get(depot);
            for(int slot=0;slot<handler.getSlots();slot++)handler.setStackInSlot(slot,new ItemStack(Items.COBBLESTONE,64));}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static int count(net.neoforged.neoforge.items.IItemHandler handler,Item item){int count=0;for(int slot=0;slot<handler.getSlots();slot++)if(handler.getStackInSlot(slot).is(item))count+=handler.getStackInSlot(slot).getCount();return count;}
    private static int dropped(ServerLevel level,BlockPos pos,Item item){return level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(3)).stream().filter(e->e.getItem().is(item)).mapToInt(e->e.getItem().getCount()).sum();}
    private static void clearEntities(ServerLevel level,BlockPos pos){for(var entity:level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(4)))entity.discard();}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATETRANSPORTAUDIT] PASS {}",name);}
}
