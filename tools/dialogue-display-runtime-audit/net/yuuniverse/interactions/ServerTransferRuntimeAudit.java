package net.yuuniverse.interactions;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.payload.MinecraftRegisterPayload;
import net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.yuuniverse.interactions.DialogueDisplayRuntimeAudit.AuditPlayer;
import org.slf4j.LoggerFactory;

/** Advertised Bungee channels on actual OTHER connections, without the all-channel mock negotiation. */
final class ServerTransferRuntimeAudit {
    private static final Component NPC = Component.literal("Transfer speaker");
    private static final String PAYMENT = "remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%";
    private final MinecraftServer server;
    private final BiFunction<String, UUID, AuditPlayer> factory;
    private final Actions actions = new Actions(new ItemLibrary(), new Economy());
    private AuditPlayer alice, bob, delayedPlayer, revokedPlayer, reconnectedPlayer;
    private ActionExecution delayed, revoked, reconnected;
    private int elapsed, passed;

    ServerTransferRuntimeAudit(MinecraftServer server, BiFunction<String, UUID, AuditPlayer> factory) {
        this.server = server; this.factory = factory;
    }

    void start() throws Exception {
        alice = player("TransferAlice"); bob = player("TransferBob");
        check(!available(alice) && !available(bob), "local_codec_does_not_advertise_a_proxy");
        reject("Lobby", "missing_channel_rejects_before_payment");
        incoming(alice, new MinecraftRegisterPayload(Set.of(ResourceLocation.parse("other:channel"))));
        check(!available(alice), "unrelated_advertisement_is_not_a_transfer_channel");
        reject("Lobby", "unrelated_channel_rejects_before_payment");
        register(alice); register(bob);
        check(available(alice) && available(bob), "actual_register_packet_enables_each_connection");
        for (String target : List.of("Lobby", "  Lobby  ", "大厅;Second", "host:25565", "\u0000😀", "")) {
            alice.clear(); bob.clear();
            check(actions.runAll(List.of("send_to_server: " + target), alice, NPC), "opaque_target_action_accepted");
            check(targets(alice).equals(List.of(target)) && targets(bob).isEmpty(), "exact_opaque_target_is_private");
            verifyWire(requests(alice).getFirst(), target);
        }
        alice.clear(); alice.setExperienceLevels(12);
        check(actions.runAll(List.of("send_to_server: Realm-%player_level% "), alice, NPC)
                && targets(alice).equals(List.of("Realm-12 ")), "placeholders_preserve_destination_suffix");
        alice.clear(); alice.getInventory().clearContent(); alice.getInventory().add(new ItemStack(Items.PAPER));
        check(actions.runAll(List.of(PAYMENT, "send_to_server: UnconfiguredOpaqueName", "player_command_as_op: give @s minecraft:diamond 1"), alice, NPC)
                && targets(alice).equals(List.of("UnconfiguredOpaqueName")) && alice.getInventory().countItem(Items.PAPER) == 0
                && alice.getInventory().countItem(Items.DIAMOND) == 1, "request_dispatch_continues_following_actions_without_ack");
        reject("x".repeat(32756), "oversized_request_rejects_before_payment");
        var prepared = ServerTransfers.prepare("Lobby");
        unregister(alice); alice.clear();
        boolean refused = false;
        try { ServerTransfers.send(alice, prepared); } catch (IllegalStateException expected) { refused = true; }
        check(refused && targets(alice).isEmpty(), "send_rechecks_live_channel_after_prepare");
        reject("Lobby", "actual_unregister_revokes_transfer_before_payment");
        check(available(bob), "channel_revocation_is_connection_scoped");
        checkFailedSession();

        delayedPlayer = player("TransferLater"); revokedPlayer = player("TransferRevoked");
        register(delayedPlayer); register(revokedPlayer);
        delayedPlayer.getInventory().add(new ItemStack(Items.PAPER)); revokedPlayer.getInventory().add(new ItemStack(Items.PAPER));
        delayedPlayer.setExperienceLevels(1);
        delayed = actions.executeAll(List.of("wait_ticks: 2", PAYMENT, "send_to_server: Realm-%player_level%"), delayedPlayer, NPC);
        revoked = actions.executeAll(List.of("wait_ticks: 2", PAYMENT, "send_to_server: Lobby", "player_command_as_op: give @s minecraft:diamond 1"), revokedPlayer, NPC);
        delayedPlayer.setExperienceLevels(9); unregister(revokedPlayer);
        check(delayed.pending() && revoked.pending() && targets(delayedPlayer).isEmpty(), "accepted_waits_do_not_emit_early");

        AuditPlayer oldLogin = player("TransferLogin"); register(oldLogin);
        reconnected = actions.executeAll(List.of("wait_ticks: 2", "send_to_server: Lobby"), oldLogin, NPC);
        UUID id = oldLogin.getUUID(); server.getPlayerList().remove(oldLogin);
        reconnectedPlayer = factory.apply("TransferLogin", id);
        check(!available(reconnectedPlayer), "same_uuid_new_connection_has_no_inherited_channel");
        register(reconnectedPlayer);
        check(available(reconnectedPlayer), "new_login_can_advertise_its_own_channel");
        AuditPlayer closed = player("TransferClosed"); register(closed); closed.channel.close();
        refused = false;
        try { ServerTransfers.checkAvailable(closed); } catch (IllegalStateException expected) { refused = true; }
        check(refused, "closed_connection_cannot_transfer");
    }

    boolean tick(ServerTickEvent.Post event) {
        actions.tick(event.getServer()); elapsed++;
        if (elapsed == 1) check(targets(delayedPlayer).isEmpty() && delayed.pending(), "transfer_wait_yields_until_deadline");
        if (elapsed < 2) return false;
        check(delayed.result() == ActionExecution.Result.SUCCEEDED && targets(delayedPlayer).equals(List.of("Realm-9"))
                && delayedPlayer.getInventory().countItem(Items.PAPER) == 0, "resumed_transfer_reads_current_placeholder_and_channel");
        check(revoked.result() == ActionExecution.Result.FAILED && targets(revokedPlayer).isEmpty()
                && revokedPlayer.getInventory().countItem(Items.PAPER) == 1 && revokedPlayer.getInventory().countItem(Items.DIAMOND) == 0,
                "revoked_channel_rejects_resumed_tail_before_payment");
        check(reconnected.result() == ActionExecution.Result.CANCELLED && targets(reconnectedPlayer).isEmpty(),
                "new_advertisement_cannot_inherit_old_connection_batch");
        LoggerFactory.getLogger("interactions").info("[TRANSFERAUDIT] COMPLETE {} checks", passed);
        return true;
    }

    void close() { actions.reset(true); }

    private void checkFailedSession() {
        var progress = new ProgressStore(new java.io.File("config/transfer-audit-players"));
        Session.Engine engine = new Session.Engine() {
            public Actions actions() { return actions; }
            public ProgressStore progress() { return progress; }
            public DialogueSettings settings() { return DialogueSettings.DEFAULT; }
            public DialogueMessages messages() { return DialogueMessages.DEFAULT; }
        };
        Conversation story = new Conversation(); story.source = "transfer-failure.yml"; story.saveProgress = true;
        var node = new Conversation.Node("conversation1"); story.nodes.put(node.key, node);
        var line = new Conversation.Line(); line.key = "dialogue1"; line.time = 0; line.saveToPlayer = true; line.text.add("Transfer");
        line.actions.addAll(List.of(PAYMENT, "send_to_server: Lobby")); node.lines.add(line);
        alice.getInventory().clearContent(); alice.getInventory().add(new ItemStack(Items.PAPER));
        Session session = new Session(engine, story, node, alice, null); session.tick();
        check(session.isFinished() && !progress.hasSeen(alice.getUUID(), "transfer-failure.conversation1.dialogue1")
                && !progress.hasSeen(alice.getUUID(), "transfer-failure.conversation1.completed")
                && alice.getInventory().countItem(Items.PAPER) == 1, "unavailable_transfer_never_saves_session_success");
        session.end(false);
        story.source = "transfer-success.yml";
        bob.clear(); bob.getInventory().clearContent(); bob.getInventory().add(new ItemStack(Items.PAPER));
        Session accepted = new Session(engine, story, node, bob, null);
        for (int i = 0; i < 3 && !accepted.isFinished(); i++) accepted.tick();
        check(accepted.isFinished() && progress.hasSeen(bob.getUUID(), "transfer-success.conversation1.dialogue1")
                && progress.hasSeen(bob.getUUID(), "transfer-success.conversation1.completed")
                && targets(bob).equals(List.of("Lobby")), "emitted_request_completes_local_session_without_proxy_ack");
        accepted.end(false);
    }

    private void reject(String target, String name) {
        alice.clear(); alice.getInventory().clearContent(); alice.getInventory().add(new ItemStack(Items.PAPER));
        check(!actions.runAll(List.of(PAYMENT, "send_to_server: " + target, "player_command_as_op: give @s minecraft:diamond 1"), alice, NPC)
                && targets(alice).isEmpty() && alice.getInventory().countItem(Items.PAPER) == 1
                && alice.getInventory().countItem(Items.DIAMOND) == 0, name);
    }

    private void verifyWire(ClientboundCustomPayloadPacket packet, String target) throws Exception {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess(), ConnectionType.OTHER);
        try {
            ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(buffer, packet);
            int start = buffer.readerIndex();
            check(buffer.readResourceLocation().equals(ServerTransfers.CHANNEL), "game_packet_encodes_bungee_channel");
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
            try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                check(input.readUTF().equals("Connect") && input.readUTF().equals(target) && input.read() == -1,
                        "game_packet_contains_only_exact_modified_utf_fields");
            }
            buffer.readerIndex(start);
            var decoded = ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(buffer);
            check(decoded.payload() instanceof ServerTransfers.Request request && request.target().equals(target) && !buffer.isReadable(),
                    "registered_game_codec_round_trips_transfer");
        } finally { buffer.release(); }
    }

    private static void incoming(AuditPlayer player, CustomPacketPayload payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerboundCustomPayloadPacket.STREAM_CODEC.encode(buffer, new ServerboundCustomPayloadPacket(payload));
            player.connection.handleCustomPayload(ServerboundCustomPayloadPacket.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    private static void register(AuditPlayer player) { incoming(player, new MinecraftRegisterPayload(Set.of(ServerTransfers.CHANNEL))); }
    private static void unregister(AuditPlayer player) { incoming(player, new MinecraftUnregisterPayload(Set.of(ServerTransfers.CHANNEL))); }
    private static boolean available(AuditPlayer player) {
        return NetworkRegistry.hasChannel(player.connection.getConnection(), ConnectionProtocol.PLAY, ServerTransfers.CHANNEL);
    }
    private static List<ClientboundCustomPayloadPacket> requests(AuditPlayer player) {
        player.pump(); return player.packets.stream().filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(ClientboundCustomPayloadPacket.class::cast).filter(packet -> packet.payload().type().id().equals(ServerTransfers.CHANNEL)).toList();
    }
    private static List<String> targets(AuditPlayer player) {
        return requests(player).stream().map(packet -> ((ServerTransfers.Request) packet.payload()).target()).toList();
    }
    private AuditPlayer player(String name) { return factory.apply(name, UUID.randomUUID()); }
    private void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("interactions").info("[TRANSFERAUDIT] PASS {}", name);
    }
}
