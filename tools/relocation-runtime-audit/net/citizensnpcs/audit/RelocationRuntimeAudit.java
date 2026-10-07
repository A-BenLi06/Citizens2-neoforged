package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.*;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.trait.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class RelocationRuntimeAudit {
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<PermissionUtil.Attachment> grants = new ArrayList<>();
    private static final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private static final List<Runnable> later = new ArrayList<>();
    private static NPCRegistry registry, named;
    private static Actor alice, bob;
    private static MinecraftServer server;
    private static ServerLevel level, nether;
    private static Consumer<NPCSpawnEvent> spawn;
    private static Consumer<NPCTeleportEvent> teleport;
    private static Consumer<EntityJoinLevelEvent> join;
    private static Consumer<EntityMountEvent> mount;
    private static Consumer<EntityTravelToDimensionEvent> travel;
    private static PermissionUtil.Attachment spawnGrant, selectGrant;
    private static int start = -1, passed, sequence, deadline;
    private static boolean forced, done;

    @SubscribeEvent public static void spawn(NPCSpawnEvent e) { if (spawn != null) spawn.accept(e); }
    @SubscribeEvent public static void teleport(NPCTeleportEvent e) { if (teleport != null) teleport.accept(e); }
    @SubscribeEvent public static void join(EntityJoinLevelEvent e) { if (join != null) join.accept(e); }
    @SubscribeEvent public static void mount(EntityMountEvent e) { if (mount != null) mount.accept(e); }
    @SubscribeEvent public static void travel(EntityTravelToDimensionEvent e) { if (travel != null) travel.accept(e); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer(); level = server.overworld(); nether = server.getLevel(Level.NETHER);
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("relocation-audit-fixture.txt")), "isolated_fixture");
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_default_registry");
                level.setChunkForced(0, 0, true); nether.setChunkForced(0, 0, true);
                forced = true; deadline = server.getTickCount() + 600;
            }
            if (server.getTickCount() > deadline) throw new AssertionError("fixture_timeout");
            if (!level.isPositionEntityTicking(new BlockPos(4, -60, 4)) || !nether.isPositionEntityTicking(new BlockPos(4, 70, 4))) return;
            if (alice != null) { alice.pump(); bob.pump(); }
            if (start < 0) {
                start = server.getTickCount(); registry = CitizensAPI.getNPCRegistry();
                named = CitizensAPI.createInMemoryNPCRegistry("relocation-audit-memory");
                alice = actor("RelocationAlice"); bob = actor("RelocationBob");
                grant(alice.player, "citizens.npc.tp", "citizens.npc.tphere", "citizens.npc.despawn");
                selectGrant = grant(alice.player, "citizens.npc.select");
                spawnGrant = grant(alice.player, "citizens.npc.spawn");
                check(!PermissionUtil.hasPermission(alice.player, "citizens.admin")
                        && !PermissionUtil.hasPermission(alice.player, "citizens.npc.tphere.multiworld"), "independent_permissions");
                spawnCommands(); playerTeleport(); relocateCommands(); prompts(); completionOwnership(); npcCommandSources(); chatTriggerInput(); numericTriggerInput(); teleportTriggerInput(); movetoCommands(); tptoCommands(); blockBreakerEntityOwnership(); mountedCompletion();
            }
            if (server.getTickCount() == start + 1) mount = null;
            if (server.getTickCount() >= start + 5) {
                later.forEach(Runnable::run);
                close();
                LoggerFactory.getLogger("citizens").info("[RELOCATIONAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[RELOCATIONAUDIT] FAILED", failure);
            close();
        }
    }

    private static void spawnCommands() throws Exception {
        CommandSourceStack a = alice.player.createCommandSourceStack();
        NPC selected = npc(registry, EntityType.COW, false, alice.player, false);
        select(a, selected);
        for (EntityType<?> type : List.of(EntityType.COW, EntityType.PLAYER)) for (boolean packet : new boolean[] { false, true }) {
            for (String reference : List.of("id", "name", "uuid")) {
                NPC target = npc(reference.equals("uuid") ? named : registry, type, packet, alice.player, false);
                Location at = new Location(level, 3.25, -58, 4.75, 90, 30);
                target.getOrAddTrait(CurrentLocation.class).setLocation(at);
                String argument = reference.equals("id") ? Integer.toString(target.getId()) : reference.equals("uuid") ? target.getUniqueId().toString() : target.getName().toLowerCase(Locale.ROOT);
                run(a, "spawn " + argument + " -l");
                check(target.isSpawned() && CitizensAPI.getDefaultNPCSelector().getSelected(a) == target, "spawn_resolves_and_selects_" + reference + type + packet);
                at(target.getStoredLocation(), at, "stored_spawn_location_" + reference + type + packet);
                check(target.getOrAddTrait(Spawned.class).shouldSpawn(), "successful_spawn_sets_persistence");
                reject(a, "spawn " + argument);
            }
        }
        NPC byFlag = npc(registry, EntityType.COW, false, alice.player, false);
        run(a, "spawn --id " + byFlag.getId()); check(byFlag.isSpawned(), "global_id_target");
        NPC namedTarget = npc(registry, EntityType.COW, false, alice.player, false); namedTarget.setName("North Gate");
        run(a, "spawn \"north gate\""); check(namedTarget.isSpawned(), "quoted_name_target");
        NPC selectedOnly = npc(registry, EntityType.COW, false, alice.player, false);
        NPC flagOnly = npc(registry, EntityType.COW, false, alice.player, false);
        select(a, selectedOnly); selectGrant.remove();
        check(!PermissionUtil.hasPermission(alice.player, "citizens.npc.select"), "selection_permission_revoked");
        reject(a, "spawn --id " + flagOnly.getId());
        check(!selectedOnly.isSpawned() && !flagOnly.isSpawned(), "unauthorized_id_cannot_fall_back_to_selected_npc");
        selectGrant = grant(alice.player, "citizens.npc.select");
        reject(a, "spawn --uuid malformed");
        reject(a, "spawn --id invalid");
        NPC foreign = npc(registry, EntityType.COW, false, bob.player, false);
        reject(a, "spawn " + foreign.getId());
        check(!foreign.isSpawned(), "foreign_target_not_spawned");
        NPC owned = npc(registry, EntityType.COW, false, alice.player, false);
        select(a, foreign); run(a, "spawn " + owned.getId());
        check(owned.isSpawned() && !foreign.isSpawned(), "explicit_owned_target_ignores_foreign_selection");
        reject(bob.player.createCommandSourceStack(), "spawn " + foreign.getId());
        reject(a, "despawn " + foreign.getId());
        NPC overridden = npc(registry, EntityType.COW, false, alice.player, false);
        run(a, "spawn " + overridden.getId() + " --location 9.5,-58,9.25,minecraft:overworld,25,10");
        at(overridden.getStoredLocation(), new Location(level, 9.5, -58, 9.25, 25, 10), "explicit_spawn_location");
        NPC canceled = npc(registry, EntityType.COW, true, alice.player, false);
        select(a, selected); int messages = alice.player.messages.size();
        spawn = e -> { if (e.getNPC() == canceled) e.setCanceled(true); };
        run(a, "spawn " + canceled.getId()); spawn = null;
        check(canceled.getEntity() == null && !canceled.getOrAddTrait(Spawned.class).shouldSpawn()
                        && CitizensAPI.getDefaultNPCSelector().getSelected(a) == selected
                        && alice.player.messages.size() == messages, "canceled_spawn_has_no_success_side_effects");
        for (String suffix : List.of("--location NaN,1,2", "--entitylocation invalid", "--range NaN", "--range nope", "--unknown true", "-z"))
            reject(a, "spawn " + canceled.getId() + " " + suffix);
        check(canceled.getEntity() == null, "invalid_spawn_inputs_leave_target_unchanged");
        NPC unlocated = npc(registry, EntityType.COW, false, alice.player, false);
        unlocated.getOrAddTrait(CurrentLocation.class).setLocation(new Location(null, 0, 0, 0));
        CommandSourceStack context = a.withPosition(new Vec3(7.25, -58, 6.75)).withRotation(new Vec2(15, 45));
        run(context, "spawn " + unlocated.getId());
        at(unlocated.getStoredLocation(), new Location(level, 7.25, -58, 6.75, 45, 15), "missing_location_uses_native_context");
        NPC supersededSelection = npc(registry, EntityType.COW, false, alice.player, false);
        spawn = e -> { if (e.getNPC() == supersededSelection) select(a, foreign); };
        select(a, selected); run(a, "spawn " + supersededSelection.getId()); spawn = null;
        check(CitizensAPI.getDefaultNPCSelector().getSelected(a) == foreign, "spawn_completion_preserves_callback_selection");
        NPC ownerChanged = npc(registry, EntityType.COW, false, alice.player, false);
        select(a, selected);
        spawn = e -> { if (e.getNPC() == ownerChanged) ownerChanged.getOrAddTrait(Owner.class).setOwner(bob.player.getUUID()); };
        run(a, "spawn " + ownerChanged.getId()); spawn = null;
        check(ownerChanged.isSpawned() && CitizensAPI.getDefaultNPCSelector().getSelected(a) == selected,
                "spawn_completion_does_not_select_newly_foreign_npc");
    }

    private static void playerTeleport() throws Exception {
        CommandSourceStack a = alice.player.createCommandSourceStack();
        NPC target = npc(registry, EntityType.COW, false, alice.player, false);
        Location saved = new Location(level, 5, -58, 5, 90, 60); target.getOrAddTrait(CurrentLocation.class).setLocation(saved);
        select(a, target); run(a, "tp");
        at(Location.of(alice.player), new Location(level, 4.5, -58, 5, 270, 0), "tp_front_of_unspawned_npc");
        run(a, "teleport -e");
        at(Location.of(alice.player), saved, "tp_exact_preserves_rotation");
        at(target.getStoredLocation(), saved, "tp_does_not_mutate_npc_location");
        reject(a, "tp -x");
        target.getOrAddTrait(CurrentLocation.class).setLocation(new Location(nether, 3, 70, 3, 45, -10));
        int messages = alice.player.messages.size(); Location before = Location.of(alice.player);
        travel = e -> { if (e.getEntity() == alice.player) e.setCanceled(true); };
        run(a, "tp -e"); travel = null;
        at(Location.of(alice.player), before, "native_player_travel_cancellation_preserves_location");
        check(alice.player.messages.size() == messages, "failed_player_teleport_does_not_report_success");
        run(a, "tp -e"); check(alice.player.level() == nether, "player_tp_crosses_dimension");
        moveAlice(new Location(level, 4, -58, 4, 0, 0));
    }

    private static void relocateCommands() throws Exception {
        for (EntityType<?> type : List.of(EntityType.COW, EntityType.PLAYER)) for (boolean packet : new boolean[] { false, true }) {
            NPC target = npc(registry, type, packet, alice.player, true);
            CommandSourceStack source = alice.player.createCommandSourceStack().withPosition(new Vec3(-1.25, -58.6, 2.75)).withRotation(new Vec2(60, 90));
            select(source, target);
            for (String flags : List.of("", "-c", "-f", "-cf")) {
                run(source, "tphere " + flags);
                Location expected = switch (flags) {
                    case "-c" -> new Location(level, -1.5, -59, 2.5, 0, 0);
                    case "-f" -> new Location(level, -1.75, -58.6, 2.75, 270, 0);
                    case "-cf" -> new Location(level, -1.5, -59, 3.5, 180, 0);
                    default -> new Location(level, -1.25, -58.6, 2.75, 90, 60);
                };
                at(target.getStoredLocation(), expected, "native_tphere_geometry_" + type + packet + flags);
                check(!alice.player.messages.get(alice.player.messages.size() - 1).contains("{1}"), "movement_feedback_has_destination");
            }
            Entity old = target.getEntity(); Location before = target.getStoredLocation(); int messages = alice.player.messages.size();
            teleport = e -> { if (e.getNPC() == target) e.setCanceled(true); };
            run(source, "move --location 4,-58,4"); teleport = null;
            at(target.getStoredLocation(), before, "canceled_move_preserves_location");
            check(alice.player.messages.size() == messages, "canceled_move_has_no_success_feedback");
            reject(source, "tphere --location 4,70,4,minecraft:the_nether");
            check(target.getEntity() == old, "cross_world_permission_checked_before_transfer");
            PermissionUtil.Attachment multi = grant(alice.player, "citizens.npc.tphere.multiworld");
            run(source, "tph --location 4,70,4,minecraft:the_nether");
            check(target.getEntity().level() == nether && target.getEntity().getUUID().equals(old.getUUID()), "permitted_native_cross_world_move");
            run(source, "move --location 4,-58,4,minecraft:overworld"); multi.remove();
            target.despawn(); target.getOrAddTrait(Spawned.class).setSpawned(false);
            messages = alice.player.messages.size();
            teleport = e -> { if (e.getNPC() == target) e.setCanceled(true); };
            run(source, "tphere"); teleport = null;
            check(target.getEntity() == null && !target.getOrAddTrait(Spawned.class).shouldSpawn()
                    && alice.player.messages.size() == messages, "unspawned_teleport_event_can_cancel_without_side_effects");
            teleport = e -> { if (e.getNPC() == target) e.getTo().add(2, 1, 3); };
            run(source, "tphere"); teleport = null;
            at(target.getStoredLocation(), new Location(level, 0.75, -57.6, 5.75, 90, 60), "unspawned_event_destination_applied");
        }
        NPC target = npc(registry, EntityType.COW, false, alice.player, true);
        moveAlice(new Location(level, 4, -58, 4, 0, 0)); CommandSourceStack a = alice.player.createCommandSourceStack(); select(a, target);
        BlockPos block = new BlockPos(4, -57, 8); blocks.put(block, level.getBlockState(block)); level.setBlock(block, Blocks.STONE.defaultBlockState(), 3);
        run(a, "tphere cursor -cf"); at(target.getStoredLocation(), new Location(level, 4.5, -56, 9.5, 180, 0), "cursor_center_front_order");
        level.setBlock(block, Blocks.WATER.defaultBlockState(), 3);
        run(a, "tphere cursor"); at(target.getStoredLocation(), new Location(level, 4, -56, 8, 0, 0), "cursor_can_target_fluid");
        level.setBlock(block, blocks.get(block), 3);
        moveAlice(new Location(level, 4, -58, 4, 0, -90)); Location before = target.getStoredLocation();
        reject(alice.player.createCommandSourceStack(), "tphere cursor"); at(target.getStoredLocation(), before, "cursor_miss_does_not_fall_back");
        reject(a, "tphere typo"); reject(a, "tphere --location NaN,1,2"); reject(a, "tphere --unknown true");
        CommandSourceStack console = server.createCommandSourceStack().withPosition(new Vec3(12.25, -58, 12.75));
        select(console, target); run(console, "move -c"); at(target.getStoredLocation(), new Location(level, 12.5, -58, 12.5), "console_context_center");
        reject(console, "tphere cursor");
    }

    private static void prompts() throws Exception {
        CommandSourceStack a = alice.player.createCommandSourceStack();
        NPC first = npc(registry, EntityType.COW, false, alice.player, false);
        NPC second = npc(registry, EntityType.COW, false, alice.player, false);
        first.setName("SpawnChoice"); second.setName("SpawnChoice");
        run(a, "spawn SpawnChoice"); check(ChatPrompts.isActive(alice.player), "duplicate_name_prompts");
        ChatPrompts.acceptInput(alice.player, Integer.toString(second.getId()));
        check(second.isSpawned() && !first.isSpawned(), "prompt_spawns_only_chosen_candidate");
        second.despawn(); second.getOrAddTrait(Spawned.class).setSpawned(false);
        run(a, "spawn SpawnChoice"); second.getOrAddTrait(Owner.class).setOwner(bob.player.getUUID());
        ChatPrompts.acceptInput(alice.player, Integer.toString(second.getId()));
        check(!second.isSpawned(), "prompt_rechecks_ownership");
        second.getOrAddTrait(Owner.class).setOwner(alice.player.getUUID());
        run(a, "spawn SpawnChoice"); spawnGrant.remove();
        ChatPrompts.acceptInput(alice.player, Integer.toString(second.getId()));
        check(!second.isSpawned(), "prompt_rechecks_command_permission");
        spawnGrant = grant(alice.player, "citizens.npc.spawn");
        run(a, "spawn SpawnChoice");
        UUID uuid = second.getUniqueId(); int id = second.getId(); second.destroy();
        NPC replacement = registry.createNPC(EntityType.COW, uuid, id, "Replacement"); npcs.add(replacement);
        replacement.getOrAddTrait(Owner.class).setOwner(alice.player.getUUID());
        ChatPrompts.acceptInput(alice.player, Integer.toString(id));
        check(!replacement.isSpawned() && ChatPrompts.isActive(alice.player), "prompt_rejects_replaced_identity");
        ChatPrompts.acceptInput(alice.player, "exit");
    }

    private static void completionOwnership() throws Exception {
        for (boolean packet : new boolean[] { false, true }) {
            NPC target = npc(registry, EntityType.COW, packet, alice.player, true);
            int[] callbacks = { 0, 0 };
            teleport = e -> {
                if (e.getNPC() != target) return;
                teleport = null;
                target.teleport(new Location(level, 11, -58, 11), TeleportCause.PLUGIN, entity -> callbacks[1]++);
            };
            target.teleport(new Location(level, 8, -58, 8), TeleportCause.PLUGIN, entity -> callbacks[0]++); teleport = null;
            at(target.getStoredLocation(), new Location(level, 11, -58, 11), "newer_teleport_destination_wins_" + packet);
            check(callbacks[0] == 0 && callbacks[1] == 1, "only_newer_teleport_completes_" + packet);
            target.despawn();
            spawn = e -> {
                if (e.getNPC() == target)
                    target.teleport(new Location(level, 12, -58, 12), TeleportCause.PLUGIN, entity -> callbacks[1]++);
            };
            target.spawn(new Location(level, 4, -58, 4)); spawn = null;
            at(target.getStoredLocation(), new Location(level, 12, -58, 12), "spawn_deferred_teleport_destination_" + packet);
            check(callbacks[1] == 2, "spawn_deferred_teleport_completes_once_" + packet);
        }
        NPC target = npc(registry, EntityType.COW, false, alice.player, true);
        Entity original = target.getEntity(); int[] callbacks = { 0, 0 };
        join = e -> { if (net.citizensnpcs.npc.NPCRegistries.lookup(e.getEntity()) == target && e.getLevel() == nether) e.setCanceled(true); };
        target.teleport(new Location(nether, 4, 70, 4), TeleportCause.PLUGIN, entity -> callbacks[0]++); join = null;
        check(target.getEntity() == original && target.getEntity().level() == level && callbacks[0] == 0,
                "rejected_native_admission_does_not_complete");
        join = e -> {
            if (net.citizensnpcs.npc.NPCRegistries.lookup(e.getEntity()) != target || e.getLevel() != nether) return;
            join = null;
            target.teleport(new Location(level, 13, -58, 13), TeleportCause.PLUGIN, entity -> callbacks[1]++);
        };
        target.teleport(new Location(nether, 4, 70, 4), TeleportCause.PLUGIN, entity -> callbacks[0]++); join = null;
        at(target.getStoredLocation(), new Location(level, 13, -58, 13), "admission_callback_destination_wins");
        check(callbacks[0] == 0 && movedAndIndexed(target) && callbacks[1] == 1, "admission_deferred_callback_owns_completion");
        NPC root = npc(registry, EntityType.COW, false, alice.player, true);
        NPC rider = npc(registry, EntityType.COW, true, alice.player, true);
        check(rider.getEntity().startRiding(root.getEntity(), true), "rollback_mount_setup");
        join = e -> { if (net.citizensnpcs.npc.NPCRegistries.lookup(e.getEntity()) == root && e.getLevel() == nether) e.setCanceled(true); };
        rider.teleport(new Location(nether, 6, 70, 6), TeleportCause.PLUGIN, entity -> callbacks[0]++); join = null;
        check(root.getEntity().level() == level && rider.getEntity().getVehicle() == root.getEntity() && callbacks[0] == 0,
                "rolled_back_mount_transfer_does_not_complete");
    }

    private static boolean movedAndIndexed(NPC npc) {
        return npc.isSpawned() && npc.getEntity().level() instanceof ServerLevel world && world.getEntity(npc.getUniqueId()) == npc.getEntity();
    }

    private static void npcCommandSources() throws Exception {
        for (EntityType<?> type : List.of(EntityType.COW, EntityType.PLAYER)) for (boolean packet : new boolean[] { false, true }) {
            NPC actor = npc(registry, type, packet, alice.player, true);
            CommandSourceStack source = actor.getEntity().createCommandSourceStack().withPermission(4);
            NPC target = npc(registry, EntityType.COW, false, bob.player, false);
            run(source, "spawn " + target.getId());
            check(target.isSpawned(), "live_npc_command_source_can_spawn_" + type + packet);
            select(source, target);
            run(source, "tphere --location 12,-58,12");
            at(target.getStoredLocation(), new Location(level, 12, -58, 12), "live_npc_command_source_can_move_" + type + packet);
            if (type == EntityType.PLAYER) {
                target.teleport(new Location(nether, 8, 70, 8), TeleportCause.PLUGIN);
                run(source, "tp -e");
                at(actor.getStoredLocation(), new Location(nether, 8, 70, 8), "player_npc_command_source_tp_" + packet);
                check((nether.getEntity(actor.getEntity().getUUID()) == actor.getEntity()) == !packet,
                        "player_npc_tp_preserves_transport_" + packet);
            }
            run(source, "despawn " + target.getId());
            check(!target.isSpawned(), "live_npc_command_source_can_despawn_" + type + packet);
            actor.despawn();
            reject(source, "spawn " + target.getId());
            check(!target.isSpawned(), "retired_npc_command_source_cannot_act_" + type + packet);
        }
        ServerPlayer automated = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new GameProfile(UUID.randomUUID(), "RelocationAutomation"));
        NPC target = npc(registry, EntityType.COW, false, bob.player, false);
        CommandSourceStack source = automated.createCommandSourceStack().withPermission(4);
        run(source, "spawn " + target.getId());
        check(target.isSpawned(), "native_fake_player_command_source_can_spawn");
        run(source, "despawn " + target.getId());
        automated.discard();
        reject(source, "spawn " + target.getId());
        check(!target.isSpawned(), "retired_fake_player_command_source_rejected");
    }

    private static void chatTriggerInput() {
        String key = net.citizensnpcs.trait.waypoint.triggers.WaypointTriggerPrompt.CREATED_TRIGGER_KEY;
        var prompt = new net.citizensnpcs.trait.waypoint.triggers.ChatTriggerPrompt();
        var session = ChatPrompts.begin(alice.player, prompt);
        ChatPrompts.acceptInput(alice.player, "radiusStory Must Stay");
        ChatPrompts.acceptInput(alice.player, "RADIUS\t 8.5");
        var persister = new net.citizensnpcs.trait.waypoint.triggers.WaypointTriggerRegistry();
        for (String invalid : List.of("radius NaN", "radius Infinity", "radius -Infinity", "radius 4 extra", "radius")) {
            ChatPrompts.acceptInput(alice.player, invalid);
            DataKey snapshot = new MemoryDataKey();
            persister.save(prompt.createFromShortInput(session, "Snapshot"), snapshot);
            check(ChatPrompts.isActive(session) && session.getSessionData(key) == null && snapshot.getDouble("radius") == 8.5,
                    "invalid_chat_radius_preserves_value_" + invalid);
        }
        ChatPrompts.acceptInput(alice.player, "Hello CASE");
        ChatPrompts.acceptInput(alice.player, "finish");
        DataKey saved = new MemoryDataKey();
        persister.save((net.citizensnpcs.trait.waypoint.triggers.WaypointTrigger) session.getSessionData(key), saved);
        check(!ChatPrompts.isActive(session) && saved.getDouble("radius") == 8.5
                && saved.getString("lines.0").equals("radiusStory Must Stay") && saved.getString("lines.1").equals("Hello CASE"),
                "chat_radius_keyword_preserves_ordinary_text");
        prompt = new net.citizensnpcs.trait.waypoint.triggers.ChatTriggerPrompt();
        session = ChatPrompts.begin(alice.player, prompt);
        ChatPrompts.acceptInput(alice.player, "radius -1");
        ChatPrompts.acceptInput(alice.player, "finish");
        saved = new MemoryDataKey();
        persister.save((net.citizensnpcs.trait.waypoint.triggers.WaypointTrigger) session.getSessionData(key), saved);
        check(saved.getDouble("radius") == -1, "chat_radius_preserves_unlimited_range_value");
    }

    private static void numericTriggerInput() {
        String key = net.citizensnpcs.trait.waypoint.triggers.WaypointTriggerPrompt.CREATED_TRIGGER_KEY;
        for (boolean speed : new boolean[]{false, true}) {
            net.citizensnpcs.trait.waypoint.triggers.WaypointTriggerPrompt prompt = speed
                    ? new net.citizensnpcs.trait.waypoint.triggers.SpeedTriggerPrompt()
                    : new net.citizensnpcs.trait.waypoint.triggers.DelayTriggerPrompt();
            Object sentinel = new net.citizensnpcs.trait.waypoint.triggers.DelayTrigger(7);
            var session = ChatPrompts.begin(alice.player, prompt, Map.of(key, sentinel));
            for (String invalid : List.of("invalid", "NaN", "Infinity", "1e100")) {
                ChatPrompts.acceptInput(alice.player, invalid);
                check(ChatPrompts.isActive(session) && session.getSessionData(key) == sentinel,
                        "invalid_numeric_trigger_preserves_session_" + speed + invalid);
            }
            ChatPrompts.acceptInput(alice.player, speed ? "1.25" : "3s");
            Object created = session.getSessionData(key);
            check(!ChatPrompts.isActive(session) && (speed
                    ? created instanceof net.citizensnpcs.trait.waypoint.triggers.SpeedTrigger speedTrigger && speedTrigger.getSpeed() == 1.25F
                    : created instanceof net.citizensnpcs.trait.waypoint.triggers.DelayTrigger delayTrigger && delayTrigger.getDelay() == 60),
                    "valid_numeric_trigger_completes_" + speed);
            if (speed) {
                NPC target = npc(registry, EntityType.COW, false, alice.player, true);
                ((net.citizensnpcs.trait.waypoint.triggers.WaypointTrigger) created).onWaypointReached(target, null);
                check(target.getNavigator().getDefaultParameters().speedModifier() == 1.25F, "validated_speed_trigger_executes");
            }
        }
        var session = ChatPrompts.begin(alice.player, new net.citizensnpcs.trait.waypoint.triggers.TriggerAddPrompt());
        ChatPrompts.acceptInput(alice.player, "delay invalid");
        check(ChatPrompts.isActive(session) && session.getSessionData(key) == null, "invalid_inline_delay_keeps_editing");
        ChatPrompts.acceptInput(alice.player, "3s");
        check(!ChatPrompts.isActive(session)
                && session.getSessionData(key) instanceof net.citizensnpcs.trait.waypoint.triggers.DelayTrigger trigger
                && trigger.getDelay() == 60, "invalid_inline_delay_can_be_repaired");
    }

    private static void teleportTriggerInput() {
        String createdKey = net.citizensnpcs.trait.waypoint.triggers.WaypointTriggerPrompt.CREATED_TRIGGER_KEY;
        var prompt = new net.citizensnpcs.trait.waypoint.triggers.TeleportTriggerPrompt();
        var sentinel = new net.citizensnpcs.trait.waypoint.triggers.TeleportTrigger(Location.of(alice.player));
        var session = ChatPrompts.begin(alice.player, prompt, Map.of(createdKey, sentinel));
        for (String invalid : List.of("minecraft:the_nether:1:2", "missing:overworld:1:2:3",
                "overworld:NaN:2:3", "minecraft:overworld::2:3")) {
            ChatPrompts.acceptInput(alice.player, invalid);
            check(ChatPrompts.isActive(session) && session.getSessionData(createdKey) == sentinel,
                    "invalid_teleport_trigger_input_preserves_session_" + invalid);
        }
        ChatPrompts.acceptInput(alice.player, "minecraft:the_nether:4:70:4");
        check(!ChatPrompts.isActive(session) && session.getSessionData(createdKey) instanceof net.citizensnpcs.trait.waypoint.triggers.TeleportTrigger,
                "namespaced_teleport_trigger_prompt_completes");
        NPC target = npc(registry, EntityType.COW, false, alice.player, true);
        ((net.citizensnpcs.trait.waypoint.triggers.WaypointTrigger) session.getSessionData(createdKey)).onWaypointReached(target, null);
        at(target.getStoredLocation(), new Location(nether, 4, 70, 4), "namespaced_teleport_trigger_executes");
        session = ChatPrompts.begin(alice.player, new net.citizensnpcs.trait.waypoint.triggers.TriggerAddPrompt());
        ChatPrompts.acceptInput(alice.player, "teleport minecraft:overworld:6:-58:6");
        check(!ChatPrompts.isActive(session) && session.getSessionData(createdKey) instanceof net.citizensnpcs.trait.waypoint.triggers.TeleportTrigger,
                "inline_teleport_trigger_completes");
        ((net.citizensnpcs.trait.waypoint.triggers.WaypointTrigger) session.getSessionData(createdKey)).onWaypointReached(target, null);
        at(target.getStoredLocation(), new Location(level, 6, -58, 6), "inline_teleport_trigger_executes");
        Location here = Location.of(alice.player);
        session = ChatPrompts.begin(alice.player, new net.citizensnpcs.trait.waypoint.triggers.TriggerAddPrompt());
        ChatPrompts.acceptInput(alice.player, "teleport here");
        check(!ChatPrompts.isActive(session) && session.getSessionData(createdKey) instanceof net.citizensnpcs.trait.waypoint.triggers.TeleportTrigger,
                "inline_here_trigger_completes");
        ((net.citizensnpcs.trait.waypoint.triggers.WaypointTrigger) session.getSessionData(createdKey)).onWaypointReached(target, null);
        at(target.getStoredLocation(), here, "inline_here_trigger_captures_player_location");
        session = ChatPrompts.begin(alice.player, new net.citizensnpcs.trait.waypoint.triggers.TriggerAddPrompt());
        ChatPrompts.acceptInput(alice.player, "teleport missing:overworld:1:2:3");
        check(ChatPrompts.isActive(session) && session.getSessionData(createdKey) == null, "invalid_inline_trigger_keeps_editing");
        ChatPrompts.abandon(session);
    }

    private static void movetoCommands() throws Exception {
        grant(alice.player, "citizens.npc.moveto");
        CommandSourceStack source = alice.player.createCommandSourceStack();
        for (EntityType<?> type : List.of(EntityType.COW, EntityType.PLAYER)) {
            for (boolean packet : new boolean[] { false, true }) {
                NPC target = npc(named, type, packet, alice.player, false);
                select(source, target); Location before = target.getStoredLocation();
                for (String invalid : List.of("1:2", "--x NaN", "--yaw 1e100", "--world missing:overworld", "--unknown 1")) {
                    reject(source, "moveto " + invalid);
                    check(!target.isSpawned() && target.getEntity() == null, "invalid_moveto_does_not_spawn_" + invalid + type + packet);
                    at(target.getStoredLocation(), before, "invalid_moveto_preserves_location_" + invalid + type + packet);
                }
                int messages = alice.player.messages.size();
                teleport = event -> { if (event.getNPC() == target) event.setCanceled(true); };
                try { run(source, "moveto 7.25 -58 6.5 minecraft:overworld"); }
                finally { teleport = null; }
                check(!target.isSpawned() && alice.player.messages.size() == messages, "canceled_unspawned_moveto_has_no_effect_" + type + packet);
                spawn = event -> { if (event.getNPC() == target) event.setCanceled(true); };
                try { run(source, "moveto 7.25 -58 6.5 minecraft:overworld"); }
                finally { spawn = null; }
                check(!target.isSpawned() && target.getEntity() == null && alice.player.messages.size() == messages,
                        "canceled_moveto_spawn_has_no_success_" + type + packet);
                run(source, "moveto 7.25 -58 6.5 minecraft:overworld");
                at(target.getStoredLocation(), new Location(level, 7.25, -58, 6.5), "moveto_spawns_at_validated_destination_" + type + packet);
                check(target.isSpawned() && alice.player.messages.size() == messages + 1, "moveto_spawn_reports_once_" + type + packet);
                run(source, "moveto --x 8.5 --yaw 90 --pitch 20");
                at(Location.of(target.getEntity()), new Location(level, 8.5, -58, 6.5, 90, 20), "moveto_flags_preserve_requested_rotation_" + type + packet);
                messages = alice.player.messages.size(); before = target.getStoredLocation();
                teleport = event -> { if (event.getNPC() == target) event.setCanceled(true); };
                try { run(source, "moveto 4:70:4:minecraft:the_nether"); }
                finally { teleport = null; }
                at(target.getStoredLocation(), before, "canceled_spawned_moveto_preserves_location_" + type + packet);
                check(alice.player.messages.size() == messages, "canceled_spawned_moveto_has_no_success_" + type + packet);
                run(source, "moveto 4:70:4:minecraft:the_nether");
                at(target.getStoredLocation(), new Location(nether, 4, 70, 4, 90, 20), "moveto_supports_namespaced_colon_destination_" + type + packet);
                check(alice.player.messages.size() == messages + 1, "moveto_cross_world_reports_once_" + type + packet);
            }
        }
    }

    private static void tptoCommands() throws Exception {
        grant(alice.player, "citizens.npc.tpto");
        CommandSourceStack source = alice.player.createCommandSourceStack();
        NPC destination = npc(registry, EntityType.COW, false, alice.player, true);
        destination.teleport(new Location(level, 12, -58, 12, 60, 15), TeleportCause.PLUGIN);
        Location expected = Location.of(destination.getEntity());
        for (EntityType<?> type : List.of(EntityType.COW, EntityType.PLAYER)) {
            for (boolean packet : new boolean[] { false, true }) {
                NPC target = npc(named, type, packet, alice.player, true);
                select(source, target);
                Location before = target.getStoredLocation(); int messages = alice.player.messages.size();
                teleport = event -> { if (event.getNPC() == target) event.setCanceled(true); };
                try { run(source, "tpto " + destination.getId()); }
                finally { teleport = null; }
                at(target.getStoredLocation(), before, "tpto_cancellation_preserves_target_" + type + packet);
                check(alice.player.messages.size() == messages, "canceled_tpto_has_no_success_" + type + packet);
                run(source, "tpto " + destination.getId());
                at(target.getStoredLocation(), expected, "tpto_moves_selected_named_registry_npc_" + type + packet);
                check(alice.player.messages.size() == messages + 1, "completed_tpto_reports_once_" + type + packet);
            }
        }
        destination.teleport(new Location(nether, 4, 70, 4), TeleportCause.PLUGIN);
        check(destination.getEntity().level() == nether, "tpto_cross_world_destination_setup");
        Location before = Location.of(alice.player); int messages = alice.player.messages.size();
        travel = event -> { if (event.getEntity() == alice.player) event.setCanceled(true); };
        try { run(source, "tpto " + alice.player.getGameProfile().getName() + " " + destination.getId()); }
        finally { travel = null; }
        at(Location.of(alice.player), before, "tpto_native_player_cancellation_preserves_location");
        check(alice.player.messages.size() == messages, "tpto_native_player_cancellation_has_no_success");
        run(source, "tpto " + alice.player.getGameProfile().getName() + " " + destination.getId());
        at(Location.of(alice.player), Location.of(destination.getEntity()), "tpto_native_player_cross_world_completion");
        check(alice.player.messages.size() == messages + 1, "tpto_native_player_success_reports_once");
        moveAlice(before);
    }

    private static void blockBreakerEntityOwnership() {
        BlockPos target = new BlockPos(14,-58,14);
        BlockState beforeBlock = level.getBlockState(target);
        blocks.putIfAbsent(target, beforeBlock);
        Location beforePlayer = Location.of(alice.player);
        level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
        int[] effects = {0};
        var config = new BlockBreaker.BlockBreakerConfiguration().blockStrengthModifier(1000000);
        config.blockBreaker((block,item) -> effects[0]++);
        var breaker = new net.citizensnpcs.npc.CitizensBlockBreaker(null, alice.player, level, target, config);
        try {
            check(breaker.shouldExecute(), "player_block_breaker_admitted_in_captured_world");
            moveAlice(new Location(nether,4,70,4));
            check(!breaker.shouldExecute() && breaker.run() == net.citizensnpcs.api.ai.tree.BehaviorStatus.FAILURE
                    && effects[0] == 0 && !level.getBlockState(target).isAir(), "block_breaker_rejects_transferred_player_entity");
        } finally { breaker.reset(); moveAlice(beforePlayer); }
        try {
            NPC owner = npc(registry, EntityType.COW, false, alice.player, true);
            var mismatched = new net.citizensnpcs.npc.CitizensBlockBreaker(owner, alice.player, level, target, config);
            check(!mismatched.shouldExecute() && mismatched.run() == net.citizensnpcs.api.ai.tree.BehaviorStatus.FAILURE
                    && effects[0] == 0, "block_breaker_rejects_mismatched_npc_entity");
            mismatched.reset();
        } finally { level.setBlock(target, beforeBlock, 3); }
    }

    private static void mountedCompletion() throws Exception {
        NPC parent = npc(registry, EntityType.COW, false, alice.player, true);
        NPC child = npc(registry, EntityType.COW, true, alice.player, true);
        check(child.getEntity().startRiding(parent.getEntity(), true), "mounted_setup");
        CommandSourceStack a = alice.player.createCommandSourceStack(); select(a, child);
        int messages = alice.player.messages.size();
        mount = e -> { if (e.isMounting() && e.getEntityMounting() == child.getEntity()) e.setCanceled(true); };
        run(a, "tphere --location 6,-58,6");
        check(alice.player.messages.size() == messages && child.getEntity().getVehicle() == null, "pending_remount_does_not_report_success");
        later.add(() -> check(child.getEntity().getVehicle() == parent.getEntity() && alice.player.messages.size() == messages + 1,
                "completed_remount_reports_success_once"));
    }

    private static NPC npc(NPCRegistry in, EntityType<?> type, boolean packet, ServerPlayer owner, boolean spawned) {
        NPC npc = in.createNPC(type, "Relocation" + sequence++); npcs.add(npc);
        npc.getOrAddTrait(Owner.class).setOwner(owner.getUUID()); npc.getOrAddTrait(LookClose.class).setEnabled(false);
        npc.getOrAddTrait(CurrentLocation.class).setLocation(new Location(level, 4, -58, 4)); npc.getOrAddTrait(Spawned.class).setSpawned(false);
        if (type == EntityType.PLAYER) npc.getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false);
        if (packet) npc.getOrAddTrait(PacketNPC.class);
        if (spawned) { check(npc.spawn(new Location(level, 4, -58, 4)), "fixture_npc_spawn"); npc.getEntity().setNoGravity(true); }
        return npc;
    }
    private static PermissionUtil.Attachment grant(ServerPlayer player, String... permissions) {
        var grant = PermissionUtil.grantTemporary(player, List.of(permissions)); grants.add(grant); return grant;
    }
    private static void select(CommandSourceStack source, NPC npc) { CitizensAPI.getDefaultNPCSelector().select(source, npc); }
    private static void run(CommandSourceStack source, String command) throws Exception {
        check(server.getCommands().getDispatcher().execute("npc " + command.trim(), source) == 1, "dispatch_" + command);
        alice.pump();
    }
    private static void reject(CommandSourceStack source, String command) throws Exception {
        try { check(server.getCommands().getDispatcher().execute("npc " + command, source) == 0, "reject_" + command); }
        catch (CommandSyntaxException expected) { check(true, "reject_" + command); }
    }
    private static void moveAlice(Location at) {
        check(EntityUtil.teleport(alice.player, at) != null, "fixture_player_move"); alice.pump();
    }
    private static void at(Location actual, Location expected, String label) {
        check(actual != null && actual.getWorld() == expected.getWorld() && Math.abs(actual.getX() - expected.getX()) < 1e-6
                && Math.abs(actual.getY() - expected.getY()) < 1e-6 && Math.abs(actual.getZ() - expected.getZ()) < 1e-6
                && Math.abs(Mth.wrapDegrees(actual.getYaw() - expected.getYaw())) < 1e-4
                && Math.abs(actual.getPitch() - expected.getPitch()) < 1e-4,
                label + " actual=" + actual + " expected=" + expected);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; }
    private static void close() {
        if (done) return;
        done = true; spawn = null; teleport = null; join = null; mount = null; travel = null; ChatPrompts.abandonAll();
        try {
            for (NPC npc : npcs) if (npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc) npc.destroy();
            if (named != null) CitizensAPI.removeNamedNPCRegistry("relocation-audit-memory");
            for (var entry : blocks.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), 3);
            grants.forEach(PermissionUtil.Attachment::remove);
            for (Actor actor : new Actor[] { alice, bob }) if (actor != null) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
            if (forced) { level.setChunkForced(0, 0, false); nether.setChunkForced(0, 0, false); }
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[RELOCATIONAUDIT] FAILED cleanup", failure); }
        finally { server.halt(false); }
    }

    private static Actor actor(String name) {
        Actor actor = new Actor();
        ClientInformation information = ClientInformation.createDefault();
        actor.player = new AuditPlayer(name);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("relocation-audit-capture", new ChannelOutboundHandlerAdapter() {
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
        actor.player.setPos(4, -58, 4); actor.player.setNoGravity(true);
        return actor;
    }
    private static final class AuditPlayer extends ServerPlayer {
        final List<String> messages = new ArrayList<>();
        AuditPlayer(String name) { super(RelocationRuntimeAudit.server, RelocationRuntimeAudit.level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
    }
    private static final class Actor {
        AuditPlayer player; EmbeddedChannel channel; int batches;
        final List<Integer> teleports = new ArrayList<>();
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            if (packet instanceof ClientboundChunkBatchFinishedPacket) batches++;
            if (packet instanceof ClientboundPlayerPositionPacket position) teleports.add(position.getId());
        }
        void pump() {
            channel.runPendingTasks();
            while (batches > 0) { batches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            for (int id : List.copyOf(teleports)) player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
            teleports.clear();
            Object value; while ((value = channel.readOutbound()) != null) ReferenceCountUtil.release(value);
        }
    }
}
