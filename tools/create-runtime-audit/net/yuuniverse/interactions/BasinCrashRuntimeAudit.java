package net.yuuniverse.interactions;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
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
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Real multi-input/fluid mixing, externally killed with older native basin/FTB saves. */
public final class BasinCrashRuntimeAudit {
    private static final BlockPos POS = new BlockPos(4, -58, 10), BASIN = POS.below(2), COG = POS.east(), MOTOR = COG.above();
    private static final ResourceLocation RECIPE = ResourceLocation.parse("citizens_audit:basin_crash");
    private static CreateMachineEvidence.Observation observed;
    private static MachineOutcomeEvent.Outcome outcome;
    private static int passed;

    public static void run(MinecraftServer server, String phase) {
        ServerPlayer actor = null;
        io.netty.channel.embedded.EmbeddedChannel connection = null;
        CraftQuestEvidence opened = null;
        try {
            boolean recovery = phase.equals("recover");
            Path statePath = server.getServerDirectory().resolve("basin-crash-state.json");
            var state = recovery ? JsonParser.parseString(Files.readString(statePath)).getAsJsonObject() : new JsonObject();
            var level = server.overworld(); level.getChunkAt(POS);
            UUID actorId = recovery ? UUID.fromString(state.get("actor").getAsString()) : UUID.randomUUID();
            actor = new ServerPlayer(server, level, new com.mojang.authlib.GameProfile(actorId, "BasinCrash"), ClientInformation.createDefault());
            actor.setPos(1, -60, 1); connection = CreateRuntimeAudit.connect(actor);
            var source = actor.createCommandSourceStack().withPermission(2);
            var file = ServerQuestFile.getInstance().orElseThrow(); var team = file.getTeamData(actor).orElseThrow();
            Path fixture = server.getServerDirectory().toAbsolutePath().normalize();
            Path evidencePath = recovery ? fixture.resolve(state.get("evidenceDirectory").getAsString()).normalize()
                    : Files.createTempDirectory(fixture, "basin-crash-evidence-");
            if (!evidencePath.startsWith(fixture)) throw new AssertionError("Evidence escaped fixture");
            var service = CraftQuestEvidence.open(server, evidencePath); opened = service;
            var recipe = CraftingRecipes.find(server, RECIPE).orElseThrow();
            check(recipe.processing().process() == CreateProcessingRecipes.Process.MIXING, "registered_native_mixing_recipe_available");
            NeoForge.EVENT_BUS.addListener(MachineResultEvent.class, event -> { if (event.getObservation().position().equals(POS)) observed = event.getObservation(); });
            NeoForge.EVENT_BUS.addListener(MachineOutcomeEvent.class, event -> { if (event.getObservation().position().equals(POS)) outcome = event.getOutcome(); });
            if (!recovery) {
                String stage = phase.substring("commit:".length());
                if (!Set.of("prepared", "confirmed", "applied").contains(stage)) throw new IllegalArgumentException("Unknown kill stage");
                var chapter = new Chapter(file.newID(), file, file.getDefaultChapterGroup()); chapter.onCreated();
                var quest = new Quest(file.newID(), chapter); quest.onCreated();
                var task = new CustomTask(file.newID(), quest); task.onCreated(); file.clearCachedData(); file.clearCachedProgress();
                setup(level); CreateMachineEvidence.assign(source, actorId, POS);
                service.bind(source, task.getCodeString(), RECIPE);
                state.addProperty("actor", actorId.toString()); state.addProperty("team", team.getTeamId().toString());
                state.addProperty("task", task.getCodeString()); state.addProperty("stage", stage);
                state.addProperty("maximum", task.getMaxProgress()); state.addProperty("revision", recipe.revision());
                state.addProperty("machine", CreateMachineEvidence.attribution(level.getBlockEntity(POS)).machineId().toString());
                state.addProperty("evidenceDirectory", fixture.relativize(evidencePath).toString());
                state.add("savedStorage", storage(server, level));
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();
                team.saveIfChanged(); file.saveNow(); server.saveEverything(true, true, true);
                if (stage.equals("prepared")) NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, MachineResultEvent.class, event -> {
                    if (event.getObservation().position().equals(POS)) {
                        var entry = entry(service, source, event.getObservation().id());
                        check(entry.state() == QuestEvidenceJournal.State.PREPARED && storage(server, level).equals(state.get("savedStorage")),
                                "prepared_admission_precedes_all_native_item_fluid_mutations");
                        kill(server, level, statePath, state, entry, service.entries(source).size());
                    }
                });
                if (stage.equals("confirmed")) dev.ftb.mods.ftbquests.events.ObjectCompletedEvent.TASK.register(event -> {
                    if (event.getTask() == task) {
                        var entry = service.entries(source).stream().filter(e -> e.admission().taskId().equals(task.getCodeString())
                                && e.state() == QuestEvidenceJournal.State.CONFIRMED).findFirst().orElseThrow();
                        assertStored(level); kill(server, level, statePath, state, entry, service.entries(source).size());
                    }
                    return dev.architectury.event.EventResult.pass();
                });
                process(level);
                if (!stage.equals("applied")) throw new AssertionError("Missed native basin kill boundary");
                var entry = entry(service, source, observed.id());
                check(entry.state() == QuestEvidenceJournal.State.APPLIED && team.getProgress(task) == task.getMaxProgress(),
                        "native_item_fluid_storage_and_quest_projection_precede_applied_kill");
                assertStored(level); kill(server, level, statePath, state, entry, service.entries(source).size());
            } else {
                String stage = state.get("stage").getAsString();
                var task = file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(), 16));
                var entry = service.entries(source).stream().filter(e -> e.id().equals(state.get("entry").getAsString())).findFirst().orElseThrow();
                var expectedState = switch (stage) { case "prepared" -> QuestEvidenceJournal.State.REVIEW; case "confirmed" -> QuestEvidenceJournal.State.CONFIRMED; default -> QuestEvidenceJournal.State.APPLIED; };
                check(entry.state() == expectedState && task.getMaxProgress() == state.get("maximum").getAsLong()
                        && team.getProgress(task) == 0 && team.getTeamId().toString().equals(state.get("team").getAsString()),
                        "restart_retains_original_journal_phase_and_older_native_team_task");
                check(storage(server, level).equals(state.get("savedStorage")), "restart_restores_every_saved_basin_slot_and_tank_with_components");
                var assignment = CreateMachineEvidence.attribution(level.getBlockEntity(POS));
                check(assignment != null && assignment.machineId().toString().equals(state.get("machine").getAsString()) && assignment.beneficiaryId().equals(actorId),
                        "restart_retains_original_machine_and_beneficiary");
                var payload = JsonParser.parseString(entry.admission().evidence()).getAsJsonObject();
                check(payload.equals(state.get("evidence")) && entry.admission().actorId().equals(actorId) && entry.admission().units() == 2
                        && entry.admission().recipeId().equals(RECIPE.toString()) && entry.admission().recipeRevision().equals(recipe.revision())
                        && recipe.revision().equals(state.get("revision").getAsString()), "original_complete_multi_item_fluid_payload_survives_kill");
                int entries = state.get("entries").getAsInt();
                if (stage.equals("prepared")) {
                    check(entry.outcome() == QuestEvidenceJournal.Outcome.MISSING && service.reconcile(source) == 0 && team.getProgress(task) == 0,
                            "missing_basin_outcome_cannot_award_quest_credit");
                    process(level);
                    check(service.entries(source).size() == entries && team.getProgress(task) == 0, "uncertain_original_fences_reprocessing_credit");
                    var storageBeforeReview = storage(server, level);
                    service.resolve(source, entry.id(), false, "Actual kill before basin consumption; original saved inputs restored and credit remained fenced");
                    check(service.entries(source).stream().anyMatch(e -> e.id().equals(entry.id()) && e.state() == QuestEvidenceJournal.State.REJECTED)
                            && storageBeforeReview.equals(storage(server, level)) && team.getProgress(task) == 0, "authenticated_rejection_has_no_native_item_fluid_or_task_side_effect");
                } else if (stage.equals("confirmed")) {
                    var beforeReconcile = storage(server, level);
                    check(service.reconcile(source) == 1 && team.getProgress(task) == task.getMaxProgress() && beforeReconcile.equals(storage(server, level)),
                            "confirmed_absolute_projection_never_reconsumes_native_items_or_fluids");
                    check(service.reconcile(source) == 0 && team.getProgress(task) == task.getMaxProgress(), "confirmed_reconciliation_retry_is_noop");
                    process(level);
                    check(service.entries(source).size() == entries && team.getProgress(task) == task.getMaxProgress(), "completed_original_task_cannot_credit_restored_inputs_again");
                } else {
                    check(service.reconcile(source) == 0 && team.getProgress(task) == 0, "applied_evidence_does_not_silently_repair_independent_native_rollback");
                    process(level);
                    check(service.entries(source).size() == entries && team.getProgress(task) == 0, "retained_applied_total_fences_rolled_back_basin_reprocessing");
                }
                assertStored(level);
                check(observed != null && outcome == MachineOutcomeEvent.Outcome.RETURNED, "native_mixing_returns_normally_when_quest_credit_is_fenced");
                state.add("recoveredStorage", storage(server, level));
                state.addProperty("recoveredProgress", team.getProgress(task));
                state.add("recoveredEntries", new Gson().toJsonTree(service.entries(source)));
                Files.writeString(fixture.resolve("basin-crash-recovery.json"), state.toString());
                org.slf4j.LoggerFactory.getLogger("interactions").info("[BASINCRASHAUDIT] COMPLETE {} checks stage={}", passed, stage);
                org.slf4j.LoggerFactory.getLogger("interactions").info("[CREATEAUDIT] COMPLETE actual basin crash recovery");
            }
        } catch (Throwable failure) { org.slf4j.LoggerFactory.getLogger("interactions").error("[CREATEAUDIT] FAILED basin crash", failure); }
        finally {
            if (opened != null) try { opened.close(); } catch (Exception failure) { org.slf4j.LoggerFactory.getLogger("interactions").error("Fixture close failed", failure); }
            if (actor != null && actor.connection != null) server.getPlayerList().remove(actor);
            if (connection != null) connection.finishAndReleaseAll();
            server.halt(false);
        }
    }

    private static void setup(ServerLevel level) {
        level.setBlockAndUpdate(BASIN, AllBlocks.BASIN.getDefaultState()); basin(level).initialize();
        level.setBlockAndUpdate(POS, AllBlocks.MECHANICAL_MIXER.getDefaultState()); ((MechanicalMixerBlockEntity)level.getBlockEntity(POS)).initialize();
        level.setBlockAndUpdate(COG, AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        ((SimpleKineticBlockEntity)level.getBlockEntity(COG)).initialize();
        level.setBlockAndUpdate(MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING, Direction.DOWN));
        var motor = (CreativeMotorBlockEntity)level.getBlockEntity(MOTOR); motor.initialize(); power(motor, 0);
        var basin = basin(level); basin.inputInventory.setStackInSlot(0, new ItemStack(Items.SNOWBALL, 5));
        basin.inputInventory.setStackInSlot(1, new ItemStack(Items.FLINT, 2));
        basin.inputTank.getPrimaryHandler().fill(new FluidStack(Fluids.WATER, 750), IFluidHandler.FluidAction.EXECUTE);
        basin.notifyChangeOfContents(); basin.setChanged();
    }
    private static void process(ServerLevel level) {
        observed = null; outcome = null;
        var motor = (CreativeMotorBlockEntity)level.getBlockEntity(MOTOR); power(motor, 256);
        var mixer = (MechanicalMixerBlockEntity)level.getBlockEntity(POS); var cog = (SimpleKineticBlockEntity)level.getBlockEntity(COG);
        for (int tick = 0; tick < 1200; tick++) {
            motor.tick(); cog.tick(); mixer.tick(); basin(level).tick();
            if (observed != null && outcome != null) return;
        }
        throw new AssertionError("Native mixer did not return: speed=" + mixer.getSpeed() + " running=" + mixer.running + " ticks=" + mixer.runningTicks);
    }
    private static BasinBlockEntity basin(ServerLevel level) { return (BasinBlockEntity)level.getBlockEntity(BASIN); }
    private static JsonObject storage(MinecraftServer server, ServerLevel level) {
        var snapshot = ((CreateBasinStorage)basin(level)).citizens$basinSnapshot();
        var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE); var result = new JsonObject();
        var items = new JsonArray(); var fluids = new JsonArray();
        snapshot.items().forEach(s -> items.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, s).getOrThrow()));
        snapshot.fluids().forEach(s -> fluids.add(FluidStack.OPTIONAL_CODEC.encodeStart(ops, s).getOrThrow()));
        result.add("items", items); result.add("fluids", fluids); return result;
    }
    private static void assertStored(ServerLevel level) {
        var snapshot = ((CreateBasinStorage)basin(level)).citizens$basinSnapshot();
        var expected = new ItemStack(Items.PAPER, 2); expected.set(DataComponents.CUSTOM_NAME, Component.literal("Basin crash output"));
        check(count(snapshot.items(), Items.SNOWBALL) == 3 && count(snapshot.items(), Items.FLINT) == 1 && count(snapshot.items(), Items.PAPER) == 2
                && snapshot.items().stream().mapToInt(ItemStack::getCount).sum() == 6
                && snapshot.items().stream().filter(s -> s.is(Items.PAPER)).allMatch(s -> ItemStack.matches(s, expected)),
                "native_mix_stores_exact_component_output_and_unconsumed_multi_item_inputs");
        check(snapshot.fluids().stream().filter(s -> s.getFluid() == Fluids.WATER).mapToInt(FluidStack::getAmount).sum() == 500
                && snapshot.fluids().stream().filter(s -> s.getFluid() == Fluids.LAVA).mapToInt(FluidStack::getAmount).sum() == 100
                && snapshot.fluids().stream().mapToInt(FluidStack::getAmount).sum() == 600, "native_mix_consumes_exact_fluid_and_stores_exact_fluid_output");
    }
    private static int count(List<ItemStack> values, Item item) { return values.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static QuestEvidenceJournal.Entry entry(CraftQuestEvidence service, net.minecraft.commands.CommandSourceStack source, UUID observation) {
        return service.entries(source).stream().filter(e -> e.admission().observationId().equals(observation)).findFirst().orElseThrow();
    }
    private static void power(CreativeMotorBlockEntity motor, int speed) { motor.generatedSpeed.setValue(speed); motor.updateGeneratedRotation(); }
    private static void kill(MinecraftServer server, ServerLevel level, Path path, JsonObject state, QuestEvidenceJournal.Entry entry, int entries) {
        state.addProperty("entry", entry.id()); state.addProperty("entries", entries); state.addProperty("run", System.getenv("CITIZENS_MACHINE_AUDIT_RUN"));
        state.add("evidence", JsonParser.parseString(entry.admission().evidence())); state.add("liveStorage", storage(server, level));
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var bytes = StandardCharsets.UTF_8.encode(state.toString()); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true);
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        org.slf4j.LoggerFactory.getLogger("interactions").info("[MACHINECRASHAUDIT] KILL_READY pid={} entry={} run={}", ProcessHandle.current().pid(), entry.id(), state.get("run").getAsString());
        while (true) java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
    }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); passed++; org.slf4j.LoggerFactory.getLogger("interactions").info("[BASINCRASHAUDIT] PASS {}", name); }
    private BasinCrashRuntimeAudit() { }
}
