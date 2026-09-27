package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCTeleportEvent;
import net.citizensnpcs.api.event.NPCRemoveEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.TeleportCause;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.*;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class HologramTransferRuntimeAudit {
    private static boolean done, forced;
    private static int phase, nextTick, deadline, variant, passed;
    private static final List<NPC> parents = new ArrayList<>();
    private static final List<Actor> actors = new ArrayList<>();
    private static Actor sourceViewer, destinationViewer;
    private static NPC parent;
    private static HologramTrait hologram;
    private static List<Entity> original = List.of();
    private static List<Entity> departed = List.of();
    private static List<NPC> owners = List.of();
    private static Map<NPC, NPC> vehicles;
    private static Map<NPC, Vec3> offsets;
    private static NPC deniedHelper;
    private static Entity deniedEntity;
    private static Location deniedLocation;
    private static int refusal, attempts;
    private static Consumer<NPCTeleportEvent> teleport;
    private static Consumer<EntityJoinLevelEvent> join;
    private static Consumer<EntityMountEvent> mount;
    private static Consumer<NPCRemoveEvent> remove;

    @SubscribeEvent public static void teleport(NPCTeleportEvent event) { if (teleport != null) teleport.accept(event); }
    @SubscribeEvent public static void join(EntityJoinLevelEvent event) { if (join != null) join.accept(event); }
    @SubscribeEvent public static void mount(EntityMountEvent event) { if (mount != null) mount.accept(event); }
    @SubscribeEvent public static void remove(NPCRemoveEvent event) { if (remove != null) remove.accept(event); }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        var server = event.getServer(); var source = server.overworld(); var destination = server.getLevel(Level.NETHER);
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("hologram-transfer-audit-fixture.txt")), "isolated_fixture");
                source.setChunkForced(0, 0, true); destination.setChunkForced(0, 0, true);
                forced = true; deadline = server.getTickCount() + 1200;
            }
            actors.forEach(Actor::pump);
            if (server.getTickCount() > deadline) throw new AssertionError("timed_out_phase_" + phase);
            if (!source.isPositionEntityTicking(new BlockPos(4, -60, 4))
                    || !destination.isPositionEntityTicking(new BlockPos(4, 80, 4)) || server.getTickCount() < nextTick) return;
            if (phase == 1 && (!sourceViewer.watches(source, 0, 0) || !destinationViewer.watches(destination, 0, 0))) return;
            nextTick = server.getTickCount() + 15;
            switch (phase) {
                case 0 -> {
                    sourceViewer = actor(server, "HoloSource", 6); destinationViewer = actor(server, "HoloDest", 6);
                    destinationViewer.player.teleportTo(destination, 6, 80, 4, Set.of(), 0, 0); phase = 1;
                }
                case 1 -> { startCase(source); phase = 2; }
                case 2 -> {
                    original = helpers(); owners = original.stream().map(NPCRegistries::lookup).toList();
                    vehicles = new IdentityHashMap<>(); offsets = new IdentityHashMap<>();
                    check(original.size() == 10 && owners.stream().allMatch(Objects::nonNull), "all_helpers_created_" + variant);
                    for (Entity entity : original) {
                        vehicles.put(NPCRegistries.lookup(entity), NPCRegistries.lookup(entity.getVehicle()));
                        offsets.put(NPCRegistries.lookup(entity), entity.position().subtract(parent.getEntity().position()));
                        check(sourceViewer.known.contains(entity.getId()), "source_initial_pairing_" + variant + "_" + entity.getType());
                    }
                    departed = helpers(); parent.teleport(at(destination), TeleportCause.PLUGIN); phase = 3;
                }
                case 3 -> {
                    verify(destination, source, destinationViewer, sourceViewer, "outbound_" + variant);
                    departed = helpers(); parent.teleport(at(source), TeleportCause.PLUGIN); phase = 4;
                }
                case 4 -> {
                    verify(source, destination, sourceViewer, destinationViewer, "return_" + variant);
                    var retired = helpers(); parent.destroy();
                    for (Entity entity : retired) check(entity.isRemoved()
                            && source.getEntity(entity.getUUID()) == null && destination.getEntity(entity.getUUID()) == null,
                            "destroy_cleans_helper_" + variant + "_" + entity.getType());
                    for (NPC owner : owners) check(owner.getOwningRegistry().getByUniqueId(owner.getUniqueId()) == null,
                            "destroy_cleans_owner_" + variant);
                    phase = ++variant < 4 ? 1 : 5;
                }
                case 5 -> {
                    parent = simple(source, refusal == 1, refusal == 3 ? "<item:stone>" : "Hello <player>");
                    hologram = parent.getTraitNullable(HologramTrait.class); phase = 6;
                }
                case 6 -> {
                    deniedHelper = NPCRegistries.lookup(helpers().getFirst()); deniedEntity = deniedHelper.getEntity();
                    deniedLocation = deniedHelper.getStoredLocation().clone(); attempts = 0;
                    check(sourceViewer.known.contains(deniedEntity.getId()), "refused_helper_initial_pairing_" + refusal);
                    if (refusal < 2) teleport = e -> { if (e.getNPC() == deniedHelper) { attempts++; e.setCanceled(true); } };
                    else if (refusal == 2) join = e -> {
                        if (e.getLevel() == destination && e.getEntity().getUUID().equals(deniedHelper.getUniqueId())) {
                            attempts++; e.setCanceled(true);
                        }
                    };
                    else mount = e -> {
                        if (e.isDismounting() && e.getEntityBeingMounted() == deniedHelper.getEntity()) {
                            attempts++; e.setCanceled(true);
                        }
                    };
                    parent.teleport(at(destination), TeleportCause.PLUGIN); phase = 7;
                }
                case 7 -> {
                    check(attempts >= 2, "stationary_parent_retries_refused_transfer_" + refusal);
                    check(deniedHelper.getEntity() == deniedEntity && deniedLocation.equals(deniedHelper.getStoredLocation()),
                            "refused_transfer_keeps_original_position_and_owner_" + refusal);
                    check(!sourceViewer.known.contains(deniedEntity.getId()) && !destinationViewer.known.contains(deniedEntity.getId()),
                            "refused_cross_dimension_helper_is_not_visible_" + refusal);
                    check(destination.getEntity(deniedHelper.getUniqueId()) == null, "refused_transfer_has_no_destination_orphan_" + refusal);
                    teleport = null; join = null; mount = null; phase = 8;
                }
                case 8 -> {
                    check(deniedHelper.isSpawned() && deniedHelper.getEntity().level() == destination
                            && destinationViewer.known.contains(deniedHelper.getEntity().getId()), "refused_transfer_eventually_arrives_" + refusal);
                    check(helpers().stream().allMatch(e -> e.level() == destination), "refused_renderer_group_arrives_" + refusal);
                    parent.destroy();
                    if (++refusal < 4) phase = 5;
                    else {
                        callbackCases(source, destination);
                        done = true; LoggerFactory.getLogger("citizens").info("[HOLOGRAMTRANSFERAUDIT] COMPLETE {} checks", passed);
                    }
                }
                default -> throw new AssertionError("unexpected_phase_" + phase);
            }
        } catch (Throwable failure) {
            done = true; LoggerFactory.getLogger("citizens").error("[HOLOGRAMTRANSFERAUDIT] FAILED", failure);
        } finally {
            if (done) try {
                teleport = null; join = null; mount = null; remove = null;
                for (NPC npc : parents) if (npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc) npc.destroy();
                for (Actor actor : actors) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
                source.setChunkForced(0, 0, false); destination.setChunkForced(0, 0, false); configure(false);
            } finally { server.halt(false); }
        }
    }

    private static void startCase(ServerLevel source) {
        configure((variant & 1) != 0);
        parent = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "Transfer <gold>name"); parents.add(parent);
        parent.getOrAddTrait(LookClose.class).setEnabled(false);
        if ((variant & 2) != 0) parent.getOrAddTrait(PacketNPC.class);
        parent.data().set(NPC.Metadata.ALWAYS_USE_NAME_HOLOGRAM, true);
        parent.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, true);
        check(parent.spawn(at(source)), "parent_spawn_" + variant);
        parent.getEntity().setNoGravity(true);
        hologram = parent.getOrAddTrait(HologramTrait.class);
        hologram.addLine("Hello <player>", new HologramTrait.TextDisplayRenderer());
        hologram.addLine("Hello <player>", new HologramTrait.ArmorstandRenderer());
        hologram.addLine("Hello <player>", new HologramTrait.AreaEffectCloudRenderer());
        hologram.addLine("Hello <player>", new HologramTrait.ArmorstandVehicleRenderer());
        hologram.addLine("Hello <player>", new HologramTrait.TextDisplayVehicleRenderer());
        hologram.addLine("Hello <player>", new HologramTrait.InteractionVehicleRenderer());
        hologram.addLine("<item:stone>"); hologram.addLine("<item:diamond>", new HologramTrait.ItemDisplayRenderer());
    }

    private static void verify(ServerLevel current, ServerLevel previous, Actor viewer, Actor oldViewer, String label) {
        var entities = helpers();
        check(entities.size() == original.size(), label + "_helper_count");
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i); NPC owner = NPCRegistries.lookup(entity);
            check(entity.level() == current, label + "_dimension_" + i);
            check(owner == owners.get(i) && entity.getUUID().equals(original.get(i).getUUID()), label + "_identity_" + i);
            check(previous.getEntity(entity.getUUID()) == null
                    && (owner.hasTrait(PacketNPC.class) ? current.getEntity(entity.getUUID()) == null
                            : current.getEntity(entity.getUUID()) == entity), label + "_native_indexes_" + i);
            check(viewer.known.contains(entity.getId()) && !oldViewer.known.contains(entity.getId()), label + "_viewers_" + i);
            check(!oldViewer.known.contains(departed.get(i).getId()), label + "_old_entity_unpaired_" + i);
            check(entity.position().subtract(parent.getEntity().position()).distanceToSqr(offsets.get(owner)) < 0.0001,
                    label + "_relative_position_" + i);
            if (i < 6) check(seenText(viewer, entity, viewer.player.getGameProfile().getName()), label + "_personalized_helper_" + i);
            NPC vehicle = vehicles.get(owner);
            check(vehicle == null ? entity.getVehicle() == null : entity.getVehicle() == vehicle.getEntity(), label + "_vehicle_" + i);
        }
        check(actors.stream().allMatch(a -> a.invalid.isEmpty()), label + "_packet_mount_order");
        check(viewer.packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket)
                .map(p -> (ClientboundSetEntityDataPacket) p).flatMap(p -> p.packedItems().stream())
                .anyMatch(v -> v.value() instanceof net.minecraft.network.chat.Component c
                        && c.getString().contains(viewer.player.getGameProfile().getName())), label + "_personalized_text");
    }

    private static boolean seenText(Actor actor, Entity entity, String expected) {
        return actor.packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket data && data.id() == entity.getId())
                .map(p -> (ClientboundSetEntityDataPacket) p).flatMap(p -> p.packedItems().stream()).anyMatch(v -> {
                    Object value = v.value();
                    if (value instanceof Optional<?> optional) value = optional.orElse(null);
                    return value instanceof net.minecraft.network.chat.Component component && component.getString().contains(expected);
                });
    }

    private static NPC simple(ServerLevel source, boolean virtual, String text) {
        configure(virtual);
        NPC result = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "CallbackParent"); parents.add(result);
        result.getOrAddTrait(LookClose.class).setEnabled(false);
        if (virtual) result.getOrAddTrait(PacketNPC.class);
        check(result.spawn(at(source)), "callback_parent_spawned"); result.getEntity().setNoGravity(true);
        HologramTrait trait = result.getOrAddTrait(HologramTrait.class);
        trait.addLine(text, new HologramTrait.TextDisplayRenderer()); trait.run();
        return result;
    }

    private static void callbackCases(ServerLevel source, ServerLevel destination) {
        for (String operation : List.of("destroy", "remove_trait", "clear", "replace", "reenter", "parent_return", "helper_move")) {
            NPC current = simple(source, false, "Hello <player>");
            HologramTrait trait = current.getTraitNullable(HologramTrait.class);
            NPC helper = NPCRegistries.lookup(trait.getHologramEntities().iterator().next());
            Entity original = helper.getEntity(); int[] calls = { 0 };
            current.teleport(at(destination), TeleportCause.PLUGIN);
            teleport = e -> {
                if (e.getNPC() != helper) return;
                calls[0]++; teleport = null;
                switch (operation) {
                    case "destroy" -> current.destroy();
                    case "remove_trait" -> current.removeTrait(HologramTrait.class);
                    case "clear" -> trait.clear();
                    case "replace" -> { trait.clear(); trait.addLine("Replacement"); trait.run(); }
                    case "reenter" -> trait.run();
                    case "parent_return" -> current.teleport(at(source), TeleportCause.PLUGIN);
                    case "helper_move" -> helper.teleport(new Location(destination, 14, 80, 14), TeleportCause.PLUGIN);
                }
            };
            trait.run(); teleport = null;
            check(calls[0] == 1, "callback_runs_once_" + operation);
            if (List.of("destroy", "remove_trait", "clear", "replace").contains(operation)) {
                check(!helper.isSpawned() && original.isRemoved()
                        && source.getEntity(helper.getUniqueId()) == null && destination.getEntity(helper.getUniqueId()) == null,
                        "retired_helper_not_resurrected_" + operation);
                if (operation.equals("replace")) check(trait.getHologramEntities().size() == 1
                        && trait.getHologramEntities().iterator().next().level() == destination,
                        "callback_replacement_renderer_survives");
                if (operation.equals("remove_trait")) {
                    for (int i = 0; i < 8; i++) trait.run();
                    check(trait.getHologramEntities().isEmpty(), "removed_trait_cannot_respawn_helpers");
                }
            } else if (operation.equals("parent_return")) {
                check(current.getEntity().level() == source, "new_parent_transfer_survives");
                trait.run();
                check(helper.getEntity().level() == source, "helper_follows_new_parent_transfer");
            } else if (operation.equals("helper_move")) {
                check(helper.getEntity().getX() == 14 && helper.getEntity().getZ() == 14, "new_helper_move_not_overwritten");
            } else check(helper.getEntity().level() == destination, "reentrant_render_transfer_completes");
            if (current.isSpawned()) current.destroy();
        }
        NPC current = simple(source, false, "Hello <player>");
        HologramTrait trait = current.getTraitNullable(HologramTrait.class);
        NPC helper = NPCRegistries.lookup(trait.getHologramEntities().iterator().next());
        current.teleport(at(destination), TeleportCause.PLUGIN);
        join = e -> {
            if (e.getLevel() == destination && e.getEntity().getUUID().equals(helper.getUniqueId())) {
                join = null; trait.clear(); trait.addLine("Joined replacement"); trait.run();
            }
        };
        trait.run(); join = null;
        check(!helper.isSpawned() && destination.getEntity(helper.getUniqueId()) == null, "join_callback_retired_candidate_removed");
        check(trait.getHologramEntities().size() == 1 && trait.getHologramEntities().iterator().next().level() == destination,
                "join_callback_replacement_survives");
        current.destroy();
        NPC clearingParent = simple(source, false, "Original line");
        HologramTrait clearing = clearingParent.getTraitNullable(HologramTrait.class);
        NPC clearingHelper = NPCRegistries.lookup(clearing.getHologramEntities().iterator().next());
        remove = e -> {
            if (e.getNPC() == clearingHelper) {
                remove = null; clearing.addLine("Removal replacement"); clearing.run();
            }
        };
        clearing.clear(); remove = null;
        check(clearing.getHologramEntities().size() == 1
                && NPCRegistries.lookup(clearing.getHologramEntities().iterator().next()) != clearingHelper,
                "clear_preserves_new_line_added_by_removal_callback");
        clearingParent.destroy();
    }

    private static List<Entity> helpers() {
        var result = new ArrayList<>(hologram.getHologramEntities());
        if (hologram.getNameEntity() != null) result.add(hologram.getNameEntity());
        return result;
    }
    private static Location at(ServerLevel level) { return new Location(level, 4.5, level.dimension() == Level.OVERWORLD ? -60 : 80, 4.5); }
    private static void configure(boolean enabled) {
        Citizens citizens = Citizens.getInstance();
        YamlStorage config = new YamlStorage(new java.io.File(citizens.getDataFolder(), "config.yml"));
        config.load(); config.getKey("").setBoolean("npc.use-packet-holograms", enabled); config.save(); citizens.getSettings().reload();
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[HOLOGRAMTRANSFERAUDIT] PASS {}", label);
    }

    private static Actor actor(MinecraftServer server, String name, double x) {
        var defaults = ClientInformation.createDefault();
        var information = new ClientInformation(defaults.language(), 6, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing());
        Actor actor = new Actor(); actor.player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), information);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("hologram-transfer-capture", new ChannelOutboundHandlerAdapter() {
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
