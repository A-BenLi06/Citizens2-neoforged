package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.trait.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

/** Native packet dispatch with synthetic, admitted players; no direct Citizens event calls. */
@EventBusSubscriber(modid = "citizens")
public final class PacketInteractionRuntimeAudit {
    @EventBusSubscriber(modid = "citizens", bus = EventBusSubscriber.Bus.MOD)
    public static final class ItemFixture {
        static Item disabled;

        @SubscribeEvent public static void register(net.neoforged.neoforge.registries.RegisterEvent event) {
            event.register(net.minecraft.core.registries.Registries.ITEM,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("citizens", "audit_disabled_interaction"),
                    () -> disabled = new Item(new Item.Properties().requiredFeatures(net.minecraft.world.flag.FeatureFlags.TRADE_REBALANCE)));
        }
    }
    private static boolean forced, done, reenter;
    private static int phase, nextTick, passed, deadline, nativeCalls;
    private static NPCRegistry registry;
    private static NPC target, plain, parent, helper, real, canceled, delayed, invalid;
    private static Entity previous, ordinary;
    private static PacketNPC retired;
    private static Actor alice, bob;
    private static final List<Actor> actors = new ArrayList<>();
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<Runnable> deferredChecks = new ArrayList<>();
    private static final Map<String, Integer> commands = new HashMap<>(), clicks = new HashMap<>();
    private static InteractionHand lastHand;
    private static Vec3 lastHit;
    private static boolean lastSecondary;

    @SubscribeEvent public static void right(NPCRightClickEvent event) {
        if (!npcs.contains(event.getNPC())) return;
        clicks.merge(key(event.getNPC(), event.getClicker(), "right"), 1, Integer::sum);
        if (event.getNPC() == canceled) event.setCanceled(true);
        if (event.getNPC() == delayed) event.setDelayedCancellation(true);
        if (reenter) {
            reenter = false;
            send(alice, ServerboundInteractPacket.createInteractionPacket(target.getEntity(), false, InteractionHand.MAIN_HAND));
        }
    }
    @SubscribeEvent public static void left(NPCLeftClickEvent event) {
        if (!npcs.contains(event.getNPC())) return;
        clicks.merge(key(event.getNPC(), event.getClicker(), "left"), 1, Integer::sum);
        if (event.getNPC() == canceled) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void specific(PlayerInteractEvent.EntityInteractSpecific event) {
        nativeCalls++; lastHand = event.getHand(); lastHit = event.getLocalPos(); lastSecondary = event.getEntity().isShiftKeyDown();
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void generic(PlayerInteractEvent.EntityInteract event) { nativeCalls++; }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); ServerLevel level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 1200; }
        try {
            for (Actor actor : actors) actor.pump();
            if (server.getTickCount() > deadline) throw new AssertionError("timed_out_phase_" + phase);
            if (!level.areEntitiesLoaded(0L) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
            if (server.getTickCount() < nextTick) return;
            if (phase == 1 && (!alice.watches(level, 0, 0) || !bob.watches(level, 0, 0))) return;
            for (Runnable check : List.copyOf(deferredChecks)) check.run();
            deferredChecks.clear();
            nextTick = server.getTickCount() + 20;
            switch (phase++) {
                case 0 -> {
                    check(Files.isRegularFile(Path.of("packet-interaction-audit-fixture.txt"))
                            && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
                    alice = actor(server, "InteractAlice", 6); bob = actor(server, "InteractBob", 6);
                    registry = CitizensAPI.createNamedNPCRegistry("packet-interaction-audit", new MemoryNPCDataStore());
                    server.getCommands().getDispatcher().register(Commands.literal("interaction_audit")
                            .then(Commands.argument("key", StringArgumentType.word()).executes(context -> {
                                commands.merge(StringArgumentType.getString(context, "key") + ":" + context.getSource().getPlayerOrException().getUUID(), 1, Integer::sum);
                                return 1;
                            })));
                }
                case 1 -> {
                    check(alice.chunks.contains(0L) && bob.chunks.contains(0L), "native_chunk_delivery");
                    target = npc(level, "target", true, EntityType.COW); addCommands(target);
                    plain = npc(level, "plain", true, EntityType.COW);
                    parent = npc(level, "parent", false, EntityType.COW); addCommands(parent);
                    helper = npc(level, "helper", true, EntityType.INTERACTION); helper.addTrait(new ClickRedirectTrait(parent));
                    real = npc(level, "real", false, EntityType.COW); addCommands(real);
                    ordinary = EntityType.COW.create(level); ordinary.setPos(4, -60, 4);
                    ((Mob) ordinary).setNoAi(true); ordinary.setNoGravity(true);
                    check(level.addFreshEntity(ordinary), "non_npc_control_spawn");
                    check(level.getEntity(target.getEntity().getId()) == null, "packet_npc_absent_from_world");
                    ignored(alice, target.getEntity(), "unpaired_id_ignored_before_first_tick");
                }
                case 2 -> {
                    check(target.getTrait(PacketNPC.class).getPacketTracker().isLinked(alice.player)
                            && alice.spawns(target.getEntity()) == 1 && bob.spawns(target.getEntity()) == 1, "native_pairing_before_interaction");
                    wheat(alice); wheat(bob);
                    at(alice, target.getEntity(), false, InteractionHand.MAIN_HAND);
                    at(bob, target.getEntity(), false, InteractionHand.MAIN_HAND);
                    click(alice, target.getEntity()); click(bob, target.getEntity());
                    check(count(target, alice, "right") == 1 && count(target, bob, "right") == 1, "interleaved_viewers_one_citizens_event_each");
                    deferredChecks.add(() -> check(command(target, alice, "right") == 1 && command(target, bob, "right") == 1, "interleaved_viewers_one_command_each"));
                    check(wheatCount(alice) == 4 && wheatCount(bob) == 4 && !((Cow) target.getEntity()).isInLove(), "handled_duplicates_cannot_feed_cow");
                    at(alice, helper.getEntity(), true, InteractionHand.OFF_HAND);
                    check(lastHand == InteractionHand.OFF_HAND && lastHit.distanceTo(new Vec3(0.25, 0.5, 0.75)) < 0.00001 && lastSecondary, "native_offhand_hit_vector_secondary_preserved");
                    check(count(parent, alice, "right") == 1 && count(helper, alice, "right") == 0, "helper_click_redirects_to_parent");
                    deferredChecks.add(() -> check(command(parent, alice, "shift_right") == 1, "secondary_helper_dispatches_parent_shift_command"));
                    attack(alice, target.getEntity());
                    deferredChecks.add(() -> check(count(target, alice, "left") == 1 && command(target, alice, "left") == 1, "native_attack_dispatches_left_event_and_command"));
                }
                case 3 -> {
                    canceled = target; wheat(alice);
                    int before = command(target, alice, "right");
                    at(alice, target.getEntity(), false, InteractionHand.MAIN_HAND); click(alice, target.getEntity());
                    int rightBefore = before;
                    deferredChecks.add(() -> check(count(target, alice, "right") == 2 && command(target, alice, "right") == rightBefore, "canceled_event_stops_command"));
                    check(wheatCount(alice) == 4, "canceled_duplicate_still_suppresses_vanilla");
                    before = command(target, alice, "left"); attack(alice, target.getEntity());
                    int leftBefore = before;
                    deferredChecks.add(() -> check(count(target, alice, "left") == 2 && command(target, alice, "left") == leftBefore, "canceled_attack_stops_command"));
                    canceled = null;
                    wheat(alice); at(alice, plain.getEntity(), false, InteractionHand.MAIN_HAND); click(alice, plain.getEntity());
                    check(count(plain, alice, "right") == 1, "unhandled_pair_has_one_citizens_event");
                    check(wheatCount(alice) == 3 && ((Cow) plain.getEntity()).isInLove(), "unhandled_generic_keeps_native_feeding");
                    wheat(alice); at(alice, real.getEntity(), false, InteractionHand.MAIN_HAND); click(alice, real.getEntity());
                    check(count(real, alice, "right") == 1 && wheatCount(alice) == 4, "ordinary_npc_uses_same_dedup_policy");
                    deferredChecks.add(() -> check(command(real, alice, "right") == 1, "ordinary_npc_command_runs_once"));
                    int events = clicks.values().stream().mapToInt(Integer::intValue).sum();
                    wheat(alice); at(alice, ordinary, false, InteractionHand.MAIN_HAND); click(alice, ordinary);
                    check(wheatCount(alice) == 3 && ((Cow) ordinary).isInLove(), "non_npc_native_interaction_unchanged");
                    check(clicks.values().stream().mapToInt(Integer::intValue).sum() == events, "non_npc_has_no_citizens_event");
                }
                case 4 -> {
                    delayed = plain; ((Cow) plain.getEntity()).resetLove(); wheat(bob);
                    at(bob, plain.getEntity(), false, InteractionHand.MAIN_HAND); click(bob, plain.getEntity());
                    check(count(plain, bob, "right") == 1 && wheatCount(bob) == 4, "delayed_cancellation_without_commands_suppresses_duplicate");
                    delayed = null;
                    bob.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ItemFixture.disabled));
                    check(!bob.player.getMainHandItem().isItemEnabled(level.enabledFeatures()), "experimental_item_disabled_in_fixture");
                    ignored(bob, target.getEntity(), "native_item_feature_gate_preserved");
                    wheat(bob);
                    int before = count(target, alice, "right");
                    reenter = true; at(alice, target.getEntity(), false, InteractionHand.MAIN_HAND);
                    check(count(target, alice, "right") == before + 1 && !reenter, "reentrant_packet_does_not_repeat_event");
                    target.getOrAddTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                    check(target.getTrait(PacketNPC.class).getPacketTracker().isLinked(bob.player), "visibility_test_before_reconciliation");
                    ignored(bob, target.getEntity(), "same_tick_hidden_packet_npc_ignored");
                    target.removeTrait(PlayerFilter.class);
                    parent.getOrAddTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                    ignored(bob, helper.getEntity(), "same_tick_hidden_parent_rejects_helper");
                    parent.removeTrait(PlayerFilter.class);
                    real.getOrAddTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                    ignored(bob, real.getEntity(), "forged_hidden_world_npc_id_ignored");
                    real.removeTrait(PlayerFilter.class);
                    parent.addTrait(new ClickRedirectTrait(helper));
                    ignored(bob, helper.getEntity(), "redirect_cycle_rejects_interaction");
                    parent.removeTrait(ClickRedirectTrait.class);
                    bob.player.setPos(100, -60, 4);
                    check(target.getTrait(PacketNPC.class).getPacketTracker().isLinked(bob.player), "range_test_before_reconciliation");
                    ignored(bob, target.getEntity(), "same_tick_packet_tracking_range_exit_ignored");
                    bob.player.setPos(14, -60, 4);
                    check(target.getTrait(PacketNPC.class).isViewerEligible(bob.player), "reach_test_still_inside_packet_tracking_range");
                    ignored(bob, target.getEntity(), "native_reach_rejects_distant_click");
                    var range = bob.player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
                    double old = range.getBaseValue(); range.setBaseValue(20);
                    before = count(target, bob, "right"); click(bob, target.getEntity());
                    check(count(target, bob, "right") == before + 1, "native_range_attribute_changes_reach");
                    range.setBaseValue(old); bob.player.setPos(6, -60, 4);
                    double borderSize = level.getWorldBorder().getSize(); level.getWorldBorder().setSize(2);
                    ignored(bob, plain.getEntity(), "native_world_border_rejects_click");
                    level.getWorldBorder().setSize(borderSize);
                    previous = target.getEntity(); retired = target.getTrait(PacketNPC.class); target.addTrait(new PacketNPC());
                    // Trait.isRunImplemented invokes run during attachment, so replacement has already paired.
                    check(target.getTrait(PacketNPC.class).getPacketTracker().isLinked(alice.player), "replacement_attachment_performs_native_pairing");
                }
                case 5 -> {
                    check(target.getEntity() == previous && PacketNPC.getInteractionTarget(previous.getId(), alice.player) == previous, "replacement_registers_current_trait");
                    retired.onRemove();
                    check(PacketNPC.getInteractionTarget(previous.getId(), alice.player) == previous && !previous.isRemoved(), "retired_cleanup_cannot_erase_or_discard_replacement");
                    int before = count(target, alice, "right"); click(alice, previous);
                    check(count(target, alice, "right") == before + 1, "replacement_receives_native_click");
                    check(target.despawn(DespawnReason.PENDING_RESPAWN), "packet_despawn");
                    ignored(alice, previous, "despawned_id_ignored");
                    check(PacketNPC.getInteractionTarget(previous.getId(), alice.player) == null, "despawn_unregisters_target");
                    check(target.spawn(new Location(level, 4, -60, 4)), "packet_respawn");
                    ignored(alice, target.getEntity(), "respawn_not_clickable_before_pairing");
                }
                case 6 -> {
                    check(previous.getId() != target.getEntity().getId(), "respawn_assigns_new_id");
                    ignored(alice, previous, "old_id_stays_invalid_after_respawn");
                    int before = count(target, alice, "right"); click(alice, target.getEntity());
                    check(count(target, alice, "right") == before + 1, "respawn_current_id_clickable");
                    int bobBefore = count(target, bob, "right"); click(bob, target.getEntity());
                    ServerPlayer stale = bob.player;
                    stale.setHealth(0);
                    stale.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    bob.player = stale.connection.player; bob.player.setPos(6, -60, 4);
                    check(bob.player != stale, "native_respawn_packet_rebinds_listener_player");
                    check(bob.player.equals(stale), "native_respawn_reuses_entity_equality");
                    check(PacketNPC.getInteractionTarget(target.getEntity().getId(), stale) == null, "stale_player_identity_rejected_before_reconcile");
                    ignored(bob, target.getEntity(), "respawned_viewer_requires_new_pairing");
                    target.addTrait(new PacketNPC());
                    check(PacketNPC.getInteractionTarget(target.getEntity().getId(), bob.player) == target.getEntity(), "replacement_pairs_respawned_player_in_same_tick");
                    click(bob, target.getEntity());
                    check(count(target, bob, "right") == bobBefore + 2, "same_tick_respawn_has_distinct_click_cache_identity");
                }
                case 7 -> {
                    check(PacketNPC.getInteractionTarget(target.getEntity().getId(), bob.player) == target.getEntity(), "respawned_viewer_has_current_pairing");
                    check(bob.player.canInteractWithEntity(target.getEntity().getBoundingBox(), 1), "respawned_viewer_native_reach_" + bob.player.position() + "_" + target.getEntity().position());
                    int before = count(target, bob, "right"); click(bob, target.getEntity());
                    check(count(target, bob, "right") == before + 1, "reconciled_respawned_viewer_clicks");
                    bob.player.teleportTo(server.getLevel(Level.NETHER), 6, 64, 4, Set.of(), 0, 0);
                    ignored(bob, target.getEntity(), "dimension_exit_immediately_rejects_target");
                    bob.player.teleportTo(level, 6, -60, 4, Set.of(), 0, 0);
                    previous = target.getEntity(); retired = target.getTrait(PacketNPC.class);
                    long removalsBefore = alice.removals(previous); target.removeTrait(PacketNPC.class);
                    check(retired.getPacketTracker().getLinked().isEmpty() && indexSize() == 2
                            && alice.removals(previous) == removalsBefore + 1, "disable_releases_replacement_tracker_and_index");
                    ignored(alice, previous, "disabled_packet_id_immediately_invalid");
                    invalid = npc(level, "invalid", true, EntityType.ITEM);
                    ((net.minecraft.world.entity.item.ItemEntity) invalid.getEntity()).setItem(new ItemStack(Items.STONE));
                }
                case 8 -> {
                    check(target.isSpawned() && level.getEntity(target.getEntity().getId()) == target.getEntity(), "disable_restores_native_world_transport");
                    int before = count(target, alice, "right"); click(alice, target.getEntity());
                    check(count(target, alice, "right") == before + 1, "restored_world_npc_clicks");
                    ignored(alice, previous, "disabled_old_virtual_id_stays_invalid");
                    previous = helper.getEntity(); helper.destroy();
                    ignored(alice, previous, "destroyed_helper_id_invalid");
                    check(PacketNPC.getInteractionTarget(previous.getId(), alice.player) == null, "destroy_unregisters_helper");
                    check(PacketNPC.getInteractionTarget(invalid.getEntity().getId(), bob.player) == invalid.getEntity(), "invalid_attack_control_is_paired");
                    int nativeBefore = nativeCalls, clickBefore = clicks.values().stream().mapToInt(Integer::intValue).sum();
                    attack(bob, invalid.getEntity()); bob.pump();
                    check(bob.packets.stream().anyMatch(p -> p instanceof net.minecraft.network.protocol.common.ClientboundDisconnectPacket), "vanilla_invalid_item_attack_disconnects");
                    check(nativeCalls == nativeBefore && clicks.values().stream().mapToInt(Integer::intValue).sum() == clickBefore, "invalid_attack_does_not_synthesize_click");
                    invalid.destroy();
                    server.getPlayerList().remove(bob.player);
                    check(PacketNPC.getInteractionTarget(plain.getEntity().getId(), bob.player) == null, "disconnected_viewer_immediately_ineligible");
                    check(indexSize() == 1, "only_surviving_packet_npc_in_index");
                    for (NPC npc : npcs) if (registry.getByUniqueId(npc.getUniqueId()) == npc) npc.destroy();
                    check(indexSize() == 0, "all_packet_targets_released_on_destroy");
                    LoggerFactory.getLogger("citizens").info("[PACKETINTERACTIONAUDIT] COMPLETE {} checks", passed); done = true;
                }
            }
        } catch (Throwable failure) { done = true; LoggerFactory.getLogger("citizens").error("[PACKETINTERACTIONAUDIT] FAILED phase " + phase, failure); }
        if (done) {
            try {
                for (NPC npc : npcs) if (registry.getByUniqueId(npc.getUniqueId()) == npc) npc.destroy();
                if (ordinary != null) ordinary.discard();
                for (Actor actor : actors) { if (server.getPlayerList().getPlayer(actor.player.getUUID()) == actor.player) server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[PACKETINTERACTIONAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    private static int indexSize() throws ReflectiveOperationException {
        var field = PacketNPC.class.getDeclaredField("INTERACTION_TARGETS"); field.setAccessible(true);
        return ((Map<?, ?>) field.get(null)).size();
    }
    private static NPC npc(ServerLevel level, String name, boolean packet, EntityType<?> type) {
        NPC npc = registry.createNPC(type, name); npcs.add(npc); npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        if (packet) npc.getOrAddTrait(PacketNPC.class);
        check(npc.spawn(new Location(level, 4, -60, 4)), name + "_spawn");
        npc.getEntity().setNoGravity(true); if (npc.getEntity() instanceof Mob mob) mob.setNoAi(true);
        return npc;
    }
    private static void addCommands(NPC npc) {
        for (CommandTrait.Hand hand : List.of(CommandTrait.Hand.RIGHT, CommandTrait.Hand.LEFT, CommandTrait.Hand.SHIFT_RIGHT))
            npc.getOrAddTrait(CommandTrait.class).addCommand(new CommandTrait.NPCCommandBuilder("interaction_audit " + npc.getName() + "_" + hand.name().toLowerCase(Locale.ROOT), hand).player(true).cooldown(-1));
    }
    private static String key(NPC npc, ServerPlayer player, String side) { return npc.getName() + "_" + side + ":" + player.getUUID(); }
    private static int count(NPC npc, Actor actor, String side) { return clicks.getOrDefault(key(npc, actor.player, side), 0); }
    private static int command(NPC npc, Actor actor, String side) { return commands.getOrDefault(key(npc, actor.player, side), 0); }
    private static void wheat(Actor actor) { actor.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 4)); }
    private static int wheatCount(Actor actor) { return actor.player.getMainHandItem().getCount(); }
    private static void click(Actor actor, Entity entity) { send(actor, ServerboundInteractPacket.createInteractionPacket(entity, false, InteractionHand.MAIN_HAND)); }
    private static void attack(Actor actor, Entity entity) { send(actor, ServerboundInteractPacket.createAttackPacket(entity, false)); }
    private static void at(Actor actor, Entity entity, boolean secondary, InteractionHand hand) { send(actor, ServerboundInteractPacket.createInteractionPacket(entity, secondary, hand, new Vec3(0.25, 0.5, 0.75))); }
    private static void ignored(Actor actor, Entity entity, String label) {
        int before = nativeCalls; int events = clicks.values().stream().mapToInt(Integer::intValue).sum();
        at(actor, entity, false, InteractionHand.MAIN_HAND); click(actor, entity); attack(actor, entity);
        check(nativeCalls == before && clicks.values().stream().mapToInt(Integer::intValue).sum() == events, label);
    }
    private static void send(Actor actor, ServerboundInteractPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerboundInteractPacket.STREAM_CODEC.encode(buffer, packet);
            ServerboundInteractPacket decoded = ServerboundInteractPacket.STREAM_CODEC.decode(buffer);
            if (buffer.isReadable()) throw new AssertionError("interaction_codec_left_trailing_bytes");
            actor.player.connection.handleInteract(decoded);
        } finally { buffer.release(); }
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[PACKETINTERACTIONAUDIT] PASS {}", label); }
    private static Actor actor(MinecraftServer server, String name, double x) {
        var defaults = ClientInformation.createDefault();
        var information = new ClientInformation(defaults.language(), 6, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing());
        Actor actor = new Actor(); actor.player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), information);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("packet-interaction-capture", new ChannelOutboundHandlerAdapter() {
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
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(packet);
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
            method.setAccessible(true);
            return (boolean) method.invoke(level.getChunkSource().chunkMap, player, x, z);
        }
        long spawns(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundAddEntityPacket add && add.getId() == e.getId()).count(); }
        long removals(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundRemoveEntitiesPacket remove && remove.getEntityIds().contains(e.getId())).count(); }
        long metadata(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket data && data.id() == e.getId()).count(); }
        long equipment(Entity e) { pump(); return packets.stream().filter(p -> p instanceof ClientboundSetEquipmentPacket equipment && equipment.getEntity() == e.getId()).count(); }
        long profiles(NPC n) { pump(); return packets.stream().filter(p -> p instanceof ClientboundPlayerInfoUpdatePacket info && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) && info.entries().stream().anyMatch(e -> e.profileId().equals(n.getEntity().getUUID()))).count(); }
        long profileRemovals(NPC n) { pump(); return packets.stream().filter(p -> p instanceof ClientboundPlayerInfoRemovePacket remove && remove.profileIds().contains(n.getEntity().getUUID())).count(); }
        boolean profileBeforeSpawn(NPC n) {
            pump(); boolean profile = false;
            for (Packet<?> packet : packets) {
                if (packet instanceof ClientboundPlayerInfoUpdatePacket info && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
                        && info.entries().stream().anyMatch(e -> e.profileId().equals(n.getEntity().getUUID()))) profile = true;
                if (packet instanceof ClientboundAddEntityPacket add && add.getId() == n.getEntity().getId()) return profile;
            }
            return false;
        }
    }
}
