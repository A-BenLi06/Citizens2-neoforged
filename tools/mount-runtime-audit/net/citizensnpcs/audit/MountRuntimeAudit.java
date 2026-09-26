package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.trait.Controllable;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.MountTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.handler.DefaultPermissionHandler;
import net.neoforged.neoforge.server.permission.handler.IPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class MountRuntimeAudit {
    private static boolean done, allowMount = true, allowControl = true;
    private static int passed, deadline, removedEffects;
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static Consumer<EntityMountEvent> mountEvent;
    private static NPC rider, first, second;
    private static MountTrait trait;
    private static ServerPlayer alice;

    @SubscribeEvent public static void mount(EntityMountEvent event) {
        if (mountEvent != null) mountEvent.accept(event);
    }

    @SubscribeEvent public static void permissions(PermissionGatherEvent.Handler event) {
        event.addPermissionHandler(AuditPermissions.ID, AuditPermissions::new);
    }

    private static final class AuditPermissions implements IPermissionHandler {
        static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("citizens", "mount_audit");
        private final DefaultPermissionHandler defaults;
        AuditPermissions(Collection<PermissionNode<?>> nodes) { defaults = new DefaultPermissionHandler(nodes); }
        public ResourceLocation getIdentifier() { return ID; }
        public Set<PermissionNode<?>> getRegisteredNodes() { return defaults.getRegisteredNodes(); }
        @SuppressWarnings("unchecked")
        public <T> T getPermission(ServerPlayer player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
            if (player == alice && node.getType() == PermissionTypes.BOOLEAN) {
                Boolean value = switch (node.getNodeName()) {
                    case "citizens.npc.mount" -> allowMount;
                    case "citizens.npc.controllable", "citizens.npc.controllable.cow" -> allowControl;
                    case "citizens.admin", "citizens.ignore-owner" -> false;
                    default -> null;
                };
                if (value != null) return (T) value;
            }
            return defaults.getPermission(player, node, context);
        }
        public <T> T getOfflinePermission(UUID player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
            return defaults.getOfflinePermission(player, node, context);
        }
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        var server = event.getServer();
        try {
            if (deadline > 0) {
                if (server.getTickCount() < deadline) return;
                riding(rider.getEntity(), first.getEntity(), "real_tick_retry_mounts_respawned_target");
                check(first.getUniqueId().equals(trait.getMountedOn()), "real_tick_actual_mount_identity");
                done = true;
                LoggerFactory.getLogger("citizens").info("[MOUNTAUDIT] COMPLETE {} checks", passed);
                return;
            }
            check(Files.isRegularFile(Path.of("mount-audit-fixture.txt")), "isolated_fixture");
            rider = npc(server, "Rider", false);
            first = npc(server, "FirstMount", false);
            second = npc(server, "SecondMount", false);
            trait = rider.getOrAddTrait(MountTrait.class);
            cancellation(server);
            lifecycle(server);
            commands(server);
            callbacks(server);
            additional(server);
            rightClicks(server);
            mixed(server);
            trait.setMountedOn(first.getUniqueId());
            first.despawn(); trait.run();
            check(trait.getMountedOn() == null, "target_despawn_clears_actual_identity");
            check(first.spawn(new Location(server.overworld(), 4.5, -60, 0.5)), "target_respawns_for_real_ticks");
            deadline = server.getTickCount() + 10;
        } catch (Throwable failure) {
            done = true;
            LoggerFactory.getLogger("citizens").error("[MOUNTAUDIT] FAILED", failure);
        } finally {
            if (done) try {
                mountEvent = null;
                for (var player : players) { player.stopRiding(); server.getPlayerList().remove(player); }
                for (var npc : npcs) npc.destroy();
                for (var channel : channels) channel.finishAndReleaseAll();
            } finally { server.halt(false); }
        }
    }

    private static void cancellation(MinecraftServer server) {
        mountEvent = event -> { if (event.getEntityMounting() == rider.getEntity()) event.setCanceled(true); };
        trait.setMountedOn(first.getUniqueId());
        check(rider.getEntity().getVehicle() == null, "native_mount_event_canceled");
        check(trait.getMountedOn() == null, "canceled_mount_is_not_reported_as_success");
        check(first.getUniqueId().toString().equals(saved(trait)), "canceled_mount_retains_requested_target");
        mountEvent = null; trait.run();
        riding(rider.getEntity(), first.getEntity(), "canceled_mount_retries");
        mountEvent = event -> {
            if (event.isMounting() && event.getEntityMounting() == rider.getEntity()) event.setCanceled(true);
        };
        trait.setMountedOn(second.getUniqueId());
        riding(rider.getEntity(), first.getEntity(), "rejected_new_mount_keeps_existing_vehicle");
        check(!second.getEntity().getPassengers().contains(rider.getEntity()), "rejected_new_mount_has_no_passenger_entry");
        check(second.getUniqueId().toString().equals(saved(trait)), "rejected_new_mount_retains_pending_request");
        var eventOrder = new ArrayList<Boolean>();
        mountEvent = event -> {
            if (event.getEntityMounting() == rider.getEntity()) eventOrder.add(event.isMounting());
        };
        trait.run(); mountEvent = null;
        riding(rider.getEntity(), second.getEntity(), "rejected_new_mount_retries_later");
        check(eventOrder.size() >= 2 && eventOrder.get(0) && !eventOrder.get(1), "native_mount_event_precedes_dismount_event");
        trait.setMountedOn(first.getUniqueId());
        mountEvent = event -> { if (event.isDismounting() && event.getEntityMounting() == rider.getEntity()) event.setCanceled(true); };
        trait.setMountedOn(second.getUniqueId());
        riding(rider.getEntity(), first.getEntity(), "canceled_dismount_preserves_old_relationship");
        check(!second.getEntity().getPassengers().contains(rider.getEntity()), "canceled_switch_does_not_duplicate_passenger");
        check(second.getUniqueId().toString().equals(saved(trait)), "canceled_switch_retains_requested_target");
        trait.run();
        check(second.getUniqueId().toString().equals(saved(trait)), "old_vehicle_does_not_replace_pending_request");
        mountEvent = null; trait.run();
        riding(rider.getEntity(), second.getEntity(), "switch_retries_after_dismount_allowed");
        check(!first.getEntity().getPassengers().contains(rider.getEntity()), "switch_removes_old_passenger_entry");
        mountEvent = event -> { if (event.isDismounting() && event.getEntityMounting() == rider.getEntity()) event.setCanceled(true); };
        trait.unmount(); trait.run();
        riding(rider.getEntity(), second.getEntity(), "unmount_honors_native_cancellation");
        check(saved(trait) == null, "canceled_unmount_clears_desired_mount_without_readopting_old_vehicle");
        mountEvent = null; trait.unmount();
        check(rider.getEntity().getVehicle() == null && trait.getMountedOn() == null, "unmount_clears_actual_relationship");
        trait.setMountedOn(UUID.randomUUID()); trait.unmount();
        check(saved(trait) == null, "unmount_cancels_unresolved_saved_target");
    }

    private static void lifecycle(MinecraftServer server) {
        trait.setMountedOn(first.getUniqueId());
        var key = new MemoryDataKey(); PersistenceLoader.save(trait, key);
        rider.removeTrait(MountTrait.class);
        check(rider.getEntity().getVehicle() == null, "trait_removal_dismounts");
        trait = rider.getOrAddTrait(MountTrait.class); PersistenceLoader.load(trait, key); trait.run();
        riding(rider.getEntity(), first.getEntity(), "saved_uuid_restores_mount");
        Entity previousRider = rider.getEntity(); rider.despawn();
        check(!first.getEntity().getPassengers().contains(previousRider) && previousRider.getVehicle() == null,
                "rider_despawn_detaches");
        check(first.getUniqueId().toString().equals(saved(trait)), "rider_despawn_preserves_request");
        check(rider.spawn(new Location(server.overworld(), 0.5, -60, 0.5)), "rider_respawns"); trait.run();
        riding(rider.getEntity(), first.getEntity(), "rider_respawn_remounts");
        trait.unmount(); rider.getEntity().startRiding(second.getEntity(), true); trait.run();
        check(second.getUniqueId().toString().equals(saved(trait)), "external_native_mount_is_adopted");
        trait.unmount(); trait.setMountedOn(rider.getUniqueId());
        check(rider.getEntity().getVehicle() == null, "self_mount_never_enters_native_tree");
        trait.unmount(); first.getEntity().startRiding(rider.getEntity(), true);
        trait.setMountedOn(first.getUniqueId());
        check(rider.getEntity().getVehicle() == null && trait.getMountedOn() == null, "cycle_rejected_without_false_actual_state");
        first.getEntity().stopRiding(); trait.run();
        riding(rider.getEntity(), first.getEntity(), "pending_cycle_retries_when_tree_allows_it");
        trait.unmount();
        var remote = npc(server, "RemoteMount", false);
        remote.teleport(new Location(server.getLevel(net.minecraft.world.level.Level.NETHER), 4, 80, 4),
                net.citizensnpcs.api.util.TeleportCause.PLUGIN);
        trait.setMountedOn(remote.getUniqueId());
        check(rider.getEntity().getVehicle() == null && trait.getMountedOn() == null, "cross_dimension_mount_is_pending");
        remote.teleport(new Location(server.overworld(), 4.5, -60, 0.5), net.citizensnpcs.api.util.TeleportCause.PLUGIN);
        trait.run(); riding(rider.getEntity(), remote.getEntity(), "same_dimension_return_allows_mount");
        trait.unmount();
    }

    private static void commands(MinecraftServer server) throws Exception {
        var console = server.createCommandSourceStack(); select(console, rider);
        check(command(server, console, "npc mount --onnpc " + first.getUniqueId()) == 1, "console_uuid_command");
        riding(rider.getEntity(), first.getEntity(), "console_uuid_relationship");
        check(command(server, console, "npc mount -c") == 1 && rider.getEntity().getVehicle() == null, "console_cancel_command");
        check(command(server, console, "npc mount --onnpc " + first.getId()) == 1, "console_numeric_command");
        check(command(server, console, "npc mount -d") == 1, "console_dismount_is_noop");
        riding(rider.getEntity(), first.getEntity(), "console_dismount_preserves_npc_mount");
        check(command(server, console, "npc mount --onnpc bad-id") == 0, "invalid_target_rejected");
        check(command(server, console, "npc mount --onnpc " + rider.getUniqueId()) == 0, "self_target_command_rejected");
        check(command(server, console, "npc mount --unknown true") == 0, "unknown_option_rejected");
        check(command(server, console, "npc mount extra") == 0, "extra_argument_rejected");
        riding(rider.getEntity(), first.getEntity(), "invalid_commands_preserve_mount");
        trait.unmount();
        check(command(server, console, "npc mount --onnpc " + first.getId() + " -c") == 1, "target_precedes_cancel_like_original");
        riding(rider.getEntity(), first.getEntity(), "target_precedence_relationship"); trait.unmount();
        alice = admit(server, "MountAlice");
        rider.getOrAddTrait(Owner.class).setOwner(alice.getUUID());
        var player = alice.createCommandSourceStack(); select(player, rider);
        check(command(server, player, "npc mount") == 1 && alice.getVehicle() == null, "noncontrollable_npc_not_mounted");
        check(!rider.hasTrait(Controllable.class), "mount_does_not_attach_controllable");
        var controllable = rider.getOrAddTrait(Controllable.class); controllable.setEnabled(false);
        check(command(server, player, "npc mount") == 1 && alice.getVehicle() == null, "disabled_controllable_not_mounted");
        controllable.setEnabled(true);
        check(command(server, player, "npc mount") == 1, "player_controllable_command");
        riding(alice, rider.getEntity(), "player_controllable_relationship");
        check(!controllable.mount(alice), "already_mounted_returns_false");
        check(command(server, player, "npc mount -dc --onnpc " + first.getId()) == 1 && alice.getVehicle() == null,
                "dismount_precedes_target_and_cancel");
        check(rider.getEntity().getVehicle() == null, "player_dismount_does_not_change_npc_request");
        allowMount = false;
        check(command(server, player, "npc mount --onnpc " + first.getId()) == 0, "mount_permission_required");
        allowMount = true;
        rider.getOrAddTrait(Owner.class).setOwner(UUID.randomUUID());
        check(command(server, player, "npc mount --onnpc " + first.getId()) == 0, "npc_ownership_required");
        rider.getOrAddTrait(Owner.class).setOwner(alice.getUUID());
        check(rider.getEntity().getVehicle() == null, "denied_commands_have_no_mount_side_effects");
        check(command(server, console, "npc mount") == 0, "console_cannot_be_player_rider");
        mountEvent = event -> { if (event.getEntityMounting() == alice) event.setCanceled(true); };
        check(!controllable.mount(alice) && alice.getVehicle() == null, "controllable_reports_native_cancellation");
        mountEvent = null;
    }

    private static void callbacks(MinecraftServer server) {
        trait.unmount();
        mountEvent = event -> {
            if (event.isMounting() && event.getEntityMounting() == rider.getEntity()) {
                mountEvent = null; trait.setMountedOn(second.getUniqueId());
            }
        };
        trait.setMountedOn(first.getUniqueId());
        riding(rider.getEntity(), second.getEntity(), "mount_callback_newer_request_wins");
        check(!first.getEntity().getPassengers().contains(rider.getEntity()), "stale_mount_callback_has_no_passenger_entry");
        check(second.getUniqueId().toString().equals(saved(trait)), "mount_callback_preserves_saved_request");
        trait.unmount();
        mountEvent = event -> {
            if (event.isMounting() && event.getEntityMounting() == rider.getEntity()) { mountEvent = null; trait.unmount(); }
        };
        trait.setMountedOn(first.getUniqueId());
        check(rider.getEntity().getVehicle() == null && saved(trait) == null, "cancel_during_mount_event_prevents_stale_mount");
        trait.setMountedOn(first.getUniqueId());
        mountEvent = event -> {
            if (event.isDismounting() && event.getEntityMounting() == rider.getEntity()) {
                mountEvent = null; trait.setMountedOn(second.getUniqueId());
            }
        };
        trait.unmount();
        riding(rider.getEntity(), second.getEntity(), "dismount_callback_newer_request_wins");
        check(second.getUniqueId().toString().equals(saved(trait)), "dismount_callback_preserves_saved_request");
        trait.unmount();
        var retired = npc(server, "RetiredMount", false);
        Entity old = retired.getEntity();
        mountEvent = event -> { if (event.getEntityBeingMounted() == old) { mountEvent = null; retired.destroy(); } };
        trait.setMountedOn(retired.getUniqueId());
        check(rider.getEntity().getVehicle() == null && old.getPassengers().isEmpty(), "destroyed_target_not_mounted_after_event");
        trait.unmount();
        MountTrait detached = trait;
        mountEvent = event -> {
            if (event.getEntityMounting() == rider.getEntity()) { mountEvent = null; rider.removeTrait(MountTrait.class); }
        };
        detached.setMountedOn(first.getUniqueId());
        check(rider.getEntity().getVehicle() == null && !rider.hasTrait(MountTrait.class), "removed_trait_cannot_finish_mount");
        detached.run(); check(rider.getEntity().getVehicle() == null, "detached_trait_cannot_restart_mount");
        trait = rider.getOrAddTrait(MountTrait.class);
        var control = rider.getOrAddTrait(Controllable.class);
        mountEvent = event -> { if (event.getEntityMounting() == alice) { mountEvent = null; control.setEnabled(false); } };
        check(!control.mount(alice) && alice.getVehicle() == null, "disable_controllable_during_event_prevents_mount");
        control.setEnabled(true);
    }

    private static void mixed(MinecraftServer server) {
        for (boolean virtualRider : new boolean[] { false, true }) {
            for (boolean virtualVehicle : new boolean[] { false, true }) {
                var child = npc(server, "MixedRider", virtualRider);
                var parent = npc(server, "MixedMount", virtualVehicle);
                var mount = child.getOrAddTrait(MountTrait.class);
                String label = "mixed_" + virtualRider + "_" + virtualVehicle;
                mount.setMountedOn(parent.getUniqueId());
                riding(child.getEntity(), parent.getEntity(), label + "_mount");
                mount.unmount();
                check(child.getEntity().getVehicle() == null && parent.getEntity().getPassengers().isEmpty(), label + "_unmount");
                check(virtualRider == PacketNPC.isPacketEntity(child.getEntity())
                        && virtualVehicle == PacketNPC.isPacketEntity(parent.getEntity()), label + "_transport_preserved");
            }
        }
        for (boolean virtual : new boolean[] { false, true }) {
            var playerNPC = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "MountPlayer"); npcs.add(playerNPC);
            playerNPC.getOrAddTrait(net.citizensnpcs.trait.SkinTrait.class).setFetchDefaultSkin(false);
            playerNPC.getOrAddTrait(LookClose.class).setEnabled(false);
            if (virtual) playerNPC.getOrAddTrait(PacketNPC.class);
            check(playerNPC.spawn(new Location(server.overworld(), 8.5, -60, 0.5)), "player_npc_spawn_" + virtual);
            var mount = playerNPC.getOrAddTrait(MountTrait.class);
            mount.setMountedOn(first.getUniqueId());
            riding(playerNPC.getEntity(), first.getEntity(), "player_npc_mount_" + virtual);
            mount.unmount(); check(playerNPC.getEntity().getVehicle() == null, "player_npc_dismount_" + virtual);
        }
    }

    private static void additional(MinecraftServer server) throws Exception {
        trait.unmount();
        mountEvent = event -> {
            if (event.isMounting() && event.getEntityMounting() == rider.getEntity()) trait.run();
        };
        trait.setMountedOn(first.getUniqueId()); mountEvent = null;
        riding(rider.getEntity(), first.getEntity(), "reentrant_tick_does_not_repeat_same_request");
        trait.unmount();
        mountEvent = event -> {
            if (event.getEntityMounting() == rider.getEntity()) {
                mountEvent = null; rider.getEntity().startRiding(second.getEntity(), true);
            }
        };
        trait.setMountedOn(first.getUniqueId());
        riding(rider.getEntity(), second.getEntity(), "direct_native_callback_mount_is_preserved");
        check(!first.getEntity().getPassengers().contains(rider.getEntity()), "direct_native_callback_no_stale_entry");
        trait.run();
        check(second.getUniqueId().toString().equals(saved(trait)), "direct_native_callback_request_adopted");
        trait.unmount();
        var console = server.createCommandSourceStack(); select(console, rider);
        rider.despawn();
        check(command(server, console, "npc mount --onnpc " + first.getId()) == 1, "unspawned_rider_accepts_saved_request");
        check(first.getUniqueId().toString().equals(saved(trait)), "unspawned_request_is_persisted");
        check(command(server, console, "npc mount -c") == 1 && saved(trait) == null, "unspawned_request_can_be_canceled");
        check(rider.spawn(new Location(server.overworld(), 0.5, -60, 0.5)), "canceled_rider_respawns");
        trait.run(); check(rider.getEntity().getVehicle() == null, "canceled_request_does_not_return_on_spawn");
        var bare = npc(server, "BareMountRider", false);
        bare.getEntity().startRiding(first.getEntity(), true); select(console, bare);
        check(!bare.hasTrait(MountTrait.class), "bare_native_rider_has_no_mount_trait");
        var adopted = bare.getOrAddTrait(MountTrait.class); adopted.run();
        check(first.getUniqueId().toString().equals(saved(adopted)), "new_trait_adopts_existing_native_mount");
        bare.removeTrait(MountTrait.class); bare.getEntity().startRiding(first.getEntity(), true);
        check(command(server, console, "npc mount -c") == 1 && bare.getEntity().getVehicle() == null,
                "cancel_handles_native_rider_without_previous_trait");
        select(console, rider);
        var registry = CitizensAPI.createInMemoryNPCRegistry("mount-audit-named");
        var named = registry.createNPC(EntityType.COW, "NamedMount"); npcs.add(named);
        check(named.spawn(new Location(server.overworld(), 4.5, -60, 0.5)), "named_registry_target_spawned");
        check(command(server, console, "npc mount --onnpc " + named.getUniqueId()) == 0,
                "command_target_scope_matches_default_registry");
        trait.setMountedOn(named.getUniqueId());
        riding(rider.getEntity(), named.getEntity(), "saved_api_target_resolves_across_registries"); trait.unmount();
        var source = alice.createCommandSourceStack(); select(source, rider);
        var control = rider.getOrAddTrait(Controllable.class);
        ((net.minecraft.world.entity.LivingEntity) rider.getEntity()).addEffect(
                new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 200));
        check(control.mount(alice), "player_mount_before_canceling_dismount");
        int previousEffects = removedEffects;
        mountEvent = event -> { if (event.isDismounting() && event.getEntityMounting() == alice) event.setCanceled(true); };
        check(command(server, source, "npc mount -d") == 1, "player_dismount_command_with_canceled_event");
        riding(alice, rider.getEntity(), "player_dismount_cancellation_preserved");
        check(removedEffects == previousEffects, "canceled_dismount_retains_vehicle_effect_packets");
        check(!first.getOrAddTrait(Controllable.class).mount(alice), "controllable_switch_requires_dismount");
        check(!first.getEntity().getPassengers().contains(alice), "controllable_switch_no_duplicate_passenger");
        mountEvent = null; alice.stopRiding();
        check(removedEffects > previousEffects, "successful_native_dismount_removes_vehicle_effect_packets");
        check(control.mount(alice), "player_mount_before_reentrant_dismount");
        mountEvent = event -> {
            if (event.isDismounting() && event.getEntityMounting() == alice) {
                mountEvent = null; first.getOrAddTrait(Controllable.class).mount(alice);
            }
        };
        check(command(server, source, "npc mount -d") == 1, "player_dismount_callback_command");
        riding(alice, first.getEntity(), "player_dismount_callback_new_vehicle_preserved"); alice.stopRiding();
        var ordinaryRider = EntityType.COW.create(server.overworld());
        var ordinaryMount = EntityType.COW.create(server.overworld());
        check(ordinaryRider.startRiding(ordinaryMount, true), "unscoped_native_mount_unchanged");
        ordinaryRider.stopRiding();
        check(ordinaryRider.getVehicle() == null && ordinaryMount.getPassengers().isEmpty(), "unscoped_native_dismount_unchanged");
        mountEvent = event -> {
            if (event.getEntityMounting() == rider.getEntity()) {
                mountEvent = null; throw new IllegalStateException("Intentional mount listener failure");
            }
        };
        boolean thrown = false;
        try { trait.setMountedOn(first.getUniqueId()); }
        catch (IllegalStateException expected) { thrown = expected.getMessage().equals("Intentional mount listener failure"); }
        check(thrown && rider.getEntity().getVehicle() == null, "listener_exception_propagates_without_partial_mount");
        trait.run(); riding(rider.getEntity(), first.getEntity(), "listener_exception_releases_request_scope_for_retry");
        trait.unmount();
    }

    private static NPC npc(MinecraftServer server, String name, boolean virtual) {
        var npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, name); npcs.add(npc);
        npc.getOrAddTrait(LookClose.class).setEnabled(false);
        if (virtual) npc.getOrAddTrait(PacketNPC.class);
        check(npc.spawn(new Location(server.overworld(), npcs.size() + 0.5, -60, 0.5)), name + "_spawned");
        return npc;
    }

    private static void rightClicks(MinecraftServer server) {
        var controlled = npc(server, "RightClickMount", false);
        controlled.getOrAddTrait(Owner.class).setOwner(alice.getUUID());
        var control = controlled.getOrAddTrait(Controllable.class); control.setOwnerRequired(true);
        var previous = net.citizensnpcs.api.util.PermissionUtil.getPermissionResolver();
        net.citizensnpcs.api.util.PermissionUtil.setPermissionResolver((player, permission) ->
                player == alice && permission.startsWith("citizens.npc.controllable") ? allowControl : null);
        try {
            var click = new net.citizensnpcs.api.event.NPCRightClickEvent(controlled, alice);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(click);
            riding(alice, controlled.getEntity(), "right_click_uses_controllable_mount");
            check(click.isDelayedCancellation(), "right_click_preserves_delayed_cancellation"); alice.stopRiding();
            allowControl = false;
            click = new net.citizensnpcs.api.event.NPCRightClickEvent(controlled, alice);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(click);
            check(alice.getVehicle() == null && !click.isDelayedCancellation(), "right_click_permission_required");
            allowControl = true; controlled.getOrAddTrait(Owner.class).setOwner(UUID.randomUUID());
            click = new net.citizensnpcs.api.event.NPCRightClickEvent(controlled, alice);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(click);
            check(alice.getVehicle() == null && !click.isDelayedCancellation(), "right_click_owner_required");
            controlled.getOrAddTrait(Owner.class).setOwner(alice.getUUID()); control.setEnabled(false);
            click = new net.citizensnpcs.api.event.NPCRightClickEvent(controlled, alice);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(click);
            check(alice.getVehicle() == null && !click.isDelayedCancellation(), "right_click_disabled_control");
            control.setEnabled(true);
            mountEvent = event -> {
                if (event.getEntityMounting() == alice) { mountEvent = null; controlled.removeTrait(Controllable.class); }
            };
            click = new net.citizensnpcs.api.event.NPCRightClickEvent(controlled, alice);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(click);
            check(alice.getVehicle() == null && !click.isDelayedCancellation() && !controlled.hasTrait(Controllable.class),
                    "right_click_trait_removal_aborts_stale_controller");
            controlled.despawn();
            control.onRightClick(new net.citizensnpcs.api.event.NPCRightClickEvent(controlled, alice));
            check(alice.getVehicle() == null, "right_click_unspawned_npc_is_safe");
        } finally { net.citizensnpcs.api.util.PermissionUtil.setPermissionResolver(previous); }
    }

    private static String saved(MountTrait trait) {
        var data = new MemoryDataKey(); PersistenceLoader.save(trait, data);
        Object value = data.getRaw("mountedon"); return value == null ? null : value.toString();
    }
    private static void select(CommandSourceStack source, NPC npc) { CitizensAPI.getDefaultNPCSelector().select(source, npc); }
    private static int command(MinecraftServer server, CommandSourceStack source, String command) throws Exception {
        try {
            return server.getCommands().getDispatcher().execute(command, source);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
            return 0;
        }
    }
    private static void riding(Entity rider, Entity vehicle, String label) {
        check(rider.getVehicle() == vehicle && vehicle.getPassengers().contains(rider), label);
    }
    private static ServerPlayer admit(MinecraftServer server, String name) {
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("mount-audit-packets", new io.netty.channel.ChannelOutboundHandlerAdapter() {
                    @Override public void write(io.netty.channel.ChannelHandlerContext context, Object message,
                            io.netty.channel.ChannelPromise promise) throws Exception {
                        if (message instanceof net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket) removedEffects++;
                        super.write(context, message, promise);
                    }
                });
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setPos(18.5, -60, 4.5); channels.add(channel); players.add(player);
        return player;
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[MOUNTAUDIT] PASS {}", label);
    }
}
