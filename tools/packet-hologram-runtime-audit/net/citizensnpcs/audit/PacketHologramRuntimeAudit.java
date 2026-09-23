package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.*;
import net.citizensnpcs.util.PacketMounts;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

/** Observe actual native pairing packets and replay mount references in connection order. */
@EventBusSubscriber(modid = "citizens")
public final class PacketHologramRuntimeAudit {
    private static boolean forced, done;
    private static int phase, nextTick, passed, deadline, riderTicks;
    private static NPCRegistry registry;
    private static NPC parent, baseline, nested, anchor, worldVehicle, playerRider;
    private static HologramTrait hologram;
    private static List<Entity> helpers = List.of(), previous = List.of();
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<Actor> actors = new ArrayList<>();
    private static Actor alice, bob;
    private static Entity filtered;
    private static Entity controlVehicle, controlPassenger;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); ServerLevel level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 1600; }
        try {
            for (Actor actor : actors) actor.pump();
            if (server.getTickCount() > deadline) throw new AssertionError("timed_out_phase_" + phase);
            if (!level.areEntitiesLoaded(0L) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
            if (server.getTickCount() < nextTick) return;
            if (phase == 1 && (!alice.watches(level, 0, 0) || !bob.watches(level, 0, 0))) return;
            nextTick = server.getTickCount() + 25;
            switch (phase++) {
                case 0 -> {
                    check(Files.isRegularFile(Path.of("packet-hologram-audit-fixture.txt")) && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
                    check(!Setting.PACKET_HOLOGRAMS.asBoolean(), "packet_holograms_default_false");
                    alice = actor(server, "MountAlice", 6); bob = actor(server, "MountBob", 8);
                    registry = CitizensAPI.createNamedNPCRegistry("packet-hologram-audit", new MemoryNPCDataStore());
                    baseline = npc(level, "baseline", EntityType.COW, false);
                    baseline.getOrAddTrait(HologramTrait.class).addLine("Default world helper");
                }
                case 1 -> {
                    check(alice.chunks.contains(0L) && bob.chunks.contains(0L), "native_chunk_delivery");
                    var initial = baseline.getTrait(HologramTrait.class).getHologramEntities();
                    check(initial.size() == 1 && initial.stream().allMatch(e -> level.getEntity(e.getId()) == e && !PacketNPC.isPacketEntity(e)), "false_setting_uses_world_helper");
                    configure(true); check(Setting.PACKET_HOLOGRAMS.asBoolean(), "real_config_reload_enables_packet_holograms");
                    parent = npc(level, "world parent", EntityType.COW, false); hologram = parent.getOrAddTrait(HologramTrait.class); addRenderers(hologram);
                    parent.data().set(NPC.Metadata.ALWAYS_USE_NAME_HOLOGRAM, true); parent.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, true);
                    controlVehicle = EntityType.COW.create(level); controlPassenger = EntityType.COW.create(level);
                    for (Entity control : List.of(controlVehicle, controlPassenger)) {
                        control.setPos(4, -60, 4); control.setNoGravity(true); ((Mob) control).setNoAi(true);
                        check(level.addFreshEntity(control), "non_npc_control_spawn");
                    }
                    check(controlPassenger.startRiding(controlVehicle, true), "non_npc_control_mount");
                }
                case 2 -> {
                    helpers = helpersOf(hologram);
                    check(helpers.size() == 10, "all_renderer_and_name_entities_created");
                    check(baseline.getTrait(HologramTrait.class).getHologramEntities().stream().allMatch(e -> level.getEntity(e.getId()) == e), "existing_helpers_keep_transport_until_rebuilt");
                    baseline.destroy();
                    check(hologram.getNameEntity() != null && PacketNPC.isPacketEntity(hologram.getNameEntity()), "native_nameplate_uses_packet_transport");
                    mounted(alice, List.of(controlPassenger), "non_npc_native_mount_unchanged");
                    check(controlPassenger.tickCount > 0, "non_npc_passenger_still_ticks");
                    var controlPacket = new ClientboundSetPassengersPacket(controlVehicle);
                    check(PacketMounts.rewrite(controlVehicle, alice.player, controlPacket) == controlPacket, "non_npc_mount_packet_untouched");
                    for (Entity helper : helpers) {
                        check(PacketNPC.isPacketEntity(helper) && level.getEntity(helper.getId()) == null, "helper_is_virtual_" + helper.getType());
                        check(alice.spawns(helper) == 1 && bob.spawns(helper) == 1, "helper_paired_once_" + helper.getType());
                    }
                    mounted(alice, helpers, "initial_alice"); mounted(bob, helpers, "initial_bob"); ordered("initial_pairing_order");
                    check(helpers.stream().filter(Entity::isPassenger).count() == 5, "five_mounted_renderer_entities");
                    check(personalized(alice, "MountAlice") && personalized(bob, "MountBob"), "packet_text_stays_personalized");
                    clear(); parent.getEntity().setPos(11, -60, 8);
                }
                case 3 -> {
                    positions(helpers, "moved_world_parent"); mounted(alice, helpers, "moved_world_parent_client"); ordered("moving_mount_order");
                    check(helpers.stream().allMatch(e -> e.tickCount == 0), "virtual_helpers_never_receive_world_ticks");
                    clear(); parent.getOrAddTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                }
                case 4 -> {
                    check(helpers.stream().allMatch(e -> bob.removals(e) == 1 && !bob.known.contains(e.getId())), "parent_filter_removes_all_helpers");
                    check(helpers.stream().allMatch(e -> alice.known.contains(e.getId())), "other_viewer_keeps_helpers");
                    ordered("hidden_hierarchy_order"); clear(); parent.removeTrait(PlayerFilter.class);
                }
                case 5 -> {
                    check(helpers.stream().allMatch(e -> bob.spawns(e) == 1), "unhide_rebuilds_native_pairing");
                    mounted(bob, helpers, "unhidden_mounts"); ordered("unhide_pairing_order");
                    filtered = helpers.stream().filter(e -> e instanceof Display.TextDisplay && e.getVehicle() == parent.getEntity()).findFirst().orElseThrow();
                    NPCRegistries.lookup(filtered).getOrAddTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                    var shared = new ClientboundSetPassengersPacket(parent.getEntity()); int[] original = shared.getPassengers().clone();
                    var projected = (ClientboundSetPassengersPacket) PacketMounts.rewrite(parent.getEntity(), bob.player, shared);
                    check(projected != shared && Arrays.stream(projected.getPassengers()).noneMatch(id -> id == filtered.getId()), "same_tick_filter_projects_fresh_packet");
                    check(Arrays.equals(original, shared.getPassengers()) && parent.getEntity().hasPassenger(filtered), "projection_preserves_shared_packet_and_mount_state");
                    check(PacketMounts.rewrite(parent.getEntity(), alice.player, shared) == shared, "unfiltered_viewer_keeps_original_packet");
                    clear();
                }
                case 6 -> {
                    check(!bob.known.contains(filtered.getId()) && alice.known.contains(filtered.getId()), "child_filter_affects_one_viewer");
                    NPCRegistries.lookup(filtered).removeTrait(PlayerFilter.class); clear();
                }
                case 7 -> {
                    check(bob.spawns(filtered) == 1, "child_visibility_reentry_pairs_again"); mounted(bob, helpers, "child_reentry_mounts"); ordered("child_reentry_order");
                    clear(); bob.player.setPos(100, -60, 4);
                }
                case 8 -> {
                    check(helpers.stream().noneMatch(e -> bob.known.contains(e.getId())), "range_exit_removes_helpers");
                    clear(); bob.player.setPos(8, -60, 4);
                }
                case 9 -> {
                    check(helpers.stream().allMatch(e -> bob.spawns(e) == 1), "range_return_pairs_helpers"); mounted(bob, helpers, "range_return_mounts"); ordered("range_return_order");
                    previous = helpers; clear(); parent.getOrAddTrait(PacketNPC.class);
                }
                case 10 -> {
                    helpers = helpersOf(hologram);
                    check(PacketNPC.isPacketEntity(parent.getEntity()) && level.getEntity(parent.getEntity().getId()) == null, "parent_converted_to_packet_transport");
                    check(helpers.size() == 10 && helpers.stream().noneMatch(previous::contains), "parent_conversion_recreates_helpers");
                    filtered = helpers.stream().filter(e -> e instanceof Display.TextDisplay && e.getVehicle() == parent.getEntity()).findFirst().orElseThrow();
                    mounted(alice, helpers, "packet_parent_mounts"); ordered("packet_parent_pairing_order");
                    nested = npc(level, "nested child first", EntityType.TEXT_DISPLAY, true);
                    anchor = npc(level, "nested vehicle second", EntityType.ARMOR_STAND, true);
                    anchor.getOrAddTrait(ArmorStandTrait.class).setAsPointEntity();
                    check(nested.getEntity().startRiding(anchor.getEntity(), true) && anchor.getEntity().startRiding(parent.getEntity(), true), "nested_mounts_established_child_created_first");
                    worldVehicle = npc(level, "native vehicle", EntityType.COW, false);
                    playerRider = npc(level, "virtual player", EntityType.PLAYER, true);
                    check(playerRider.getEntity().startRiding(worldVehicle.getEntity(), true), "virtual_player_mounts_world_vehicle");
                    riderTicks = playerRider.getEntity().tickCount; clear();
                }
                case 11 -> {
                    positions(List.of(nested.getEntity(), anchor.getEntity(), playerRider.getEntity()), "mixed_nested_positions");
                    mounted(alice, List.of(nested.getEntity(), anchor.getEntity(), playerRider.getEntity()), "mixed_nested_client"); ordered("child_before_vehicle_pairing_order");
                    check(playerRider.getEntity().tickCount == riderTicks, "virtual_player_excluded_from_native_passenger_ticks");
                    parent.getEntity().setPos(2, -60, 8); worldVehicle.getEntity().setPos(3, -60, 8); clear();
                }
                case 12 -> {
                    positions(helpers, "moved_packet_parent"); positions(List.of(nested.getEntity(), anchor.getEntity(), playerRider.getEntity()), "moved_mixed_hierarchy");
                    check(playerRider.getEntity().tickCount == riderTicks, "virtual_player_position_updates_without_simulation");
                    Entity root = parent.getEntity(); var riders = List.copyOf(root.getPassengers());
                    parent.addTrait(new PacketNPC());
                    check(parent.getEntity() == root && !root.isRemoved() && root.getPassengers().equals(riders)
                            && riders.stream().allMatch(e -> e.getVehicle() == root), "tracker_replacement_preserves_vehicle_and_passengers");
                    NPCRegistries.lookup(filtered).addTrait(new PacketNPC());
                    check(filtered.getVehicle() == root && root.hasPassenger(filtered), "rider_tracker_replacement_preserves_mount");
                    nested.getEntity().stopRiding(); clear();
                }
                case 13 -> {
                    mounted(alice, helpers, "replaced_tracker_client_mounts"); ordered("replacement_pairing_order");
                    check(!alice.mounts.containsKey(nested.getEntity().getId()) && !bob.mounts.containsKey(nested.getEntity().getId()), "native_dismount_reaches_both_viewers");
                    check(nested.getEntity().startRiding(worldVehicle.getEntity(), true), "virtual_child_remounts_world_vehicle"); clear();
                }
                case 14 -> {
                    mounted(alice, List.of(nested.getEntity()), "remount_client_graph"); positions(List.of(nested.getEntity()), "remount_server_position"); ordered("dismount_remount_order");
                    ServerPlayer stale = bob.player; stale.setHealth(0);
                    stale.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    bob.player = stale.connection.player; bob.player.setPos(8, -60, 4); clear();
                }
                case 15 -> {
                    mounted(bob, helpers, "respawn_rebuilds_mounts"); mounted(bob, List.of(nested.getEntity(), anchor.getEntity()), "respawn_nested_mounts"); ordered("respawn_pairing_order");
                    bob.player.teleportTo(server.getLevel(Level.NETHER), 8, 64, 4, Set.of(), 0, 0); clear();
                }
                case 16 -> {
                    check(helpers.stream().noneMatch(e -> bob.known.contains(e.getId())), "other_dimension_has_no_holograms");
                    bob.player.teleportTo(level, 8, -60, 4, Set.of(), 0, 0); clear();
                }
                case 17 -> {
                    mounted(bob, helpers, "dimension_return_mounts"); ordered("dimension_return_pairing_order");
                    previous = helpers; check(parent.despawn(DespawnReason.PENDING_RESPAWN), "packet_parent_despawn");
                    check(parent.spawn(new Location(level, 4, -60, 4)), "packet_parent_respawn"); clear();
                }
                case 18 -> {
                    helpers = helpersOf(hologram);
                    check(helpers.size() == 10 && helpers.stream().noneMatch(previous::contains), "respawn_new_helper_entities");
                    check(previous.stream().noneMatch(e -> alice.known.contains(e.getId()) || bob.known.contains(e.getId())), "old_helper_ids_removed");
                    mounted(alice, helpers, "parent_respawn_mounts"); ordered("parent_respawn_pairing_order");
                    parent.destroy(); configure(false); check(!Setting.PACKET_HOLOGRAMS.asBoolean(), "real_config_reload_disables_packet_holograms");
                    baseline = npc(level, "world helpers restored", EntityType.COW, false);
                    addRenderers(baseline.getOrAddTrait(HologramTrait.class)); clear();
                }
                case 19 -> {
                    var restored = baseline.getTrait(HologramTrait.class).getHologramEntities();
                    check(restored.size() == 9 && restored.stream().allMatch(e -> level.getEntity(e.getId()) == e && !PacketNPC.isPacketEntity(e)), "false_setting_restores_all_world_renderers");
                    mounted(alice, List.copyOf(restored), "world_renderer_mounts"); ordered("restored_world_pairing_order");
                    for (NPC npc : npcs) if (registry.getByUniqueId(npc.getUniqueId()) == npc) npc.destroy();
                    controlVehicle.discard(); controlPassenger.discard();
                    check(helpers.stream().allMatch(Entity::isRemoved), "destroy_removes_virtual_helpers");
                    for (Actor actor : actors) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
                    var field = PacketMounts.class.getDeclaredField("views"); field.setAccessible(true);
                    check(((Map<?, ?>) field.get(null)).isEmpty(), "logout_releases_pairing_state");
                    LoggerFactory.getLogger("citizens").info("[PACKETHOLOGRAMAUDIT] COMPLETE {} checks", passed); done = true;
                }
            }
        } catch (Throwable failure) { done = true; LoggerFactory.getLogger("citizens").error("[PACKETHOLOGRAMAUDIT] FAILED phase " + phase, failure); }
        if (done) {
            try {
                configure(false);
                if (controlVehicle != null) controlVehicle.discard(); if (controlPassenger != null) controlPassenger.discard();
                for (NPC npc : npcs) if (registry.getByUniqueId(npc.getUniqueId()) == npc) npc.destroy();
                for (Actor actor : actors) { if (server.getPlayerList().getPlayer(actor.player.getUUID()) == actor.player) server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[PACKETHOLOGRAMAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    private static NPC npc(ServerLevel level, String name, EntityType<?> type, boolean packet) {
        NPC npc = registry.createNPC(type, name); npcs.add(npc); npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        if (type == EntityType.PLAYER) { npc.getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false); npc.getTrait(SkinTrait.class).setShouldUpdateSkins(false); }
        if (packet) npc.getOrAddTrait(PacketNPC.class);
        check(npc.spawn(new Location(level, 4, -60, 4)), name + "_spawn");
        npc.getEntity().setNoGravity(true); if (npc.getEntity() instanceof Mob mob) mob.setNoAi(true);
        return npc;
    }
    private static List<Entity> helpersOf(HologramTrait h) {
        List<Entity> entities = new ArrayList<>(h.getHologramEntities());
        if (h.getNameEntity() != null) entities.add(h.getNameEntity());
        return List.copyOf(entities);
    }
    private static void configure(boolean enabled) {
        Citizens citizens = Citizens.getInstance();
        YamlStorage config = new YamlStorage(new java.io.File(citizens.getDataFolder(), "config.yml"));
        config.load(); config.getKey("").setBoolean("npc.use-packet-holograms", enabled); config.save(); citizens.getSettings().reload();
    }
    private static void addRenderers(HologramTrait h) {
        h.addLine("Hello <player>", new HologramTrait.TextDisplayRenderer());
        h.addLine("Hello <player>", new HologramTrait.ArmorstandRenderer());
        h.addLine("Hello <player>", new HologramTrait.AreaEffectCloudRenderer());
        h.addLine("Hello <player>", new HologramTrait.ArmorstandVehicleRenderer());
        h.addLine("Hello <player>", new HologramTrait.TextDisplayVehicleRenderer());
        h.addLine("Hello <player>", new HologramTrait.InteractionVehicleRenderer());
        h.addLine("<item:stone>"); h.addLine("<item:diamond>", new HologramTrait.ItemDisplayRenderer());
    }
    private static void positions(List<Entity> entities, String label) {
        for (Entity entity : entities) if (entity.getVehicle() != null) {
            Entity vehicle = entity.getVehicle();
            var expected = vehicle.getPassengerRidingPosition(entity).subtract(entity.getVehicleAttachmentPoint(vehicle));
            check(entity.position().distanceTo(expected) < 0.00001, label + "_" + entity.getType() + "_" + entity.position() + "_" + expected);
        }
    }
    private static void mounted(Actor actor, List<Entity> entities, String label) {
        actor.pump();
        for (Entity entity : entities) if (entity.getVehicle() != null)
            check(Objects.equals(actor.mounts.get(entity.getId()), entity.getVehicle().getId()), label + "_" + entity.getType());
    }
    private static void ordered(String label) { check(actors.stream().allMatch(a -> a.invalid.isEmpty()), label + "_" + actors.stream().map(a -> a.invalid).toList()); }
    private static boolean personalized(Actor actor, String expected) {
        return actor.packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket).map(p -> (ClientboundSetEntityDataPacket) p)
                .flatMap(p -> p.packedItems().stream()).anyMatch(v -> v.value() instanceof net.minecraft.network.chat.Component c && c.getString().contains(expected));
    }
    private static void clear() { for (Actor actor : actors) { actor.pump(); actor.packets.clear(); } }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[PACKETHOLOGRAMAUDIT] PASS {}", label); }
    private static Actor actor(MinecraftServer server, String name, double x) {
        var defaults = ClientInformation.createDefault();
        var information = new ClientInformation(defaults.language(), 6, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing());
        Actor actor = new Actor(); actor.player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), information);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("packet-hologram-capture", new ChannelOutboundHandlerAdapter() {
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
        final List<Packet<?>> packets = new ArrayList<>();
        final Set<Long> chunks = new HashSet<>();
        final Set<Integer> known = new HashSet<>();
        final Map<Integer, Integer> mounts = new HashMap<>();
        final List<String> invalid = new ArrayList<>();
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(packet);
            if (packet instanceof ClientboundRespawnPacket) { known.clear(); mounts.clear(); chunks.clear(); }
            if (packet instanceof ClientboundAddEntityPacket add) known.add(add.getId());
            if (packet instanceof ClientboundRemoveEntitiesPacket remove) for (int id : remove.getEntityIds()) {
                known.remove(id); mounts.remove(id); mounts.entrySet().removeIf(e -> e.getValue() == id);
            }
            if (packet instanceof ClientboundSetPassengersPacket passengers) {
                if (!known.contains(passengers.getVehicle()) && passengers.getVehicle() != player.getId()) invalid.add("unknown_vehicle_" + passengers.getVehicle());
                mounts.entrySet().removeIf(e -> e.getValue() == passengers.getVehicle());
                for (int id : passengers.getPassengers()) {
                    if (!known.contains(id) && id != player.getId()) invalid.add("unknown_passenger_" + id);
                    mounts.put(id, passengers.getVehicle());
                }
            }
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) chunks.add(ChunkPos.asLong(chunk.getX(), chunk.getZ()));
            if (packet instanceof ClientboundChunkBatchFinishedPacket) pendingBatches++;
        }
        void pump() {
            channel.runPendingTasks();
            while (pendingBatches > 0) { pendingBatches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            Object output; while ((output = channel.readOutbound()) != null) ReferenceCountUtil.release(output);
        }
        boolean watches(ServerLevel level, int x, int z) throws ReflectiveOperationException {
            var method = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("isChunkTracked", ServerPlayer.class, int.class, int.class);
            method.setAccessible(true); return (boolean) method.invoke(level.getChunkSource().chunkMap, player, x, z);
        }
        long spawns(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundAddEntityPacket add && add.getId() == e.getId()).count(); }
        long removals(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundRemoveEntitiesPacket remove && remove.getEntityIds().contains(e.getId())).count(); }
    }
}
