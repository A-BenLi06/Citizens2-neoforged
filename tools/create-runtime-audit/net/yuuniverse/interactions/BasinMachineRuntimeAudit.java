package net.yuuniverse.interactions;

import java.util.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.processing.basin.*;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.fluids.FluidStack;

/** Native basin cycles: no direct apply invocation is used to manufacture completion evidence. */
@EventBusSubscriber(modid="interactions")
public final class BasinMachineRuntimeAudit {
    private static int passed;
    private static boolean recording, blockStorage, changeFluid;
    private static BasinBlockEntity active;
    private static final List<CreateMachineEvidence.Observation> observations=new ArrayList<>();
    private static final List<MachineOutcomeEvent> outcomes=new ArrayList<>();
    @SubscribeEvent public static void result(MachineResultEvent event){
        if(!recording||event.getObservation().basin()==null)return;observations.add(event.getObservation());
        if(blockStorage)for(int slot=0;slot<active.getOutputInventory().getSlots();slot++)active.getOutputInventory().setStackInSlot(slot,new ItemStack(Items.COBBLESTONE,64));
        if(changeFluid)active.inputTank.getPrimaryHandler().getFluid().shrink(1);
    }
    @SubscribeEvent public static void outcome(MachineOutcomeEvent event){if(recording&&event.getObservation().basin()!=null)outcomes.add(event);}
    public static void run(MinecraftServer server) throws Exception {
        var level=server.overworld();var manager=server.getRecipeManager();var original=List.copyOf(manager.getRecipes());
        var mixId=ResourceLocation.parse("citizens_audit:basin_mix");var compactId=ResourceLocation.parse("citizens_audit:basin_compact");
        var fluidId=ResourceLocation.parse("citizens_audit:basin_fluid");var heatId=ResourceLocation.parse("citizens_audit:basin_heat");
        var pureId=ResourceLocation.parse("citizens_audit:basin_pure_fluid");
        var remainderId=ResourceLocation.parse("citizens_audit:basin_remainder");
        var remainderRecipe=new com.simibubi.create.content.processing.recipe.StandardProcessingRecipe.Builder<>(com.simibubi.create.content.kinetics.mixer.MixingRecipe::new,remainderId)
                .withItemIngredients(NonNullList.of(Ingredient.EMPTY,Ingredient.of(MachineRemainderItem.item),Ingredient.of(MachineRemainderItem.item)))
                .withSingleItemOutput(new ItemStack(Items.PAPER)).build();
        var ops=server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var mix=Recipe.CODEC.parse(ops,JsonParser.parseString("""
            {"type":"create:mixing","ingredients":[{"item":"minecraft:snowball"},{"item":"minecraft:snowball"},{"item":"minecraft:flint"},
               {"type":"neoforge:single","fluid":"minecraft:water","amount":250}],
             "results":[{"id":"minecraft:paper","count":2,"components":{"minecraft:custom_name":"\\\"Basin result\\\""}},
                 {"id":"minecraft:diamond","chance":0.5},{"id":"minecraft:lava","amount":100}],"processingTime":20}
            """)).getOrThrow();
        var compact=Recipe.CODEC.parse(ops,JsonParser.parseString("""
            {"type":"create:compacting","ingredients":[{"item":"minecraft:clay_ball"},{"item":"minecraft:clay_ball"}],
              "results":[{"id":"minecraft:clay","count":2}],"processingTime":20}
            """)).getOrThrow();
        var fluid=Recipe.CODEC.parse(ops,JsonParser.parseString("""
            {"type":"create:mixing","ingredients":[{"item":"minecraft:sugar"},{"type":"neoforge:single","fluid":"minecraft:water","amount":250}],
             "results":[{"id":"minecraft:lava","amount":100}],"processingTime":20}
            """)).getOrThrow();
        var heat=Recipe.CODEC.parse(ops,JsonParser.parseString("""
            {"type":"create:mixing","ingredients":[{"item":"minecraft:brick"}],"results":[{"id":"minecraft:paper"}],"heat_requirement":"heated"}
            """)).getOrThrow();
        var pure=Recipe.CODEC.parse(ops,JsonParser.parseString("""
            {"type":"create:mixing","ingredients":[{"type":"neoforge:single","fluid":"minecraft:lava","amount":100}],
             "results":[{"id":"minecraft:water","amount":50}],"processingTime":20}
            """)).getOrThrow();
        var expanded=new ArrayList<RecipeHolder<?>>(original);expanded.add(new RecipeHolder<>(mixId,mix));expanded.add(new RecipeHolder<>(compactId,compact));
        expanded.add(new RecipeHolder<>(fluidId,fluid));expanded.add(new RecipeHolder<>(heatId,heat));expanded.add(new RecipeHolder<>(remainderId,remainderRecipe));expanded.add(new RecipeHolder<>(pureId,pure));manager.replaceRecipes(expanded);
        var file=dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();
        var chapter=new dev.ftb.mods.ftbquests.quest.Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
        var quest=new dev.ftb.mods.ftbquests.quest.Quest(file.newID(),chapter);quest.onCreated();
        var mixTask=task(file,quest);var compactTask=task(file,quest);var fluidTask=task(file,quest);var heatTask=task(file,quest);var pureTask=task(file,quest);file.clearCachedData();file.clearCachedProgress();
        var actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"BasinActor"),ClientInformation.createDefault());actor.setPos(1,-60,1);
        var channel=CreateRuntimeAudit.connect(actor);var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();
        var path=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"basin-evidence-");var service=CraftQuestEvidence.open(server,path);
        var pos=new BlockPos(4,-58,10);var basinPos=pos.below(2);var cogPos=pos.east();var motorPos=cogPos.above();var pressMotorPos=pos.west();
        recording=true;
        try {
            level.setBlockAndUpdate(basinPos,AllBlocks.BASIN.getDefaultState());active=(BasinBlockEntity)level.getBlockEntity(basinPos);active.initialize();
            level.setBlockAndUpdate(pos,AllBlocks.MECHANICAL_MIXER.getDefaultState());var mixer=(MechanicalMixerBlockEntity)level.getBlockEntity(pos);mixer.initialize();
            level.setBlockAndUpdate(cogPos,AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.Y));
            var cog=(com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity)level.getBlockEntity(cogPos);cog.initialize();
            var motor=motor(level,motorPos,Direction.DOWN);
            for(int tick=0;tick<10;tick++){motor.tick();cog.tick();mixer.tick();active.tick();}
            check(mixer.getSpeed()!=0&&mixer.isSpeedRequirementFulfilled(),"native_cogwheel_and_motor_power_basin_mixer");
            var binding=service.bind(source,mixTask.getCodeString(),mixId);service.bind(source,fluidTask.getCodeString(),fluidId);service.bind(source,heatTask.getCodeString(),heatId);
            service.bind(source,pureTask.getCodeString(),pureId);
            inputs(active,new ItemStack(Items.SNOWBALL,5),new ItemStack(Items.FLINT,2));active.inputTank.getPrimaryHandler().fill(new FluidStack(Fluids.WATER,750),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            cycle(mixer,motor,cog,false);
            check(observations.isEmpty()&&team.getProgress(mixTask)==0,"unassigned_native_basin_cycles_do_not_credit_tasks");clear(mixer,motor,cog);
            CreateMachineEvidence.assign(source,actor.getUUID(),pos);
            inputs(active,new ItemStack(Items.SNOWBALL,5),new ItemStack(Items.FLINT,2));active.inputTank.getPrimaryHandler().fill(new FluidStack(Fluids.WATER,750),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            cycle(mixer,motor,cog,true);var observation=observations.getLast();var outcome=outcomes.getLast();
            check(observation.source()==CreateMachineEvidence.Source.CREATE_MIXER&&observation.basin().consumedItems().stream().mapToInt(ItemStack::getCount).sum()==3
                    &&observation.basin().consumedFluids().getFirst().getAmount()==250&&outcome.getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(mixTask)==2,
                    "basin_mix_confirms_actual_multi_item_fluid_consumption_and_primary_units");
            var snapshot=((CreateBasinStorage)active).citizens$basinSnapshot();
            check(count(snapshot.items(),Items.SNOWBALL)==3&&count(snapshot.items(),Items.FLINT)==1&&fluidAmount(snapshot.fluids(),Fluids.WATER)==500
                    &&fluidAmount(snapshot.fluids(),Fluids.LAVA)==100,"native_basin_retains_unconsumed_items_and_fluids_and_exact_fluid_output");
            check(observation.rolledOutputs().stream().filter(s->s.is(Items.PAPER)).allMatch(s->s.getHoverName().getString().equals("Basin result"))
                    &&count(snapshot.items(),Items.DIAMOND)==count(observation.rolledOutputs(),Items.DIAMOND),"basin_actual_random_outputs_and_components_match_stored_result");
            observation.basin().consumedItems().getFirst().setCount(99);observation.basin().consumedFluids().getFirst().setAmount(999);
            check(observation.basin().consumedItems().stream().mapToInt(ItemStack::getCount).sum()==3&&observation.basin().consumedFluids().getFirst().getAmount()==250,
                    "basin_multi_input_fluid_snapshots_are_defensive");
            check(JsonParser.parseString(CreateMachineEvidence.encode(observation,server.registryAccess())).getAsJsonObject().getAsJsonObject("basin").getAsJsonArray("consumedFluids").size()==1,
                    "durable_basin_payload_preserves_native_fluid_plan");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new MachineResultEvent(actor,observation));net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outcome);
            check(team.getProgress(mixTask)==2,"duplicate_basin_outcome_cannot_repeat_progress");clear(mixer,motor,cog);
            int before=observations.size();inputs(active,new ItemStack(Items.SNOWBALL,2),new ItemStack(Items.FLINT));
            check(!BasinRecipe.match(active,mix)&&observations.size()==before&&count(((CreateBasinStorage)active).citizens$basinSnapshot().items(),Items.SNOWBALL)==2,
                    "native_basin_match_simulation_with_missing_fluid_has_no_consumption_or_evidence");clear(mixer,motor,cog);
            inputs(active,new ItemStack(Items.BRICK));for(int tick=0;tick<150;tick++){motor.tick();cog.tick();mixer.tick();active.tick();}
            check(observations.size()==before&&team.getProgress(heatTask)==0&&count(((CreateBasinStorage)active).citizens$basinSnapshot().items(),Items.BRICK)==1,
                    "native_heat_requirement_prevents_unheated_basin_completion");clear(mixer,motor,cog);
            level.setBlockAndUpdate(basinPos.below(),AllBlocks.BLAZE_BURNER.getDefaultState().setValue(com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HEAT_LEVEL,
                    com.simibubi.create.content.processing.burner.BlazeBurnerBlock.HeatLevel.KINDLED));
            active.tick();inputs(active,new ItemStack(Items.BRICK));cycle(mixer,motor,cog,true);
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(heatTask)==1,
                    "native_blaze_burner_heat_allows_registered_basin_recipe");clear(mixer,motor,cog);level.removeBlock(basinPos.below(),false);active.tick();
            inputs(active,new ItemStack(Items.SUGAR));active.inputTank.getPrimaryHandler().fill(new FluidStack(Fluids.WATER,500),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            cycle(mixer,motor,cog,true);
            check(observations.getLast().rolledOutputs().isEmpty()&&observations.getLast().basin().fluidOutputs().getFirst().getAmount()==100
                    &&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(fluidTask)==100,
                    "fluid_only_basin_recipe_advances_exact_native_millibucket_units");clear(mixer,motor,cog);
            inputs(active,new ItemStack(Items.SNOWBALL,2),new ItemStack(Items.FLINT));active.inputTank.getPrimaryHandler().fill(new FluidStack(Fluids.WATER,250),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            blockStorage=true;cycle(mixer,motor,cog,true);blockStorage=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(mixTask)==2&&service.entries(source).getLast().state()==QuestEvidenceJournal.State.REVIEW,
                    "changed_basin_output_capacity_after_admission_requires_review_without_credit");service.resolve(source,service.entries(source).getLast().id(),false,"Fixture filled native output after simulation");clear(mixer,motor,cog);
            inputs(active,new ItemStack(Items.SUGAR));active.inputTank.getPrimaryHandler().fill(new FluidStack(Fluids.WATER,500),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            changeFluid=true;cycle(mixer,motor,cog,true);changeFluid=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(fluidTask)==100,"fluid_mutation_during_basin_admission_cannot_confirm_old_plan");
            service.resolve(source,service.entries(source).getLast().id(),false,"Fixture changed native input fluid during admission");clear(mixer,motor,cog);
            var remainderTask=task(file,quest);file.clearCachedData();file.clearCachedProgress();
            service.bind(source,remainderTask.getCodeString(),remainderId);MachineRemainderItem.calls=0;
            var dummyInventory=new net.neoforged.neoforge.items.ItemStackHandler(1);dummyInventory.setStackInSlot(0,new ItemStack(MachineRemainderItem.item,3));
            var dummy=new com.simibubi.create.foundation.recipe.DummyCraftingContainer(dummyInventory,new int[]{2});
            check(dummy.asCraftInput().size()==2&&dummy.asCraftInput().items().stream().allMatch(s->s.getCount()==1&&s.is(MachineRemainderItem.item)),
                    "native_dummy_crafting_input_exposes_complete_expanded_consumed_inventory");
            inputs(active,new ItemStack(MachineRemainderItem.item,3));cycle(mixer,motor,cog,true);
            check(observations.getLast().nativeRemainder().is(Items.BUCKET)&&count(((CreateBasinStorage)active).citizens$basinSnapshot().items(),Items.BUCKET)==2
                    &&count(((CreateBasinStorage)active).citizens$basinSnapshot().items(),MachineRemainderItem.item)==1&&team.getProgress(remainderTask)==1
                    &&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED,"native_basin_remainders_are_stored_and_excluded_from_primary_progress");clear(mixer,motor,cog);
            int beforeRemainderFailure=observations.size();inputs(active,new ItemStack(MachineRemainderItem.item,2));MachineRemainderItem.fail=true;
            try{BasinRecipe.match(active,remainderRecipe);throw new AssertionError("Native basin remainder failure hidden");}
            catch(IllegalStateException expected){check(observations.size()==beforeRemainderFailure&&count(((CreateBasinStorage)active).citizens$basinSnapshot().items(),MachineRemainderItem.item)==2
                    &&team.getProgress(remainderTask)==1,"native_basin_simulation_remainder_exception_preserves_input_without_evidence");}
            finally{MachineRemainderItem.fail=false;clear(mixer,motor,cog);}
            level.setBlockAndUpdate(basinPos.below().east(),AllBlocks.BASIN.getDefaultState());var receiver=(BasinBlockEntity)level.getBlockEntity(basinPos.below().east());receiver.initialize();
            active.tick();inputs(active,new ItemStack(Items.SUGAR));active.inputTank.getPrimaryHandler().fill(new FluidStack(Fluids.WATER,500),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            cycle(mixer,motor,cog,true);
            check(active.getBlockState().getValue(BasinBlock.FACING)!=Direction.DOWN&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED
                    &&team.getProgress(fluidTask)==200,"native_basin_spout_queued_fluid_output_is_confirmed_before_transfer");
            for(int tick=0;tick<30;tick++){active.tick();receiver.tick();}
            check(fluidAmount(((CreateBasinStorage)receiver).citizens$basinSnapshot().fluids(),Fluids.LAVA)==100,
                    "native_basin_spout_transfers_confirmed_fluid_to_receiver");clear(mixer,motor,cog);level.removeBlock(basinPos.below().east(),false);active.tick();
            active.inputTank.getPrimaryHandler().fill(new FluidStack(Fluids.LAVA,150),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);active.notifyChangeOfContents();
            cycle(mixer,motor,cog,true);
            check(observations.getLast().consumedInput().isEmpty()&&observations.getLast().basin().consumedItems().isEmpty()
                    &&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(pureTask)==50
                    &&fluidAmount(((CreateBasinStorage)active).citizens$basinSnapshot().fluids(),Fluids.LAVA)==50,
                    "pure_fluid_basin_recipe_preserves_empty_item_plan_and_exact_fluid_progress");clear(mixer,motor,cog);
            var oldChannel=channel;server.getPlayerList().remove(actor);oldChannel.finishAndReleaseAll();
            int offlineBefore=observations.size();inputs(active,new ItemStack(Items.SUGAR));active.inputTank.getPrimaryHandler().fill(new FluidStack(Fluids.WATER,500),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            cycle(mixer,motor,cog,false);
            check(observations.size()==offlineBefore&&team.getProgress(fluidTask)==200,"offline_assigned_basin_beneficiary_has_no_task_credit");clear(mixer,motor,cog);
            channel=CreateRuntimeAudit.connect(actor);
            CreateMachineEvidence.clear(source,pos);level.removeBlock(pos,false);level.removeBlock(cogPos,false);level.removeBlock(motorPos,false);
            level.setBlockAndUpdate(pos,AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST));
            var press=(MechanicalPressBlockEntity)level.getBlockEntity(pos);press.initialize();var pressMotor=motor(level,pressMotorPos,Direction.EAST);
            CreateMachineEvidence.assign(source,actor.getUUID(),pos);service.bind(source,compactTask.getCodeString(),compactId);
            inputs(active,new ItemStack(Items.CLAY_BALL,3));int prior=observations.size();
            for(int tick=0;tick<500;tick++){pressMotor.tick();press.tick();active.tick();if(observations.size()>prior)break;}
            check(observations.size()==prior+1&&observations.getLast().source()==CreateMachineEvidence.Source.CREATE_PRESS
                    &&observations.getLast().recipe().processing().process()==CreateProcessingRecipes.Process.COMPACTING
                    &&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(compactTask)==2
                    &&count(((CreateBasinStorage)active).citizens$basinSnapshot().items(),Items.CLAY_BALL)==1,
                    "powered_native_basin_press_compacts_multi_input_and_retains_remainder_input");
            service.close();service=CraftQuestEvidence.open(server,path);
            check(service.bindings(source).stream().anyMatch(b->b.epoch().equals(binding.epoch()))&&service.reconcile(source)==0&&team.getProgress(mixTask)==2
                    &&team.getProgress(fluidTask)==200&&team.getProgress(compactTask)==2,"reopened_basin_journal_preserves_item_and_fluid_absolute_progress");
            org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEBASINAUDIT] COMPLETE {} checks",passed);
        } finally {
            recording=false;blockStorage=false;changeFluid=false;service.close();active=null;MachineRemainderItem.fail=false;
            for(var p:List.of(pos,basinPos,cogPos,motorPos,pressMotorPos,basinPos.below(),basinPos.below().east()))level.removeBlock(p,false);
            server.getPlayerList().remove(actor);channel.finishAndReleaseAll();manager.replaceRecipes(original);
        }
    }
    private static dev.ftb.mods.ftbquests.quest.task.CustomTask task(dev.ftb.mods.ftbquests.quest.ServerQuestFile file,dev.ftb.mods.ftbquests.quest.Quest quest){
        var task=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);task.setMaxProgress(1000);task.onCreated();return task;
    }
    private static void inputs(BasinBlockEntity basin,ItemStack...items){for(int slot=0;slot<items.length;slot++)basin.inputInventory.setStackInSlot(slot,items[slot].copy());basin.notifyChangeOfContents();}
    private static CreativeMotorBlockEntity motor(ServerLevel level,BlockPos pos,Direction facing){
        level.setBlockAndUpdate(pos,AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,facing));
        var motor=(CreativeMotorBlockEntity)level.getBlockEntity(pos);motor.initialize();motor.generatedSpeed.setValue(256);motor.updateGeneratedRotation();return motor;
    }
    private static void cycle(MechanicalMixerBlockEntity mixer,CreativeMotorBlockEntity motor,com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity cog,boolean evidence){
        int before=observations.size();boolean started=false;
        for(int tick=0;tick<1000;tick++){motor.tick();cog.tick();mixer.tick();active.tick();started|=mixer.running;
            if(evidence&&observations.size()>before)return;
            if(!evidence&&started&&!mixer.running)return;
        }throw new AssertionError("Native basin mixer did not complete: speed="+mixer.getSpeed()+" running="+mixer.running+" tick="+mixer.runningTicks);
    }
    private static void clear(MechanicalMixerBlockEntity mixer,CreativeMotorBlockEntity motor,com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity cog){
        active.clearContent();clearFluids();for(int tick=0;tick<100&&mixer.running;tick++){motor.tick();cog.tick();mixer.tick();active.tick();}active.clearContent();clearFluids();
    }
    private static void clearFluids(){active.getTanks().forEach(tank->tank.getPrimaryHandler().setFluid(FluidStack.EMPTY));}
    private static int count(List<ItemStack> items,Item item){return items.stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();}
    private static int fluidAmount(List<FluidStack> items,net.minecraft.world.level.material.Fluid fluid){return items.stream().filter(s->s.getFluid()==fluid).mapToInt(FluidStack::getAmount).sum();}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEBASINAUDIT] PASS {}",name);}
}
