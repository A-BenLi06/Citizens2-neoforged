package net.yuuniverse.interactions;

import java.util.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.fan.*;
import com.simibubi.create.content.kinetics.fan.processing.*;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.belt.*;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.depot.*;
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
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Native airflow/timers and complete world/depot/belt writes. */
@EventBusSubscriber(modid="interactions")
public final class FanRuntimeAudit {
    private static int passed;
    private static boolean recording, rejectOutput, damageOutput, changeInput, throwOutput;
    private static ItemEntity activeInput;
    private static DepotBlockEntity depot;
    private static int combinedAdmissionCount;
    private static boolean combinedDeferred;
    private static final List<CreateMachineEvidence.Observation> observations=new ArrayList<>();
    private static final List<MachineOutcomeEvent> outcomes=new ArrayList<>();
    @SubscribeEvent public static void result(MachineResultEvent event){
        if(!recording||event.getObservation().source()!=CreateMachineEvidence.Source.CREATE_FAN)return;observations.add(event.getObservation());
        if(changeInput){if(activeInput!=null)activeInput.getItem().grow(1);else depot.getHeldItem().grow(1);}
    }
    @SubscribeEvent public static void outcome(MachineOutcomeEvent event){if(recording&&event.getObservation().source()==CreateMachineEvidence.Source.CREATE_FAN){outcomes.add(event);if(combinedAdmissionCount>0)combinedDeferred&=observations.size()>=combinedAdmissionCount;}}
    @SubscribeEvent public static void insertion(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event){
        if(!recording||!(event.getEntity() instanceof ItemEntity item)||(!item.getItem().is(Items.DIAMOND)&&!item.getItem().is(Items.BUCKET)))return;
        if(rejectOutput)event.setCanceled(true);
        if(throwOutput)throw new IllegalStateException("Expected native fan storage failure");
        if(damageOutput)item.getItem().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Changed fan output"));
    }
    public static void run(MinecraftServer server)throws Exception{
        var level=server.overworld();var manager=server.getRecipeManager();var original=List.copyOf(manager.getRecipes());
        var splashId=ResourceLocation.parse("citizens_audit:fan_splash");var hauntId=ResourceLocation.parse("citizens_audit:fan_haunt");
        var remainderId=ResourceLocation.parse("citizens_audit:fan_remainder");var emptyId=ResourceLocation.parse("citizens_audit:fan_empty");var unchangedId=ResourceLocation.parse("citizens_audit:fan_same");
        var splash=recipe(server,"""
            {"type":"create:splashing","ingredients":[{"item":"minecraft:snowball"}],"results":[{"id":"minecraft:paper","count":2,"components":{"minecraft:custom_name":"\\\"Washed result\\\""}},{"id":"minecraft:diamond","count":1}]}
            """);
        var haunt=recipe(server,"""
            {"type":"create:haunting","ingredients":[{"item":"minecraft:chorus_fruit"}],"results":[{"id":"minecraft:paper","count":2},{"id":"minecraft:diamond","chance":0.5}]}
            """);
        var empty=recipe(server,"""
            {"type":"create:splashing","ingredients":[{"item":"minecraft:leather"}],"results":[{"id":"minecraft:diamond","chance":0.000001}]}
            """);
        var unchanged=recipe(server,"""
            {"type":"create:splashing","ingredients":[{"item":"minecraft:rabbit_hide"}],"results":[{"id":"minecraft:rabbit_hide"}]}
            """);
        var remainder=new com.simibubi.create.content.processing.recipe.StandardProcessingRecipe.Builder<>(SplashingRecipe::new,remainderId)
                .withItemIngredients(NonNullList.of(Ingredient.EMPTY,Ingredient.of(MachineRemainderItem.item))).withSingleItemOutput(new ItemStack(Items.PAPER)).build();
        var expanded=new ArrayList<RecipeHolder<?>>(original);expanded.add(new RecipeHolder<>(splashId,splash));expanded.add(new RecipeHolder<>(hauntId,haunt));expanded.add(new RecipeHolder<>(remainderId,remainder));expanded.add(new RecipeHolder<>(emptyId,empty));expanded.add(new RecipeHolder<>(unchangedId,unchanged));manager.replaceRecipes(expanded);
        var file=dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();var chapter=new dev.ftb.mods.ftbquests.quest.Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
        var quest=new dev.ftb.mods.ftbquests.quest.Quest(file.newID(),chapter);quest.onCreated();var splashTask=task(file,quest);var hauntTask=task(file,quest);var remainderTask=task(file,quest);var sameTask=task(file,quest);file.clearCachedData();file.clearCachedProgress();
        var actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"FanActor"),ClientInformation.createDefault());actor.setPos(1,-60,1);var channel=CreateRuntimeAudit.connect(actor);var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();
        var path=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"fan-evidence-");var service=CraftQuestEvidence.open(server,path);
        var pos=new BlockPos(3,-58,7);var motorPos=pos.west();var catalystPos=pos.east();var holderPos=pos.east(3).below();var itemPos=pos.east(3);
        var beltStart=new BlockPos(6,-59,5);var beltEnd=new BlockPos(6,-59,10);var beltMotorPos=beltStart.west();recording=true;
        try{
            clearEntities(level,pos);level.setBlockAndUpdate(pos,AllBlocks.ENCASED_FAN.getDefaultState().setValue(BlockStateProperties.FACING,Direction.EAST));var fan=(EncasedFanBlockEntity)level.getBlockEntity(pos);fan.initialize();
            var motor=motor(level,motorPos);for(int tick=0;tick<10;tick++){motor.tick();fan.tick();}
            check(fan.getSpeed()!=0&&fan.airCurrent.maxDistance>3,"native_motor_drives_fan_airflow");
            var binding=service.bind(source,splashTask.getCodeString(),splashId);service.bind(source,hauntTask.getCodeString(),hauntId);service.bind(source,remainderTask.getCodeString(),remainderId);
            catalyst(level,fan,catalystPos,false);var input=world(level,itemPos,new ItemStack(Items.SNOWBALL,3));for(int tick=0;tick<250;tick++){motor.tick();fan.tick();}
            check(observations.isEmpty()&&team.getProgress(splashTask)==0&&input.getItem().is(Items.PAPER),"unassigned_native_world_fan_process_has_no_credit");clearEntities(level,pos);
            CreateMachineEvidence.assign(source,actor.getUUID(),pos);input=world(level,itemPos,new ItemStack(Items.SNOWBALL,3));runWorld(motor,fan,input);var observation=observations.getLast();var outcome=outcomes.getLast();
            check(observation.inputEntityId().equals(input.getUUID())&&observation.consumedInput().getCount()==3&&observation.remainingInput().isEmpty()
                    &&outcome.getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(splashTask)==6,"world_fan_confirms_actual_bulk_input_and_primary_output");
            check(input.getItem().getHoverName().getString().equals("Washed result")&&countWorld(level,pos,Items.DIAMOND)==3,"world_fan_component_output_and_secondary_entities_are_stored");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new MachineResultEvent(actor,observation));net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outcome);
            check(team.getProgress(splashTask)==6,"fan_duplicate_outcomes_do_not_credit_twice");clearEntities(level,pos);
            rejectOutput=true;input=world(level,itemPos,new ItemStack(Items.SNOWBALL,2));runWorld(motor,fan,input);rejectOutput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(splashTask)==6,"rejected_native_fan_secondary_entity_prevents_confirmation");resolve(service,source);clearEntities(level,pos);
            damageOutput=true;input=world(level,itemPos,new ItemStack(Items.SNOWBALL,2));runWorld(motor,fan,input);damageOutput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(splashTask)==6,"changed_fan_entity_components_prevent_confirmation");resolve(service,source);clearEntities(level,pos);
            changeInput=true;input=world(level,itemPos,new ItemStack(Items.SNOWBALL,2));runWorld(motor,fan,input);changeInput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(splashTask)==6,"changed_world_fan_input_during_admission_stays_in_review");resolve(service,source);clearEntities(level,pos);
            MachineRemainderItem.calls=0;input=world(level,itemPos,new ItemStack(MachineRemainderItem.item,2));runWorld(motor,fan,input);
            check(MachineRemainderItem.calls==2&&countWorld(level,pos,Items.BUCKET)==2&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(remainderTask)==2,"native_world_fan_remainder_callbacks_match_stored_entities");clearEntities(level,pos);
            level.random.setSeed(42);input=world(level,itemPos,new ItemStack(Items.LEATHER,2));runWorld(motor,fan,input);
            check(observations.getLast().rolledOutputs().isEmpty()&&!input.isAlive()&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(splashTask)==6,"empty_world_fan_roll_consumes_input_without_primary_progress");clearEntities(level,pos);
            catalyst(level,fan,catalystPos,true);input=world(level,itemPos,new ItemStack(Items.CHORUS_FRUIT,4));runWorld(motor,fan,input);
            check(observations.getLast().recipe().processing().process()==CreateProcessingRecipes.Process.HAUNTING&&team.getProgress(hauntTask)==8&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED
                    &&countWorld(level,pos,Items.DIAMOND)==observations.getLast().rolledOutputs().stream().filter(s->s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum(),"native_soul_fire_airflow_confirms_actual_haunting_random_results");clearEntities(level,pos);
            level.setBlockAndUpdate(catalystPos,Blocks.AIR.defaultBlockState());fan.blockInFrontChanged();fan.airCurrent.rebuild();input=world(level,itemPos,new ItemStack(Items.SNOWBALL));int before=observations.size();for(int tick=0;tick<250;tick++){motor.tick();fan.tick();}
            check(observations.size()==before&&input.getItem().is(Items.SNOWBALL),"airflow_without_catalyst_does_not_process_or_credit");clearEntities(level,pos);
            catalyst(level,fan,catalystPos,false);level.setBlockAndUpdate(holderPos,AllBlocks.DEPOT.getDefaultState());depot=(DepotBlockEntity)level.getBlockEntity(holderPos);depot.initialize();fan.airCurrent.rebuild();
            runDepot(motor,fan,new ItemStack(Items.SNOWBALL,3));
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(splashTask)==12&&countDepot(Items.PAPER)==6&&countDepot(Items.DIAMOND)==3,"native_depot_fan_confirms_complete_holder_storage");depot.clearContent();
            changeInput=true;runDepot(motor,fan,new ItemStack(Items.SNOWBALL,2));changeInput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(splashTask)==12,"changed_depot_fan_input_stays_in_review");resolve(service,source);depot.clearContent();
            fillBuffer();MachineRemainderItem.calls=0;runDepot(motor,fan,new ItemStack(MachineRemainderItem.item,3));
            check(MachineRemainderItem.calls==3&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(remainderTask)==5&&countWorld(level,pos,Items.BUCKET)==3,"full_fan_depot_confirms_exact_native_overflow_remainders");depot.clearContent();clearEntities(level,pos);
            fillBuffer();rejectOutput=true;runDepot(motor,fan,new ItemStack(MachineRemainderItem.item,2));rejectOutput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(remainderTask)==5,"rejected_fan_depot_overflow_stays_in_review");resolve(service,source);depot.clearContent();clearEntities(level,pos);
            fillBuffer();damageOutput=true;runDepot(motor,fan,new ItemStack(MachineRemainderItem.item,2));damageOutput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(remainderTask)==5,"changed_fan_overflow_components_prevent_confirmation");resolve(service,source);depot.clearContent();clearEntities(level,pos);
            int failedBefore=observations.size();MachineRemainderItem.calls=0;MachineRemainderItem.fail=true;
            try{runDepot(motor,fan,new ItemStack(MachineRemainderItem.item,2));throw new AssertionError("Native fan remainder exception hidden");}
            catch(IllegalStateException expected){check(MachineRemainderItem.calls==1&&observations.size()==failedBefore&&team.getProgress(remainderTask)==5,"native_fan_remainder_failure_is_preserved_without_evidence");}
            finally{MachineRemainderItem.fail=false;depot.clearContent();}
            fillBuffer();throwOutput=true;
            try{runDepot(motor,fan,new ItemStack(MachineRemainderItem.item,2));throw new AssertionError("Native fan storage exception hidden");}
            catch(IllegalStateException expected){check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.INTERRUPTED&&team.getProgress(remainderTask)==5,"native_fan_overflow_exception_after_admission_is_interrupted");}
            finally{throwOutput=false;depot.clearContent();clearEntities(level,pos);}resolve(service,source);
            level.random.setSeed(42);runDepot(motor,fan,new ItemStack(Items.LEATHER,2));
            check(observations.getLast().rolledOutputs().isEmpty()&&depot.getHeldItem().isEmpty()&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(splashTask)==12,"empty_transported_fan_roll_consumes_input_without_progress");depot.clearContent();
            catalyst(level,fan,catalystPos,true);runDepot(motor,fan,new ItemStack(Items.CHORUS_FRUIT,2));
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(hauntTask)==12&&countDepot(Items.PAPER)==4,"native_depot_fan_haunting_confirms_complete_storage");depot.clearContent();catalyst(level,fan,catalystPos,false);
            service.bind(source,sameTask.getCodeString(),unchangedId);runDepot(motor,fan,new ItemStack(Items.RABBIT_HIDE,2));
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(sameTask)==0&&depot.getHeldItem().getCount()==2,"native_unchanged_depot_fan_result_is_skipped_without_credit");resolve(service,source);depot.clearContent();
            before=observations.size();FanProcessing.applyProcessing(new TransportedItemStack(new ItemStack(Items.SNOWBALL)),level,AllFanProcessingTypes.SPLASHING);AllFanProcessingTypes.SPLASHING.process(new ItemStack(Items.SNOWBALL),level);
            check(observations.size()==before,"unrelated_fan_utilities_are_unattributed");
            server.getPlayerList().remove(actor);channel.finishAndReleaseAll();depot.setHeldItem(new ItemStack(Items.SNOWBALL));for(int tick=0;tick<250;tick++){motor.tick();fan.tick();depot.tick();}
            check(observations.size()==before&&team.getProgress(splashTask)==12,"offline_fan_beneficiary_has_no_credit");channel=CreateRuntimeAudit.connect(actor);depot.clearContent();
            level.removeBlock(holderPos,false);depot=null;
            level.setBlockAndUpdate(beltStart,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));level.setBlockAndUpdate(beltEnd,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));BeltConnectorItem.createBelts(level,beltStart,beltEnd);BeltBlock.initBelt(level,beltStart);
            var belt=(BeltBlockEntity)level.getBlockEntity(beltStart);belt.initialize();var beltMotor=motor(level,beltMotorPos);beltMotor.generatedSpeed.setValue(1);beltMotor.updateGeneratedRotation();
            fan.airCurrent.rebuild();var transported=new TransportedItemStack(new ItemStack(Items.SNOWBALL,2));transported.beltPosition=2.5f;transported.prevBeltPosition=2.5f;belt.getInventory().addItem(transported);before=observations.size();
            for(int tick=0;tick<1000&&observations.size()==before;tick++){motor.tick();beltMotor.tick();fan.tick();belt.tick();}
            check(observations.size()==before+1&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(splashTask)==16,"native_connected_belt_fan_confirms_pending_output_storage");
            check(((CreateTransportInventory)belt.getInventory()).citizens$transportSnapshot().stream().filter(s->s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum()==4,"fan_belt_snapshot_retains_actual_outputs_without_flushing");
            belt.tick();belt.getInventory().ejectAll();clearEntities(level,pos);
            var first=new TransportedItemStack(new ItemStack(Items.SNOWBALL,2));first.beltPosition=2.4f;first.prevBeltPosition=2.4f;
            var second=new TransportedItemStack(new ItemStack(MachineRemainderItem.item,2));second.beltPosition=2.6f;second.prevBeltPosition=2.6f;
            belt.getInventory().addItem(first);belt.getInventory().addItem(second);before=observations.size();int outcomesBefore=outcomes.size();MachineRemainderItem.calls=0;combinedAdmissionCount=before+2;combinedDeferred=true;
            for(int tick=0;tick<1000&&observations.size()<before+2;tick++){motor.tick();beltMotor.tick();fan.tick();belt.tick();}combinedAdmissionCount=0;
            check(observations.size()==before+2&&outcomes.size()==outcomesBefore+2&&outcomes.subList(outcomesBefore,outcomes.size()).stream().allMatch(e->e.getOutcome()==MachineOutcomeEvent.Outcome.RETURNED)
                    &&combinedDeferred&&team.getProgress(splashTask)==20&&team.getProgress(remainderTask)==7&&MachineRemainderItem.calls==2,"simultaneous_native_belt_fan_recipes_confirm_complete_combined_delta");
            check(((CreateTransportInventory)belt.getInventory()).citizens$transportSnapshot().stream().filter(s->s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum()==6,"simultaneous_fan_outputs_remain_in_native_pending_queues");
            belt.tick();belt.getInventory().ejectAll();clearEntities(level,pos);
            var ambiguous=new ArrayList<>(expanded);ambiguous.add(new RecipeHolder<>(ResourceLocation.parse("citizens_audit:fan_ambiguous"),remainder));manager.replaceRecipes(ambiguous);
            first=new TransportedItemStack(new ItemStack(Items.SNOWBALL,2));first.beltPosition=2.4f;first.prevBeltPosition=2.4f;
            second=new TransportedItemStack(new ItemStack(MachineRemainderItem.item,2));second.beltPosition=2.6f;second.prevBeltPosition=2.6f;
            belt.getInventory().addItem(first);belt.getInventory().addItem(second);before=observations.size();MachineRemainderItem.calls=0;
            for(int tick=0;tick<1000&&observations.size()==before;tick++){motor.tick();beltMotor.tick();fan.tick();belt.tick();}
            check(observations.size()==before+1&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(splashTask)==24&&team.getProgress(remainderTask)==7&&MachineRemainderItem.calls==2,"supported_fan_evidence_accounts_for_simultaneous_unidentified_native_recipe");
            manager.replaceRecipes(expanded);belt.tick();belt.getInventory().ejectAll();clearEntities(level,pos);
            var revised=recipe(server,"""
                {"type":"create:splashing","ingredients":[{"item":"minecraft:snowball"}],"results":[{"id":"minecraft:paper","count":3}]}
                """);var replaced=new ArrayList<>(expanded);replaced.removeIf(h->h.id().equals(splashId));replaced.add(new RecipeHolder<>(splashId,revised));manager.replaceRecipes(replaced);
            input=world(level,itemPos,new ItemStack(Items.SNOWBALL,2));runWorld(motor,fan,input);
            check(observations.getLast().recipe().revision().equals(CraftingRecipes.find(server,splashId).orElseThrow().revision())&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&input.getItem().getCount()==6&&team.getProgress(splashTask)==24,"replaced_fan_recipe_uses_actual_current_revision_without_old_binding_credit");manager.replaceRecipes(expanded);clearEntities(level,pos);
            service.close();service=CraftQuestEvidence.open(server,path);
            check(service.bindings(source).stream().anyMatch(b->b.epoch().equals(binding.epoch()))&&service.reconcile(source)==0&&team.getProgress(splashTask)==24&&team.getProgress(hauntTask)==12,"reopened_fan_journal_preserves_absolute_progress");
            org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEFANAUDIT] COMPLETE {} checks",passed);
        }finally{recording=false;rejectOutput=false;damageOutput=false;changeInput=false;throwOutput=false;combinedAdmissionCount=0;MachineRemainderItem.fail=false;activeInput=null;depot=null;service.close();clearEntities(level,pos);
            for(var p:List.of(pos,motorPos,catalystPos,catalystPos.below(),holderPos,beltMotorPos))level.removeBlock(p,false);
            for(int z=beltStart.getZ();z<=beltEnd.getZ();z++)level.removeBlock(new BlockPos(beltStart.getX(),beltStart.getY(),z),false);
            clearFlowingWater(level,pos);server.getPlayerList().remove(actor);channel.finishAndReleaseAll();manager.replaceRecipes(original);}
    }
    private static Recipe<?> recipe(MinecraftServer server,String json){return Recipe.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString(json)).getOrThrow();}
    private static void catalyst(ServerLevel level,EncasedFanBlockEntity fan,BlockPos pos,boolean soul){level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());clearFlowingWater(level,fan.getBlockPos());level.setBlockAndUpdate(pos.below(),soul?Blocks.SOUL_SAND.defaultBlockState():Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(pos,soul?Blocks.SOUL_FIRE.defaultBlockState():Blocks.WATER.defaultBlockState());fan.blockInFrontChanged();fan.airCurrent.rebuild();if(fan.airCurrent.getTypeAt(3)!=(soul?AllFanProcessingTypes.HAUNTING:AllFanProcessingTypes.SPLASHING))throw new AssertionError("Native catalyst not selected: "+level.getBlockState(pos)+" / "+fan.airCurrent.maxDistance+" / "+fan.airCurrent.getTypeAt(3));}
    private static void clearFlowingWater(ServerLevel level,BlockPos fan){for(var p:BlockPos.betweenClosed(fan.offset(0,-4,-4),fan.offset(24,1,4)))if(level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER))level.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());}
    private static ItemEntity world(ServerLevel level,BlockPos pos,ItemStack stack){var input=new ItemEntity(level,pos.getX()+0.5,pos.getY()+0.1,pos.getZ()+0.5,stack);input.setNoGravity(true);if(!level.addFreshEntity(input))throw new AssertionError("Fixture input refused");return input;}
    private static void runWorld(CreativeMotorBlockEntity motor,EncasedFanBlockEntity fan,ItemEntity input){activeInput=input;int before=observations.size();try{for(int tick=0;tick<1000;tick++){motor.tick();fan.tick();if(observations.size()>before)return;}throw new AssertionError("Native world fan did not process: "+fan.airCurrent.direction+" "+fan.airCurrent.bounds);}finally{activeInput=null;}}
    private static void runDepot(CreativeMotorBlockEntity motor,EncasedFanBlockEntity fan,ItemStack input){depot.setHeldItem(input.copy());int before=observations.size();for(int tick=0;tick<1000;tick++){motor.tick();fan.tick();depot.tick();if(observations.size()>before)return;}throw new AssertionError("Native depot fan did not process");}
    private static int countDepot(Item item){var inventory=depot.getBehaviour(DepotBehaviour.TYPE).itemHandler;int count=0;for(int slot=0;slot<inventory.getSlots();slot++)if(inventory.getStackInSlot(slot).is(item))count+=inventory.getStackInSlot(slot).getCount();return count;}
    private static void fillBuffer()throws ReflectiveOperationException{var behaviour=depot.getBehaviour(DepotBehaviour.TYPE);var field=DepotBehaviour.class.getDeclaredField("processingOutputBuffer");field.setAccessible(true);var buffer=(net.neoforged.neoforge.items.ItemStackHandler)field.get(behaviour);for(int slot=0;slot<buffer.getSlots();slot++)buffer.setStackInSlot(slot,new ItemStack(Items.COBBLESTONE,64));}
    private static int countWorld(ServerLevel level,BlockPos pos,Item item){return level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(12)).stream().filter(e->e.getItem().is(item)).mapToInt(e->e.getItem().getCount()).sum();}
    private static void clearEntities(ServerLevel level,BlockPos pos){for(var entity:level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(12)))entity.discard();}
    private static CreativeMotorBlockEntity motor(ServerLevel level,BlockPos pos){level.setBlockAndUpdate(pos,AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,Direction.EAST));var motor=(CreativeMotorBlockEntity)level.getBlockEntity(pos);motor.initialize();motor.generatedSpeed.setValue(256);motor.updateGeneratedRotation();return motor;}
    private static dev.ftb.mods.ftbquests.quest.task.CustomTask task(dev.ftb.mods.ftbquests.quest.ServerQuestFile file,dev.ftb.mods.ftbquests.quest.Quest quest){var task=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);task.setMaxProgress(1000);task.onCreated();return task;}
    private static void resolve(CraftQuestEvidence service,net.minecraft.commands.CommandSourceStack source)throws java.io.IOException{service.resolve(source,service.entries(source).getLast().id(),false,"Fixture changed native fan input/output storage");}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEFANAUDIT] PASS {}",name);}
}
