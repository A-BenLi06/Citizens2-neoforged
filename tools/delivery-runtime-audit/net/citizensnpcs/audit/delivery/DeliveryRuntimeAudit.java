package net.citizensnpcs.audit.delivery;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@EventBusSubscriber(modid = "citizens")
public final class DeliveryRuntimeAudit {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("citizens");
    private static final String TASK = "00000000004D1003", OTHER = "00000000004D1013";
    private static boolean ran, armed, failReturn, reenter, rejectedReentry;
    private static int passed;
    private static ServerPlayer actor;
    private static TeamData team;
    private static JsonObject state;
    private static String phase, killBoundary;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (ran) return; ran = true;
        String storage = System.getenv("CITIZENS_DELIVERY_AUDIT_STORAGE");
        if (storage != null) { DeliveryStorageRuntimeAudit.run(event.getServer(), storage); return; }
        String history = System.getenv("CITIZENS_DELIVERY_AUDIT_HISTORY");
        if (history != null) { DeliveryHistoryRuntimeAudit.run(event.getServer(), history); return; }
        String mutation = System.getenv("CITIZENS_DELIVERY_AUDIT_MUTATION");
        if (mutation != null) { DeliveryMutationRuntimeAudit.run(event.getServer(), mutation); return; }
        MinecraftServer server = event.getServer(); EmbeddedChannel channel = null;
        try {
            phase = Objects.requireNonNullElse(System.getenv("CITIZENS_DELIVERY_AUDIT_PHASE"), "normal");
            killBoundary = System.getenv("CITIZENS_DELIVERY_AUDIT_BOUNDARY");
            state = phase.equals("recover") ? JsonParser.parseString(Files.readString(server.getServerDirectory().resolve("delivery-crash-state.json"))).getAsJsonObject() : new JsonObject();
            UUID actorId = phase.equals("recover") ? UUID.fromString(state.get("actor").getAsString()) : UUID.randomUUID();
            actor = new ServerPlayer(server, server.overworld(), new GameProfile(actorId, "DeliveryActor"), ClientInformation.createDefault());
            actor.setPos(1, -60, 1); channel = connect(actor);
            var file = ServerQuestFile.getInstance().orElseThrow(); team = file.getTeamData(actor).orElseThrow();
            var service = QuestDeliveryRecovery.current(server);
            if (phase.equals("recover")) recover(service);
            else if (phase.equals("crash")) {
                actor.getInventory().clearContent(); actor.getInventory().setItem(0, parcel(3));
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();
                team.saveIfChanged(); file.saveNow(); server.saveEverything(true, true, true);
                state.addProperty("actor", actorId.toString()); state.addProperty("team", team.getTeamId().toString());
                state.addProperty("boundary", killBoundary); state.addProperty("run", System.getenv("CITIZENS_DELIVERY_AUDIT_RUN"));
                armed = true; FtbQuestTasks.submit(actor, TASK, false); boundary("confirmed", actor);
                throw new AssertionError("Crash boundary was not reached");
            } else normal(service);
            LOG.info("[DELIVERYAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) { LOG.error("[DELIVERYAUDIT] FAILED", failure); }
        finally {
            if (actor != null && server.getPlayerList().getPlayer(actor.getUUID()) == actor) server.getPlayerList().remove(actor);
            if (channel != null) channel.finishAndReleaseAll();
            server.halt(false);
        }
    }
    public static void boundary(String point, ServerPlayer player) {
        DeliveryStorageRuntimeAudit.boundary(point, player);
        DeliveryHistoryRuntimeAudit.boundary(point, player);
        if (actor == null || player != null && player != actor) return;
        if (reenter && point.equals("before")) {
            reenter = false;
            try { FtbQuestTasks.submit(actor, TASK, false); throw new AssertionError("Recursive native delivery admitted"); }
            catch (IllegalStateException expected) { rejectedReentry = true; }
        }
        if (failReturn && point.equals("inventory")) { failReturn = false; throw new IllegalStateException("Expected fixture failure after native inventory mutation"); }
        if (!armed || !phase.equals("crash")) return;
        String pointForBoundary = Set.of("player_rollback", "task_rollback").contains(killBoundary) ? "confirmed" : killBoundary;
        if (!point.equals(pointForBoundary)) return;
        armed = false;
        try {
            if (Set.of("progress", "confirmed", "player_rollback").contains(killBoundary)) team.saveIfChanged();
            if (Set.of("inventory", "confirmed", "task_rollback").contains(killBoundary)) actor.getServer().getPlayerList().saveAll();
            var entry = latest();
            check(entry.state() == (point.equals("confirmed") ? QuestDeliveryJournal.State.CONFIRMED : QuestDeliveryJournal.State.PREPARED), "kill_boundary_has_expected_durable_delivery_state");
            check(actor.getInventory().countItem(Items.PAPER) == (Set.of("before", "progress").contains(point) ? 3 : 0), "kill_boundary_observes_actual_native_inventory_mutation");
            check(team.getProgress(task(TASK)) == (point.equals("before") ? 0 : 3), "kill_boundary_observes_actual_native_ftb_progress");
            state.addProperty("entry", entry.id().toString());
            state.addProperty("savedInventory", Set.of("inventory", "confirmed", "task_rollback").contains(killBoundary) ? 0 : 3);
            state.addProperty("savedProgress", Set.of("progress", "confirmed", "player_rollback").contains(killBoundary) ? 3 : 0);
            Path path = actor.getServer().getServerDirectory().resolve("delivery-crash-state.json");
            try (var output = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                var bytes = StandardCharsets.UTF_8.encode(state.toString()); while (bytes.hasRemaining()) output.write(bytes); output.force(true);
            }
            LOG.info("[DELIVERYAUDIT] KILL_READY pid={} actor={} entry={} run={} boundary={}", ProcessHandle.current().pid(), actor.getUUID(), entry.id(), state.get("run").getAsString(), killBoundary);
            while (true) java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static void normal(QuestDeliveryRecovery service) {
        var source = actor.createCommandSourceStack().withPermission(4);
        actor.getInventory().clearContent(); actor.getInventory().setItem(0, parcel(2));
        int before = service.entries(source).size();
        FtbQuestTasks.submit(actor, TASK, true);
        check(service.entries(source).size() == before && actor.getInventory().countItem(Items.PAPER) == 2, "preflight_does_not_prepare_or_consume");
        reenter = true; FtbQuestTasks.submit(actor, TASK, false);
        var first = latest();
        check(rejectedReentry && team.getProgress(task(TASK)) == 2 && actor.getInventory().countItem(Items.PAPER) == 0, "native_partial_delivery_and_callback_reentry_have_one_consumption");
        check(first.state() == QuestDeliveryJournal.State.CONFIRMED && first.inventoryBefore().contains("native-delivery") && first.inventoryBefore().contains("Delivery parcel"), "retained_native_snapshot_preserves_components");
        actor.getInventory().setItem(0, parcel(3)); FtbQuestTasks.submit(actor, TASK, false);
        var second = latest();
        check(second.previousActorEntry().equals(first.id()) && team.getProgress(task(TASK)) == 5 && actor.getInventory().countItem(Items.PAPER) == 0, "second_partial_delivery_has_a_distinct_chained_intent");
        int completed = service.entries(source).size(); actor.getInventory().setItem(0, parcel(1)); FtbQuestTasks.submit(actor, TASK, false);
        check(service.entries(source).size() == completed && actor.getInventory().countItem(Items.PAPER) == 1, "completed_retry_does_not_prepare_or_consume");
        actor.getInventory().setItem(0, parcel(3)); failReturn = true;
        try { FtbQuestTasks.submit(actor, OTHER, false); throw new AssertionError("Native tail exception hidden"); }
        catch (IllegalStateException expected) { check(team.getProgress(task(OTHER)) == 3 && actor.getInventory().countItem(Items.PAPER) == 0, "native_tail_exception_keeps_actual_mutations"); }
        var uncertain = latest();
        check(uncertain.state() == QuestDeliveryJournal.State.REVIEW && uncertain.after() == null && uncertain.inventoryBefore().contains("native-delivery"), "interrupted_native_tail_retains_original_input_for_review");
        int rows = service.entries(source).size(); actor.getInventory().setItem(0, parcel(2));
        rejected(() -> FtbQuestTasks.submit(actor, OTHER, true), "preflight_rejects_uncertain_delivery");
        rejected(() -> FtbQuestTasks.submit(actor, OTHER, false), "execution_rejects_uncertain_delivery");
        check(service.entries(source).size() == rows && actor.getInventory().countItem(Items.PAPER) == 2 && team.getProgress(task(OTHER)) == 3, "rejected_retry_changes_neither_native_owner_nor_intent_count");
        rejected(() -> service.resolve(actor.getServer().createCommandSourceStack(), uncertain.id(), true, "console has no operator UUID"), "console_cannot_resolve_delivery_without_operator_identity");
        service.resolve(source, uncertain.id(), true, "Verified native partial consumption and retained task progress");
        check(actor.getInventory().countItem(Items.PAPER) == 2 && team.getProgress(task(OTHER)) == 3, "operator_resolution_records_a_decision_without_native_side_effects");
        FtbQuestTasks.submit(actor, OTHER, false);
        check(team.getProgress(task(OTHER)) == 5 && actor.getInventory().countItem(Items.PAPER) == 0, "explicit_resolution_allows_remaining_native_delivery");
        actor.getPersistentData().remove("citizens:quest_delivery_checkpoint");
        rejected(() -> FtbQuestTasks.submit(actor, OTHER, true), "read_only_preflight_detects_old_player_checkpoint");
        check(latest().state() == QuestDeliveryJournal.State.CONFIRMED, "read_only_checkpoint_failure_does_not_rewrite_journal");
        rejected(() -> FtbQuestTasks.submit(actor, OTHER, false), "execution_records_old_player_checkpoint_for_review");
        check(latest().state() == QuestDeliveryJournal.State.REVIEW, "old_player_checkpoint_has_a_durable_review_fence");
    }
    private static void recover(QuestDeliveryRecovery service) {
        check(team.getTeamId().toString().equals(state.get("team").getAsString()), "restart_reacquires_original_native_team");
        int count = state.get("savedInventory").getAsInt(); long progress = state.get("savedProgress").getAsLong();
        check(actor.getInventory().countItem(Items.PAPER) == count && team.getProgress(task(TASK)) == progress, "restart_loads_independently_saved_inventory_and_task_progress");
        var entry = service.entries(actor.createCommandSourceStack().withPermission(4)).stream()
                .filter(value -> value.id().toString().equals(state.get("entry").getAsString())).findFirst().orElseThrow();
        check(entry.inventoryBefore().contains("native-delivery") && entry.inventoryBefore().contains("Delivery parcel"), "restart_retains_original_component_sensitive_input");
        if (state.get("boundary").getAsString().equals("confirmed")) {
            check(entry.state() == QuestDeliveryJournal.State.CONFIRMED, "consistent_restart_retains_confirmed_native_outcome");
            actor.getInventory().setItem(0, parcel(2)); FtbQuestTasks.submit(actor, TASK, true);
            FtbQuestTasks.submit(actor, TASK, false);
            check(actor.getInventory().countItem(Items.PAPER) == 0 && team.getProgress(task(TASK)) == 5, "consistent_restart_accepts_only_the_remaining_native_quantity");
        } else {
            rejected(() -> FtbQuestTasks.submit(actor, TASK, false), "restart_rejects_uncertain_or_mismatched_delivery_without_reconsumption");
            check(actor.getInventory().countItem(Items.PAPER) == count && team.getProgress(task(TASK)) == progress, "restart_rejection_preserves_both_saved_native_owners");
            var reviewed = service.entries(actor.createCommandSourceStack().withPermission(4)).stream().filter(value -> value.id().equals(entry.id())).findFirst().orElseThrow();
            check(reviewed.state() == QuestDeliveryJournal.State.REVIEW, "restart_uncertainty_is_durably_reviewable");
            service.resolve(actor.createCommandSourceStack().withPermission(4), entry.id(), false, "Fixture operator inspected the independent saved states; no automatic item/progress repair requested");
            check(actor.getInventory().countItem(Items.PAPER) == count && team.getProgress(task(TASK)) == progress, "restart_resolution_has_no_implicit_native_side_effects");
        }
    }
    private static QuestDeliveryJournal.Entry latest() { return QuestDeliveryRecovery.current(actor.getServer()).entries(actor.createCommandSourceStack().withPermission(4)).stream().filter(entry -> entry.actorId().equals(actor.getUUID())).max(Comparator.comparingLong(QuestDeliveryJournal.Entry::sequence)).orElseThrow(); }
    private static ItemTask task(String id) { return (ItemTask)ServerQuestFile.getInstance().orElseThrow().getTask(FtbQuestTasks.parseId(id)); }
    static ItemStack parcel(int count) {
        var stack = new ItemStack(Items.PAPER, count); stack.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Delivery parcel"));
        var data = new CompoundTag(); data.putString("route", "native-delivery"); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data)); return stack;
    }
    private static void rejected(Runnable action, String name) { try { action.run(); throw new AssertionError(name); } catch (IllegalStateException expected) { check(true, name); } }
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); passed++; LOG.info("[DELIVERYAUDIT] PASS {}", name); }
    static EmbeddedChannel connect(ServerPlayer player) {
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) { Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.SERVERBOUND); connection.configurePacketHandler(channel.pipeline()); }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(player.registryAccess(), cookie.connectionType())));
        player.getServer().getPlayerList().placeNewPlayer(connection, player, cookie); return channel;
    }
}
