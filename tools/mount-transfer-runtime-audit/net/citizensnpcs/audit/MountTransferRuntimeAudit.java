package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCTeleportEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.TeleportCause;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.MountTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.trait.SkinTrait;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class MountTransferRuntimeAudit {
    private static boolean done, prepared;
    private static int passed, deadline;
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<Entity> plain = new ArrayList<>();
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static Consumer<NPCTeleportEvent> teleport;
    private static Consumer<EntityMountEvent> mount;
    private static Consumer<EntityJoinLevelEvent> join;
    private static NPC retryRoot, retryChild;
    private static NPC changedRoot, changedChild, replacement, deletedRoot, deletedChild;
    private static NPC newerRoot, newerChild, retrySibling;
    private static boolean denyRemount;

    @SubscribeEvent public static void teleport(NPCTeleportEvent event) { if (teleport != null) teleport.accept(event); }
    @SubscribeEvent public static void mount(EntityMountEvent event) { if (mount != null) mount.accept(event); }
    @SubscribeEvent public static void join(EntityJoinLevelEvent event) { if (join != null) join.accept(event); }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        var server = event.getServer();
        var destination = server.getLevel(Level.NETHER);
        try {
            if (!prepared) {
                check(Files.isRegularFile(Path.of("mount-transfer-audit-fixture.txt")), "isolated_fixture");
                destination.setChunkForced(0, 0, true); prepared = true; deadline = server.getTickCount() + 120; return;
            }
            if (!destination.isPositionEntityTicking(new BlockPos(4, 80, 4))) {
                if (server.getTickCount() >= deadline) throw new AssertionError("Destination did not become ticking");
                return;
            }
            if (retryRoot != null) {
                if (denyRemount) {
                    if (server.getTickCount() < deadline) return;
                    check(retryChild.getEntity().getVehicle() == null, "denied_remount_stays_detached");
                    changedChild.getOrAddTrait(MountTrait.class).setMountedOn(replacement.getUniqueId());
                    deletedRoot.destroy();
                    newerChild.teleport(new Location(server.overworld(), 11, -60, 11), TeleportCause.PLUGIN);
                    denyRemount = false; deadline = server.getTickCount() + 10; return;
                }
                if (server.getTickCount() < deadline) return;
                riding(retryChild, retryRoot, "remount_retries_through_real_ticks");
                riding(changedChild, replacement, "new_mount_request_supersedes_pending_tree");
                check(!deletedRoot.isSpawned() && deletedChild.getEntity().getVehicle() == null,
                        "pending_tree_does_not_resurrect_removed_root");
                check(newerChild.getEntity().level() == server.overworld() && newerChild.getEntity().getX() == 11
                        && newerChild.getEntity().getVehicle() == null && newerRoot.getEntity().level() == destination,
                        "new_teleport_supersedes_pending_tree");
                check(retryRoot.getEntity().getPassengers().equals(List.of(retryChild.getEntity(), retrySibling.getEntity())),
                        "retry_preserves_sibling_order");
                done = true; LoggerFactory.getLogger("citizens").info("[MOUNTTRANSFERAUDIT] COMPLETE {} checks", passed);
                return;
            }
            matrix(server.overworld(), destination);
            cancellation(server.overworld(), destination);
            partialCancellation(server.overworld(), destination);
            rejectedAdmission(server.overworld(), destination);
            admissionCallbacks(server.overworld(), destination);
            callbacks(server.overworld(), destination);
            partialSupersession(server.overworld(), destination);
            plainEntities(server.overworld(), destination);
            admittedPlayers(server, destination);
            retryRoot = npc(server.overworld(), "RetryRoot", false, EntityType.COW);
            retryChild = npc(server.overworld(), "RetryChild", true, EntityType.COW);
            // Neither endpoint has MountTrait: retry must belong to the transfer, not an unrelated saved trait.
            check(retryChild.getEntity().startRiding(retryRoot.getEntity(), true), "retry_tree_created");
            retrySibling = npc(server.overworld(), "RetrySibling", false, EntityType.COW);
            check(retrySibling.getEntity().startRiding(retryRoot.getEntity(), true), "retry_sibling_created");
            denyRemount = true;
            mount = e -> {
                if (denyRemount && e.isMounting() && (e.getEntityMounting() == retryChild.getEntity()
                        || changedChild != null && e.getEntityMounting() == changedChild.getEntity()
                                && e.getEntityBeingMounted() == changedRoot.getEntity()
                        || deletedChild != null && e.getEntityMounting() == deletedChild.getEntity())) e.setCanceled(true);
            };
            retryRoot.teleport(at(destination), TeleportCause.PLUGIN);
            check(retryRoot.getEntity().level() == destination && retryChild.getEntity().level() == destination,
                    "retry_tree_entities_arrive");
            changedRoot = npc(server.overworld(), "ChangedRoot", false, EntityType.COW);
            changedChild = npc(server.overworld(), "ChangedChild", true, EntityType.COW);
            changedChild.getOrAddTrait(MountTrait.class).setMountedOn(changedRoot.getUniqueId());
            // Establish the source relation before refusing only destination remounts.
            mount = null; changedChild.getTraitNullable(MountTrait.class).run();
            deletedRoot = npc(server.overworld(), "DeletedRoot", false, EntityType.COW);
            deletedChild = npc(server.overworld(), "DeletedChild", true, EntityType.COW);
            deletedChild.getOrAddTrait(MountTrait.class).setMountedOn(deletedRoot.getUniqueId());
            replacement = npc(server.overworld(), "ReplacementMount", false, EntityType.COW);
            replacement.teleport(at(destination), TeleportCause.PLUGIN);
            newerRoot = npc(server.overworld(), "NewerRoot", false, EntityType.COW);
            newerChild = npc(server.overworld(), "NewerChild", true, EntityType.COW);
            check(newerChild.getEntity().startRiding(newerRoot.getEntity(), true), "newer_source_tree_created");
            mount = e -> {
                if (denyRemount && e.isMounting() && e.getEntityMounting().level() == destination
                        && (e.getEntityMounting() == retryChild.getEntity()
                        || e.getEntityMounting() == changedChild.getEntity() && e.getEntityBeingMounted() == changedRoot.getEntity()
                        || e.getEntityMounting() == deletedChild.getEntity()
                        || e.getEntityMounting() == newerChild.getEntity())) e.setCanceled(true);
            };
            changedRoot.teleport(at(destination), TeleportCause.PLUGIN);
            deletedRoot.teleport(at(destination), TeleportCause.PLUGIN);
            newerRoot.teleport(at(destination), TeleportCause.PLUGIN);
            deadline = server.getTickCount() + 5;
        } catch (Throwable failure) {
            done = true; LoggerFactory.getLogger("citizens").error("[MOUNTTRANSFERAUDIT] FAILED", failure);
        } finally {
            if (done) try {
                mount = null; join = null; teleport = null;
                for (NPC npc : npcs) npc.destroy();
                for (Entity entity : plain) entity.discard();
                for (ServerPlayer player : players) server.getPlayerList().remove(player);
                for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
                destination.setChunkForced(0, 0, false);
            } finally { server.halt(false); }
        }
    }

    private static void matrix(ServerLevel source, ServerLevel destination) {
        for (boolean virtualRoot : new boolean[] { false, true }) {
            for (boolean virtualChild : new boolean[] { false, true }) {
                var root = npc(source, "TreeRoot", virtualRoot, EntityType.COW);
                var child = npc(source, "TreeChild", virtualChild, EntityType.COW);
                var sibling = npc(source, "TreeSibling", !virtualChild, EntityType.COW);
                var leaf = npc(source, "TreeLeaf", !virtualRoot, EntityType.PLAYER);
                child.getOrAddTrait(MountTrait.class).setMountedOn(root.getUniqueId());
                sibling.getOrAddTrait(MountTrait.class).setMountedOn(root.getUniqueId());
                leaf.getOrAddTrait(MountTrait.class).setMountedOn(child.getUniqueId());
                var tree = List.of(root, child, sibling, leaf);
                var old = tree.stream().map(NPC::getEntity).toList();
                for (Entity entity : old) ((LivingEntity) entity).setHealth(7);
                String label = virtualRoot + "_" + virtualChild;
                leaf.teleport(at(destination), TeleportCause.PLUGIN);
                for (int i = 0; i < tree.size(); i++) {
                    var npc = tree.get(i); var current = npc.getEntity();
                    check(npc.isSpawned() && current.level() == destination && current.getUUID().equals(old.get(i).getUUID()),
                            "tree_member_arrives_" + label + "_" + i);
                    check(NPCRegistries.lookup(current) == npc && ((LivingEntity) current).getHealth() == 7,
                            "tree_member_identity_and_health_" + label + "_" + i);
                    boolean virtual = npc.hasTrait(PacketNPC.class);
                    check(source.getEntity(current.getUUID()) == null
                            && (virtual ? destination.getEntity(current.getUUID()) == null : destination.getEntity(current.getUUID()) == current),
                            "tree_member_transport_" + label + "_" + i);
                }
                riding(child, root, "child_edge_" + label); riding(sibling, root, "sibling_edge_" + label);
                riding(leaf, child, "leaf_edge_" + label);
                check(root.getEntity().getPassengers().equals(List.of(child.getEntity(), sibling.getEntity())), "sibling_order_" + label);
                root.teleport(new Location(destination, 8, 80, 8, 60, 10), TeleportCause.PLUGIN);
                riding(child, root, "same_world_child_" + label); riding(leaf, child, "same_world_leaf_" + label);
                check(root.getEntity().getX() == 8 && child.getEntity().distanceToSqr(root.getEntity()) < 16,
                        "same_world_positions_" + label);
                child.teleport(new Location(source, 4, -60, 4), TeleportCause.PLUGIN);
                for (NPC npc : tree) check(npc.getEntity().level() == source, "round_trip_member_" + label);
                riding(child, root, "round_trip_child_" + label); riding(sibling, root, "round_trip_sibling_" + label);
                riding(leaf, child, "round_trip_leaf_" + label);
                tree.forEach(NPC::despawn);
            }
        }
    }

    private static void cancellation(ServerLevel source, ServerLevel destination) {
        var root = npc(source, "CancelRoot", false, EntityType.COW);
        var child = npc(source, "CancelChild", true, EntityType.COW);
        child.getOrAddTrait(MountTrait.class).setMountedOn(root.getUniqueId());
        teleport = event -> { if (event.getNPC() == child) event.setCanceled(true); };
        child.teleport(at(destination), TeleportCause.PLUGIN); teleport = null;
        riding(child, root, "canceled_teleport_preserves_tree");
        check(root.getEntity().level() == source && child.getEntity().level() == source, "canceled_teleport_moves_nothing");
        mount = event -> { if (event.isDismounting() && event.getEntityMounting() == child.getEntity()) event.setCanceled(true); };
        root.teleport(at(destination), TeleportCause.PLUGIN); mount = null;
        check(root.getEntity().level() == source && child.getEntity().level() == source, "canceled_detachment_moves_nothing");
        riding(child, root, "canceled_detachment_preserves_tree");
        root.teleport(at(destination), TeleportCause.PLUGIN);
        riding(child, root, "transfer_after_cancel_is_allowed");
        check(root.getEntity().level() == destination && child.getEntity().level() == destination, "transfer_after_cancel_arrives");
        root.despawn(); child.despawn();
    }

    private static void callbacks(ServerLevel source, ServerLevel destination) {
        var root = npc(source, "CallbackRoot", false, EntityType.COW);
        var child = npc(source, "CallbackChild", true, EntityType.COW);
        child.getOrAddTrait(MountTrait.class).setMountedOn(root.getUniqueId());
        teleport = event -> {
            if (event.getNPC() == child) { teleport = null; child.teleport(new Location(source, 9, -60, 9), TeleportCause.PLUGIN); }
        };
        child.teleport(at(destination), TeleportCause.PLUGIN);
        check(root.getEntity().level() == source && root.getEntity().getX() == 9, "newer_tree_teleport_supersedes_outer");
        riding(child, root, "newer_tree_keeps_mount");
        root.despawn(); child.despawn();
    }

    private static void partialCancellation(ServerLevel source, ServerLevel destination) {
        var root = npc(source, "PartialRoot", false, EntityType.COW);
        var child = npc(source, "PartialChild", false, EntityType.COW);
        var sibling = npc(source, "PartialSibling", true, EntityType.COW);
        child.getOrAddTrait(MountTrait.class).setMountedOn(root.getUniqueId());
        sibling.getOrAddTrait(MountTrait.class).setMountedOn(root.getUniqueId());
        mount = event -> {
            if (event.isDismounting() && event.getEntityMounting() == child.getEntity()) event.setCanceled(true);
        };
        child.teleport(at(destination), TeleportCause.PLUGIN); mount = null;
        check(root.getEntity().level() == source && child.getEntity().level() == source && sibling.getEntity().level() == source,
                "partial_detach_cancellation_keeps_all_members_in_source");
        riding(child, root, "partial_detach_preserves_denied_edge");
        riding(sibling, root, "partial_detach_restores_already_detached_sibling");
        root.despawn(); child.despawn(); sibling.despawn();
    }

    private static void rejectedAdmission(ServerLevel source, ServerLevel destination) {
        var root = npc(source, "RejectedRoot", false, EntityType.COW);
        var child = npc(source, "RejectedChild", false, EntityType.COW);
        child.getOrAddTrait(MountTrait.class).setMountedOn(root.getUniqueId());
        var originalRoot = root.getEntity(); var originalChild = child.getEntity();
        join = event -> {
            if (event.getLevel() == destination && event.getEntity().getUUID().equals(root.getUniqueId())) event.setCanceled(true);
        };
        root.teleport(at(destination), TeleportCause.PLUGIN); join = null;
        check(root.getEntity() == originalRoot && child.getEntity() == originalChild && !originalRoot.isRemoved(),
                "rejected_root_keeps_original_entities_alive");
        riding(child, root, "rejected_root_restores_tree");
        check(source.getEntity(root.getUniqueId()) == originalRoot && destination.getEntity(root.getUniqueId()) == null,
                "rejected_root_has_no_destination_orphan");
        join = event -> {
            if (event.getLevel() == destination && event.getEntity().getUUID().equals(child.getUniqueId())) event.setCanceled(true);
        };
        root.teleport(at(destination), TeleportCause.PLUGIN); join = null;
        check(root.getEntity().level() == source && child.getEntity() == originalChild, "rejected_child_rolls_back_already_moved_root");
        riding(child, root, "rejected_child_restores_tree");
        check(destination.getEntity(root.getUniqueId()) == null && destination.getEntity(child.getUniqueId()) == null,
                "rollback_cleans_destination_indexes");
        root.despawn(); child.despawn();
    }

    private static void admissionCallbacks(ServerLevel source, ServerLevel destination) {
        for (boolean rejected : new boolean[] { false, true }) {
            var npc = npc(source, "AdmissionReturn", false, EntityType.COW);
            var old = npc.getEntity();
            join = event -> {
                if (event.getLevel() != destination || !event.getEntity().getUUID().equals(npc.getUniqueId())) return;
                join = null;
                check(NPCRegistries.lookup(event.getEntity()) == npc && npc.getEntity() == event.getEntity(),
                        "admission_callback_sees_current_owner_" + rejected);
                npc.teleport(new Location(source, 12, -60, 12), TeleportCause.PLUGIN);
                if (rejected) event.setCanceled(true);
            };
            npc.teleport(at(destination), TeleportCause.PLUGIN); join = null;
            check(npc.isSpawned() && npc.getEntity().level() == source && npc.getEntity().getX() == 12,
                    "new_join_teleport_returns_to_source_" + rejected);
            check(source.getEntity(npc.getUniqueId()) == npc.getEntity() && destination.getEntity(npc.getUniqueId()) == null,
                    "new_join_teleport_has_one_native_entity_" + rejected);
            check(rejected ? npc.getEntity() == old : old.isRemoved(), "join_return_source_lifecycle_" + rejected);
            npc.despawn();
        }
        var retired = npc(source, "AdmissionDestroy", false, EntityType.COW);
        var original = retired.getEntity();
        join = event -> {
            if (event.getLevel() == destination && event.getEntity().getUUID().equals(retired.getUniqueId())) {
                join = null; retired.destroy();
            }
        };
        retired.teleport(at(destination), TeleportCause.PLUGIN); join = null;
        check(!retired.isSpawned(), "destroy_during_join_retires_npc");
        check(original.isRemoved(), "destroy_during_join_retires_source");
        check(source.getEntity(retired.getUniqueId()) == null, "destroy_during_join_cleans_source_index");
        check(destination.getEntity(retired.getUniqueId()) == null, "destroy_during_join_cleans_destination_index");

        var root = npc(source, "ThrowRoot", false, EntityType.COW);
        var child = npc(source, "ThrowChild", false, EntityType.COW);
        check(child.getEntity().startRiding(root.getEntity(), true), "exception_tree_created");
        join = event -> {
            if (event.getLevel() == destination && event.getEntity().getUUID().equals(child.getUniqueId())) {
                join = null; throw new IllegalStateException("expected admission failure");
            }
        };
        boolean failed = false;
        try { root.teleport(at(destination), TeleportCause.PLUGIN); }
        catch (IllegalStateException expected) { failed = expected.getMessage().equals("expected admission failure"); }
        join = null;
        check(failed, "admission_exception_is_propagated");
        check(root.getEntity().level() == source && child.getEntity().level() == source, "admission_exception_rolls_back_moves");
        riding(child, root, "admission_exception_restores_edges");
        root.despawn(); child.despawn();
    }

    private static void partialSupersession(ServerLevel source, ServerLevel destination) {
        var root = npc(source, "SupersedeRoot", false, EntityType.COW);
        var child = npc(source, "SupersedeChild", false, EntityType.COW);
        var sibling = npc(source, "SupersedeSibling", true, EntityType.COW);
        var target = npc(source, "SupersedeTarget", false, EntityType.COW);
        child.getOrAddTrait(MountTrait.class).setMountedOn(root.getUniqueId());
        check(sibling.getEntity().startRiding(root.getEntity(), true), "superseded_source_sibling_created");
        mount = event -> {
            if (event.isDismounting() && event.getEntityMounting() == child.getEntity()) {
                mount = null;
                child.getTraitNullable(MountTrait.class).setMountedOn(target.getUniqueId());
            }
        };
        root.teleport(at(destination), TeleportCause.PLUGIN); mount = null;
        riding(child, target, "dismount_callback_new_mount_survives");
        riding(sibling, root, "dismount_callback_restores_untouched_sibling");
        check(root.getEntity().level() == source, "superseded_detach_does_not_move_root");
        child.getTraitNullable(MountTrait.class).setMountedOn(root.getUniqueId());
        mount = event -> {
            if (event.isDismounting() && event.getEntityMounting() == child.getEntity()) {
                mount = null; child.getEntity().startRiding(target.getEntity(), true);
            }
        };
        root.teleport(at(destination), TeleportCause.PLUGIN); mount = null;
        child.getTraitNullable(MountTrait.class).run();
        riding(child, target, "direct_callback_mount_is_adopted_after_transfer_pause");
        riding(sibling, root, "direct_callback_restores_untouched_sibling");
        root.despawn(); child.despawn(); sibling.despawn(); target.despawn();
    }

    private static void admittedPlayers(MinecraftServer server, ServerLevel destination) {
        var player = admit(server, "TransferPlayer");
        var source = server.overworld();
        for (boolean virtual : new boolean[] { false, true }) {
            var root = npc(source, "RealPlayerMount", virtual, EntityType.COW);
            check(player.startRiding(root.getEntity(), true), "real_player_source_mount_" + virtual);
            var connection = player.connection;
            root.teleport(at(destination), TeleportCause.PLUGIN);
            check(player.level() == destination && player.getVehicle() == root.getEntity()
                    && player.connection == connection, "real_player_rider_transfers_" + virtual);
            check(destination.getEntity(player.getUUID()) == player && destination.players().contains(player)
                    && source.getEntity(player.getUUID()) == null && !source.players().contains(player),
                    "real_player_native_membership_" + virtual);
            root.teleport(new Location(source, 4, -60, 4), TeleportCause.PLUGIN);
            check(player.level() == source && player.getVehicle() == root.getEntity(), "real_player_round_trip_" + virtual);
            player.stopRiding(); root.despawn();
        }
        var rider = npc(source, "PlayerRootRider", true, EntityType.COW);
        check(rider.getEntity().startRiding(player, true), "real_player_root_created");
        rider.teleport(at(destination), TeleportCause.PLUGIN);
        check(player.level() == destination && rider.getEntity().getVehicle() == player, "real_player_root_transfers");
        rider.teleport(new Location(source, 4, -60, 4), TeleportCause.PLUGIN);
        check(player.level() == source && rider.getEntity().getVehicle() == player, "real_player_root_round_trip");
        rider.despawn();
    }

    private static ServerPlayer admit(MinecraftServer server, String name) {
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) { connection.configurePacketHandler(channel.pipeline()); }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setPos(4, -60, 4); channels.add(channel); players.add(player);
        return player;
    }

    private static void plainEntities(ServerLevel source, ServerLevel destination) {
        var boat = EntityType.BOAT.create(source); boat.moveTo(4, -60, 4); source.addFreshEntity(boat); plain.add(boat);
        var child = npc(source, "PlainBoatChild", true, EntityType.COW);
        check(child.getEntity().startRiding(boat, true), "plain_root_created");
        UUID id = boat.getUUID();
        child.teleport(at(destination), TeleportCause.PLUGIN);
        Entity moved = child.getEntity().getVehicle(); if (moved != null) plain.add(moved);
        check(moved != null && moved.getUUID().equals(id) && moved.level() == destination && moved != boat,
                "plain_root_replaced_and_remounted");
        check(source.getEntity(id) == null && destination.getEntity(id) == moved, "plain_root_world_indexes");
        child.despawn(); if (moved != null) moved.discard();
    }

    private static NPC npc(ServerLevel level, String name, boolean virtual, EntityType<?> type) {
        var npc = CitizensAPI.getNPCRegistry().createNPC(type, name); npcs.add(npc);
        npc.getOrAddTrait(LookClose.class).setEnabled(false);
        if (type == EntityType.PLAYER) npc.getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false);
        if (virtual) npc.getOrAddTrait(PacketNPC.class);
        check(npc.spawn(new Location(level, 4.5, -60, 4.5)), name + "_spawned");
        return npc;
    }
    private static Location at(ServerLevel level) { return new Location(level, 4, 80, 4, 30, 25); }
    private static void riding(NPC child, NPC parent, String label) {
        check(child.getEntity().getVehicle() == parent.getEntity() && parent.getEntity().getPassengers().contains(child.getEntity()), label);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[MOUNTTRANSFERAUDIT] PASS {}", label);
    }
}
