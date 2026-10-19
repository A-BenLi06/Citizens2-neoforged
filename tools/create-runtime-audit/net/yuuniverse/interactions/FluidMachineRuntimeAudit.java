package net.yuuniverse.interactions;

import java.util.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.spout.SpoutBlockEntity;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;
import com.simibubi.create.content.fluids.transfer.GenericItemEmptying;
import com.simibubi.create.content.fluids.spout.FillingBySpout;
import com.simibubi.create.content.kinetics.belt.*;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.depot.*;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Real held-item cycles and tank storage; direct utility calls exercise only non-credit boundaries. */
@EventBusSubscriber(modid="interactions")
public final class FluidMachineRuntimeAudit {
    private static int passed;
    private static boolean recording, changeInput, changeFluid, fillDrain;
    private static BlockEntity active;
    private static DepotBlockEntity depot;
    private static final List<CreateMachineEvidence.Observation> observations=new ArrayList<>();
    private static final List<MachineOutcomeEvent> outcomes=new ArrayList<>();
    @SubscribeEvent public static void result(MachineResultEvent event){
        if(!recording||event.getObservation().fluid()==null)return;
        observations.add(event.getObservation());
        if(changeInput){if(active instanceof ItemDrainBlockEntity drain)drain.getHeldItemStack().grow(1);else depot.getHeldItem().grow(1);}
        if(changeFluid)tank(active).drain(1,IFluidHandler.FluidAction.EXECUTE);
        if(fillDrain){var drain=(ItemDrainBlockEntity)active;
            var behaviour=drain.getBehaviour(com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour.TYPE);
            behaviour.allowInsertion();behaviour.getPrimaryHandler().fill(new FluidStack(Fluids.WATER,1500),IFluidHandler.FluidAction.EXECUTE);behaviour.forbidInsertion();}
    }
    @SubscribeEvent public static void outcome(MachineOutcomeEvent event){if(recording&&event.getObservation().fluid()!=null)outcomes.add(event);}
    public static void run(MinecraftServer server)throws Exception{
        var level=server.overworld();var manager=server.getRecipeManager();var original=List.copyOf(manager.getRecipes());
        var fillId=ResourceLocation.parse("citizens_audit:spout_fill");var emptyId=ResourceLocation.parse("citizens_audit:drain_empty");
        var pureId=ResourceLocation.parse("citizens_audit:drain_fluid_only");var chanceId=ResourceLocation.parse("citizens_audit:spout_chance");
        var unchangedId=ResourceLocation.parse("citizens_audit:spout_unchanged");
        var fill=recipe(server,"""
            {"type":"create:filling","ingredients":[{"item":"minecraft:snowball"},{"type":"neoforge:single","fluid":"minecraft:water","amount":250}],
             "results":[{"id":"minecraft:paper","count":2,"components":{"minecraft:custom_name":"\\\"Filled result\\\""}}]}
            """);
        var empty=recipe(server,"""
            {"type":"create:emptying","ingredients":[{"item":"minecraft:flint"}],
             "results":[{"id":"minecraft:paper","count":2,"chance":0.5,"components":{"minecraft:custom_name":"\\\"Drained result\\\""}},{"id":"minecraft:water","amount":250}]}
            """);
        var pure=recipe(server,"""
            {"type":"create:emptying","ingredients":[{"item":"minecraft:brick"}],"results":[{"id":"minecraft:water","amount":100}]}
            """);
        var chance=recipe(server,"""
            {"type":"create:filling","ingredients":[{"item":"minecraft:clay_ball"},{"type":"neoforge:single","fluid":"minecraft:water","amount":50}],
             "results":[{"id":"minecraft:diamond","chance":0.000001}]}
            """);
        var unchanged=recipe(server,"""
            {"type":"create:filling","ingredients":[{"item":"minecraft:rabbit_hide"},{"type":"neoforge:single","fluid":"minecraft:water","amount":50}],
             "results":[{"id":"minecraft:rabbit_hide"}]}
            """);
        var expanded=new ArrayList<RecipeHolder<?>>(original);
        expanded.add(new RecipeHolder<>(fillId,fill));expanded.add(new RecipeHolder<>(emptyId,empty));expanded.add(new RecipeHolder<>(pureId,pure));
        expanded.add(new RecipeHolder<>(chanceId,chance));expanded.add(new RecipeHolder<>(unchangedId,unchanged));manager.replaceRecipes(expanded);
        var file=dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();
        var chapter=new dev.ftb.mods.ftbquests.quest.Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
        var quest=new dev.ftb.mods.ftbquests.quest.Quest(file.newID(),chapter);quest.onCreated();
        var fillTask=task(file,quest);var emptyTask=task(file,quest);var pureTask=task(file,quest);var unchangedTask=task(file,quest);file.clearCachedData();file.clearCachedProgress();
        var actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"FluidActor"),ClientInformation.createDefault());actor.setPos(1,-60,1);
        var channel=CreateRuntimeAudit.connect(actor);var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();
        var path=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"fluid-evidence-");var service=CraftQuestEvidence.open(server,path);
        var pos=new BlockPos(8,-58,7);var holder=pos.below(2);var drainPos=new BlockPos(4,-60,9);
        var beltStart=new BlockPos(8,-60,5);var beltEnd=new BlockPos(8,-60,10);var motorPos=beltStart.west();
        recording=true;
        try{
            level.setBlockAndUpdate(pos,AllBlocks.SPOUT.getDefaultState());var spout=(SpoutBlockEntity)level.getBlockEntity(pos);spout.initialize();active=spout;
            level.setBlockAndUpdate(holder,AllBlocks.DEPOT.getDefaultState());depot=(DepotBlockEntity)level.getBlockEntity(holder);depot.initialize();
            var binding=service.bind(source,fillTask.getCodeString(),fillId);service.bind(source,emptyTask.getCodeString(),emptyId);service.bind(source,pureTask.getCodeString(),pureId);
            resetSpout(spout);tank(spout).fill(new FluidStack(Fluids.WATER,750),IFluidHandler.FluidAction.EXECUTE);depot.setHeldItem(new ItemStack(Items.SNOWBALL,3));
            for(int tick=0;tick<18;tick++){spout.tick();depot.tick();}
            check(observations.isEmpty()&&team.getProgress(fillTask)==0,"unassigned_spout_has_no_evidence");
            resetSpout(spout);CreateMachineEvidence.assign(source,actor.getUUID(),pos);
            runDepot(spout,new ItemStack(Items.SNOWBALL,3),750);var observation=observations.getLast();var outcome=outcomes.getLast();
            check(observation.source()==CreateMachineEvidence.Source.CREATE_SPOUT&&observation.consumedInput().getCount()==1&&observation.remainingInput().getCount()==2
                    &&outcome.getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(fillTask)==2,"depot_spout_confirms_single_native_item_and_fluid_operation");
            check(depot.getHeldItem().is(Items.SNOWBALL)&&depot.getHeldItem().getCount()==2&&countDepot(Items.PAPER)==2
                    &&tank(spout).getFluidInTank(0).getAmount()==500,"spout_retains_unconsumed_input_and_exact_fluid_delta");
            check(observation.rolledOutputs().getFirst().getHoverName().getString().equals("Filled result")&&observation.fluid().consumedFluids().getFirst().getAmount()==250
                    &&JsonParser.parseString(CreateMachineEvidence.encode(observation,server.registryAccess())).getAsJsonObject().has("fluid"),"spout_components_and_typed_fluid_plan_are_retained");
            observation.fluid().beforeFluids().getFirst().setAmount(99);observation.fluid().consumedFluids().getFirst().setAmount(99);
            check(observation.fluid().beforeFluids().getFirst().getAmount()==750&&observation.fluid().consumedFluids().getFirst().getAmount()==250,"fluid_observation_is_defensive");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new MachineResultEvent(actor,observation));net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outcome);
            check(team.getProgress(fillTask)==2,"duplicate_fluid_outcome_does_not_credit_twice");
            runDepot(spout,new ItemStack(Items.SNOWBALL),250);
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(fillTask)==4&&tank(spout).getFluidInTank(0).isEmpty(),"last_item_and_last_fluid_are_confirmed");
            changeInput=true;runDepot(spout,new ItemStack(Items.SNOWBALL,3),750);changeInput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(fillTask)==4,"spout_changed_input_stays_in_review");resolve(service,source);
            changeFluid=true;runDepot(spout,new ItemStack(Items.SNOWBALL,3),750);changeFluid=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(fillTask)==4,"spout_changed_fluid_stays_in_review");resolve(service,source);
            level.random.setSeed(42);runDepot(spout,new ItemStack(Items.CLAY_BALL,2),100);
            check(observations.getLast().rolledOutputs().isEmpty()&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED
                    &&depot.getHeldItem().getCount()==1&&tank(spout).getFluidInTank(0).getAmount()==50,"empty_random_spout_roll_does_not_manufacture_output_or_credit");
            service.bind(source,unchangedTask.getCodeString(),unchangedId);runDepot(spout,new ItemStack(Items.RABBIT_HIDE,2),100);
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(unchangedTask)==1
                    &&countDepot(Items.RABBIT_HIDE)==2&&tank(spout).getFluidInTank(0).getAmount()==50,"same_item_filling_confirms_stored_result_and_consumed_fluid");
            resetSpout(spout);int before=observations.size();depot.setHeldItem(new ItemStack(Items.SNOWBALL));tank(spout).fill(new FluidStack(Fluids.WATER,100),IFluidHandler.FluidAction.EXECUTE);
            for(int tick=0;tick<30;tick++){spout.tick();depot.tick();}
            check(observations.size()==before&&depot.getHeldItem().getCount()==1&&tank(spout).getFluidInTank(0).getAmount()==100,"insufficient_spout_fluid_preserves_input_without_evidence");
            resetSpout(spout);before=observations.size();runDepot(spout,new ItemStack(Items.BUCKET),1000,false);
            check(observations.size()==before&&countDepot(Items.WATER_BUCKET)==1,"generic_bucket_filling_is_not_a_registered_recipe_credit");
            var utilityInput=new ItemStack(Items.SNOWBALL,2);var utilityFluid=new FluidStack(Fluids.WATER,500);before=observations.size();
            FillingBySpout.fillItem(level,250,utilityInput,utilityFluid);GenericItemEmptying.emptyItem(level,new ItemStack(Items.FLINT),false);
            check(observations.size()==before,"unrelated_filling_and_emptying_utilities_do_not_emit_machine_evidence");
            resetSpout(spout);level.removeBlock(holder,false);depot=null;
            level.setBlockAndUpdate(beltStart,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));
            level.setBlockAndUpdate(beltEnd,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));BeltConnectorItem.createBelts(level,beltStart,beltEnd);BeltBlock.initBelt(level,beltStart);
            var belt=(BeltBlockEntity)level.getBlockEntity(beltStart);belt.initialize();
            level.setBlockAndUpdate(motorPos,AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,Direction.EAST));
            var motor=(CreativeMotorBlockEntity)level.getBlockEntity(motorPos);motor.initialize();motor.generatedSpeed.setValue(256);motor.updateGeneratedRotation();
            for(int tick=0;tick<10;tick++){motor.tick();belt.tick();spout.tick();}
            tank(spout).fill(new FluidStack(Fluids.WATER,500),IFluidHandler.FluidAction.EXECUTE);var transported=new TransportedItemStack(new ItemStack(Items.SNOWBALL,2));transported.beltPosition=0.5f;transported.prevBeltPosition=0.5f;belt.getInventory().addItem(transported);
            before=observations.size();for(int tick=0;tick<1000&&observations.size()==before;tick++){motor.tick();spout.tick();belt.tick();}
            check(observations.size()==before+1&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(fillTask)==6,"native_connected_belt_spout_pending_storage_is_confirmed");
            check(((CreateTransportInventory)belt.getInventory()).citizens$transportSnapshot().stream().filter(s->s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum()==2,"belt_snapshot_retains_actual_filled_output_without_flushing");
            level.setBlockAndUpdate(drainPos,AllBlocks.ITEM_DRAIN.getDefaultState());var drain=(ItemDrainBlockEntity)level.getBlockEntity(drainPos);drain.initialize();active=drain;
            var inserted=level.getCapability(Capabilities.ItemHandler.BLOCK,drainPos,Direction.EAST).insertItem(0,new ItemStack(Items.BRICK,3),false);
            check(inserted.getCount()==2&&drain.getHeldItemStack().getCount()==1,"native_drain_insertion_retains_surplus_outside_single_held_item");
            for(int tick=0;tick<30;tick++)drain.tick();
            check(observations.size()==before+1&&team.getProgress(pureTask)==0,"unassigned_drain_does_not_credit");
            clearDrain(drain);CreateMachineEvidence.assign(source,actor.getUUID(),drainPos);runDrain(drain,Items.BRICK);
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&observations.getLast().source()==CreateMachineEvidence.Source.CREATE_ITEM_DRAIN
                    &&team.getProgress(pureTask)==100&&tank(drain).getFluidInTank(0).getAmount()==100&&drain.getHeldItemStack().isEmpty(),"fluid_only_emptying_confirms_actual_stored_millibuckets");
            long primary=0;for(int cycle=0;cycle<8;cycle++){clearDrain(drain);runDrain(drain,Items.FLINT);var observed=observations.getLast();primary+=observed.rolledOutputs().stream().mapToInt(ItemStack::getCount).sum();
                if(outcomes.getLast().getOutcome()!=MachineOutcomeEvent.Outcome.RETURNED||tank(drain).getFluidInTank(0).getAmount()!=250)throw new AssertionError("Drain actual roll/storage mismatch");
                if(!drain.getHeldItemStack().isEmpty()&&!drain.getHeldItemStack().getHoverName().getString().equals("Drained result"))throw new AssertionError("Drain components lost");}
            check(team.getProgress(emptyTask)==primary,"drain_simulation_rolls_are_excluded_and_actual_random_item_units_match");
            clearDrain(drain);var input=new ItemStack(Items.FLINT,2);before=observations.size();GenericItemEmptying.emptyItem(level,input,true);
            check(input.getCount()==2&&observations.size()==before,"emptying_simulation_has_no_consumption_or_evidence");
            fillDrain=true;runDrain(drain,Items.BRICK);fillDrain=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(pureTask)==100,"drain_capacity_mutation_prevents_confirmation");resolve(service,source);
            clearDrain(drain);changeInput=true;runDrain(drain,Items.BRICK);changeInput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(pureTask)==100,"drain_changed_held_input_prevents_confirmation");resolve(service,source);
            clearDrain(drain);before=observations.size();runDrain(drain,Items.WATER_BUCKET,false);
            check(observations.size()==before&&drain.getHeldItemStack().is(Items.BUCKET)&&tank(drain).getFluidInTank(0).getAmount()==1000,"generic_emptying_does_not_claim_registered_recipe_evidence");
            clearDrain(drain);var behaviour=drain.getBehaviour(com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour.TYPE);behaviour.allowInsertion();behaviour.getPrimaryHandler().fill(new FluidStack(Fluids.WATER,1500),IFluidHandler.FluidAction.EXECUTE);behaviour.forbidInsertion();
            before=observations.size();level.getCapability(Capabilities.ItemHandler.BLOCK,drainPos,Direction.EAST).insertItem(0,new ItemStack(Items.BRICK),false);
            for(int tick=0;tick<60;tick++)drain.tick();
            check(observations.size()==before&&drain.getHeldItemStack().is(Items.BRICK)&&tank(drain).getFluidInTank(0).getAmount()==1500,"full_drain_waits_without_consumption_or_evidence");
            clearDrain(drain);before=observations.size();server.getPlayerList().remove(actor);channel.finishAndReleaseAll();
            runDrain(drain,Items.BRICK,false);
            check(observations.size()==before&&team.getProgress(pureTask)==100,"offline_drain_beneficiary_has_no_credit");
            channel=CreateRuntimeAudit.connect(actor);clearDrain(drain);
            var revised=recipe(server,"""
                {"type":"create:emptying","ingredients":[{"item":"minecraft:brick"}],"results":[{"id":"minecraft:water","amount":120}]}
                """);
            var replaced=new ArrayList<>(expanded);replaced.removeIf(h->h.id().equals(pureId));replaced.add(new RecipeHolder<>(pureId,revised));manager.replaceRecipes(replaced);
            runDrain(drain,Items.BRICK);
            check(observations.getLast().recipe().revision().equals(CraftingRecipes.find(server,pureId).orElseThrow().revision())
                    &&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&tank(drain).getFluidInTank(0).getAmount()==120&&team.getProgress(pureTask)==100,
                    "replaced_emptying_recipe_uses_current_identity_and_does_not_credit_old_binding");
            manager.replaceRecipes(expanded);
            service.close();service=CraftQuestEvidence.open(server,path);
            check(service.bindings(source).stream().anyMatch(b->b.epoch().equals(binding.epoch()))&&service.reconcile(source)==0&&team.getProgress(fillTask)==6
                    &&team.getProgress(pureTask)==100&&team.getProgress(emptyTask)==primary,"reopened_fluid_journal_preserves_absolute_item_and_fluid_progress");
            org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEFLUIDAUDIT] COMPLETE {} checks",passed);
        }finally{
            recording=false;changeInput=false;changeFluid=false;fillDrain=false;service.close();active=null;depot=null;
            for(var p:List.of(pos,holder,drainPos,motorPos,beltStart,beltEnd))level.removeBlock(p,false);
            server.getPlayerList().remove(actor);channel.finishAndReleaseAll();manager.replaceRecipes(original);
        }
    }
    private static Recipe<?> recipe(MinecraftServer server,String json){return Recipe.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString(json)).getOrThrow();}
    private static dev.ftb.mods.ftbquests.quest.task.CustomTask task(dev.ftb.mods.ftbquests.quest.ServerQuestFile file,dev.ftb.mods.ftbquests.quest.Quest quest){var task=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);task.setMaxProgress(10000);task.onCreated();return task;}
    private static IFluidHandler tank(BlockEntity machine){return machine.getLevel().getCapability(Capabilities.FluidHandler.BLOCK,machine.getBlockPos(),null);}
    private static void resetSpout(SpoutBlockEntity spout){if(depot!=null)depot.clearContent();tank(spout).drain(Integer.MAX_VALUE,IFluidHandler.FluidAction.EXECUTE);spout.processingTicks=-1;}
    private static void runDepot(SpoutBlockEntity spout,ItemStack input,int fluid){runDepot(spout,input,fluid,true);}
    private static void runDepot(SpoutBlockEntity spout,ItemStack input,int fluid,boolean evidence){resetSpout(spout);depot.setHeldItem(input.copy());tank(spout).fill(new FluidStack(Fluids.WATER,fluid),IFluidHandler.FluidAction.EXECUTE);int before=observations.size();
        for(int tick=0;tick<30;tick++){spout.tick();depot.tick();if(evidence&&observations.size()>before)return;if(!evidence&&countDepot(Items.WATER_BUCKET)>0)return;}throw new AssertionError("Spout did not process native input");}
    private static int countDepot(Item item){var behaviour=depot.getBehaviour(DepotBehaviour.TYPE);int count=0;for(int slot=0;slot<behaviour.itemHandler.getSlots();slot++){var stack=behaviour.itemHandler.getStackInSlot(slot);if(stack.is(item))count+=stack.getCount();}return count;}
    private static void clearDrain(ItemDrainBlockEntity drain){drain.setHeldItem(new TransportedItemStack(ItemStack.EMPTY),Direction.EAST);tank(drain).drain(Integer.MAX_VALUE,IFluidHandler.FluidAction.EXECUTE);for(int tick=0;tick<40;tick++)drain.tick();}
    private static void runDrain(ItemDrainBlockEntity drain,Item item){runDrain(drain,item,true);}
    private static void runDrain(ItemDrainBlockEntity drain,Item item,boolean evidence){var surplus=drain.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,drain.getBlockPos(),Direction.EAST).insertItem(0,new ItemStack(item),false);if(!surplus.isEmpty())throw new AssertionError("Drain refused input");int before=observations.size();
        for(int tick=0;tick<40;tick++){drain.tick();if(evidence&&observations.size()>before)return;if(!evidence&&tank(drain).getFluidInTank(0).getAmount()>0)return;}throw new AssertionError("Drain did not process native input");}
    private static void resolve(CraftQuestEvidence service,net.minecraft.commands.CommandSourceStack source)throws java.io.IOException{service.resolve(source,service.entries(source).getLast().id(),false,"Fixture changed native item or tank storage");}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEFLUIDAUDIT] PASS {}",name);}
}
