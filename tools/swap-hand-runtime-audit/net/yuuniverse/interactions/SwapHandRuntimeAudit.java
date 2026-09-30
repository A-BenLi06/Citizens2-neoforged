package net.yuuniverse.interactions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.NPCVisibility;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

/** Opt-in native packet/event audit. No client, production registry or saved NPC data is used. */
@EventBusSubscriber(modid = "interactions")
public final class SwapHandRuntimeAudit {
    private static final Path ROOT = Path.of("config/interactions");
    private static final BlockPos WALL = new BlockPos(4, -59, 6);
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<Actor> actors = new ArrayList<>();
    private static final List<Entity> entities = new ArrayList<>();
    private static boolean forced, finished;
    private static int phase, deadline, passed, handEvents;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static NPCRegistry registry;
    private static NPC primary, near, packet, named;
    private static Actor alice;
    private static InteractionsMod controller;
    private static ConversationLibrary library;
    private static Map<UUID, Session> sessions;
    private static PermissionUtil.PermissionResolver previousResolver;
    private static Consumer<LivingSwapItemsEvent.Hands> handHook;

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void hands(LivingSwapItemsEvent.Hands event) {
        if (alice == null || event.getEntity() != alice.player) return;
        handEvents++;
        if (handHook != null) handHook.accept(event);
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (finished) return;
        server = event.getServer(); level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 1200; }
        try {
            for (Actor actor : actors) actor.pump();
            if (server.getTickCount() > deadline) throw new AssertionError("fixture_timeout_phase_" + phase);
            if (!level.areEntitiesLoaded(0L) || !level.isPositionEntityTicking(new BlockPos(4, -60, 4))) return;
            if (phase == 0) { initialize(); phase++; }
            else if (phase == 1 && alice.watches()) { spawn(); phase++; }
            else if (phase == 2 && npcs.stream().allMatch(npc -> NPCVisibility.isTracked(npc.getEntity(), alice.player))) {
                modesAndLookup(); geometry(); admission(); nativeListeners(); callbacks(); reload(); currentPlayers();
                finished = true;
            }
        } catch (Throwable failure) {
            finished = true; LoggerFactory.getLogger("interactions").error("[SWAPHANDAUDIT] FAILED phase " + phase, failure);
            cleanup(); server.halt(false); return;
        }
        if (finished) {
            if (cleanup()) LoggerFactory.getLogger("interactions").info("[SWAPHANDAUDIT] COMPLETE {} checks", passed);
            server.halt(false);
        }
    }

    @SuppressWarnings("unchecked") private static void initialize() throws Exception {
        check(Files.isRegularFile(Path.of("swap-hand-audit-fixture.txt"))
                && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
        previousResolver = PermissionUtil.getPermissionResolver();
        controller = new InteractionsMod();
        field(controller, "actions", new Actions(new ItemLibrary(), new Economy()));
        library = (ConversationLibrary) field(controller, "library");
        sessions = (Map<UUID, Session>) field(controller, "sessions");
        controller.onRegisterCommands(new RegisterCommandsEvent(server.getCommands().getDispatcher(), Commands.CommandSelection.DEDICATED,
                CommandBuildContext.simple(server.registryAccess(), FeatureFlags.DEFAULT_FLAGS)));
        registry = CitizensAPI.createNamedNPCRegistry("swap-hand-audit", new MemoryNPCDataStore());
        alice = actor("SwapAlice"); resetPlayer(alice.player);
    }

    private static void spawn() throws Exception {
        primary = npc("SharedName", false, 7.5);
        near = npc("Nearest", false, 11.0);
        packet = npc("PacketSpeaker", true, 12.0);
        named = npc("NamedSpeaker", false, 13.0);
        Files.createDirectories(ROOT.resolve("conversations"));
        definition("primary", "NPC with id " + primary.getId());
        definition("fallback", "NPC named SharedName");
        definition("nearest", "NPC with id " + near.getId());
        definition("packet", "NPC with id " + packet.getId());
        definition("named", "NPC named namedspeaker");
        library.load(ROOT.resolve("conversations").toFile());
        check(library.size() == 5, "legacy_conversations_loaded");
        mode(ConversationStartClick.SWAP_HAND_CLICK);
    }

    private static NPC npc(String name, boolean virtual, double x) {
        NPC npc = registry.createNPC(EntityType.VILLAGER, name); npcs.add(npc);
        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        if (virtual) npc.getOrAddTrait(PacketNPC.class);
        check(npc.spawn(new Location(level, x, -60, 10.5)), "spawn_" + name);
        npc.getEntity().setNoGravity(true); return npc;
    }

    private static void definition(String id, String trigger) throws Exception {
        Files.writeString(ROOT.resolve("conversations").resolve(id + ".yml"), """
                name: Swap audit
                starts_with:
                  - %s
                end_conversation_radius: 5
                conversation:
                  conversation1:
                    dialogue:
                      dialogue1:
                        text: 'First %%next%%'
                        time: -1
                      dialogue2:
                        text: 'Second %%next%%'
                        time: -1
                """.formatted(trigger));
    }

    private static void modesAndLookup() throws Exception {
        focus(primary);
        for (ConversationStartClick value : ConversationStartClick.values()) {
            mode(value); resetHands(); int events = handEvents; nativeSwap(alice.player);
            check(handEvents == events + 1, "native_event_delivered_" + value);
            if (value == ConversationStartClick.SWAP_HAND_CLICK) accepted("primary", "swap_mode_starts");
            else { check(sessions.isEmpty(), "right_click_mode_ignores_swap_" + value); swapped("native_swap_" + value); }
            end();
            for (boolean sneak : List.of(false, true)) {
                alice.player.setShiftKeyDown(sneak);
                controller.onRightClick(new NPCRightClickEvent(primary, alice.player));
                check(!sessions.isEmpty() == value.permits(sneak), "right_click_mode_" + value + "_" + sneak);
                end();
            }
            alice.player.setShiftKeyDown(false);
        }
        mode(ConversationStartClick.SWAP_HAND_CLICK);
        alice.player.setShiftKeyDown(true);
        resetHands(); nativeSwap(alice.player); accepted("primary", "sneaking_swap_starts"); end();
        alice.player.setShiftKeyDown(false);
        resetHands(); nativeSwap(alice.player); accepted("primary", "npc_id_precedes_name");
        Session active = sessions.get(alice.player.getUUID()); active.tick();
        Object line = field(active, "current");
        resetHands(); nativeSwap(alice.player); swapped("active_session_keeps_native_swap");
        check(sessions.get(alice.player.getUUID()) == active && field(active, "current") == line
                && !((Boolean) field(active, "skipRequested")), "swap_never_skips_active_dialogue"); end();
        focus(named); resetHands(); nativeSwap(alice.player); accepted("named", "case_insensitive_npc_name"); end();
        focus(primary);
    }

    @SuppressWarnings("unchecked") private static void geometry() throws Exception {
        float previousHead = alice.player.getYHeadRot();
        alice.player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Rot(180, 0, true));
        check(Math.abs(alice.player.getYRot()) == 180 && alice.player.getYHeadRot() == previousHead,
                "native_look_packet_precedes_head_rotation_tick");
        refuse("looking_away_after_native_look_packet", () -> {}); resetPlayer(alice.player);
        refuse("looking_above", () -> alice.player.setXRot(-75)); resetPlayer(alice.player);
        refuse("four_block_limit", () -> primary.getEntity().setPos(4.5, -60, 9.0)); focus(primary);
        level.setBlockAndUpdate(WALL, Blocks.STONE.defaultBlockState());
        refuse("solid_block_occlusion", () -> {}); level.setBlockAndUpdate(WALL, Blocks.AIR.defaultBlockState());
        ArmorStand blocker = EntityType.ARMOR_STAND.create(level); entities.add(blocker);
        blocker.setPos(4.5, -60, 6.0); blocker.setNoGravity(true); check(level.addFreshEntity(blocker), "ordinary_occluder_added");
        refuse("ordinary_entity_occlusion", () -> {}); blocker.discard();

        near.getEntity().setPos(4.5, -60, 6.5);
        resetHands(); nativeSwap(alice.player); accepted("nearest", "nearest_native_hit_selected"); end();
        Map<Integer, Conversation> byId = (Map<Integer, Conversation>) field(library, "byNpcId");
        Conversation removed = byId.remove(near.getId());
        refuse("unconfigured_nearest_npc_blocks_farther_dialogue", () -> {}); byId.put(near.getId(), removed);
        near.getOrAddTrait(PlayerFilter.class).addPlayer(alice.player.getUUID());
        resetHands(); nativeSwap(alice.player); accepted("primary", "hidden_native_npc_does_not_block_visible_target"); end();
        near.removeTrait(PlayerFilter.class); park(near);
        primary.getOrAddTrait(PlayerFilter.class).addPlayer(alice.player.getUUID());
        refuse("hidden_native_target", () -> {}); primary.removeTrait(PlayerFilter.class); refresh(primary);

        focus(packet);
        check(level.getEntity(packet.getEntity().getId()) == null && PacketNPC.getInteractionTarget(packet.getEntity().getId(), alice.player) == packet.getEntity(),
                "virtual_target_is_paired_but_absent_from_level");
        resetHands(); nativeSwap(alice.player); accepted("packet", "packet_npc_native_eye_ray"); end();
        packet.getOrAddTrait(PlayerFilter.class).addPlayer(alice.player.getUUID());
        refuse("hidden_packet_target", () -> {}); packet.removeTrait(PlayerFilter.class); refresh(packet);
        packet.getTrait(PacketNPC.class).getPacketTracker().unlink(alice.player);
        refuse("unpaired_packet_target", () -> {}); refresh(packet);
        level.setBlockAndUpdate(WALL, Blocks.STONE.defaultBlockState());
        refuse("packet_target_behind_solid_block", () -> {}); level.setBlockAndUpdate(WALL, Blocks.AIR.defaultBlockState());
        primary.getEntity().setPos(4.5, -60, 8.0); packet.getEntity().setPos(4.5, -60, 6.5);
        resetHands(); nativeSwap(alice.player); accepted("packet", "nearer_virtual_target_beats_native_target"); end();
        packet.addTrait(new ClickRedirectTrait(primary));
        resetHands(); nativeSwap(alice.player); accepted("primary", "virtual_click_redirect_resolves_parent"); end();
        primary.addTrait(new ClickRedirectTrait(packet));
        refuse("redirect_cycle", () -> {}); primary.removeTrait(ClickRedirectTrait.class);
        packet.removeTrait(ClickRedirectTrait.class); focus(primary);
    }

    private static void admission() throws Exception {
        Conversation story = library.forNpc(primary.getId());
        story.requiresPermission = true;
        PermissionUtil.setPermissionResolver((player, permission) -> permission.startsWith("interactions.start.") ? false : null);
        refuse("permission_denial", () -> {});
        PermissionUtil.setPermissionResolver((player, permission) -> permission.startsWith("interactions.start.") ? true : null);
        resetHands(); nativeSwap(alice.player); accepted("primary", "permission_grant"); end(); story.requiresPermission = false;
        PermissionUtil.setPermissionResolver(previousResolver);
        story.cooldownSeconds = 3600;
        controller.progress().setCooldown(alice.player.getUUID(), "SwapAlice", story.id(), System.currentTimeMillis());
        refuse("cooldown_refusal", () -> {});
        controller.progress().setCooldown(alice.player.getUUID(), "SwapAlice", story.id(), 0);
        resetHands(); nativeSwap(alice.player); accepted("primary", "cooldown_acceptance");
        check(controller.progress().cooldownStartedAt(alice.player.getUUID(), story.id()) > 0, "accepted_swap_records_cooldown"); end();
        refuse("accepted_cooldown_prevents_restart", () -> {}); story.cooldownSeconds = 0;
        story.endRadius = 1; refuse("conversation_end_radius", () -> {}); story.endRadius = 5;
        BlockPos floor = new BlockPos(4, -61, 4); var previous = level.getBlockState(floor);
        level.setBlockAndUpdate(floor, Blocks.AIR.defaultBlockState());
        refuse("air_start_refusal", () -> {}); story.canBeStartedOnAir = true;
        resetHands(); nativeSwap(alice.player); accepted("primary", "air_start_opt_in"); end();
        story.canBeStartedOnAir = false; level.setBlockAndUpdate(floor, previous);
        alice.player.setGameMode(GameType.SPECTATOR); resetHands(); int events = handEvents; nativeSwap(alice.player);
        unchanged("spectator_keeps_native_no_swap"); check(sessions.isEmpty() && handEvents == events, "spectator_does_not_emit_native_swap_event");
        alice.player.setGameMode(GameType.SURVIVAL); resetPlayer(alice.player);
    }

    private static void nativeListeners() throws Exception {
        handHook = event -> event.setCanceled(true); resetHands(); nativeSwap(alice.player);
        unchanged("pre_canceled_event_preserves_hands"); check(sessions.isEmpty(), "pre_canceled_event_cannot_start"); handHook = null;
        handHook = event -> {
            event.setItemSwappedToMainHand(new ItemStack(Items.DIAMOND, 3));
            event.setItemSwappedToOffHand(new ItemStack(Items.EMERALD, 5));
        };
        alice.player.setYRot(180); resetHands(); nativeSwap(alice.player);
        check(alice.player.getMainHandItem().is(Items.DIAMOND) && alice.player.getMainHandItem().getCount() == 3
                && alice.player.getOffhandItem().is(Items.EMERALD) && alice.player.getOffhandItem().getCount() == 5
                && sessions.isEmpty(), "refusal_preserves_native_listener_proposed_stacks");
        resetPlayer(alice.player); resetHands(); nativeSwap(alice.player); accepted("primary", "accepted_dialogue_cancels_modified_native_swap"); end(); handHook = null;
    }

    private static void callbacks() throws Exception {
        Conversation story = library.forNpc(primary.getId()); story.requiresPermission = true;
        PermissionUtil.setPermissionResolver((player, permission) -> {
            if (!permission.startsWith("interactions.start.")) return null;
            primary.getEntity().setPos(9, -60, 7.5); return true;
        });
        refuse("permission_callback_moves_target", () -> {}); focus(primary);
        PermissionUtil.setPermissionResolver((player, permission) -> {
            if (!permission.startsWith("interactions.start.")) return null;
            player.setYRot(180); return true;
        });
        refuse("permission_callback_turns_player", () -> {}); resetPlayer(alice.player);
        PermissionUtil.setPermissionResolver((player, permission) -> {
            if (!permission.startsWith("interactions.start.")) return null;
            level.setBlockAndUpdate(WALL, Blocks.STONE.defaultBlockState()); return true;
        });
        refuse("permission_callback_adds_occluding_block", () -> {}); level.setBlockAndUpdate(WALL, Blocks.AIR.defaultBlockState());
        int[] permissions = {0};
        PermissionUtil.setPermissionResolver((player, permission) -> {
            if (!permission.startsWith("interactions.start.")) return null;
            permissions[0]++;
            var nested = new LivingSwapItemsEvent.Hands(player); controller.onSwapHands(nested);
            check(!nested.isCanceled() && sessions.isEmpty(), "reentrant_event_does_not_claim_session"); return true;
        });
        resetHands(); nativeSwap(alice.player); accepted("primary", "outer_event_can_start_after_reentry");
        check(permissions[0] == 1 && sessions.size() == 1, "permission_reentry_is_bounded"); end();

        boolean[] armed = {false};
        primary.getOrAddTrait(PlayerFilter.class).setPlayerFilter(player -> {
            if (armed[0]) { armed[0] = false; primary.getEntity().setPos(9, -60, 7.5); }
            return false;
        });
        PermissionUtil.setPermissionResolver((player, permission) -> permission.startsWith("interactions.start.") ? true : null);
        armed[0] = true; refuse("visibility_callback_moves_target", () -> {});
        primary.removeTrait(PlayerFilter.class); focus(primary);
        primary.getOrAddTrait(PlayerFilter.class).setPlayerFilter(player -> {
            if (armed[0]) { armed[0] = false; level.setBlockAndUpdate(WALL, Blocks.STONE.defaultBlockState()); }
            return false;
        });
        PermissionUtil.setPermissionResolver((player, permission) -> {
            if (!permission.startsWith("interactions.start.")) return null;
            armed[0] = true; return true;
        });
        refuse("final_visibility_callback_adds_occluding_block", () -> {});
        primary.removeTrait(PlayerFilter.class); level.setBlockAndUpdate(WALL, Blocks.AIR.defaultBlockState());
        ArmorStand lateBlocker = EntityType.ARMOR_STAND.create(level); entities.add(lateBlocker);
        lateBlocker.setPos(10, -60, 6); lateBlocker.setNoGravity(true);
        check(level.addFreshEntity(lateBlocker), "callback_occluder_added");
        primary.getOrAddTrait(PlayerFilter.class).setPlayerFilter(player -> {
            if (armed[0]) { armed[0] = false; lateBlocker.setPos(4.5, -60, 6); }
            return false;
        });
        refuse("final_visibility_callback_moves_occluding_entity", () -> {});
        primary.removeTrait(PlayerFilter.class); lateBlocker.discard();
        PermissionUtil.setPermissionResolver((player, permission) -> {
            if (!permission.startsWith("interactions.start.")) return null;
            primary.despawn(); return true;
        });
        refuse("permission_callback_despawns_target", () -> {});
        check(primary.spawn(new Location(level, 4.5, -60, 7.5)), "callback_target_respawns");
        primary.getEntity().setNoGravity(true); refresh(primary);
        Entity previousEntity = primary.getEntity();
        PermissionUtil.setPermissionResolver((player, permission) -> {
            if (!permission.startsWith("interactions.start.")) return null;
            primary.despawn();
            check(primary.spawn(new Location(level, 4.5, -60, 7.5)), "permission_callback_replacement_spawns");
            primary.getEntity().setNoGravity(true); refresh(primary); return true;
        });
        refuse("permission_callback_replaces_target_entity", () -> {});
        check(primary.getEntity() != previousEntity && primary.isSpawned(), "replacement_entity_survives_refused_start");
        story.requiresPermission = false; PermissionUtil.setPermissionResolver(previousResolver);
    }

    private static void reload() throws Exception {
        mode(ConversationStartClick.SWAP_HAND_CLICK); focus(primary);
        resetHands(); nativeSwap(alice.player); accepted("primary", "before_reload_session");
        Files.writeString(ROOT.resolve("config.yml"), "conversation_start_click_type: RIGHT_CLICK\n");
        check(server.getCommands().getDispatcher().execute("interactions reload", server.createCommandSourceStack().withPermission(4)) == 1
                && sessions.isEmpty() && controller.settings().startClick() == ConversationStartClick.RIGHT_CLICK, "reload_changes_mode_and_cleans_session");
        refuse("reloaded_right_click_preserves_native_swap", () -> {});
        mode(ConversationStartClick.SWAP_HAND_CLICK); Conversation story = library.forNpc(primary.getId()); story.requiresPermission = true;
        PermissionUtil.setPermissionResolver((player, permission) -> {
            if (!permission.startsWith("interactions.start.")) return null;
            try {
                Files.writeString(ROOT.resolve("config.yml"), "conversation_start_click_type: RIGHT_CLICK\n");
                server.getCommands().getDispatcher().execute("interactions reload", server.createCommandSourceStack().withPermission(4));
            } catch (Exception failure) { throw new RuntimeException(failure); }
            return true;
        });
        refuse("permission_callback_reload_invalidates_inflight_start", () -> {});
        PermissionUtil.setPermissionResolver(previousResolver); mode(ConversationStartClick.SWAP_HAND_CLICK);
        resetHands(); nativeSwap(alice.player); accepted("primary", "fresh_swap_after_callback_reload"); end();
    }

    private static void currentPlayers() throws Exception {
        Actor stale = actor("SwapStale"); resetPlayer(stale.player);
        check(DialogueTargeting.isCurrentPlayer(stale.player), "admitted_player_is_current");
        server.getPlayerList().remove(stale.player);
        ItemStack main = new ItemStack(Items.STICK, 2), off = new ItemStack(Items.APPLE, 4);
        stale.player.setItemInHand(InteractionHand.MAIN_HAND, main); stale.player.setItemInHand(InteractionHand.OFF_HAND, off);
        nativeSwap(stale.player);
        check(!DialogueTargeting.isCurrentPlayer(stale.player) && !sessions.containsKey(stale.player.getUUID())
                && stale.player.getMainHandItem().is(Items.APPLE), "removed_player_packet_cannot_start_dialogue");
        var duplicate = new ServerPlayer(server, level, alice.player.getGameProfile(), ClientInformation.createDefault());
        duplicate.connection = alice.player.connection;
        var event = new LivingSwapItemsEvent.Hands(duplicate); controller.onSwapHands(event);
        check(!DialogueTargeting.isCurrentPlayer(duplicate) && !event.isCanceled() && sessions.isEmpty(), "same_uuid_noncurrent_player_is_rejected");
        Actor disconnected = actor("SwapClosed"); resetPlayer(disconnected.player); disconnected.channel.close();
        event = new LivingSwapItemsEvent.Hands(disconnected.player); controller.onSwapHands(event);
        check(!DialogueTargeting.isCurrentPlayer(disconnected.player) && !event.isCanceled() && sessions.isEmpty(), "closed_connection_is_rejected");
    }

    private static void mode(ConversationStartClick value) throws Exception {
        Files.createDirectories(ROOT);
        Path config = ROOT.resolve("config.yml");
        Files.writeString(config, "conversation_start_click_type: " + value.name().toLowerCase(java.util.Locale.ROOT)
                + "\nallow_chat_while_in_conversation: true\nskip_dialogue_on_npc_click: true\nuse_empty_spaces: false\n");
        DialogueSettings settings = DialogueSettings.load(config.toFile(), DialogueSettings.DEFAULT);
        check(settings.startClick() == value && settings.allowChat() && settings.skipDialogueOnNpcClick(), "settings_parse_" + value);
        field(controller, "settings", settings);
    }
    private static void focus(NPC target) {
        for (NPC npc : npcs) if (npc != target && npc.isSpawned()) park(npc);
        target.getEntity().setPos(4.5, -60, 7.5); refresh(target); resetPlayer(alice.player);
    }
    private static void park(NPC npc) { npc.getEntity().setPos(10.5 + npcs.indexOf(npc), -60, 10.5); }
    private static void refresh(NPC npc) {
        if (npc.hasTrait(PacketNPC.class)) npc.getTrait(PacketNPC.class).run();
        else NPCVisibility.refresh(npc.getEntity());
    }
    private static void resetPlayer(ServerPlayer player) { player.setPos(4.5, -60, 4.5); player.setYRot(0); player.setXRot(0); player.setShiftKeyDown(false); }
    private static void resetHands() {
        alice.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK, 2));
        alice.player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.APPLE, 4));
    }
    private static void nativeSwap(ServerPlayer player) {
        player.connection.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO, Direction.DOWN));
    }
    private static void accepted(String id, String label) {
        Session session = sessions.get(alice.player.getUUID());
        check(session != null && !session.isFinished() && session.conversation().id().equals(id), label);
        unchanged(label + "_preserves_hands");
    }
    private static void unchanged(String label) {
        check(alice.player.getMainHandItem().is(Items.STICK) && alice.player.getMainHandItem().getCount() == 2
                && alice.player.getOffhandItem().is(Items.APPLE) && alice.player.getOffhandItem().getCount() == 4, label);
    }
    private static void swapped(String label) {
        check(alice.player.getMainHandItem().is(Items.APPLE) && alice.player.getMainHandItem().getCount() == 4
                && alice.player.getOffhandItem().is(Items.STICK) && alice.player.getOffhandItem().getCount() == 2, label);
    }
    private static void refuse(String label, Runnable prepare) {
        prepare.run(); resetHands(); nativeSwap(alice.player);
        check(sessions.isEmpty(), label + "_does_not_start"); swapped(label + "_preserves_native_swap");
    }
    private static void end() { for (Session session : List.copyOf(sessions.values())) session.end(false); sessions.clear(); }
    private static boolean cleanup() {
        try {
            handHook = null; PermissionUtil.setPermissionResolver(previousResolver);
            if (sessions != null) end(); if (controller != null) NeoForge.EVENT_BUS.unregister(controller);
            for (Entity entity : entities) entity.discard();
            for (NPC npc : npcs) npc.destroy();
            for (Actor actor : actors) {
                if (server.getPlayerList().getPlayer(actor.player.getUUID()) == actor.player) server.getPlayerList().remove(actor.player);
                actor.channel.finishAndReleaseAll();
            }
            level.setBlockAndUpdate(WALL, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(4, -61, 4), Blocks.GRASS_BLOCK.defaultBlockState());
            level.setChunkForced(0, 0, false); return true;
        } catch (Throwable failure) { LoggerFactory.getLogger("interactions").error("[SWAPHANDAUDIT] FAILED cleanup", failure); return false; }
    }
    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void field(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("interactions").info("[SWAPHANDAUDIT] PASS {}", label);
    }

    private static Actor actor(String name) {
        var defaults = ClientInformation.createDefault();
        var information = new ClientInformation(defaults.language(), 6, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing());
        var actor = new Actor();
        actor.player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), name), information);
        var connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("swap-hand-capture", new ChannelOutboundHandlerAdapter() {
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
        server.getPlayerList().placeNewPlayer(connection, actor.player, cookie); actors.add(actor); return actor;
    }
    private static final class Actor {
        ServerPlayer player; EmbeddedChannel channel; int batches;
        final Set<Long> chunks = new HashSet<>();
        final List<Integer> teleports = new ArrayList<>();
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            if (packet instanceof ClientboundChunkBatchFinishedPacket) batches++;
            if (packet instanceof ClientboundPlayerPositionPacket position) teleports.add(position.getId());
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) chunks.add(ChunkPos.asLong(chunk.getX(), chunk.getZ()));
        }
        void pump() {
            channel.runPendingTasks();
            for (int id : List.copyOf(teleports)) player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
            teleports.clear();
            while (batches > 0) { batches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            Object output; while ((output = channel.readOutbound()) != null) ReferenceCountUtil.release(output);
        }
        boolean watches() throws ReflectiveOperationException {
            var method = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("isChunkTracked", ServerPlayer.class, int.class, int.class);
            method.setAccessible(true); return chunks.contains(0L) && (boolean) method.invoke(level.getChunkSource().chunkMap, player, 0, 0);
        }
    }
}
