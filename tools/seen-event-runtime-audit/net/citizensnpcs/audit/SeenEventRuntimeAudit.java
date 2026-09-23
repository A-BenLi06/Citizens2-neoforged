package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.HologramTrait;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import net.citizensnpcs.api.event.NPCSeenByPlayerEvent;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.MirrorTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.NPCVisibility;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
/** Native tracking attempts, cancellable admission and actual ordered outgoing packets. */
@EventBusSubscriber(modid = "citizens")
public final class SeenEventRuntimeAudit {
    private static boolean forced, done, deny = true;
    private static int phase, nextTick, passed, deadline;
    private static NPCRegistry registry;
    private static Actor alice, bob;
    private static final List<Actor> actors = new ArrayList<>();
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<NPC> destroyOnSeen = new ArrayList<>();
    private static final List<Entity> retired = new ArrayList<>();
    private static final Map<String, Integer> seen = new HashMap<>(), starts = new HashMap<>(), rendered = new HashMap<>();
    private static Map<String, Integer> snapshot;
    private static final List<String> errors = new ArrayList<>();
    private static final Set<String> dispatching = new HashSet<>();

    @SubscribeEvent public static void seen(NPCSeenByPlayerEvent event) {
        NPC npc = event.getNPC(); ServerPlayer player = event.getPlayer();
        if (!npcs.contains(npc) && !destroyOnSeen.contains(npc)) return;
        Actor actor = actors.stream().filter(a -> a.player == player).findFirst().orElse(null);
        if (actor == null) return;
        seen.merge(key(npc, actor), 1, Integer::sum);
        Entity entity = npc.getEntity();
        if (actor.known.contains(entity.getId()) || NPCVisibility.isTracked(entity, player)) errors.add("event_after_pairing_" + npc.getName());
        if (destroyOnSeen.contains(npc)) { npc.destroy(); return; }
        // The same native refresh may be requested synchronously by an addon; it must not recursively dispatch.
        String dispatchKey = key(npc, actor);
        if (!dispatching.add(dispatchKey)) errors.add("recursive_admission");
        try {
            PacketNPC packet = npc.getTraitNullable(PacketNPC.class);
            if (packet == null) NPCVisibility.refresh(entity); else packet.run();
        } finally { dispatching.remove(dispatchKey); }
        if (deny && actor == bob) event.setCanceled(true);
    }

    @SubscribeEvent public static void started(PlayerEvent.StartTracking event) {
        NPC npc = net.citizensnpcs.npc.NPCRegistries.lookup(event.getTarget());
        if (!npcs.contains(npc)) return;
        Actor actor = actors.stream().filter(a -> a.player == event.getEntity()).findFirst().orElse(null);
        if (actor == null) return;
        starts.merge(key(npc, actor), 1, Integer::sum);
        if (!actor.known.contains(event.getTarget().getId())) errors.add("start_before_spawn_" + npc.getName());
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); ServerLevel level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 1300; }
        try {
            for (Actor actor : actors) actor.pump();
            if (server.getTickCount() > deadline) throw new AssertionError("timed_out_phase_" + phase);
            if (!level.areEntitiesLoaded(0L) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
            if (server.getTickCount() < nextTick) return;
            if (phase == 1 && (!alice.watches(level, 0, 0) || !bob.watches(level, 0, 0))) return;
            nextTick = server.getTickCount() + 25;
            switch (phase++) {
                case 0 -> {
                    check(Files.isRegularFile(Path.of("seen-event-audit-fixture.txt")) && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
                    alice = actor(server, "SeenAlice", 6); bob = actor(server, "SeenBob", 8);
                    alice.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
                    bob.player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD));
                    registry = CitizensAPI.createNamedNPCRegistry("seen-event-audit", new MemoryNPCDataStore());
                }
                case 1 -> {
                    for (boolean packet : List.of(false, true)) for (EntityType<?> type : List.of(EntityType.COW, EntityType.PLAYER, EntityType.TEXT_DISPLAY)) {
                        NPC npc = registry.createNPC(type, (packet ? "virtual " : "world ") + EntityType.getKey(type));
                        npcs.add(npc); npc.data().set(NPC.Metadata.TRACKING_RANGE, 64);
                        if (packet) npc.getOrAddTrait(PacketNPC.class);
                        if (type == EntityType.COW) npc.getOrAddTrait(Equipment.class).setCosmetic(Equipment.EquipmentSlot.HAND, new ItemStack(Items.GOLD_INGOT));
                        if (type == EntityType.PLAYER) {
                            MirrorTrait mirror = npc.getOrAddTrait(MirrorTrait.class); mirror.setMirrorEquipment(true); mirror.setEnabled(true);
                        }
                        if (type == EntityType.TEXT_DISPLAY) npc.data().set(NPC.Metadata.HOLOGRAM_RENDERER, new HologramTrait.TextDisplayRenderer() {
                            @Override public void onSeenByPlayer(NPC helper, ServerPlayer player) {
                                Actor actor = actors.stream().filter(a -> a.player == player).findFirst().orElseThrow();
                                rendered.merge(key(helper, actor), 1, Integer::sum);
                                if (!actor.known.contains(helper.getEntity().getId())) errors.add("render_before_spawn");
                            }
                        });
                        check(npc.spawn(new Location(level, 4, -60, 4)), "spawn_" + npc.getName());
                    }
                }
                case 2 -> {
                    for (NPC npc : npcs) {
                        check(alice.spawns(npc.getEntity()) == 1 && bob.spawns(npc.getEntity()) == 0, "cancel_blocks_only_bob_" + npc.getName());
                        check(NPCVisibility.isTracked(npc.getEntity(), alice.player) && !NPCVisibility.isTracked(npc.getEntity(), bob.player), "membership_matches_admission_" + npc.getName());
                        check(count(seen, npc, alice) == 1 && count(seen, npc, bob) > 0, "one_accepted_event_retried_denial_" + npc.getName());
                        check(count(starts, npc, alice) == 1 && count(starts, npc, bob) == 0, "start_only_after_acceptance_" + npc.getName());
                        check(bob.metadata(npc.getEntity()) == 0 && bob.equipment(npc.getEntity()) == 0, "denied_viewer_gets_no_updates_" + npc.getName());
                        if (npc.getEntity().getType() == EntityType.COW) check(mirrored(alice, npc, Items.GOLD_INGOT), "cosmetic_after_spawn_" + npc.getName());
                        if (npc.hasTrait(MirrorTrait.class)) {
                            check(bob.profiles(npc) == 0 && mirrored(alice, npc, Items.DIAMOND), "mirror_after_spawn_only_" + npc.getName());
                        }
                        if (npc.getEntity().getType() == EntityType.TEXT_DISPLAY)
                            check(count(rendered, npc, alice) == 1 && count(rendered, npc, bob) == 0, "renderer_only_after_pairing_" + npc.getName());
                    }
                    check(errors.isEmpty(), "event_packet_order_" + errors); snapshot = Map.copyOf(seen); clear();
                }
                case 3 -> {
                    for (NPC npc : npcs) check(count(seen, npc, alice) == count(snapshot, npc, alice), "paired_viewer_no_repeat_" + npc.getName());
                    check(npcs.stream().allMatch(n -> bob.equipment(n.getEntity()) == 0), "mirror_refresh_respects_cancelled_admission");
                    clear();
                    for (NPC npc : npcs) if (npc.getEntity().getType() == EntityType.COW) {
                        npc.getTrait(Equipment.class).setCosmetic(Equipment.EquipmentSlot.HAND, new ItemStack(Items.IRON_INGOT));
                        check(equipmentItem(alice, npc, Items.IRON_INGOT) && bob.equipment(npc.getEntity()) == 0, "live_cosmetic_respects_admission_" + npc.getName());
                    }
                    deny = false; clear();
                }
                case 4 -> {
                    for (NPC npc : npcs) {
                        check(bob.spawns(npc.getEntity()) == 1 && NPCVisibility.isTracked(npc.getEntity(), bob.player), "retry_pairs_when_allowed_" + npc.getName());
                        if (npc.getEntity().getType() == EntityType.COW) check(mirrored(bob, npc, Items.IRON_INGOT), "cosmetic_retry_after_spawn_" + npc.getName());
                        if (npc.hasTrait(MirrorTrait.class)) check(mirrored(bob, npc, Items.EMERALD), "retry_mirrors_equipment_" + npc.getName());
                    }
                    snapshot = Map.copyOf(seen); deny = true; clear();
                }
                case 5 -> {
                    for (NPC npc : npcs) check(NPCVisibility.isTracked(npc.getEntity(), bob.player) && count(seen, npc, bob) == count(snapshot, npc, bob), "admission_is_not_live_revocation_" + npc.getName());
                    clear(); bob.player.setPos(180, -60, 4);
                }
                case 6 -> {
                    for (NPC npc : npcs) check(bob.removals(npc.getEntity()) == 1 && !NPCVisibility.isTracked(npc.getEntity(), bob.player), "native_range_unpairs_" + npc.getName());
                    clear(); bob.player.setPos(8, -60, 4);
                }
                case 7 -> {
                    for (NPC npc : npcs) check(bob.spawns(npc.getEntity()) == 0 && count(seen, npc, bob) > count(snapshot, npc, bob), "reentry_can_be_denied_" + npc.getName());
                    check(npcs.stream().allMatch(n -> bob.equipment(n.getEntity()) == 0), "no_equipment_after_denied_reentry");
                    deny = false; clear();
                }
                case 8 -> {
                    for (NPC npc : npcs) {
                        check(bob.spawns(npc.getEntity()) == 1, "reentry_accepts_fresh_pairing_" + npc.getName());
                        npc.getOrAddTrait(PlayerFilter.class).addPlayer(alice.player.getUUID());
                    }
                    snapshot = Map.copyOf(seen); clear();
                }
                case 9 -> {
                    for (NPC npc : npcs) {
                        check(alice.removals(npc.getEntity()) == 1 && count(seen, npc, alice) == count(snapshot, npc, alice), "filter_unpairs_without_admission_" + npc.getName());
                        npc.removeTrait(PlayerFilter.class);
                    }
                    clear();
                }
                case 10 -> {
                    for (NPC npc : npcs) check(alice.spawns(npc.getEntity()) == 1 && count(seen, npc, alice) == count(snapshot, npc, alice) + 1, "filter_clear_rechecks_admission_" + npc.getName());
                    for (boolean packet : List.of(false, true)) {
                        NPC npc = registry.createNPC(EntityType.COW, "destroy during admission " + packet);
                        if (packet) npc.getOrAddTrait(PacketNPC.class);
                        PlayerFilter filter = npc.getOrAddTrait(PlayerFilter.class); filter.addPlayer(alice.player.getUUID()); filter.addPlayer(bob.player.getUUID());
                        check(npc.spawn(new Location(level, 4, -60, 4)), "mutation_fixture_spawn_" + packet);
                        destroyOnSeen.add(npc); retired.add(npc.getEntity());
                    }
                }
                case 11 -> { clear(); for (NPC npc : destroyOnSeen) npc.removeTrait(PlayerFilter.class); }
                case 12 -> {
                    for (int i = 0; i < destroyOnSeen.size(); i++) {
                        NPC npc = destroyOnSeen.get(i); Entity entity = retired.get(i);
                        check(registry.getByUniqueId(npc.getUniqueId()) == null && entity.isRemoved(), "listener_destroy_is_final_" + i);
                        check(alice.spawns(entity) == 0 && bob.spawns(entity) == 0, "destroyed_target_never_paired_" + i);
                    }
                    check(errors.isEmpty(), "all_ordering_and_reentry_checks_" + errors);
                    var pending = NPCVisibility.class.getDeclaredField("pairing"); pending.setAccessible(true);
                    check(((Map<?, ?>) pending.get(null)).isEmpty(), "admission_guard_released_after_callbacks");
                    LoggerFactory.getLogger("citizens").info("[SEENEVENTAUDIT] COMPLETE {} checks", passed); done = true;
                }
            }
        } catch (Throwable failure) { done = true; LoggerFactory.getLogger("citizens").error("[SEENEVENTAUDIT] FAILED phase " + phase, failure); }
        if (done) {
            try {
                for (NPC npc : npcs) npc.destroy();
                for (Actor actor : actors) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[SEENEVENTAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }
    private static String key(NPC npc, Actor actor) { return npc.getUniqueId() + "/" + actor.player.getUUID(); }
    private static int count(Map<String, Integer> counts, NPC npc, Actor actor) { return counts.getOrDefault(key(npc, actor), 0); }
    private static boolean equipmentItem(Actor actor, NPC npc, net.minecraft.world.item.Item item) {
        actor.pump();
        return actor.packets.stream().anyMatch(p -> p instanceof ClientboundSetEquipmentPacket equipment
                && equipment.getEntity() == npc.getEntity().getId() && equipment.getSlots().stream()
                .anyMatch(slot -> slot.getFirst() == EquipmentSlot.MAINHAND && slot.getSecond().is(item)));
    }
    private static boolean mirrored(Actor actor, NPC npc, net.minecraft.world.item.Item item) {
        int spawn = -1;
        for (int i = 0; i < actor.packets.size(); i++) {
            Packet<?> packet = actor.packets.get(i);
            if (packet instanceof ClientboundAddEntityPacket add && add.getId() == npc.getEntity().getId()) spawn = i;
            if (packet instanceof ClientboundSetEquipmentPacket equipment && equipment.getEntity() == npc.getEntity().getId()
                    && equipment.getSlots().stream().anyMatch(slot -> slot.getFirst() == EquipmentSlot.MAINHAND && slot.getSecond().is(item))) return spawn >= 0 && i > spawn;
        }
        return false;
    }
    private static void clear() { for (Actor actor : actors) { actor.pump(); actor.packets.clear(); } }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[SEENEVENTAUDIT] PASS {}", label); }
    private static Actor actor(MinecraftServer server, String name, double x) {
        var defaults = ClientInformation.createDefault();
        var information = new ClientInformation(defaults.language(), 6, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing());
        Actor actor = new Actor(); actor.player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), information);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("seen-event-capture", new ChannelOutboundHandlerAdapter() {
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
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(packet);
            if (packet instanceof ClientboundAddEntityPacket add) known.add(add.getId());
            if (packet instanceof ClientboundRemoveEntitiesPacket remove) for (int id : remove.getEntityIds()) known.remove(id);
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
