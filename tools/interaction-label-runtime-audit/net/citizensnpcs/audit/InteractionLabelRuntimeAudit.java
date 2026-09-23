package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.trait.*;
import net.citizensnpcs.util.HologramMetadata;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData.DataValue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

/** Captures real tracking packets and replays metadata through the same native callback used by the client. */
@EventBusSubscriber(modid = "citizens")
public final class InteractionLabelRuntimeAudit {
    private static boolean forced, done;
    private static int phase, nextTick, deadline, passed;
    private static NPCRegistry registry;
    private static NPC parent;
    private static NPC nestedRider;
    private static HologramTrait hologram;
    private static Actor alice, bob;
    private static final List<Actor> actors = new ArrayList<>();
    private static List<Entity> helpers = List.of(), old = List.of();
    private static double spacing = 0.4, bottom;
    private static float unscaledHeight;
    private static int worldTicks, nestedTicks, tickSnapshot;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); ServerLevel level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 1400; }
        try {
            for (Actor actor : actors) actor.pump();
            if (server.getTickCount() > deadline) throw new AssertionError("timed_out_phase_" + phase);
            if (!level.areEntitiesLoaded(0L) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
            if (server.getTickCount() < nextTick) return;
            if (phase == 1 && (!alice.watches(level) || !bob.watches(level))) return;
            nextTick = server.getTickCount() + 30;
            switch (phase++) {
                case 0 -> {
                    check(Files.isRegularFile(Path.of("interaction-label-audit-fixture.txt")) && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
                    alice = actor(server, "LabelAlice", 6); bob = actor(server, "LabelBob", 8);
                    registry = CitizensAPI.createNamedNPCRegistry("interaction-label-audit", new MemoryNPCDataStore());
                }
                case 1 -> {
                    parent = registry.createNPC(EntityType.COW, "Label parent"); parent.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
                    hologram = parent.getOrAddTrait(HologramTrait.class); hologram.setLineHeight(spacing);
                    hologram.addLine("World <player>", new LabelRenderer(false));
                    hologram.addLine("Packet <player>", new LabelRenderer(true));
                    clear(); check(parent.spawn(new Location(level, 4, -60, 4)), "parent_spawn"); parent.getEntity().setNoGravity(true);
                }
                case 2 -> {
                    helpers = List.copyOf(hologram.getHologramEntities()); check(helpers.size() == 2, "two_interaction_helpers");
                    check(level.getEntity(helpers.get(0).getId()) == helpers.get(0) && PacketNPC.isPacketEntity(helpers.get(1)), "world_and_virtual_helpers");
                    verify("initial", true); packetOrdering(level); initializerFailure(level);
                    unscaledHeight = parent.getEntity().getBbHeight(); clear();
                    parent.getEntity().setPos(10, -60, 7);
                }
                case 3 -> {
                    verify("moving_parent", false); clear();
                    ((LivingEntity) parent.getEntity()).getAttribute(Attributes.SCALE).setBaseValue(1.8);
                }
                case 4 -> {
                    check(close(parent.getEntity().getBbHeight(), unscaledHeight * 1.8), "native_scale_changes_parent_dimensions");
                    verify("scaled_parent", true); clear();
                    parent.getOrAddTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                    hologram.setLine(0, "Edited <player>");
                }
                case 5 -> {
                    check(helpers.stream().allMatch(e -> bob.metadata(e).isEmpty()), "hidden_viewer_gets_no_shape_refresh");
                    check(helpers.stream().allMatch(e -> !bob.replicas.containsKey(e.getId())), "hidden_helpers_unpaired");
                    clear(); parent.removeTrait(PlayerFilter.class);
                }
                case 6 -> {
                    verify("visible_again", false);
                    check(helpers.stream().allMatch(e -> !bob.metadata(e).isEmpty()), "returning_viewer_gets_shape_metadata"); clear();
                    old = helpers; spacing = 0.65; hologram.setLineHeight(spacing); bottom = -1.25; hologram.setMargin(0, "bottom", bottom);
                }
                case 7 -> {
                    helpers = List.copyOf(hologram.getHologramEntities()); check(helpers.stream().noneMatch(old::contains), "margin_rebuilds_helpers");
                    verify("negative_offsets", true); clear(); bob.player.setPos(100, -60, 4);
                    HologramMetadata.refresh(List.copyOf(hologram.getHologramRenderers()).get(1));
                    check(bob.metadata(helpers.get(1)).isEmpty(), "same_tick_range_exit_excludes_shape_refresh");
                }
                case 8 -> {
                    check(!bob.replicas.containsKey(helpers.get(1).getId()), "virtual_range_exit_unpairs"); clear(); bob.player.setPos(8, -60, 4);
                }
                case 9 -> {
                    verify("range_return", false); clear(); parent.setEntityType(EntityType.CAMEL);
                }
                case 10 -> {
                    check(parent.getEntity().getType() == EntityType.CAMEL, "native_camel_parent");
                    parent.getEntity().setNoGravity(true); helpers = List.copyOf(hologram.getHologramEntities());
                    verify("camel_native_seats", true); clear(); old = helpers; parent.getOrAddTrait(PacketNPC.class);
                }
                case 11 -> {
                    helpers = List.copyOf(hologram.getHologramEntities()); check(helpers.stream().noneMatch(old::contains), "virtual_parent_rebuilds_helpers");
                    check(PacketNPC.isPacketEntity(parent.getEntity()), "parent_is_virtual"); verify("virtual_parent", true); clear();
                    nestedRider = registry.createNPC(EntityType.COW, "Native nested rider");
                    check(nestedRider.spawn(new Location(level, 4, -60, 4)), "nested_world_rider_spawn");
                    nestedRider.getEntity().setNoGravity(true);
                    check(nestedRider.getEntity().startRiding(helpers.get(1), true), "world_rider_mounts_virtual_helper");
                    worldTicks = helpers.get(0).tickCount; nestedTicks = nestedRider.getEntity().tickCount; tickSnapshot = server.getTickCount();
                    parent.getEntity().setPos(3, -60, 5);
                }
                case 12 -> {
                    verify("moving_virtual_parent", false); clear();
                    int elapsed = server.getTickCount() - tickSnapshot;
                    check(helpers.get(0).tickCount - worldTicks == elapsed && nestedRider.getEntity().tickCount - nestedTicks == elapsed, "world_passengers_tick_exactly_once_in_virtual_hierarchy");
                    check(helpers.get(1).tickCount == 0 && parent.getEntity().tickCount == 0, "virtual_entities_are_not_simulated");
                    worldTicks = helpers.get(0).tickCount; nestedTicks = nestedRider.getEntity().tickCount;
                    server.tickRateManager().setFrozen(true);
                }
                case 13 -> {
                    check(helpers.get(0).tickCount == worldTicks && nestedRider.getEntity().tickCount == nestedTicks, "frozen_virtual_root_does_not_tick_world_passengers");
                    server.tickRateManager().setFrozen(false); nestedRider.destroy(); nestedRider = null;
                    check(helpers.stream().allMatch(e -> alice.metadata(e).isEmpty() && bob.metadata(e).isEmpty()), "unchanged_shape_not_rebroadcast");
                    old = helpers; check(parent.despawn(net.citizensnpcs.api.event.DespawnReason.PENDING_RESPAWN), "parent_despawn");
                    check(parent.spawn(new Location(level, 4, -60, 4)), "parent_respawn"); clear();
                }
                case 14 -> {
                    helpers = List.copyOf(hologram.getHologramEntities()); check(helpers.stream().noneMatch(old::contains), "respawn_rebuilds_helpers");
                    verify("respawn", true); old = helpers; parent.destroy(); parent = null;
                }
                case 15 -> {
                    check(old.stream().allMatch(Entity::isRemoved), "destroy_removes_helpers");
                    check(old.stream().allMatch(e -> !alice.replicas.containsKey(e.getId()) && !bob.replicas.containsKey(e.getId())), "destroy_removes_viewer_entities");
                    LoggerFactory.getLogger("citizens").info("[INTERACTIONLABELAUDIT] COMPLETE {} checks", passed); done = true;
                }
            }
        } catch (Throwable failure) { done = true; LoggerFactory.getLogger("citizens").error("[INTERACTIONLABELAUDIT] FAILED phase " + phase, failure); }
        if (done) {
            try { server.tickRateManager().setFrozen(false); if (nestedRider != null) nestedRider.destroy(); if (parent != null) parent.destroy(); for (Actor actor : actors) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); } }
            catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[INTERACTIONLABELAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    private static final class LabelRenderer extends HologramTrait.InteractionVehicleRenderer {
        private final boolean packet;
        LabelRenderer(boolean packet) { this.packet = packet; }
        @Override protected void configureHologram(NPC child, NPC parent) {
            super.configureHologram(child, parent); if (packet) child.getOrAddTrait(PacketNPC.class);
        }
        @Override public boolean isSneaking(NPC helper, ServerPlayer viewer) { return viewer == alice.player; }
    }
    private static void verify(String label, boolean requireMetadata) {
        Entity vehicle = parent.getEntity();
        for (int index = 0; index < helpers.size(); index++) {
            Entity helper = helpers.get(index); double target = vehicle.getY() + vehicle.getBbHeight() + bottom + index * spacing + 0.5;
            check(helper.getVehicle() == vehicle && helper instanceof Interaction, label + "_keeps_native_interaction_mount");
            check(close(helper.getY() + helper.getAttachments().get(EntityAttachment.NAME_TAG, 0, 0).y + 0.5, target), label + "_server_label_height");
            check(helper.getPose() == Pose.STANDING, label + "_shared_pose_unchanged");
            for (Actor actor : actors) {
                actor.pump(); Interaction replica = actor.replicas.get(helper.getId()); check(replica != null, label + "_viewer_has_entity");
                double ridingY = vehicle.getPassengerRidingPosition(helper).y - replica.getVehicleAttachmentPoint(vehicle).y;
                check(close(ridingY + replica.getAttachments().get(EntityAttachment.NAME_TAG, 0, 0).y + 0.5, target), label + "_native_replayed_label_height");
                check(replica.getPose() == Pose.STANDING && replica.isShiftKeyDown() == (actor == alice), label + "_viewer_flags_independent_of_real_pose");
                check(replica.getCustomName() != null && replica.getCustomName().getString().contains(actor.player.getGameProfile().getName()), label + "_viewer_text");
                List<ClientboundSetEntityDataPacket> metadata = actor.metadata(helper);
                if (requireMetadata) check(!metadata.isEmpty(), label + "_metadata_received");
                for (var packet : metadata) {
                    int height = -1, width = -1, pose = -1;
                    for (int i = 0; i < packet.packedItems().size(); i++) {
                        int id = packet.packedItems().get(i).id();
                        if (id == Interaction.DATA_HEIGHT_ID.id()) height = i;
                        if (id == Interaction.DATA_WIDTH_ID.id()) width = i;
                        if (id == Entity.DATA_POSE.id()) pose = i;
                    }
                    check(height >= 0 && width >= 0 && pose > height && pose > width, label + "_shape_before_pose");
                    float sentHeight = (Float) packet.packedItems().get(height).value();
                    check(close(ridingY + sentHeight + 0.5, target), label + "_every_metadata_has_current_height");
                }
            }
        }
    }
    private static boolean close(double left, double right) { return Math.abs(left - right) < 0.00001; }
    private static void initializerFailure(ServerLevel level) {
        NPC probe = registry.createNPC(EntityType.INTERACTION, "Initializer probe");
        boolean[] reject = {true}; Entity[] failed = {null}; var passengers = List.copyOf(parent.getEntity().getPassengers());
        probe.data().set(NPC.Metadata.HOLOGRAM_RENDERER, new HologramTrait.InteractionVehicleRenderer() {
            @Override public void onPreSpawn(NPC helper) {
                if (!reject[0]) return;
                failed[0] = helper.getEntity(); failed[0].startRiding(parent.getEntity(), true);
                throw new IllegalStateException("expected_initializer_failure");
            }
        });
        try {
            probe.spawn(new Location(level, 4, -60, 4)); throw new AssertionError("initializer_failure_not_propagated");
        } catch (IllegalStateException expected) { check(expected.getMessage().equals("expected_initializer_failure"), "initializer_failure_propagates"); }
        check(probe.getEntity() == null && failed[0].isRemoved() && parent.getEntity().getPassengers().equals(passengers), "initializer_failure_discards_entity_and_mount");
        check(actors.stream().noneMatch(actor -> actor.replicas.containsKey(failed[0].getId())), "initializer_failure_does_not_pair");
        reject[0] = false;
        check(probe.spawn(new Location(level, 4, -60, 4)) && probe.getEntity() != failed[0], "initializer_failure_allows_fresh_retry");
        probe.destroy();
    }
    private static void packetOrdering(ServerLevel level) {
        Entity helper = helpers.getFirst();
        var pose = DataValue.create(Entity.DATA_POSE, Pose.CROUCHING);
        var height = DataValue.create(Interaction.DATA_HEIGHT_ID, 2.75f);
        var width = DataValue.create(Interaction.DATA_WIDTH_ID, 0.125f);
        var raw = new ClientboundSetEntityDataPacket(helper.getId(), List.of(pose, height, width));
        var rewritten = (ClientboundSetEntityDataPacket) HologramMetadata.rewrite(helper, alice.player, raw);
        Interaction replica = EntityType.INTERACTION.create(level); replica.getEntityData().assignValues(rewritten.packedItems());
        check(replica.getPose() == Pose.CROUCHING && close(replica.getAttachments().get(EntityAttachment.NAME_TAG, 0, 0).y, 2.75), "incoming_real_pose_and_shape_preserved_in_native_replay");
        check(close(replica.getBbWidth(), 0.125) && raw.packedItems().equals(List.of(pose, height, width)), "incoming_width_and_packet_immutable");
        check(helper.getPose() == Pose.STANDING && helper.getEntityData().get(Interaction.DATA_HEIGHT_ID) != 2.75f, "packet_overlay_preserves_shared_shape");
        var ordinary = EntityType.INTERACTION.create(level);
        var control = new ClientboundSetEntityDataPacket(ordinary.getId(), List.of(pose, height, width));
        check(HologramMetadata.rewrite(ordinary, alice.player, control) == control, "ordinary_interaction_untouched");
    }
    private static void clear() { for (Actor actor : actors) { actor.pump(); actor.packets.clear(); } }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[INTERACTIONLABELAUDIT] PASS {}", label); }
    private static Actor actor(MinecraftServer server, String name, double x) {
        var defaults = ClientInformation.createDefault();
        var information = new ClientInformation(defaults.language(), 6, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing());
        Actor actor = new Actor(); actor.player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), information);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("interaction-label-capture", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof Packet<?> packet) actor.capture(packet);
                        super.write(context, message, promise);
                    }
                });
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(actor.player.getGameProfile(), 0, information, false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, actor.player, cookie);
        actor.player.setPos(x, -60, 4); actors.add(actor); return actor;
    }

    private static final class Actor {
        ServerPlayer player; EmbeddedChannel channel; int pendingBatches;
        final Map<Integer, Interaction> replicas = new HashMap<>();
        final List<Packet<?>> packets = new ArrayList<>();
        final Set<Long> chunks = new HashSet<>();
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(packet);
            if (packet instanceof ClientboundAddEntityPacket spawn && spawn.getType() == EntityType.INTERACTION)
                replicas.put(spawn.getId(), EntityType.INTERACTION.create(player.serverLevel()));
            if (packet instanceof ClientboundSetEntityDataPacket data && replicas.containsKey(data.id()))
                replicas.get(data.id()).getEntityData().assignValues(data.packedItems());
            if (packet instanceof ClientboundRemoveEntitiesPacket remove)
                for (int id : remove.getEntityIds()) replicas.remove(id);
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) chunks.add(ChunkPos.asLong(chunk.getX(), chunk.getZ()));
            if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingBatches++;
        }
        void pump() {
            channel.runPendingTasks();
            while (pendingBatches > 0) { pendingBatches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            Object output; while ((output = channel.readOutbound()) != null) ReferenceCountUtil.release(output);
        }
        boolean watches(ServerLevel level) throws ReflectiveOperationException {
            var method = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("isChunkTracked", ServerPlayer.class, int.class, int.class);
            method.setAccessible(true);
            return (boolean) method.invoke(level.getChunkSource().chunkMap, player, 0, 0);
        }
        List<ClientboundSetEntityDataPacket> metadata(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket data && data.id() == e.getId()).map(p -> (ClientboundSetEntityDataPacket) p).toList(); }
    }
}
