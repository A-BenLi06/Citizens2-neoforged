package net.yuuniverse.interactions;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.fluids.spout.SpoutBlockEntity;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Actual fixture JVM kill with independently saved native assembly input, tool/tank and FTB state. */
public final class AssemblyCrashRuntimeAudit {
    private static final BlockPos POS = new BlockPos(10,-58,7);
    private static final BlockPos START = new BlockPos(10,-60,5), END = new BlockPos(10,-60,10);
    private static final ResourceLocation RECIPE = ResourceLocation.parse("citizens_audit:assembly_crash");
    private static int passed;
    private static CreateMachineEvidence.Observation observed;
    private static MachineOutcomeEvent.Outcome outcome;
    private AssemblyCrashRuntimeAudit() { }

    public static void run(MinecraftServer server, String phase, String type) {
        ServerPlayer actor = null; io.netty.channel.embedded.EmbeddedChannel connection = null;
        CraftQuestEvidence opened = null;
        try {
            if (!Set.of("depot","belt","deployer","spout").contains(type)) throw new IllegalArgumentException("Unknown assembly crash type");
            var level = server.overworld(); level.getChunkAt(POS);
            boolean recovery = phase.equals("recover");
            var path = server.getServerDirectory().resolve("assembly-crash-state.json");
            var state = recovery ? JsonParser.parseString(Files.readString(path)).getAsJsonObject() : new JsonObject();
            UUID actorId = recovery ? UUID.fromString(state.get("actor").getAsString()) : UUID.randomUUID();
            if (recovery && !state.get("type").getAsString().equals(type)) throw new AssertionError("Different crash fixture type");
            actor = new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(actorId,"AssemblyCrash"),ClientInformation.createDefault());
            actor.setPos(1,-60,1); connection=CreateRuntimeAudit.connect(actor);
            var source=actor.createCommandSourceStack().withPermission(2);var file=ServerQuestFile.getInstance().orElseThrow();var team=file.getTeamData(actor).orElseThrow();
            var evidencePath=(recovery?server.getServerDirectory().resolve(state.get("evidenceDirectory").getAsString()):Files.createTempDirectory(server.getServerDirectory(),"assembly-crash-evidence-")).toAbsolutePath().normalize();
            if(!evidencePath.startsWith(server.getServerDirectory().toAbsolutePath().normalize()))throw new AssertionError("Evidence fixture escaped its directory");
            var service=CraftQuestEvidence.open(server,evidencePath);opened=service;
            if(!recovery)state.addProperty("evidenceDirectory",server.getServerDirectory().toAbsolutePath().normalize().relativize(evidencePath).toString());
            var snapshot=CraftingRecipes.find(server,RECIPE).orElseThrow();
            if (!snapshot.assembly().evidenceSupported() || snapshot.assembly().steps().size()!=2) throw new AssertionError("Native crash assembly unavailable");
            NeoForge.EVENT_BUS.addListener(MachineResultEvent.class,event->{if(event.getObservation().position().equals(POS))observed=event.getObservation();});
            NeoForge.EVENT_BUS.addListener(MachineOutcomeEvent.class,event->{if(event.getObservation().position().equals(POS))outcome=event.getOutcome();});
            if (!recovery) {
                String stage=phase.substring("commit:".length());
                var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();var quest=new Quest(file.newID(),chapter);quest.onCreated();
                var task=new CustomTask(file.newID(),quest);task.onCreated();file.clearCachedData();file.clearCachedProgress();
                // Produce the intermediate through the real first-step press, then save it in native final-machine storage.
                var intermediate=initialStep(level);
                check(intermediate.has(AllDataComponents.SEQUENCED_ASSEMBLY)&&intermediate.get(AllDataComponents.SEQUENCED_ASSEMBLY).step()==1,
                        "native_first_step_produces_saved_final_assembly_input");
                setup(level,type,intermediate);var machine=level.getBlockEntity(POS);CreateMachineEvidence.assign(source,actorId,POS);service.bind(source,task.getCodeString(),RECIPE);
                state.addProperty("actor",actorId.toString());state.addProperty("task",task.getCodeString());state.addProperty("type",type);state.addProperty("stage",stage);
                state.addProperty("machine",CreateMachineEvidence.attribution(machine).machineId().toString());state.addProperty("revision",snapshot.revision());
                state.addProperty("maximum",task.getMaxProgress());
                state.add("savedItems",itemsJson(server,items(level,type)));state.add("savedTool",toolJson(server,level,type));state.add("savedFluid",fluidJson(server,level,type));
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();
                team.saveIfChanged();file.saveNow();server.saveEverything(true,true,true);
                if(stage.equals("prepared"))NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,MachineResultEvent.class,event->{
                    if(event.getObservation().position().equals(POS)){
                        var entry=entry(service,source,event.getObservation().id());
                        if(entry.state()!=QuestEvidenceJournal.State.PREPARED)throw new AssertionError("Missing durable PREPARED");
                        check(itemsJson(server,items(level,type)).equals(state.get("savedItems")),"prepared_boundary_precedes_native_final_item_storage");
                        kill(path,state,entry.id(),service,source,server,level,type);
                    }
                });
                if(stage.equals("confirmed"))dev.ftb.mods.ftbquests.events.ObjectCompletedEvent.TASK.register(event->{
                    if(event.getTask()==task){
                        var entry=service.entries(source).stream().filter(e->e.admission().taskId().equals(task.getCodeString())&&e.state()==QuestEvidenceJournal.State.CONFIRMED).findFirst().orElseThrow();
                        assertStored(server,level,type);kill(path,state,entry.id(),service,source,server,level,type);
                    }
                    return dev.architectury.event.EventResult.pass();
                });
                process(level,type);
                if(!stage.equals("applied"))throw new AssertionError("Missed native kill boundary");
                var entry=entry(service,source,observed.id());
                check(entry.state()==QuestEvidenceJournal.State.APPLIED&&team.getProgress(task)==task.getMaxProgress(),"native_final_storage_and_absolute_projection_precede_unsaved_applied_kill");
                assertStored(server,level,type);kill(path,state,entry.id(),service,source,server,level,type);
            } else {
                var task=file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(),16));
                var entry=service.entries(source).stream().filter(e->e.id().equals(state.get("entry").getAsString())).findFirst().orElseThrow();var stage=state.get("stage").getAsString();
                check(entry.state()==switch(stage){case "prepared"->QuestEvidenceJournal.State.REVIEW;case "confirmed"->QuestEvidenceJournal.State.CONFIRMED;default->QuestEvidenceJournal.State.APPLIED;}
                        &&team.getProgress(task)==0&&task.getMaxProgress()==state.get("maximum").getAsLong(),"restart_retains_durable_phase_with_independently_saved_old_ftb_progress");
                check(itemsJson(server,items(level,type)).equals(state.get("savedItems")),"actual_kill_recovers_complete_saved_native_holder_input_without_final_output");
                check(toolJson(server,level,type).equals(state.get("savedTool"))&&fluidJson(server,level,type).equals(state.get("savedFluid")),
                        "actual_kill_recovers_saved_native_tool_and_tank_state");
                var assignment=CreateMachineEvidence.attribution(level.getBlockEntity(POS));
                check(assignment!=null&&assignment.machineId().toString().equals(state.get("machine").getAsString())&&assignment.beneficiaryId().equals(actorId),
                        "world_restart_preserves_original_machine_assignment_and_beneficiary");
                var payload=JsonParser.parseString(entry.admission().evidence()).getAsJsonObject();
                check(entry.admission().actorId().equals(actorId)&&entry.admission().units()==2&&entry.admission().recipeId().equals(RECIPE.toString())
                        &&entry.admission().recipeRevision().equals(snapshot.revision())&&snapshot.revision().equals(state.get("revision").getAsString())
                        &&payload.getAsJsonObject("assembly").get("step").getAsInt()==1&&payload.getAsJsonObject("assembly").getAsJsonArray("finalOutputs").size()==1,
                        "restart_retains_exact_final_native_assembly_recipe_components_and_units");
                check(payload.equals(state.get("evidence")),"durable_final_assembly_payload_survives_actual_process_kill_exactly");
                if(stage.equals("prepared")){
                    check(entry.outcome()==QuestEvidenceJournal.Outcome.MISSING&&service.reconcile(source)==0&&team.getProgress(task)==0,
                            "missing_final_storage_outcome_cannot_award_progress");
                    process(level,type);
                    check(service.entries(source).size()==state.get("entries").getAsInt()&&team.getProgress(task)==0,
                            "uncertain_original_assembly_blocks_reprocessing_credit");
                    service.resolve(source,entry.id(),false,"Actual kill before native storage; restored input was retained and review fenced credit");
                    check(service.entries(source).stream().anyMatch(e->e.id().equals(entry.id())&&e.state()==QuestEvidenceJournal.State.REJECTED),
                            "authenticated_review_rejects_missing_final_storage_without_extra_progress");
                } else if(stage.equals("confirmed")){
                    check(service.reconcile(source)==1&&team.getProgress(task)==task.getMaxProgress(),"explicit_reconciliation_projects_confirmed_absolute_assembly_total");
                    check(service.reconcile(source)==0&&team.getProgress(task)==task.getMaxProgress(),"confirmed_restart_retry_does_not_duplicate_final_output_credit");
                    process(level,type);check(service.entries(source).size()==state.get("entries").getAsInt()&&team.getProgress(task)==task.getMaxProgress(),
                            "confirmed_original_task_cannot_credit_restored_input_again");
                } else {
                    check(service.reconcile(source)==0&&team.getProgress(task)==0,"applied_assembly_does_not_silently_repair_independent_world_ftb_rollback");
                    process(level,type);
                    check(service.entries(source).size()==state.get("entries").getAsInt()&&team.getProgress(task)==0,
                            "retained_applied_total_fences_rolled_back_assembly_reprocessing_credit");
                }
                assertStored(server,level,type);
                check(outcome==MachineOutcomeEvent.Outcome.RETURNED&&observed!=null,"recovery_reprocessing_remains_native_even_when_quest_credit_is_fenced");
                org.slf4j.LoggerFactory.getLogger("interactions").info("[ASSEMBLYCRASHAUDIT] COMPLETE {} checks type={} stage={}",passed,type,stage);
                org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEAUDIT] COMPLETE actual assembly crash recovery");
            }
        }catch(Throwable failure){org.slf4j.LoggerFactory.getLogger("interactions").error("[CREATEAUDIT] FAILED assembly crash",failure);}
        finally{if(opened!=null)try{opened.close();}catch(Exception failure){org.slf4j.LoggerFactory.getLogger("interactions").error("Fixture journal close failed",failure);}if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(connection!=null)connection.finishAndReleaseAll();server.halt(false);}
    }

    private static QuestEvidenceJournal.Entry entry(CraftQuestEvidence service,net.minecraft.commands.CommandSourceStack source,UUID observation){
        return service.entries(source).stream().filter(e->e.admission().observationId().equals(observation)).findFirst().orElseThrow();
    }
    private static ItemStack initialStep(ServerLevel level){
        var pos=new BlockPos(10,-58,2);clear(level,pos);level.setBlockAndUpdate(pos.below(2),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos,AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST));
        var press=(MechanicalPressBlockEntity)level.getBlockEntity(pos);press.initialize();var motor=motor(level,pos.west());power(motor,256);
        var input=new ItemEntity(level,pos.getX()+0.5,pos.getY()-1,pos.getZ()+0.5,new ItemStack(Items.SNOWBALL));input.setOnGround(true);input.setNoGravity(true);input.setDeltaMovement(Vec3.ZERO);
        if(!level.addFreshEntity(input))throw new AssertionError("Native first input refused");
        boolean started=false;for(int tick=0;tick<1000;tick++){motor.tick();press.tick();started|=press.pressingBehaviour.running;if(started&&!press.pressingBehaviour.running){var result=input.getItem().copy();clear(level,pos);level.removeBlock(pos,false);level.removeBlock(pos.west(),false);level.removeBlock(pos.below(2),false);return result;}}
        throw new AssertionError("Native initial assembly press did not finish");
    }
    private static void setup(ServerLevel level,String type,ItemStack input){
        clear(level,POS);for(var pos:List.of(POS,POS.west(),POS.below(2),START.west()))level.removeBlock(pos,false);
        for(int z=START.getZ();z<=END.getZ();z++)level.removeBlock(new BlockPos(START.getX(),START.getY(),z),false);
        if(type.equals("deployer"))level.setBlockAndUpdate(POS,AllBlocks.DEPLOYER.getDefaultState().setValue(BlockStateProperties.FACING,Direction.DOWN)
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE,true));
        else if(type.equals("spout"))level.setBlockAndUpdate(POS,AllBlocks.SPOUT.getDefaultState());
        else level.setBlockAndUpdate(POS,AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST));
        ((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(POS)).initialize();
        if(!type.equals("spout"))motor(level,POS.west());
        if(type.equals("belt")){
            level.setBlockAndUpdate(START,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));
            level.setBlockAndUpdate(END,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));
            BeltConnectorItem.createBelts(level,START,END);BeltBlock.initBelt(level,START);var belt=(BeltBlockEntity)level.getBlockEntity(START);belt.initialize();motor(level,START.west());
            var transported=new TransportedItemStack(input.copy());transported.beltPosition=0.5f;transported.prevBeltPosition=0.5f;belt.getInventory().addItem(transported);belt.getInventory().tick();belt.setChanged();
        }else{
            level.setBlockAndUpdate(POS.below(2),AllBlocks.DEPOT.getDefaultState());var depot=(DepotBlockEntity)level.getBlockEntity(POS.below(2));depot.initialize();
            if(type.equals("deployer")){var deployer=(DeployerBlockEntity)level.getBlockEntity(POS);deployer.tick();deployer.getPlayer().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.FLINT,3));deployer.setChanged();}
            if(type.equals("spout"))tank(level).fill(new FluidStack(Fluids.WATER,300),IFluidHandler.FluidAction.EXECUTE);
            depot.setHeldItem(input.copy());depot.setChanged();
        }
    }
    private static void process(ServerLevel level,String type){
        observed=null;outcome=null;
        if(!type.equals("spout"))power((CreativeMotorBlockEntity)level.getBlockEntity(POS.west()),256);
        if(type.equals("belt"))power((CreativeMotorBlockEntity)level.getBlockEntity(START.west()),256);
        for(int tick=0;tick<1200;tick++){
            if(!type.equals("spout"))((CreativeMotorBlockEntity)level.getBlockEntity(POS.west())).tick();
            if(type.equals("belt"))((CreativeMotorBlockEntity)level.getBlockEntity(START.west())).tick();
            ((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(POS)).tick();
            ((com.simibubi.create.foundation.blockEntity.SmartBlockEntity)level.getBlockEntity(type.equals("belt")?START:POS.below(2))).tick();
            if(observed!=null&&outcome!=null)return;
        }
        throw new AssertionError("Native final assembly process did not return: "+type+" input="+items(level,type));
    }
    private static List<ItemStack> items(ServerLevel level,String type){
        if(type.equals("belt"))return ((CreateTransportInventory)((BeltBlockEntity)level.getBlockEntity(START)).getInventory()).citizens$transportSnapshot();
        var handler=((DepotBlockEntity)level.getBlockEntity(POS.below(2))).getBehaviour(DepotBehaviour.TYPE).itemHandler;
        var values=new ArrayList<ItemStack>();for(int slot=0;slot<handler.getSlots();slot++)if(!handler.getStackInSlot(slot).isEmpty())values.add(handler.getStackInSlot(slot).copy());return values;
    }
    private static JsonArray itemsJson(MinecraftServer server,List<ItemStack> items){
        var values=new JsonArray();var ops=server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        items.stream().filter(s->!s.isEmpty()).map(s->ItemStack.CODEC.encodeStart(ops,s).getOrThrow()).sorted(Comparator.comparing(JsonElement::toString)).forEach(values::add);return values;
    }
    private static JsonElement toolJson(MinecraftServer server,ServerLevel level,String type){
        if(!type.equals("deployer"))return JsonNull.INSTANCE;var deployer=(DeployerBlockEntity)level.getBlockEntity(POS);
        if(deployer.getPlayer()==null)deployer.tick();return ItemStack.OPTIONAL_CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),deployer.getPlayer().getMainHandItem()).getOrThrow();
    }
    private static IFluidHandler tank(ServerLevel level){return level.getCapability(Capabilities.FluidHandler.BLOCK,POS,null);}
    private static JsonElement fluidJson(MinecraftServer server,ServerLevel level,String type){
        return type.equals("spout")?FluidStack.CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),tank(level).getFluidInTank(0)).getOrThrow():JsonNull.INSTANCE;
    }
    private static void assertStored(MinecraftServer server,ServerLevel level,String type){
        var expected=new ItemStack(Items.PAPER,2);expected.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Assembly crash output"));
        var stored=items(level,type);check(stored.size()==1&&ItemStack.matches(stored.getFirst(),expected),"native_final_storage_retains_exact_component_sensitive_assembly_output");
        if(type.equals("deployer"))check(((DeployerBlockEntity)level.getBlockEntity(POS)).getPlayer().getMainHandItem().getCount()==2,"native_final_deployment_consumes_original_tool_once");
        if(type.equals("spout"))check(tank(level).getFluidInTank(0).getAmount()==200,"native_final_filling_consumes_original_fluid_once");
    }
    private static CreativeMotorBlockEntity motor(ServerLevel level,BlockPos pos){
        level.setBlockAndUpdate(pos,AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,Direction.EAST));var motor=(CreativeMotorBlockEntity)level.getBlockEntity(pos);motor.initialize();power(motor,0);return motor;
    }
    private static void power(CreativeMotorBlockEntity motor,int speed){motor.generatedSpeed.setValue(speed);motor.updateGeneratedRotation();}
    private static void clear(ServerLevel level,BlockPos pos){for(var entity:level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(4)))entity.discard();}
    private static void kill(Path path,JsonObject state,String entry,CraftQuestEvidence service,net.minecraft.commands.CommandSourceStack source,MinecraftServer server,ServerLevel level,String type){
        state.addProperty("entry",entry);state.addProperty("run",System.getenv("CITIZENS_MACHINE_AUDIT_RUN"));state.addProperty("entries",service.entries(source).size());
        state.add("evidence",JsonParser.parseString(service.entries(source).stream().filter(e->e.id().equals(entry)).findFirst().orElseThrow().admission().evidence()));
        state.add("liveItems",itemsJson(server,items(level,type)));state.add("liveTool",toolJson(server,level,type));state.add("liveFluid",fluidJson(server,level,type));
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var bytes=StandardCharsets.UTF_8.encode(state.toString());while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        org.slf4j.LoggerFactory.getLogger("interactions").info("[MACHINECRASHAUDIT] KILL_READY pid={} entry={} run={}",ProcessHandle.current().pid(),entry,System.getenv("CITIZENS_MACHINE_AUDIT_RUN"));
        while(true)java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
    }
    private static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("interactions").info("[ASSEMBLYCRASHAUDIT] PASS {}",name);}
}
