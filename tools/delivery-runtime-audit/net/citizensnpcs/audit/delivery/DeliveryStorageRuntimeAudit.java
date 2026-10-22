package net.citizensnpcs.audit.delivery;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;

/** Real immutable-publication failure after actual native item consumption. */
final class DeliveryStorageRuntimeAudit {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("citizens");
    private static final String TASK = "00000000004D1003", CHECKPOINT = "citizens:quest_delivery_checkpoint";
    private static ServerPlayer interrupted;
    private static QuestDeliveryJournal.Entry admitted;
    private static Path conflict;
    private static int passed;
    static void boundary(String point, ServerPlayer player) {
        if (interrupted != player || player == null || !point.equals("inventory")) return;
        interrupted = null;
        var service = QuestDeliveryRecovery.current(player.getServer());
        admitted = service.entries(player.getServer().createCommandSourceStack()).stream()
                .filter(e -> e.actorId().equals(player.getUUID())).findFirst().orElseThrow();
        if (admitted.state() != QuestDeliveryJournal.State.PREPARED) throw new AssertionError("Expected original prepared native delivery");
        Path directory = player.getServer().getWorldPath(LevelResource.ROOT).resolve("citizens/quest-deliveries");
        try { conflict = Files.createDirectory(directory.resolve("delivery-" + admitted.sequence() + "-" + admitted.id() + "-1.json")); }
        catch (Exception failure) { throw new IllegalStateException("Could not establish real publication conflict", failure); }
    }
    static void run(MinecraftServer server, String phase) {
        ServerPlayer player = null; EmbeddedChannel channel = null;
        try {
            if (!Set.of("failure", "recovered").contains(phase)) throw new IllegalArgumentException("Unknown storage phase");
            Path statePath = server.getServerDirectory().resolve("delivery-storage-state.json");
            var state = phase.equals("failure") ? new JsonObject() : JsonParser.parseString(Files.readString(statePath)).getAsJsonObject();
            UUID actor = phase.equals("failure") ? UUID.randomUUID() : UUID.fromString(state.get("actor").getAsString());
            player = new ServerPlayer(server, server.overworld(), new GameProfile(actor, "StorageActor"), ClientInformation.createDefault());
            player.setPos(1, -60, 1); channel = DeliveryRuntimeAudit.connect(player);
            final ServerPlayer current = player;
            var service = QuestDeliveryRecovery.current(server); var console = server.createCommandSourceStack();
            var file = ServerQuestFile.getInstance().orElseThrow(); var team = file.getTeamData(player).orElseThrow();
            var task = file.getTask(FtbQuestTasks.parseId(TASK));
            if (phase.equals("failure")) {
                state.addProperty("actor", actor.toString()); state.addProperty("team", team.getTeamId().toString());
                state.addProperty("run", System.getenv("CITIZENS_DELIVERY_AUDIT_RUN"));
                player.getInventory().setItem(0, DeliveryRuntimeAudit.parcel(3)); interrupted = player;
                Throwable reported = rejected(() -> FtbQuestTasks.submit(current, TASK, false), "failed_confirmation_reports_storage_failure");
                check(admitted != null && conflict != null && Files.isDirectory(conflict), "failure_uses_actual_original_immutable_publication_path");
                check(player.getInventory().countItem(Items.PAPER) == 0 && team.getProgress(task) == 3, "failed_confirmation_keeps_actual_native_consumption_and_progress");
                check(!player.getPersistentData().contains(CHECKPOINT), "failed_confirmation_does_not_claim_a_saved_outcome_checkpoint");
                rejected(() -> service.entries(console), "failed_writer_cannot_expose_authoritative_live_history");
                Path fixture = server.getServerDirectory().toAbsolutePath().normalize();
                Path retained = fixture.resolve("retained-publication-conflict");
                if (!conflict.toAbsolutePath().normalize().startsWith(fixture) || !retained.startsWith(fixture))
                    throw new IllegalStateException("Publication-conflict move escaped fixture");
                Files.move(conflict, retained);
                player.getInventory().setItem(0, DeliveryRuntimeAudit.parcel(2));
                rejected(() -> FtbQuestTasks.submit(current, TASK, true), "removing_collision_does_not_revive_poisoned_preflight");
                rejected(() -> FtbQuestTasks.submit(current, TASK, false), "removing_collision_does_not_revive_poisoned_execution");
                check(player.getInventory().countItem(Items.PAPER) == 2 && team.getProgress(task) == 3, "failed_writer_retry_preserves_remaining_inventory_and_progress");
                state.addProperty("entry", admitted.id().toString()); state.addProperty("reported", reported.toString());
                Files.writeString(statePath, state.toString());
                server.getPlayerList().saveAll(); team.saveIfChanged(); file.saveNow();
            } else {
                check(state.get("run").getAsString().equals(System.getenv("CITIZENS_DELIVERY_AUDIT_RUN")), "restart_reacquires_original_storage_run");
                var entry = service.entries(console).stream().filter(e -> e.id().toString().equals(state.get("entry").getAsString())).findFirst().orElseThrow();
                check(entry.actorId().equals(actor) && entry.before().teamId().equals(team.getTeamId()) && team.getTeamId().toString().equals(state.get("team").getAsString()), "restart_retains_original_actor_team_and_entry");
                check(entry.state() == QuestDeliveryJournal.State.REVIEW && entry.after() == null, "prepared_record_reopens_as_review_without_fabricated_confirmation");
                check(player.getInventory().countItem(Items.PAPER) == 2 && team.getProgress(task) == 3, "restart_preserves_actual_saved_partial_native_effects");
                rejected(() -> FtbQuestTasks.submit(current, TASK, true), "uncertain_storage_outcome_fences_restarted_preflight");
                rejected(() -> FtbQuestTasks.submit(current, TASK, false), "uncertain_storage_outcome_fences_restarted_execution");
                check(player.getInventory().countItem(Items.PAPER) == 2 && team.getProgress(task) == 3, "review_retry_does_not_repeat_native_consumption");
                service.resolve(player.createCommandSourceStack().withPermission(4), entry.id(), false, "Inspected retained consumed three paper and saved native progress; preserve effects");
                FtbQuestTasks.submit(player, TASK, false);
                var next = service.entries(console).stream().filter(e -> e.actorId().equals(actor)).max(Comparator.comparingLong(QuestDeliveryJournal.Entry::sequence)).orElseThrow();
                check(next.state() == QuestDeliveryJournal.State.CONFIRMED && entry.id().equals(next.previousActorEntry()) && player.getInventory().countItem(Items.PAPER) == 0 && team.getProgress(task) == 5, "review_resolution_allows_only_two_remaining_paper_on_original_chain");
                int count = service.entries(console).size(); FtbQuestTasks.submit(player, TASK, false);
                check(service.entries(console).size() == count, "completed_retry_creates_no_new_intent_after_storage_recovery");
            }
            LOG.info("[DELIVERYAUDIT] COMPLETE {} storage checks phase={}", passed, phase);
        } catch (Throwable failure) { LOG.error("[DELIVERYAUDIT] FAILED storage {}", phase, failure); }
        finally {
            interrupted = null;
            if (player != null && server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
            if (channel != null) channel.finishAndReleaseAll();
            server.halt(false);
        }
    }
    private static Throwable rejected(Runnable action, String name) {
        try { action.run(); throw new AssertionError(name); }
        catch (IllegalStateException expected) { check(true, name); return expected; }
    }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); passed++; LOG.info("[DELIVERYSTORAGEAUDIT] PASS {}", name); }
    private DeliveryStorageRuntimeAudit() { }
}
