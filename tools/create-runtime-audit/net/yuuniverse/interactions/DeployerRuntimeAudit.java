package net.yuuniverse.interactions;

import java.util.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.deployer.*;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid="interactions")
public final class DeployerRuntimeAudit {
    private static int passed;
    private static boolean recording, changeTool, changeInput, rejectDrop, damageDrop;
    private static DeployerBlockEntity active;
    private static DepotBlockEntity depot;
    private static final List<CreateMachineEvidence.Observation> observations=new ArrayList<>();
    private static final List<MachineOutcomeEvent> outcomes=new ArrayList<>();
    @SubscribeEvent public static void result(MachineResultEvent event){
        if(!recording||event.getObservation().tool()==null)return;observations.add(event.getObservation());
        if(changeTool)active.getPlayer().getMainHandItem().grow(1);
        if(changeInput)depot.getHeldItem().grow(1);
    }
    @SubscribeEvent public static void outcome(MachineOutcomeEvent event){if(recording&&event.getObservation().tool()!=null)outcomes.add(event);}
    @SubscribeEvent public static void drop(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event){
        if(!recording||!(event.getEntity() instanceof ItemEntity item)||!item.getItem().is(Items.BUCKET))return;
        if(rejectDrop)event.setCanceled(true);
        if(damageDrop)item.getItem().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Changed tool remainder"));
    }
    public static void run(MinecraftServer server)throws Exception{
        var level=server.overworld();var manager=server.getRecipeManager();var original=List.copyOf(manager.getRecipes());
        var id=ResourceLocation.parse("citizens_audit:deploy");var keepId=ResourceLocation.parse("citizens_audit:deploy_keep");
        var damageId=ResourceLocation.parse("citizens_audit:deploy_damage");var remainderId=ResourceLocation.parse("citizens_audit:deploy_remainder");
        var emptyId=ResourceLocation.parse("citizens_audit:deploy_empty_roll");
        var recipe=Recipe.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString("""
            {"type":"create:deploying","ingredients":[{"item":"minecraft:snowball"},{"item":"minecraft:flint"}],
             "results":[{"id":"minecraft:paper","count":2,"components":{"minecraft:custom_name":"\\\"Deployed result\\\""}},{"id":"minecraft:diamond","chance":0.5}]}
            """)).getOrThrow();
        var keep=new ItemApplicationRecipe.Builder<>(DeployerApplicationRecipe::new,keepId).withItemIngredients(NonNullList.of(Ingredient.EMPTY,Ingredient.of(Items.BRICK),Ingredient.of(Items.STICK)))
                .withSingleItemOutput(new ItemStack(Items.PAPER)).toolNotConsumed().build();
        var damage=new ItemApplicationRecipe.Builder<>(DeployerApplicationRecipe::new,damageId).withItemIngredients(NonNullList.of(Ingredient.EMPTY,Ingredient.of(Items.CLAY_BALL),Ingredient.of(Items.IRON_AXE)))
                .withSingleItemOutput(new ItemStack(Items.PAPER)).build();
        var remainder=new ItemApplicationRecipe.Builder<>(DeployerApplicationRecipe::new,remainderId).withItemIngredients(NonNullList.of(Ingredient.EMPTY,Ingredient.of(Items.SUGAR),Ingredient.of(MachineRemainderItem.item)))
                .withSingleItemOutput(new ItemStack(Items.PAPER)).build();
        var empty=Recipe.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString("""
            {"type":"create:deploying","ingredients":[{"item":"minecraft:leather"},{"item":"minecraft:flint"}],"results":[{"id":"minecraft:diamond","chance":0.000001}]}
            """)).getOrThrow();
        var expanded=new ArrayList<RecipeHolder<?>>(original);expanded.add(new RecipeHolder<>(id,recipe));expanded.add(new RecipeHolder<>(keepId,keep));expanded.add(new RecipeHolder<>(damageId,damage));expanded.add(new RecipeHolder<>(remainderId,remainder));manager.replaceRecipes(expanded);
        expanded.add(new RecipeHolder<>(emptyId,empty));manager.replaceRecipes(expanded);
        var file=dev.ftb.mods.ftbquests.quest.ServerQuestFile.getInstance().orElseThrow();var chapter=new dev.ftb.mods.ftbquests.quest.Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
        var quest=new dev.ftb.mods.ftbquests.quest.Quest(file.newID(),chapter);quest.onCreated();var task=task(file,quest);var keepTask=task(file,quest);var damageTask=task(file,quest);var remainderTask=task(file,quest);file.clearCachedData();file.clearCachedProgress();
        var actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"DeployerActor"),ClientInformation.createDefault());actor.setPos(1,-60,1);var channel=CreateRuntimeAudit.connect(actor);
        var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();var path=java.nio.file.Files.createTempDirectory(server.getServerDirectory(),"deployer-evidence-");var service=CraftQuestEvidence.open(server,path);
        var pos=new BlockPos(8,-58,7);var holder=pos.below(2);var motorPos=pos.west();var beltStart=new BlockPos(8,-60,5);var beltEnd=new BlockPos(8,-60,10);var beltMotorPos=beltStart.west();recording=true;
        try{
            level.setBlockAndUpdate(pos,AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING,Direction.DOWN)
                    .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,true));
            active=(DeployerBlockEntity)level.getBlockEntity(pos);active.initialize();var motor=motor(level,motorPos);
            level.setBlockAndUpdate(holder,AllBlocks.DEPOT.getDefaultState());depot=(DepotBlockEntity)level.getBlockEntity(holder);depot.initialize();
            for(int tick=0;tick<10;tick++){motor.tick();active.tick();depot.tick();}
            check(active.getSpeed()!=0&&active.getPlayer()!=null,"native_motor_powers_deployer_and_initializes_fake_player");
            var binding=service.bind(source,task.getCodeString(),id);service.bind(source,keepTask.getCodeString(),keepId);service.bind(source,damageTask.getCodeString(),damageId);service.bind(source,remainderTask.getCodeString(),remainderId);
            hand(new ItemStack(Items.FLINT,3));depot.setHeldItem(new ItemStack(Items.SNOWBALL,3));for(int tick=0;tick<40;tick++){motor.tick();active.tick();depot.tick();}
            check(observations.isEmpty()&&team.getProgress(task)==0,"unassigned_deployer_does_not_emit_evidence");clear(motor);
            CreateMachineEvidence.assign(source,actor.getUUID(),pos);hand(new ItemStack(Items.FLINT,3));runDepot(motor,new ItemStack(Items.SNOWBALL,3));var observation=observations.getLast();var outcome=outcomes.getLast();
            check(observation.source()==CreateMachineEvidence.Source.CREATE_DEPLOYER&&observation.consumedInput().getCount()==1&&observation.remainingInput().getCount()==2
                    &&outcome.getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==2,"native_depot_deployer_confirms_item_output_and_tool_consumption");
            check(active.getPlayer().getMainHandItem().getCount()==2&&depot.getHeldItem().getCount()==2&&count(Items.PAPER)==2
                    &&count(Items.DIAMOND)==observation.rolledOutputs().stream().filter(s->s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum(),"deployer_native_random_output_and_retained_input_match_storage");
            check(observation.rolledOutputs().getFirst().getHoverName().getString().equals("Deployed result")&&observation.tool().held().getCount()==3
                    &&JsonParser.parseString(CreateMachineEvidence.encode(observation,server.registryAccess())).getAsJsonObject().has("tool"),"deployer_tool_and_component_payload_is_serialized");
            observation.tool().held().setCount(99);observation.tool().inventory().forEach(s->s.setCount(99));
            check(observation.tool().held().getCount()==3&&observation.tool().inventory().stream().filter(s->s.is(Items.FLINT)).mapToInt(ItemStack::getCount).sum()==3,"deployer_tool_snapshots_are_defensive");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new MachineResultEvent(actor,observation));net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(outcome);
            check(team.getProgress(task)==2,"deployer_duplicate_notifications_do_not_credit_twice");clear(motor);
            hand(new ItemStack(Items.STICK));depot.setHeldItem(new ItemStack(Items.SNOWBALL));int unmatched=observations.size();
            for(int tick=0;tick<40;tick++){motor.tick();active.tick();depot.tick();}
            check(observations.size()==unmatched&&depot.getHeldItem().is(Items.SNOWBALL)&&active.getPlayer().getMainHandItem().is(Items.STICK),"nonmatching_deployer_tool_preserves_input_without_evidence");clear(motor);
            hand(ItemStack.EMPTY);depot.setHeldItem(new ItemStack(Items.SNOWBALL));
            for(int tick=0;tick<40;tick++){motor.tick();active.tick();depot.tick();}
            check(observations.size()==unmatched&&depot.getHeldItem().is(Items.SNOWBALL),"missing_deployer_tool_produces_no_evidence");clear(motor);
            level.random.setSeed(42);hand(new ItemStack(Items.FLINT,2));runDepot(motor,new ItemStack(Items.LEATHER,2));
            check(observations.getLast().rolledOutputs().isEmpty()&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED
                    &&depot.getHeldItem().getCount()==1&&active.getPlayer().getMainHandItem().getCount()==1&&team.getProgress(task)==2,"empty_native_deployer_roll_consumes_input_and_tool_without_output_progress");clear(motor);
            hand(new ItemStack(Items.STICK,3));runDepot(motor,new ItemStack(Items.BRICK,2));
            check(observations.getLast().tool().retained()&&active.getPlayer().getMainHandItem().getCount()==3&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED
                    &&team.getProgress(keepTask)==1,"native_deployer_keep_held_item_policy_is_verified");clear(motor);
            var axe=new ItemStack(Items.IRON_AXE);axe.setDamageValue(10);hand(axe);runDepot(motor,new ItemStack(Items.CLAY_BALL,2));
            check(active.getPlayer().getMainHandItem().getDamageValue()==11&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(damageTask)==1,"native_deployer_tool_damage_is_observed_once");clear(motor);
            axe=new ItemStack(Items.IRON_AXE);axe.setDamageValue(axe.getMaxDamage()-1);hand(axe);runDepot(motor,new ItemStack(Items.CLAY_BALL));
            check(active.getPlayer().getMainHandItem().isEmpty()&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(damageTask)==2,"native_deployer_tool_break_confirms_empty_hand");clear(motor);
            MachineRemainderItem.calls=0;hand(new ItemStack(MachineRemainderItem.item));runDepot(motor,new ItemStack(Items.SUGAR));
            check(MachineRemainderItem.calls==1&&active.getPlayer().getMainHandItem().is(Items.BUCKET)&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(remainderTask)==1,
                    "last_consumed_deployer_tool_remainder_replaces_hand");clear(motor);
            hand(new ItemStack(MachineRemainderItem.item,2));runDepot(motor,new ItemStack(Items.SUGAR));
            check(active.getPlayer().getMainHandItem().getCount()==1&&active.getPlayer().getInventory().items.stream().filter(s->s.is(Items.BUCKET)).mapToInt(ItemStack::getCount).sum()==1
                    &&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(remainderTask)==2,"native_tool_remainder_is_stored_separately_in_fake_player_inventory");clear(motor);
            fillInventory();hand(new ItemStack(MachineRemainderItem.item,2));runDepot(motor,new ItemStack(Items.SUGAR));
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(remainderTask)==3,"full_fake_player_inventory_confirms_exact_dropped_tool_remainder");clear(motor);
            fillInventory();hand(new ItemStack(MachineRemainderItem.item,2));rejectDrop=true;runDepot(motor,new ItemStack(Items.SUGAR));rejectDrop=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(remainderTask)==3,"rejected_tool_remainder_drop_stays_in_review");resolve(service,source);clear(motor);
            fillInventory();hand(new ItemStack(MachineRemainderItem.item,2));damageDrop=true;runDepot(motor,new ItemStack(Items.SUGAR));damageDrop=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(remainderTask)==3,"changed_tool_remainder_components_prevent_confirmation");resolve(service,source);clear(motor);
            hand(new ItemStack(Items.FLINT,3));changeTool=true;runDepot(motor,new ItemStack(Items.SNOWBALL,2));changeTool=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(task)==2,"changed_tool_during_admission_prevents_confirmation");resolve(service,source);clear(motor);
            hand(new ItemStack(Items.FLINT,3));changeInput=true;runDepot(motor,new ItemStack(Items.SNOWBALL,2));changeInput=false;
            check(outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.UNCONFIRMED&&team.getProgress(task)==2,"changed_transport_input_prevents_deployer_confirmation");resolve(service,source);clear(motor);
            MachineRemainderItem.fail=true;hand(new ItemStack(MachineRemainderItem.item,2));int before=observations.size();
            try{runDepot(motor,new ItemStack(Items.SUGAR));throw new AssertionError("Tool callback exception hidden");}
            catch(IllegalStateException expected){check(observations.size()==before+1&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.INTERRUPTED
                    &&team.getProgress(remainderTask)==3,"native_tool_remainder_failure_after_output_storage_is_interrupted");resolve(service,source);}
            finally{MachineRemainderItem.fail=false;clear(motor);}
            hand(new ItemStack(Items.FLINT,2));before=observations.size();BeltDeployerCallbacks.activate(new TransportedItemStack(new ItemStack(Items.SNOWBALL)),depot.getBehaviour(com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TYPE),active,recipe);
            check(observations.size()==before,"direct_unrelated_deployer_activate_is_unobserved");clear(motor);
            hand(new ItemStack(Items.FLINT));before=observations.size();server.getPlayerList().remove(actor);channel.finishAndReleaseAll();depot.setHeldItem(new ItemStack(Items.SNOWBALL));
            for(int tick=0;tick<40;tick++){motor.tick();active.tick();depot.tick();}
            check(observations.size()==before&&team.getProgress(task)==2,"offline_deployer_beneficiary_has_no_credit");channel=CreateRuntimeAudit.connect(actor);clear(motor);
            level.removeBlock(holder,false);depot=null;level.setBlockAndUpdate(beltStart,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));
            level.setBlockAndUpdate(beltEnd,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));BeltConnectorItem.createBelts(level,beltStart,beltEnd);BeltBlock.initBelt(level,beltStart);
            var belt=(BeltBlockEntity)level.getBlockEntity(beltStart);belt.initialize();var beltMotor=motor(level,beltMotorPos);hand(new ItemStack(Items.FLINT,3));
            var transported=new TransportedItemStack(new ItemStack(Items.SNOWBALL,2));transported.beltPosition=0.5f;transported.prevBeltPosition=0.5f;belt.getInventory().addItem(transported);before=observations.size();
            for(int tick=0;tick<1000&&observations.size()==before;tick++){motor.tick();beltMotor.tick();active.tick();belt.tick();}
            check(observations.size()==before+1&&outcomes.getLast().getOutcome()==MachineOutcomeEvent.Outcome.RETURNED&&team.getProgress(task)==4
                    &&active.getPlayer().getMainHandItem().getCount()==2,"powered_connected_belt_deployer_confirms_pending_output_and_tool_delta");
            check(((CreateTransportInventory)belt.getInventory()).citizens$transportSnapshot().stream().filter(s->s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum()==2,"belt_deployer_output_snapshot_does_not_flush_native_queues");
            service.close();service=CraftQuestEvidence.open(server,path);
            check(service.bindings(source).stream().anyMatch(b->b.epoch().equals(binding.epoch()))&&service.reconcile(source)==0&&team.getProgress(task)==4&&team.getProgress(remainderTask)==3,
                    "reopened_deployer_journal_retains_absolute_progress_without_reconsuming_tool");
            org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEDEPLOYERAUDIT] COMPLETE {} checks",passed);
        }finally{recording=false;changeTool=false;changeInput=false;rejectDrop=false;damageDrop=false;MachineRemainderItem.fail=false;service.close();
            if(active!=null)active.getPlayer().getInventory().clearContent();active=null;depot=null;
            for(var entity:level.getEntitiesOfClass(ItemEntity.class,new net.minecraft.world.phys.AABB(pos).inflate(4)))entity.discard();
            for(var p:List.of(pos,holder,motorPos,beltStart,beltEnd,beltMotorPos))level.removeBlock(p,false);server.getPlayerList().remove(actor);channel.finishAndReleaseAll();manager.replaceRecipes(original);}
    }
    private static void hand(ItemStack stack){active.getPlayer().setItemInHand(InteractionHand.MAIN_HAND,stack.copy());}
    private static void clear(CreativeMotorBlockEntity motor){depot.clearContent();active.getPlayer().getInventory().clearContent();for(int tick=0;tick<40;tick++){motor.tick();active.tick();depot.tick();}depot.clearContent();active.getPlayer().getInventory().clearContent();}
    private static void fillInventory(){for(int slot=0;slot<active.getPlayer().getInventory().items.size();slot++)active.getPlayer().getInventory().items.set(slot,new ItemStack(Items.COBBLESTONE,64));}
    private static void runDepot(CreativeMotorBlockEntity motor,ItemStack input){depot.setHeldItem(input.copy());int before=observations.size();for(int tick=0;tick<500;tick++){motor.tick();active.tick();depot.tick();if(observations.size()>before)return;}throw new AssertionError("Native deployer did not process");}
    private static int count(Item item){var inventory=depot.getBehaviour(DepotBehaviour.TYPE).itemHandler;int count=0;for(int slot=0;slot<inventory.getSlots();slot++)if(inventory.getStackInSlot(slot).is(item))count+=inventory.getStackInSlot(slot).getCount();return count;}
    private static CreativeMotorBlockEntity motor(ServerLevel level,BlockPos pos){level.setBlockAndUpdate(pos,AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,Direction.EAST));var motor=(CreativeMotorBlockEntity)level.getBlockEntity(pos);motor.initialize();motor.generatedSpeed.setValue(256);motor.updateGeneratedRotation();return motor;}
    private static dev.ftb.mods.ftbquests.quest.task.CustomTask task(dev.ftb.mods.ftbquests.quest.ServerQuestFile file,dev.ftb.mods.ftbquests.quest.Quest quest){var task=new dev.ftb.mods.ftbquests.quest.task.CustomTask(file.newID(),quest);task.setMaxProgress(1000);task.onCreated();return task;}
    private static void resolve(CraftQuestEvidence service,net.minecraft.commands.CommandSourceStack source)throws java.io.IOException{service.resolve(source,service.entries(source).getLast().id(),false,"Fixture changed native deployer tool/input/storage");}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEDEPLOYERAUDIT] PASS {}",name);}
}
