package net.yuuniverse.interactions;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.belt.*;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.depot.*;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/** Native powered world/depot/belt fan storage with external JVM termination. */
public final class FanCrashRuntimeAudit {
    private static final BlockPos POS = new BlockPos(3,-58,7), MOTOR = POS.west(), CATALYST = POS.east(), HOLDER = POS.east(3).below();
    private static final BlockPos START = new BlockPos(6,-59,5), END = new BlockPos(6,-59,10), BELT_MOTOR = START.west();
    private static final ResourceLocation RECIPE = ResourceLocation.parse("citizens_audit:fan_crash");
    private static CreateMachineEvidence.Observation observed;
    private static MachineOutcomeEvent.Outcome outcome;
    private static int passed;

    public static void run(MinecraftServer server, String phase, String type) {
        ServerPlayer actor = null;
        io.netty.channel.embedded.EmbeddedChannel connection = null;
        CraftQuestEvidence opened = null;
        try {
            if (!Set.of("world", "depot", "belt").contains(type)) throw new IllegalArgumentException("Unknown fan holder");
            boolean recovery = phase.equals("recover");
            Path statePath = server.getServerDirectory().resolve("fan-crash-state.json");
            var state = recovery ? JsonParser.parseString(Files.readString(statePath)).getAsJsonObject() : new JsonObject();
            var level = server.overworld(); level.getChunkAt(POS);
            UUID actorId = recovery ? UUID.fromString(state.get("actor").getAsString()) : UUID.randomUUID();
            actor = new ServerPlayer(server, level, new com.mojang.authlib.GameProfile(actorId, "FanCrash"), ClientInformation.createDefault());
            actor.setPos(1,-60,1); connection = CreateRuntimeAudit.connect(actor);
            var source = actor.createCommandSourceStack().withPermission(2);
            var file = ServerQuestFile.getInstance().orElseThrow(); var team = file.getTeamData(actor).orElseThrow();
            Path fixture = server.getServerDirectory().toAbsolutePath().normalize();
            Path evidencePath = recovery ? fixture.resolve(state.get("evidenceDirectory").getAsString()).normalize() : Files.createTempDirectory(fixture,"fan-crash-evidence-");
            if (!evidencePath.startsWith(fixture)) throw new AssertionError("Evidence escaped fixture");
            var service = CraftQuestEvidence.open(server,evidencePath); opened = service;
            var recipe = CraftingRecipes.find(server,RECIPE).orElseThrow();
            check(recipe.processing().process() == CreateProcessingRecipes.Process.SPLASHING,"registered_native_splashing_recipe_available");
            NeoForge.EVENT_BUS.addListener(MachineResultEvent.class,event -> { if (event.getObservation().position().equals(POS)) observed = event.getObservation(); });
            NeoForge.EVENT_BUS.addListener(MachineOutcomeEvent.class,event -> { if (event.getObservation().position().equals(POS)) outcome = event.getOutcome(); });
            if (!recovery) {
                String stage = phase.substring("commit:".length());
                if (!Set.of("prepared","confirmed","applied").contains(stage)) throw new IllegalArgumentException("Unknown kill stage");
                var chapter = new Chapter(file.newID(),file,file.getDefaultChapterGroup()); chapter.onCreated();
                var quest = new Quest(file.newID(),chapter); quest.onCreated();
                var task = new CustomTask(file.newID(),quest); task.onCreated(); file.clearCachedData(); file.clearCachedProgress();
                setup(level,type); CreateMachineEvidence.assign(source,actorId,POS); service.bind(source,task.getCodeString(),RECIPE);
                state.addProperty("actor",actorId.toString()); state.addProperty("team",team.getTeamId().toString());
                state.addProperty("task",task.getCodeString()); state.addProperty("stage",stage); state.addProperty("type",type);
                state.addProperty("maximum",task.getMaxProgress()); state.addProperty("revision",recipe.revision());
                state.addProperty("machine",CreateMachineEvidence.attribution(level.getBlockEntity(POS)).machineId().toString());
                state.addProperty("evidenceDirectory",fixture.relativize(evidencePath).toString()); state.add("savedStorage",storage(server,level,type));
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();
                team.saveIfChanged(); file.saveNow(); server.saveEverything(true,true,true);
                check(storage(server,level,type).equals(state.get("savedStorage")),"native_save_preserves_exact_input_storage");
                if (stage.equals("prepared")) NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,MachineResultEvent.class,event -> {
                    if (event.getObservation().position().equals(POS)) {
                        var entry = entry(service,source,event.getObservation().id());
                        check(entry.state() == QuestEvidenceJournal.State.PREPARED && storage(server,level,type).equals(state.get("savedStorage")),
                                "prepared_admission_precedes_native_primary_and_secondary_storage");
                        kill(server,level,type,statePath,state,entry,service.entries(source).size());
                    }
                });
                if (stage.equals("confirmed")) dev.ftb.mods.ftbquests.events.ObjectCompletedEvent.TASK.register(event -> {
                    if (event.getTask() == task) {
                        var entry = service.entries(source).stream().filter(e -> e.admission().taskId().equals(task.getCodeString()) && e.state() == QuestEvidenceJournal.State.CONFIRMED).findFirst().orElseThrow();
                        assertStored(level,type,state); kill(server,level,type,statePath,state,entry,service.entries(source).size());
                    }
                    return dev.architectury.event.EventResult.pass();
                });
                process(level,type);
                if (!stage.equals("applied")) throw new AssertionError("Missed native fan kill boundary");
                var entry = entry(service,source,observed.id());
                check(entry.state() == QuestEvidenceJournal.State.APPLIED && team.getProgress(task) == task.getMaxProgress(),"complete_native_fan_storage_and_projection_precede_applied_kill");
                assertStored(level,type,state); kill(server,level,type,statePath,state,entry,service.entries(source).size());
            } else {
                String stage = state.get("stage").getAsString();
                check(type.equals(state.get("type").getAsString()),"restart_keeps_original_native_holder_kind");
                var task = file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(),16));
                var entry = service.entries(source).stream().filter(e -> e.id().equals(state.get("entry").getAsString())).findFirst().orElseThrow();
                var expected = switch (stage) { case "prepared" -> QuestEvidenceJournal.State.REVIEW; case "confirmed" -> QuestEvidenceJournal.State.CONFIRMED; default -> QuestEvidenceJournal.State.APPLIED; };
                check(entry.state() == expected && task.getMaxProgress() == state.get("maximum").getAsLong() && team.getProgress(task) == 0
                        && team.getTeamId().toString().equals(state.get("team").getAsString()),"restart_retains_original_evidence_phase_and_older_native_team_task");
                check(storage(server,level,type).equals(state.get("savedStorage")),"restart_restores_complete_original_component_sensitive_holder_input");
                var assignment = CreateMachineEvidence.attribution(level.getBlockEntity(POS));
                check(assignment != null && assignment.machineId().toString().equals(state.get("machine").getAsString()) && assignment.beneficiaryId().equals(actorId),
                        "restart_retains_original_fan_assignment_and_beneficiary");
                var payload = JsonParser.parseString(entry.admission().evidence()).getAsJsonObject();
                check(payload.equals(state.get("evidence")) && entry.admission().actorId().equals(actorId) && entry.admission().units() == 6
                        && entry.admission().recipeId().equals(RECIPE.toString()) && entry.admission().recipeRevision().equals(recipe.revision())
                        && recipe.revision().equals(state.get("revision").getAsString()),"original_bulk_primary_secondary_and_recipe_payload_survives_kill");
                if (type.equals("world")) check(payload.get("inputEntityId").getAsString().equals(state.getAsJsonObject("savedStorage").getAsJsonArray("entities").get(0).getAsJsonObject().get("uuid").getAsString()),
                        "original_world_input_entity_identity_survives_kill");
                int entries = state.get("entries").getAsInt();
                if (stage.equals("prepared")) {
                    check(entry.outcome() == QuestEvidenceJournal.Outcome.MISSING && service.reconcile(source) == 0 && team.getProgress(task) == 0,"missing_fan_storage_outcome_cannot_award_credit");
                    process(level,type);
                    check(service.entries(source).size() == entries && team.getProgress(task) == 0,"uncertain_original_fences_reprocessed_fan_credit");
                    var before = storage(server,level,type);
                    service.resolve(source,entry.id(),false,"Actual fan kill before storage; restored original input processed natively while review fenced credit");
                    check(service.entries(source).stream().anyMatch(e -> e.id().equals(entry.id()) && e.state() == QuestEvidenceJournal.State.REJECTED)
                            && before.equals(storage(server,level,type)) && team.getProgress(task) == 0,"authenticated_rejection_changes_no_native_storage_or_task");
                } else if (stage.equals("confirmed")) {
                    var before = storage(server,level,type);
                    check(service.reconcile(source) == 1 && team.getProgress(task) == task.getMaxProgress() && before.equals(storage(server,level,type)),"confirmed_reconciliation_projects_without_reprocessing_native_fan");
                    check(service.reconcile(source) == 0 && team.getProgress(task) == task.getMaxProgress(),"confirmed_reconciliation_retry_is_noop");
                    process(level,type);
                    check(service.entries(source).size() == entries && team.getProgress(task) == task.getMaxProgress(),"completed_original_task_cannot_credit_restored_fan_input_again");
                } else {
                    check(service.reconcile(source) == 0 && team.getProgress(task) == 0,"applied_evidence_does_not_silently_repair_independent_native_rollback");
                    process(level,type);
                    check(service.entries(source).size() == entries && team.getProgress(task) == 0,"applied_total_fences_rolled_back_fan_reprocessing_credit");
                }
                assertStored(level,type,state);
                check(observed != null && outcome == MachineOutcomeEvent.Outcome.RETURNED,"native_fan_returns_normally_while_quest_credit_is_fenced");
                state.add("recoveredStorage",storage(server,level,type)); state.addProperty("recoveredProgress",team.getProgress(task));
                state.add("recoveredEntries",new Gson().toJsonTree(service.entries(source)));
                Files.writeString(fixture.resolve("fan-crash-recovery.json"),state.toString());
                org.slf4j.LoggerFactory.getLogger("interactions").info("[FANCRASHAUDIT] COMPLETE {} checks type={} stage={}",passed,type,stage);
                org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEAUDIT] COMPLETE actual fan crash recovery");
            }
        } catch (Throwable failure) { org.slf4j.LoggerFactory.getLogger("interactions").error("[CREATEAUDIT] FAILED fan crash",failure); }
        finally {
            if (opened != null) try { opened.close(); } catch (Exception failure) { org.slf4j.LoggerFactory.getLogger("interactions").error("Fixture close failed",failure); }
            if (actor != null && actor.connection != null) server.getPlayerList().remove(actor);
            if (connection != null) connection.finishAndReleaseAll(); server.halt(false);
        }
    }

    private static void setup(ServerLevel level, String type) {
        level.setBlockAndUpdate(POS,AllBlocks.ENCASED_FAN.getDefaultState().setValue(BlockStateProperties.FACING,Direction.EAST)); fan(level).initialize();
        motor(level,MOTOR,0);
        level.setBlockAndUpdate(CATALYST.below(),Blocks.STONE.defaultBlockState()); level.setBlockAndUpdate(CATALYST,Blocks.WATER.defaultBlockState());
        var input = new ItemStack(Items.SNOWBALL,3); input.set(DataComponents.CUSTOM_NAME,Component.literal("Fan crash input"));
        if (type.equals("world")) {
            var pos = POS.east(3); var entity = new ItemEntity(level,pos.getX()+0.5,pos.getY()+0.1,pos.getZ()+0.5,input);
            entity.setNoGravity(true); entity.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            if (!level.addFreshEntity(entity)) throw new AssertionError("Native input entity refused");
        } else if (type.equals("depot")) {
            level.setBlockAndUpdate(HOLDER,AllBlocks.DEPOT.getDefaultState()); var depot = (DepotBlockEntity)level.getBlockEntity(HOLDER); depot.initialize(); depot.setHeldItem(input); depot.setChanged();
        } else {
            level.setBlockAndUpdate(START,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));
            level.setBlockAndUpdate(END,AllBlocks.SHAFT.getDefaultState().setValue(BlockStateProperties.AXIS,Direction.Axis.X));
            BeltConnectorItem.createBelts(level,START,END); BeltBlock.initBelt(level,START);
            var belt = (BeltBlockEntity)level.getBlockEntity(START); belt.initialize(); motor(level,BELT_MOTOR,0);
            var item = new TransportedItemStack(input); item.beltPosition = 2.5f; item.prevBeltPosition = 2.5f;
            belt.getInventory().addItem(item); belt.getInventory().tick(); belt.setChanged();
        }
    }
    private static void process(ServerLevel level,String type) {
        observed = null; outcome = null;
        var motor = (CreativeMotorBlockEntity)level.getBlockEntity(MOTOR); power(motor,256); var fan = fan(level);
        CreativeMotorBlockEntity beltMotor = type.equals("belt") ? (CreativeMotorBlockEntity)level.getBlockEntity(BELT_MOTOR) : null;
        if (beltMotor != null) power(beltMotor,1);
        fan.blockInFrontChanged(); fan.airCurrent.rebuild();
        if (fan.airCurrent.getTypeAt(3) != AllFanProcessingTypes.SPLASHING) throw new AssertionError("Native water catalyst unavailable");
        for (int tick = 0; tick < 1400; tick++) {
            motor.tick(); if (beltMotor != null) beltMotor.tick(); fan.tick();
            if (type.equals("depot")) ((DepotBlockEntity)level.getBlockEntity(HOLDER)).tick();
            if (type.equals("belt")) ((BeltBlockEntity)level.getBlockEntity(START)).tick();
            if (observed != null && outcome != null) return;
        }
        throw new AssertionError("Native fan did not complete: type="+type+" speed="+fan.getSpeed()+" bounds="+fan.airCurrent.bounds);
    }
    private static EncasedFanBlockEntity fan(ServerLevel level) { return (EncasedFanBlockEntity)level.getBlockEntity(POS); }
    private static List<ItemEntity> entities(ServerLevel level) { return level.getEntitiesOfClass(ItemEntity.class,new AABB(POS).inflate(12)).stream().sorted(Comparator.comparing(e -> e.getUUID().toString())).toList(); }
    private static List<ItemStack> items(ServerLevel level,String type) {
        return switch (type) {
            case "world" -> entities(level).stream().map(e -> e.getItem().copy()).toList();
            case "depot" -> CreateMachineEvidence.depotInventory(((DepotBlockEntity)level.getBlockEntity(HOLDER)).getBehaviour(DepotBehaviour.TYPE));
            case "belt" -> ((CreateTransportInventory)((BeltBlockEntity)level.getBlockEntity(START)).getInventory()).citizens$transportSnapshot();
            default -> throw new IllegalArgumentException("Unknown holder");
        };
    }
    private static JsonObject storage(MinecraftServer server,ServerLevel level,String type) {
        var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE); var result = new JsonObject(); var stacks = new JsonArray();
        items(level,type).stream().map(s -> ItemStack.OPTIONAL_CODEC.encodeStart(ops,s).getOrThrow()).sorted(Comparator.comparing(JsonElement::toString)).forEach(stacks::add);
        result.add("items",stacks);
        var entities = new JsonArray();
        for (var entity : entities(level)) {
            var value = new JsonObject(); value.addProperty("uuid",entity.getUUID().toString()); value.addProperty("noGravity",entity.isNoGravity());
            value.add("stack",ItemStack.CODEC.encodeStart(ops,entity.getItem()).getOrThrow()); entities.add(value);
        }
        result.add("entities",entities); return result;
    }
    private static void assertStored(ServerLevel level,String type,JsonObject state) {
        var values = items(level,type); var paper = new ItemStack(Items.PAPER,6); paper.set(DataComponents.CUSTOM_NAME,Component.literal("Fan crash output"));
        check(values.stream().filter(s -> s.is(Items.PAPER)).mapToInt(ItemStack::getCount).sum() == 6
                && values.stream().filter(s -> s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum() == 3
                && values.stream().mapToInt(ItemStack::getCount).sum() == 9
                && values.stream().filter(s -> s.is(Items.PAPER)).allMatch(s -> ItemStack.isSameItemSameComponents(s,paper)),"complete_native_primary_secondary_storage_matches_exact_bulk_outputs");
        if (type.equals("world")) {
            UUID original = UUID.fromString(state.getAsJsonObject("savedStorage").getAsJsonArray("entities").get(0).getAsJsonObject().get("uuid").getAsString());
            check(level.getEntity(original) instanceof ItemEntity input && input.isAlive() && ItemStack.matches(input.getItem(),paper),"native_primary_output_retains_original_input_entity_identity");
        } else check(entities(level).isEmpty(),"native_holder_stores_all_outputs_without_world_overflow");
    }
    private static CreativeMotorBlockEntity motor(ServerLevel level,BlockPos pos,int speed) {
        level.setBlockAndUpdate(pos,AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,Direction.EAST));
        var motor = (CreativeMotorBlockEntity)level.getBlockEntity(pos); motor.initialize(); power(motor,speed); return motor;
    }
    private static void power(CreativeMotorBlockEntity motor,int speed) { motor.generatedSpeed.setValue(speed); motor.updateGeneratedRotation(); }
    private static QuestEvidenceJournal.Entry entry(CraftQuestEvidence service,net.minecraft.commands.CommandSourceStack source,UUID observation) { return service.entries(source).stream().filter(e -> e.admission().observationId().equals(observation)).findFirst().orElseThrow(); }
    private static void kill(MinecraftServer server,ServerLevel level,String type,Path path,JsonObject state,QuestEvidenceJournal.Entry entry,int entries) {
        state.addProperty("entry",entry.id()); state.addProperty("entries",entries); state.addProperty("run",System.getenv("CITIZENS_MACHINE_AUDIT_RUN"));
        state.add("evidence",JsonParser.parseString(entry.admission().evidence())); state.add("liveStorage",storage(server,level,type));
        try (var channel = FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) { var bytes = StandardCharsets.UTF_8.encode(state.toString()); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        org.slf4j.LoggerFactory.getLogger("interactions").info("[MACHINECRASHAUDIT] KILL_READY pid={} entry={} run={}",ProcessHandle.current().pid(),entry.id(),state.get("run").getAsString());
        while (true) java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
    }
    private static void check(boolean value,String name) { if (!value) throw new AssertionError(name); passed++; org.slf4j.LoggerFactory.getLogger("interactions").info("[FANCRASHAUDIT] PASS {}",name); }
    private FanCrashRuntimeAudit() { }
}
