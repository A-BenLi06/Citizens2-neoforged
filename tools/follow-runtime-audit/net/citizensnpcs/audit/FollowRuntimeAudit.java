package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.ai.event.NavigationBeginEvent;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.trait.FollowTrait;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.handler.DefaultPermissionHandler;
import net.neoforged.neoforge.server.permission.handler.IPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class FollowRuntimeAudit {
    private static boolean done;
    private static boolean prepared;
    private static int readyDeadline;
    private static int passed;
    private static NPC npc, target;
    private static FollowTrait follow;
    private static int deadline;
    private static double initialDistance;
    private static boolean allowOthers;
    private static PermissionUtil.PermissionResolver previousPermissions;
    private static ServerPlayer alice, bob;
    private static final List<NPC> helpers = new ArrayList<>();
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static Consumer<NavigationBeginEvent> begin;
    private static Consumer<net.citizensnpcs.api.ai.event.NavigationCancelEvent> cancel;
    private static boolean cancelDamage;
    private static Consumer<net.citizensnpcs.api.event.NPCTeleportEvent> teleport;
    private static Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> join;
    private static Consumer<net.neoforged.neoforge.event.entity.player.PlayerEvent.StopTracking> stopTracking;

    @SubscribeEvent public static void stopTracking(net.neoforged.neoforge.event.entity.player.PlayerEvent.StopTracking event) {
        if (stopTracking != null) stopTracking.accept(event);
    }

    @SubscribeEvent public static void teleport(net.citizensnpcs.api.event.NPCTeleportEvent event) {
        if (teleport == null) return;
        var action = teleport; teleport = null; action.accept(event);
    }

    @SubscribeEvent public static void join(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (join != null) join.accept(event);
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void damage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (cancelDamage && target != null && event.getEntity() == target.getEntity()) event.setCanceled(true);
    }

    @SubscribeEvent public static void cancel(net.citizensnpcs.api.ai.event.NavigationCancelEvent event) {
        if (event.getNPC() != npc || cancel == null) return;
        var action = cancel; cancel = null; action.accept(event);
    }

    @SubscribeEvent public static void permissions(PermissionGatherEvent.Handler event) {
        event.addPermissionHandler(AuditPermissions.ID, AuditPermissions::new);
    }

    private static Boolean permission(ServerPlayer player, String permission) {
        if (player != alice) return null;
        return switch (permission) {
            case "citizens.npc.follow" -> true;
            case "citizens.npc.follow.others" -> allowOthers;
            case "citizens.admin", "citizens.ignore-owner" -> false;
            default -> null;
        };
    }

    private static final class AuditPermissions implements IPermissionHandler {
        static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("citizens", "follow_audit");
        private final DefaultPermissionHandler defaults;
        AuditPermissions(Collection<PermissionNode<?>> nodes) { defaults = new DefaultPermissionHandler(nodes); }
        public ResourceLocation getIdentifier() { return ID; }
        public Set<PermissionNode<?>> getRegisteredNodes() { return defaults.getRegisteredNodes(); }
        @SuppressWarnings("unchecked")
        public <T> T getPermission(ServerPlayer player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
            Boolean result = permission(player, node.getNodeName());
            return node.getType() == PermissionTypes.BOOLEAN && result != null ? (T) result
                    : defaults.getPermission(player, node, context);
        }
        public <T> T getOfflinePermission(UUID player, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
            return defaults.getOfflinePermission(player, node, context);
        }
    }

    @SubscribeEvent public static void begin(NavigationBeginEvent event) {
        if (event.getNPC() != npc || begin == null) return;
        var action = begin; begin = null; action.accept(event);
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        var server = event.getServer();
        try {
            var nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
            if (!prepared) {
                check(Files.isRegularFile(Path.of("follow-audit-fixture.txt")), "isolated_fixture");
                nether.setChunkForced(0, 0, true);
                readyDeadline = server.getTickCount() + 100;
                prepared = true;
                return;
            }
            // Level.getEntity only exposes accessible chunks. Exercise admission in a genuinely ticking destination,
            // reached through normal chunk tickets and server ticks, not by modifying native entity indexes.
            if (!nether.isPositionEntityTicking(new net.minecraft.core.BlockPos(4, 80, 4))) {
                if (server.getTickCount() >= readyDeadline) throw new AssertionError("Destination fixture never became ticking");
                return;
            }
            if (deadline > 0) {
                if (server.getTickCount() < deadline) return;
                check(npc.getEntity().distanceToSqr(target.getEntity()) < initialDistance - 1,
                        "real_ticks_move_follower_towards_target");
                follow.follow(null);
                check(!npc.getNavigator().isNavigating(), "real_tick_route_stops_on_disable");
                done = true;
                LoggerFactory.getLogger("citizens").info("[FOLLOWAUDIT] COMPLETE {} checks", passed);
                return;
            }
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "Follower");
            npc.getOrAddTrait(LookClose.class).setEnabled(false);
            target = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "FollowTarget");
            target.getOrAddTrait(LookClose.class).setEnabled(false);
            target.data().setPersistent(NPC.Metadata.DEFAULT_PROTECTED, false);
            check(npc.spawn(new Location(server.overworld(), 0.5, -60, 0.5))
                    && target.spawn(new Location(server.overworld(), 10.5, -60, 0.5)), "npcs_spawned");
            follow = npc.getOrAddTrait(FollowTrait.class); follow.follow(target.getEntity()); follow.run();
            var source = server.createCommandSourceStack(); CitizensAPI.getDefaultNPCSelector().select(source, npc);
            check(server.getCommands().getDispatcher().execute("npc follow " + target.getId() + " --enable false", source) == 1,
                    "explicit_disable_command_executes");
            check(!follow.isEnabled() && !npc.getNavigator().isNavigating(), "explicit_disable_stops_following");
            routes(server);
            commands(server);
            targetLifecycle(server);
            worlds(server);
            transfers(server);
            callbacks(server);
            protection(server);
            follow.setProtect(false); follow.setFollowingMargin(2); follow.follow(target.getEntity());
            npc.teleport(new Location(server.overworld(), 0.5, -60, 0.5), net.citizensnpcs.api.util.TeleportCause.PLUGIN);
            initialDistance = npc.getEntity().distanceToSqr(target.getEntity());
            deadline = server.getTickCount() + 40;
        } catch (Throwable failure) {
            done = true;
            LoggerFactory.getLogger("citizens").error("[FOLLOWAUDIT] FAILED", failure);
        } finally {
            if (done) try {
                begin = null; cancel = null; teleport = null; join = null; stopTracking = null;
                if (previousPermissions != null || alice != null) PermissionUtil.setPermissionResolver(previousPermissions);
                if (npc != null) npc.destroy(); if (target != null) target.destroy();
                for (var helper : helpers) helper.destroy();
                for (var player : players) { ChatPrompts.abandon(player); server.getPlayerList().remove(player); }
                for (var channel : channels) channel.finishAndReleaseAll();
                server.getLevel(net.minecraft.world.level.Level.NETHER).setChunkForced(0, 0, false);
            }
            finally { server.halt(false); }
        }
    }

    private static NPC helper(MinecraftServer server, String name, boolean virtual) {
        NPC helper = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, name);
        helpers.add(helper); helper.getOrAddTrait(LookClose.class).setEnabled(false);
        if (virtual) helper.getOrAddTrait(PacketNPC.class);
        helper.spawn(new Location(server.overworld(), 12.5 + helpers.size(), -60, 0.5));
        return helper;
    }

    private static void start() { follow.follow(target.getEntity()); follow.run(); }
    private static boolean following(NPC target) {
        return follow.isActive() && follow.getFollowing() == target.getEntity()
                && npc.getNavigator().getEntityTarget() != null
                && npc.getNavigator().getEntityTarget().getTarget() == target.getEntity();
    }

    private static void routes(MinecraftServer server) {
        start(); check(following(target), "native_follow_route_started");
        follow.setFollowingMargin(6);
        check(!npc.getNavigator().isNavigating(), "margin_change_releases_owned_route_immediately");
        begin = event -> check(event.getNavigator().getLocalParameters().distanceMargin() == 6,
                "margin_visible_to_native_navigation_begin");
        follow.run(); check(following(target), "changed_margin_restarts_follow_route");
        var owned = npc.getNavigator().getPathStrategy();
        npc.getNavigator().setTarget(target.getEntity(), false);
        var replacement = npc.getNavigator().getPathStrategy();
        check(replacement != owned, "external_same_target_has_distinct_route");
        follow.follow(null);
        check(npc.getNavigator().getPathStrategy() == replacement, "disable_preserves_external_same_target_route");
        follow.follow(target.getEntity()); follow.setFollowingMargin(2);
        check(npc.getNavigator().getPathStrategy() == replacement, "margin_change_preserves_external_route");
        npc.getEntity().setDeltaMovement(Vec3.ZERO); follow.run();
        check(npc.getEntity().getDeltaMovement().equals(Vec3.ZERO), "follow_does_not_flock_during_external_route");
        npc.getNavigator().cancelNavigation(); follow.run();
        npc.getNavigator().setPaused(true); npc.getEntity().setDeltaMovement(Vec3.ZERO); follow.run();
        check(npc.getEntity().getDeltaMovement().equals(Vec3.ZERO), "paused_follow_does_not_apply_flocking_force");
        npc.getNavigator().setPaused(false);
        var state = state();
        for (double invalid : new double[] { Double.NaN, Double.POSITIVE_INFINITY, -2 }) {
            try { follow.setFollowingMargin(invalid); throw new AssertionError("Invalid margin accepted"); }
            catch (IllegalArgumentException expected) { check(state().equals(state), "invalid_api_margin_is_atomic_" + invalid); }
        }
        try { follow.follow(npc.getEntity()); throw new AssertionError("Self-follow accepted"); }
        catch (IllegalArgumentException expected) { check(state().equals(state), "self_follow_is_rejected_without_changes"); }
        follow.follow(null);
    }

    private static Map<String, Object> state() {
        var key = new MemoryDataKey(); PersistenceLoader.save(follow, key); return key.getValuesDeep();
    }

    private static int command(CommandSourceStack source, String value) throws Exception {
        CitizensAPI.getDefaultNPCSelector().select(source, npc);
        try { return source.getServer().getCommands().getDispatcher().execute("npc follow " + value, source); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException rejected) {
            LoggerFactory.getLogger("citizens").info("[FOLLOWAUDIT] rejected {}: {}", value, rejected.getMessage()); return 0;
        }
    }

    private static void commands(MinecraftServer server) throws Exception {
        var console = server.createCommandSourceStack();
        check(command(console, target.getId() + " --enable true -p --margin 4") == 1
                && follow.isEnabled() && follow.isProtecting() && follow.getFollowingMargin() == 4,
                "combined_explicit_follow_options_apply");
        follow.run(); check(following(target) && npc.getNavigator().getLocalParameters().distanceMargin() == 4,
                "command_options_reach_native_route");
        check(command(console, target.getId() + " --enable true") == 1 && follow.isEnabled(), "explicit_true_does_not_toggle_off");
        var unchanged = state();
        command(console, "--enable true");
        check(state().equals(unchanged), "console_enable_requires_explicit_target_without_mutation");
        check(command(console, Integer.toString(target.getId())) == 1 && !follow.isEnabled(), "implicit_named_command_toggles_off");
        check(command(console, "--enable false") == 1 && !follow.isEnabled(), "explicit_false_is_idempotent");
        check(command(console, "--margin -1") == 1 && follow.getFollowingMargin() == -1 && !follow.isEnabled(),
                "margin_only_command_preserves_enabled_state");
        Map<String, Object> state = state();
        for (String invalid : List.of("--margin NaN", "--margin -2", "--margin Infinity", "--enable maybe", "-c --enable true",
                "--enable false --margin NaN", "--range NaN", "--unknown true")) {
            command(console, invalid); check(state().equals(state), "invalid_command_is_atomic_" + invalid);
        }
        alice = admit(server, "FollowAlice", UUID.randomUUID()); bob = admit(server, "FollowBob", UUID.randomUUID());
        npc.getOrAddTrait(Owner.class).setOwner(alice.getUUID()); target.getOrAddTrait(Owner.class).setOwner(alice.getUUID());
        previousPermissions = PermissionUtil.getPermissionResolver();
        PermissionUtil.setPermissionResolver(FollowRuntimeAudit::permission);
        check(PermissionAPI.getActivePermissionHandler().equals(AuditPermissions.ID)
                && PermissionUtil.hasPermission(alice, "citizens.npc.follow")
                && !PermissionUtil.hasPermission(alice, "citizens.npc.follow.others"), "native_permission_handler_grants_follow_and_denies_others");
        state = state(); command(alice.createCommandSourceStack(), "FollowBob --enable true -p --margin 7");
        check(state().equals(state), "other_player_permission_denial_preserves_all_settings");
        check(command(alice.createCommandSourceStack(), "--enable true") == 1
                && follow.getFollowingUUID().equals(alice.getUUID()), "self_follow_requires_no_others_permission");
        follow.run(); check(follow.getFollowing() == alice, "admitted_player_target_resolves");
        command(alice.createCommandSourceStack(), "-c"); check(!follow.isEnabled(), "native_cancel_alias_stops_following");
        npc.removeTrait(FollowTrait.class);
        command(alice.createCommandSourceStack(), "FollowBob --enable true");
        check(!npc.hasTrait(FollowTrait.class), "denied_target_does_not_attach_trait");
        follow = npc.getOrAddTrait(FollowTrait.class); allowOthers = true;
        check(command(alice.createCommandSourceStack(), "FollowBob --enable true -p") == 1 && follow.isProtecting(),
                "permitted_other_player_follow_works");
        check(command(alice.createCommandSourceStack(), "--enable true") == 1
                && follow.getFollowingUUID().equals(alice.getUUID()), "omitted_target_uses_sender_even_when_already_following");
        var stranger = helper(server, "Stranger", false); stranger.getOrAddTrait(Owner.class).setOwner(bob.getUUID());
        state = state(); command(alice.createCommandSourceStack(), stranger.getId() + " --enable true --margin 9");
        check(state().equals(state), "other_npc_ownership_denial_is_atomic");
        state = state(); command(alice.createCommandSourceStack(), npc.getId() + " --enable true");
        check(state().equals(state), "self_npc_command_is_atomic");
        follow.follow(null);
        var first = helper(server, "FollowChoice", false); var second = helper(server, "FollowChoice", false);
        first.getOrAddTrait(Owner.class).setOwner(alice.getUUID()); second.getOrAddTrait(Owner.class).setOwner(alice.getUUID());
        command(alice.createCommandSourceStack(), "FollowChoice --enable true -p --margin 8");
        check(ChatPrompts.isActive(alice) && !follow.isEnabled(), "ambiguous_name_waits_without_mutation");
        command(alice.createCommandSourceStack(), "--enable false"); state = state();
        ChatPrompts.acceptInput(alice, Integer.toString(first.getId()));
        check(state().equals(state) && !ChatPrompts.isActive(alice), "stale_choice_does_not_override_later_command");
        command(alice.createCommandSourceStack(), "FollowChoice --enable true");
        ChatPrompts.acceptInput(alice, Integer.toString(second.getId())); follow.run();
        check(following(second), "current_name_choice_starts_selected_target");
        npc.removeTrait(FollowTrait.class);
        command(alice.createCommandSourceStack(), "FollowChoice --enable true");
        check(ChatPrompts.isActive(alice) && !npc.hasTrait(FollowTrait.class), "pending_choice_does_not_attach_trait");
        command(alice.createCommandSourceStack(), "--enable false");
        ChatPrompts.acceptInput(alice, Integer.toString(first.getId()));
        check(!npc.hasTrait(FollowTrait.class), "disable_without_trait_invalidates_pending_choice");
        follow = npc.getOrAddTrait(FollowTrait.class);
        follow.follow(null);
    }

    private static void targetLifecycle(MinecraftServer server) {
        start(); var old = target.getEntity(); target.despawn(); follow.run();
        check(follow.isEnabled() && !follow.isActive() && !npc.getNavigator().isNavigating(), "despawned_target_releases_route_but_keeps_uuid");
        target.spawn(new Location(server.overworld(), 10.5, -60, 0.5)); follow.run();
        check(target.getEntity() != old && following(target), "target_respawn_resolves_current_entity");
        var virtual = helper(server, "VirtualTarget", true); follow.follow(virtual.getEntity()); follow.run();
        check(following(virtual), "virtual_npc_target_resolves_through_registry");
        virtual.despawn(); follow.run(); check(!follow.isActive() && !npc.getNavigator().isNavigating(), "virtual_target_despawn_releases_route");
        virtual.spawn(new Location(server.overworld(), 14.5, -60, 0.5)); follow.run();
        check(following(virtual), "virtual_target_respawn_reacquires_current_entity");
        var virtualPlayer = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "VirtualPlayer");
        helpers.add(virtualPlayer);
        virtualPlayer.getOrAddTrait(net.citizensnpcs.trait.SkinTrait.class).setFetchDefaultSkin(false);
        virtualPlayer.getOrAddTrait(LookClose.class).setEnabled(false);
        virtualPlayer.getOrAddTrait(PacketNPC.class);
        virtualPlayer.spawn(new Location(server.overworld(), 15, -60, 2));
        follow.follow(virtualPlayer.getEntity()); follow.run();
        check(!virtualPlayer.getUniqueId().equals(virtualPlayer.getMinecraftUniqueId()) && following(virtualPlayer),
                "virtual_player_target_resolves_native_uuid");
        var key = new MemoryDataKey(); PersistenceLoader.save(follow, key);
        npc.removeTrait(FollowTrait.class); check(!npc.getNavigator().isNavigating(), "trait_removal_cancels_owned_route");
        follow = npc.getOrAddTrait(FollowTrait.class); PersistenceLoader.load(follow, key); follow.run();
        check(following(virtualPlayer), "saved_follow_uuid_and_options_resume_after_trait_load");
        npc.despawn(); check(!follow.isActive(), "follower_despawn_clears_active_target");
        npc.spawn(new Location(server.overworld(), 0.5, -60, 0.5)); follow.run();
        check(following(virtualPlayer), "follower_respawn_rebuilds_follow_route");
        follow.follow(bob); follow.run(); server.getPlayerList().remove(bob); follow.run();
        check(!follow.isActive() && !npc.getNavigator().isNavigating(), "disconnected_player_is_not_a_live_follow_target");
        var replacement = admit(server, "FollowBob", bob.getUUID()); follow.run();
        check(follow.getFollowing() == replacement && follow.isActive(), "reconnected_player_replaces_stale_identity");
        follow.follow(null);
    }

    private static void callbacks(MinecraftServer server) {
        start();
        net.citizensnpcs.api.ai.PathStrategy[] callbackRoute = new net.citizensnpcs.api.ai.PathStrategy[1];
        npc.getNavigator().getLocalParameters().addSingleUseCallback(reason -> {
            npc.getNavigator().setTarget(target.getEntity(), false);
            callbackRoute[0] = npc.getNavigator().getPathStrategy();
        });
        follow.follow(null);
        check(callbackRoute[0] != null && npc.getNavigator().getPathStrategy() == callbackRoute[0],
                "cancel_callback_replacement_survives_follow_disable");
        npc.getNavigator().cancelNavigation();
        start();
        cancel = event -> {
            npc.getNavigator().setTarget(target.getEntity(), false);
            callbackRoute[0] = npc.getNavigator().getPathStrategy();
        };
        follow.follow(null);
        check(npc.getNavigator().getPathStrategy() == callbackRoute[0], "cancel_event_replacement_survives_follow_disable");
        npc.getNavigator().cancelNavigation(); start();
        npc.getNavigator().getLocalParameters().addSingleUseCallback(reason -> {
            npc.getNavigator().setTarget(new Location(server.overworld(), 6, -60, 3));
            callbackRoute[0] = npc.getNavigator().getPathStrategy();
        });
        npc.getNavigator().setTarget(target.getEntity(), true);
        check(npc.getNavigator().getPathStrategy() == callbackRoute[0], "new_callback_request_supersedes_outer_replacement");
        npc.getNavigator().cancelNavigation(); start();
        npc.getNavigator().getLocalParameters().addSingleUseCallback(reason -> npc.getNavigator().cancelNavigation());
        npc.getNavigator().setTarget(target.getEntity(), true);
        check(!npc.getNavigator().isNavigating(), "callback_cancel_supersedes_outer_replacement");
        start(); npc.getNavigator().getLocalParameters().addSingleUseCallback(reason -> npc.despawn());
        npc.getNavigator().setTarget(target.getEntity(), true);
        check(!npc.isSpawned() && !npc.getNavigator().isNavigating(), "callback_despawn_aborts_outer_replacement");
        npc.spawn(new Location(server.overworld(), 0.5, -60, 0.5));
        start(); npc.getNavigator().getLocalParameters().addSingleUseCallback(reason -> npc.getNavigator().setTarget(target.getEntity(), false));
        npc.despawn(); check(!npc.getNavigator().isNavigating(), "despawn_callback_cannot_leave_route_active");
        npc.spawn(new Location(server.overworld(), 0.5, -60, 0.5));
        npc.getNavigator().setTarget(new Location(server.overworld(), 0.5, -60, 0.5));
        npc.getNavigator().getLocalParameters().addSingleUseCallback(reason -> {
            npc.getNavigator().setTarget(target.getEntity(), false); callbackRoute[0] = npc.getNavigator().getPathStrategy();
        });
        ((net.citizensnpcs.npc.ai.CitizensNavigator) npc.getNavigator()).run();
        check(npc.getNavigator().getPathStrategy() == callbackRoute[0], "completion_callback_replacement_survives_normal_finish");
        npc.getNavigator().cancelNavigation();
        npc.getNavigator().setTarget(params -> {
            npc.getNavigator().setTarget(target.getEntity(), false);
            callbackRoute[0] = npc.getNavigator().getPathStrategy();
            return new net.citizensnpcs.npc.ai.MCTargetStrategy(npc, target.getEntity(), true, params);
        });
        check(npc.getNavigator().getPathStrategy() == callbackRoute[0], "strategy_factory_cannot_overwrite_newer_request");
        npc.getNavigator().cancelNavigation();
        npc.getNavigator().setTarget(params -> new net.citizensnpcs.npc.ai.MCTargetStrategy(npc, target.getEntity(), false, params) {
            @Override public void stop() {
                super.stop();
                npc.getNavigator().setTarget(target.getEntity(), false);
                callbackRoute[0] = npc.getNavigator().getPathStrategy();
            }
        });
        npc.getNavigator().cancelNavigation();
        check(npc.getNavigator().getPathStrategy() == callbackRoute[0], "strategy_stop_replacement_is_not_erased_or_recursive");
        npc.getNavigator().cancelNavigation();
        follow.follow(target.getEntity()); begin = event -> follow.follow(null); follow.run();
        check(!follow.isEnabled() && !npc.getNavigator().isNavigating(), "begin_callback_disable_retires_owned_route");
        follow.follow(target.getEntity()); begin = event -> follow.run(); follow.run();
        check(following(target), "begin_callback_reentry_is_bounded");
        follow.follow(null); follow.follow(target.getEntity());
        begin = event -> npc.getNavigator().setTarget(target.getEntity(), false); follow.run();
        var replacement = npc.getNavigator().getPathStrategy(); follow.follow(null);
        check(npc.getNavigator().getPathStrategy() == replacement, "begin_callback_external_replacement_survives_cleanup");
        npc.getNavigator().cancelNavigation(); follow.follow(target.getEntity());
        begin = event -> npc.despawn(); follow.run();
        check(!npc.isSpawned() && !follow.isActive() && !npc.getNavigator().isNavigating(), "begin_callback_despawn_retires_route");
        npc.spawn(new Location(server.overworld(), 0.5, -60, 0.5));
    }

    private static void protection(MinecraftServer server) {
        var attacker = helper(server, "Attacker", false);
        target.data().setPersistent(NPC.Metadata.DEFAULT_PROTECTED, false);
        follow.setProtect(true); start();
        LivingEntity victim = (LivingEntity) target.getEntity(); victim.invulnerableTime = 0;
        var before = npc.getNavigator().getPathStrategy(); cancelDamage = true;
        try { victim.hurt(server.overworld().damageSources().mobAttack((LivingEntity) attacker.getEntity()), 1); }
        finally { cancelDamage = false; }
        check(npc.getNavigator().getPathStrategy() == before, "already_cancelled_damage_does_not_start_protection");
        victim.invulnerableTime = 0;
        victim.hurt(server.overworld().damageSources().mobAttack((LivingEntity) npc.getEntity()), 1);
        check(npc.getNavigator().getPathStrategy() == before, "follower_does_not_attack_itself");
        victim.invulnerableTime = 0;
        victim.hurt(server.overworld().damageSources().mobAttack((LivingEntity) attacker.getEntity()), 1);
        check(npc.getNavigator().getEntityTarget() != null && npc.getNavigator().getEntityTarget().isAggressive()
                && npc.getNavigator().getEntityTarget().getTarget() == attacker.getEntity(), "native_damage_causes_protection_route");
        follow.setProtect(false);
        check(!npc.getNavigator().isNavigating(), "disabling_protection_releases_owned_attack_route");
        follow.setProtect(true); start();
        Arrow arrow = new Arrow(EntityType.ARROW, server.overworld()); arrow.setOwner(attacker.getEntity());
        victim.invulnerableTime = 0; victim.hurt(server.overworld().damageSources().arrow(arrow, attacker.getEntity()), 1);
        check(npc.getNavigator().getEntityTarget() != null && npc.getNavigator().getEntityTarget().getTarget() == attacker.getEntity(),
                "projectile_damage_targets_shooter");
        follow.follow(null);
        npc.getNavigator().setTarget(new Location(server.overworld(), 2, -60, 2));
        var manual = npc.getNavigator().getPathStrategy(); victim.invulnerableTime = 0;
        victim.hurt(server.overworld().damageSources().mobAttack((LivingEntity) attacker.getEntity()), 1);
        check(npc.getNavigator().getPathStrategy() == manual, "disabled_follow_does_not_protect_stale_target");
        npc.getNavigator().cancelNavigation();
    }

    private static void worlds(MinecraftServer server) {
        var setting = net.citizensnpcs.Settings.Setting.FOLLOW_ACROSS_WORLDS;
        boolean old = setting.asBoolean();
        try {
            setting.set(false); start(); target.despawn();
            target.spawn(new Location(server.getLevel(net.minecraft.world.level.Level.NETHER), 10.5, 80, 0.5)); follow.run();
            check(follow.isEnabled() && !npc.getNavigator().isNavigating() && npc.getEntity().level() == server.overworld(),
                    "cross_world_follow_respects_disabled_teleport_policy");
            setting.set(true); follow.run(); follow.run();
            check(npc.getEntity().level() == target.getEntity().level() && following(target), "enabled_cross_world_follow_teleports_and_resumes");
        } finally {
            setting.set(old); follow.follow(null); npc.despawn(); target.despawn();
            npc.spawn(new Location(server.overworld(), 0.5, -60, 0.5));
            target.spawn(new Location(server.overworld(), 10.5, -60, 0.5));
        }
    }

    private static void transfers(MinecraftServer server) throws Exception {
        var nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
        for (boolean virtual : new boolean[] { false, true }) {
            for (var type : List.of(EntityType.COW, EntityType.PLAYER)) {
                var moved = CitizensAPI.getNPCRegistry().createNPC(type, "Transfer"); helpers.add(moved);
                moved.getOrAddTrait(LookClose.class).setEnabled(false);
                if (type == EntityType.PLAYER)
                    moved.getOrAddTrait(net.citizensnpcs.trait.SkinTrait.class).setFetchDefaultSkin(false);
                if (virtual) moved.getOrAddTrait(PacketNPC.class);
                moved.spawn(new Location(server.overworld(), 3, -60, 3));
                if (virtual) {
                    moved.getTraitNullable(PacketNPC.class).run();
                    check(!moved.getTraitNullable(PacketNPC.class).getPacketTracker().getLinked().isEmpty(),
                            "virtual_source_has_actual_viewers_" + type);
                }
                var original = moved.getEntity();
                ((LivingEntity) original).setHealth(7);
                var before = new Location(nether, 4, 80, 4, 30, 25);
                int[] joins = { 0 };
                join = event -> {
                    if (!event.getEntity().getUUID().equals(original.getUUID())) return;
                    joins[0]++;
                    check(moved.getEntity() == event.getEntity()
                            && net.citizensnpcs.npc.NPCRegistries.lookup(event.getEntity()) == moved,
                            "destination_admission_sees_current_npc_ownership_" + type);
                };
                moved.teleport(before, net.citizensnpcs.api.util.TeleportCause.PLUGIN); join = null;
                var current = moved.getEntity();
                String label = type + "_virtual_" + virtual;
                check(moved.isSpawned() && current.level() == nether && current.getUUID().equals(original.getUUID())
                        && ((LivingEntity) current).getHealth() == 7 && current.getYRot() == 30 && current.getXRot() == 25,
                        "native_state_survives_dimension_transfer_" + label);
                check(net.citizensnpcs.npc.NPCRegistries.lookup(current) == moved
                        && (current == original || net.citizensnpcs.npc.NPCRegistries.lookup(original) == null),
                        "controller_and_registry_adopt_survivor_" + label);
                check(server.overworld().getEntity(original.getUUID()) == null
                        && (virtual ? nether.getEntity(current.getUUID()) == null && joins[0] == 0
                                : nether.getEntity(current.getUUID()) == current && joins[0] == 1),
                        "native_and_virtual_world_admission_" + label + " joins=" + joins[0]
                                + " source=" + server.overworld().getEntity(original.getUUID())
                                + " destination=" + nether.getEntity(current.getUUID()));
                if (virtual) {
                    check(PacketNPC.isPacketEntity(current) && (original == current || !PacketNPC.isPacketEntity(original)),
                            "virtual_tracker_rebinds_" + label);
                    check(!server.overworld().players().contains(current) && !nether.players().contains(current),
                            "virtual_transfer_never_enters_player_lists_" + label);
                    check(moved.getTraitNullable(PacketNPC.class).getPacketTracker().getLinked().isEmpty(),
                            "virtual_transfer_releases_previous_world_viewers_" + label);
                } else if (current instanceof ServerPlayer) {
                    check(!nether.players().contains(current), "world_player_transfer_applies_configured_player_list_policy");
                }
                if (current instanceof net.minecraft.world.entity.Mob mob) {
                    var worldField = net.minecraft.world.entity.ai.navigation.PathNavigation.class.getDeclaredField("level");
                    worldField.setAccessible(true);
                    check(worldField.get(mob.getNavigation()) == nether, "native_navigation_uses_destination_world_" + label);
                }
                moved.getNavigator().setTarget(new Location(nether, 7, 80, 4));
                check(moved.getNavigator().isNavigating(), "navigation_accepts_destination_route_" + label);
                var existingRoute = moved.getNavigator().getPathStrategy();
                teleport = event -> event.setCanceled(true);
                moved.teleport(new Location(server.overworld(), 1, -60, 1), net.citizensnpcs.api.util.TeleportCause.PLUGIN);
                check(moved.getEntity() == current && current.level() == nether
                        && moved.getNavigator().getPathStrategy() == existingRoute, "cancelled_transfer_preserves_entity_and_route_" + label);
                moved.teleport(new Location(server.overworld(), 2, -60, 2), net.citizensnpcs.api.util.TeleportCause.PLUGIN);
                check(moved.getEntity().level() == server.overworld() && !moved.getNavigator().isNavigating(),
                        "round_trip_releases_old_world_route_" + label);
                moved.despawn(); check(!moved.isSpawned() && server.overworld().getEntity(current.getUUID()) == null
                        && nether.getEntity(current.getUUID()) == null, "transferred_entity_cleanup_" + label);
            }
        }
        var nested = helper(server, "NestedTransfer", false);
        teleport = event -> nested.teleport(new Location(server.overworld(), 8, -60, 8), net.citizensnpcs.api.util.TeleportCause.PLUGIN);
        nested.teleport(new Location(nether, 4, 80, 4), net.citizensnpcs.api.util.TeleportCause.PLUGIN);
        check(nested.getEntity().level() == server.overworld() && nested.getEntity().getX() == 8,
                "newer_callback_teleport_supersedes_outer_request");
        nested.getNavigator().setTarget(new Location(server.overworld(), 10, -60, 8));
        nested.getNavigator().getLocalParameters().addSingleUseCallback(reason -> nested.teleport(
                new Location(server.overworld(), 9, -60, 8), net.citizensnpcs.api.util.TeleportCause.PLUGIN));
        nested.teleport(new Location(nether, 4, 80, 4), net.citizensnpcs.api.util.TeleportCause.PLUGIN);
        check(nested.getEntity().level() == server.overworld() && nested.getEntity().getX() == 9,
                "navigation_callback_move_supersedes_cross_world_transfer");
        nested.getNavigator().setTarget(new Location(server.overworld(), 10, -60, 8));
        check(nested.getNavigator().isNavigating(), "aborted_transfer_releases_navigation_suspension");
        teleport = event -> nested.destroy();
        nested.teleport(new Location(nether, 4, 80, 4), net.citizensnpcs.api.util.TeleportCause.PLUGIN);
        check(!nested.isSpawned() && nether.getEntity(nested.getMinecraftUniqueId()) == null,
                "teleport_callback_destroy_prevents_ghost_entity");
        var retired = helper(server, "RetiredVirtual", true);
        var packet = retired.getTraitNullable(PacketNPC.class); packet.run();
        check(!packet.getPacketTracker().getLinked().isEmpty(), "retirement_fixture_has_actual_viewers");
        var old = retired.getEntity();
        stopTracking = event -> {
            if (event.getTarget() == old) { stopTracking = null; retired.destroy(); }
        };
        retired.teleport(new Location(nether, 4, 80, 4), net.citizensnpcs.api.util.TeleportCause.PLUGIN);
        check(!retired.isSpawned() && old.isRemoved() && !PacketNPC.isPacketEntity(old)
                && nether.getEntity(old.getUUID()) == null && packet.getPacketTracker().getLinked().isEmpty(),
                "unpair_callback_destroy_cannot_resurrect_virtual_transfer");
    }

    private static ServerPlayer admit(MinecraftServer server, String name, UUID uuid) {
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(uuid, name), ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) { connection.configurePacketHandler(channel.pipeline()); }
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
        passed++; LoggerFactory.getLogger("citizens").info("[FOLLOWAUDIT] PASS {}", label);
    }
}
