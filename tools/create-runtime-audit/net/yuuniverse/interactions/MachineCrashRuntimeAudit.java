package net.yuuniverse.interactions;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import com.google.gson.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import net.citizensnpcs.api.event.MachineResultEvent;
import net.citizensnpcs.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/** Actual opt-in JVM termination at native processing boundaries, using independently saved world/FTB data. */
public final class MachineCrashRuntimeAudit {
    private static int passed;
    public static void run(MinecraftServer server, String phase) {
        ServerPlayer actor = null;
        io.netty.channel.embedded.EmbeddedChannel channel = null;
        try {
            boolean recovery = phase.equals("recover");
            var statePath = server.getServerDirectory().resolve("machine-crash-state.json");
            var state = recovery ? JsonParser.parseString(Files.readString(statePath)).getAsJsonObject() : new JsonObject();
            UUID actorId = recovery ? UUID.fromString(state.get("actor").getAsString()) : UUID.randomUUID();
            actor = new ServerPlayer(server, server.overworld(), new com.mojang.authlib.GameProfile(actorId, "MachineCrash"), ClientInformation.createDefault());
            actor.setPos(1, -60, 1); channel = CreateRuntimeAudit.connect(actor);
            var source = actor.createCommandSourceStack().withPermission(2);
            var file = ServerQuestFile.getInstance().orElseThrow(); var team = file.getTeamData(actor).orElseThrow();
            var service = CraftQuestEvidence.current(server);
            var pos = new BlockPos(10, -58, 2); var motorPos = pos.west();
            if (recovery) {
                var task = file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(), 16));
                var entry = service.entries(source).stream().filter(e -> e.id().equals(state.get("entry").getAsString())).findFirst().orElseThrow();
                var stage = state.get("stage").getAsString();
                var retained = switch (stage) { case "prepared" -> QuestEvidenceJournal.State.REVIEW; case "confirmed" -> QuestEvidenceJournal.State.CONFIRMED; default -> QuestEvidenceJournal.State.APPLIED; };
                check(entry.state() == retained && team.getProgress(task) == 0, "restart_keeps_durable_phase_and_independently_saved_old_ftb_progress");
                var saw = (SawBlockEntity) server.overworld().getBlockEntity(pos);
                check(saw != null && saw.inventory.getStackInSlot(0).is(AllItems.ANDESITE_ALLOY.get()) && saw.inventory.getStackInSlot(0).getCount() == 3,
                        "process_kill_recovers_saved_unconsumed_machine_input");
                check(CreateMachineEvidence.attribution(saw).machineId().toString().equals(state.get("machine").getAsString())
                        && CreateMachineEvidence.attribution(saw).beneficiaryId().equals(actorId), "native_world_restart_retains_original_machine_assignment");
                var payload = JsonParser.parseString(entry.admission().evidence()).getAsJsonObject();
                check(entry.admission().actorId().equals(actorId) && entry.admission().units() == 18
                        && payload.get("recipeId").getAsString().equals("create:cutting/andesite_alloy")
                        && payload.getAsJsonObject("consumedInput").get("count").getAsInt() == 3,
                        "restart_retains_exact_native_recipe_batch_and_beneficiary_evidence");
                if (stage.equals("prepared")) {
                    check(entry.outcome() == QuestEvidenceJournal.Outcome.MISSING && service.reconcile(source) == 0 && team.getProgress(task) == 0,
                            "missing_native_outcome_requires_review_without_credit");
                    service.resolve(source, entry.id(), false, "Actual process kill before storage; saved world still retains the input");
                    check(service.entries(source).stream().anyMatch(e -> e.id().equals(entry.id()) && e.state() == QuestEvidenceJournal.State.REJECTED),
                            "operator_can_reject_uncertain_original_evidence_without_payment_or_progress");
                } else if (stage.equals("confirmed")) {
                    check(service.reconcile(source) == 1 && team.getProgress(task) == 1, "explicit_reconciliation_projects_confirmed_absolute_total_once");
                    check(service.reconcile(source) == 0 && team.getProgress(task) == 1, "confirmed_restart_retries_do_not_duplicate_native_progress");
                } else {
                    check(service.reconcile(source) == 0 && team.getProgress(task) == 0, "applied_evidence_does_not_silently_repair_world_or_ftb_rollback");
                    var count = service.entries(source).size(); var motor = (CreativeMotorBlockEntity) server.overworld().getBlockEntity(motorPos);
                    process(saw, motor);
                    check(service.entries(source).size() == count && team.getProgress(task) == 0, "rolled_back_input_reprocessing_is_fenced_by_retained_applied_total");
                }
                org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEAUDIT] COMPLETE {} actual machine crash recovery checks", passed);
            } else {
                String stage = phase.substring("commit:".length());
                var chapter = new Chapter(file.newID(), file, file.getDefaultChapterGroup()); chapter.onCreated();
                var quest = new Quest(file.newID(), chapter); quest.onCreated(); var task = new CustomTask(file.newID(), quest); task.onCreated();
                file.clearCachedData(); file.clearCachedProgress();
                server.overworld().removeBlock(pos, false); server.overworld().removeBlock(motorPos, false);
                server.overworld().setBlockAndUpdate(pos, AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(SawBlock.FACING, Direction.UP)
                        .setValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
                var saw = (SawBlockEntity) server.overworld().getBlockEntity(pos); saw.initialize();
                saw.getBehaviour(com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE).setFilter(AllBlocks.SHAFT.asStack());
                server.overworld().setBlockAndUpdate(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
                var motor = (CreativeMotorBlockEntity) server.overworld().getBlockEntity(motorPos); motor.initialize(); motor.generatedSpeed.setValue(0); motor.updateGeneratedRotation();
                CreateMachineEvidence.assign(source, actorId, pos);
                service.bind(source, task.getCodeString(), ResourceLocation.parse("create:cutting/andesite_alloy"));
                saw.inventory.clear(); saw.inventory.appliedRecipe = false; saw.inventory.remainingTime = -1;
                saw.inventory.setStackInSlot(0, new ItemStack(AllItems.ANDESITE_ALLOY.get(), 3));
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl) dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();
                team.saveIfChanged(); file.saveNow(); server.saveEverything(true, true, true);
                state.addProperty("actor", actorId.toString()); state.addProperty("task", task.getCodeString()); state.addProperty("stage", stage);
                state.addProperty("machine", CreateMachineEvidence.attribution(saw).machineId().toString());
                if (stage.equals("prepared")) NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, MachineResultEvent.class, event -> {
                    if (event.getObservation().position().equals(pos)) {
                        var entry = service.entries(source).stream().filter(e -> e.admission().observationId().equals(event.getObservation().id())).findFirst().orElseThrow();
                        if (entry.state() != QuestEvidenceJournal.State.PREPARED) throw new AssertionError("Expected durable PREPARED");
                        killMarker(statePath, state, entry.id());
                    }
                });
                if (stage.equals("confirmed")) dev.ftb.mods.ftbquests.events.ObjectCompletedEvent.TASK.register(event -> {
                    if (event.getTask() == task) {
                        var entry = service.entries(source).stream().filter(e -> e.admission().taskId().equals(task.getCodeString()) && e.state() == QuestEvidenceJournal.State.CONFIRMED).findFirst().orElseThrow();
                        killMarker(statePath, state, entry.id());
                    }
                    return dev.architectury.event.EventResult.pass();
                });
                process(saw, motor);
                if (!stage.equals("applied")) throw new AssertionError("Native machine missed kill boundary: entries=" + service.entries(source)
                        + ", outputs=" + saw.inventory.getStackInSlot(1) + ", speed=" + saw.getSpeed());
                var entry = service.entries(source).stream().filter(e -> e.admission().taskId().equals(task.getCodeString())).findFirst().orElseThrow();
                check(entry.state() == QuestEvidenceJournal.State.APPLIED && team.getProgress(task) == 1, "kill_point_follows_durable_applied_before_ftb_and_world_save");
                killMarker(statePath, state, entry.id());
            }
        } catch (Throwable failure) { org.slf4j.LoggerFactory.getLogger("interactions").error("[CREATEAUDIT] FAILED machine crash", failure); }
        finally { if (actor != null && actor.connection != null) server.getPlayerList().remove(actor); if (channel != null) channel.finishAndReleaseAll(); server.halt(false); }
    }
    private static void process(SawBlockEntity saw, CreativeMotorBlockEntity motor) {
        motor.generatedSpeed.setValue(256); motor.updateGeneratedRotation();
        for (int i = 0; i < 1000 && !saw.inventory.appliedRecipe; i++) { motor.tick(); saw.tick(); }
        if (!saw.inventory.appliedRecipe) throw new AssertionError("Native saw did not process");
    }
    private static void killMarker(Path path, JsonObject state, String entryId) {
        state.addProperty("entry", entryId);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var bytes = StandardCharsets.UTF_8.encode(state.toString()); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true);
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        org.slf4j.LoggerFactory.getLogger("interactions").info("[MACHINECRASHAUDIT] KILL_READY pid={} entry={}", ProcessHandle.current().pid(), entryId);
        while (true) java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name); passed++; org.slf4j.LoggerFactory.getLogger("interactions").info("[MACHINECRASHAUDIT] PASS {}", name);
    }
}
