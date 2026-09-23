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

/** Real ChunkMap pairing, including chunk delivery; no direct pairing or tracker mutation. */
@EventBusSubscriber(modid = "citizens")
public final class WorldVisibilityRuntimeAudit {
    private static boolean forced, done, deny;
    private static int phase, nextTick, passed, deadline;
    private static NPCRegistry registry;
    private static NPC npc, human, distant;
    private static Entity ordinary;
    private static Actor alice, bob;
    private static List<Entity> helpers = List.of();
    private static final List<Actor> actors = new ArrayList<>();

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); ServerLevel level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 1800; }
        try {
            for (Actor actor : actors) actor.pump();
            if (server.getTickCount() > deadline) throw new AssertionError("timed_out_phase_" + phase);
            if (!level.areEntitiesLoaded(ChunkPos.asLong(0, 0)) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
            if (server.getTickCount() < nextTick) return;
            // Let the server deliver chunks and consume legitimate batch acknowledgements before asserting pairing.
            if (phase == 1 && (!alice.watches(level, 0, 0) || !bob.watches(level, 0, 0))) return;
            if (phase == 15 && !alice.watches(level, 0, 0)) return;
            if (phase == 18 && (!bob.watches(level, 5, 0) || !level.areEntitiesLoaded(ChunkPos.asLong(5, 0)))) return;
            nextTick = server.getTickCount() + 8;
            switch (phase++) {
                case 0 -> {
                    check(Files.isRegularFile(Path.of("world-visibility-audit-fixture.txt"))
                            && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
                    alice = actor(server, "WorldAlice", 6); bob = actor(server, "WorldBob", 8);
                    check(!bob.watches(level, 0, 0), "actor_has_not_received_npc_chunk_yet");
                    registry = CitizensAPI.createNamedNPCRegistry("world-visibility-audit", new MemoryNPCDataStore());
                    npc = registry.createNPC(EntityType.COW, "World visibility");
                    npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
                    npc.getOrAddTrait(PlayerFilter.class).addPlayer(alice.player.getUUID());
                    var hologram = npc.getOrAddTrait(HologramTrait.class);
                    hologram.addLine("Visible text"); hologram.addLine("<item:diamond>");
                    clear(); check(npc.spawn(new Location(level, 4, -60, 4)), "ordinary_npc_spawn");
                    human = registry.createNPC(EntityType.PLAYER, "World profile");
                    human.getOrAddTrait(PlayerFilter.class).addPlayer(alice.player.getUUID());
                    check(human.spawn(new Location(level, 4, -60, 6)), "player_npc_spawn");
                    check(bob.spawns(npc.getEntity()) == 0 && watchers(npc.getEntity()).isEmpty(), "native_chunk_watch_required_before_pairing");
                }
                case 1 -> {
                    check(alice.chunks.contains(0L) && bob.chunks.contains(0L), "actual_native_chunk_packets_received");
                    check(alice.spawns(npc.getEntity()) == 0 && bob.spawns(npc.getEntity()) == 1, "initial_filter_prevents_entity_spawn");
                    check(alice.spawns(human.getEntity()) == 0 && alice.profiles(human) == 0, "initial_filter_prevents_player_profile_and_spawn");
                    check(bob.spawns(human.getEntity()) == 1 && bob.profileBeforeSpawn(human), "allowed_player_profile_precedes_spawn");
                    check(watchers(npc.getEntity()).contains(bob.player) && !watchers(npc.getEntity()).contains(alice.player), "native_seen_by_matches_filter");
                    // Create the stationary control after chunk delivery: vanilla otherwise waits for section movement.
                    ordinary = EntityType.COW.create(level); ordinary.setPos(10, -60, 4);
                    ((net.minecraft.world.entity.Mob) ordinary).setNoAi(true);
                    check(level.addFreshEntity(ordinary), "non_npc_world_insertion");
                    check(alice.spawns(ordinary) == 1 && bob.spawns(ordinary) == 1, "non_npc_still_tracks_normally");
                    helpers = List.copyOf(npc.getTrait(HologramTrait.class).getHologramEntities());
                    check(helpers.size() == 3, "text_item_and_anchor_created");
                    check(helpers.stream().allMatch(e -> alice.spawns(e) == 0 && bob.spawns(e) == 1), "parent_filter_prevents_all_helper_spawns");
                    clear(); change(npc, "Hidden metadata"); change(human, "Hidden profile entity");
                }
                case 2 -> {
                    check(alice.metadata(npc.getEntity()) == 0 && alice.equipment(npc.getEntity()) == 0, "initially_hidden_gets_no_entity_updates");
                    check(bob.metadata(npc.getEntity()) > 0 && bob.equipment(npc.getEntity()) > 0, "allowed_gets_native_metadata_and_equipment");
                    check(alice.metadata(human.getEntity()) == 0 && alice.equipment(human.getEntity()) == 0, "hidden_player_entity_gets_no_updates");
                    clear(); npc.getTrait(PlayerFilter.class).clear(); human.getTrait(PlayerFilter.class).clear();
                }
                case 3 -> {
                    check(alice.spawns(npc.getEntity()) == 1 && alice.spawns(human.getEntity()) == 1, "clear_restores_stationary_npcs");
                    check(alice.profileBeforeSpawn(human), "fresh_profile_before_restored_player");
                    check(helpers.stream().allMatch(e -> alice.spawns(e) == 1), "clear_restores_all_helpers");
                    check(bob.spawns(npc.getEntity()) == 0, "stable_viewer_not_repaired");
                    clear(); npc.getTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                    human.getTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                    change(npc, "Same tick denial"); change(human, "Same tick player denial");
                }
                case 4 -> {
                    check(bob.removals(npc.getEntity()) == 1 && bob.removals(human.getEntity()) == 1, "live_filter_removes_stationary_npcs");
                    check(bob.metadata(npc.getEntity()) == 0 && bob.equipment(npc.getEntity()) == 0
                            && bob.metadata(human.getEntity()) == 0, "denial_reconciles_before_dirty_broadcast");
                    check(bob.profileRemovals(human) == 1, "native_stop_tracking_removes_profile");
                    check(helpers.stream().allMatch(e -> bob.removals(e) == 1 && !watchers(e).contains(bob.player)), "live_parent_filter_removes_helpers");
                    check(level.getEntity(npc.getEntity().getId()) == npc.getEntity() && npc.isSpawned(), "hidden_npc_remains_in_server_world");
                    clear(); npc.getTrait(PlayerFilter.class).setPlayers(Set.of(bob.player.getUUID())); npc.getTrait(PlayerFilter.class).setAllowlist();
                }
                case 5 -> {
                    check(alice.removals(npc.getEntity()) == 1 && bob.spawns(npc.getEntity()) == 1, "allowlist_reconciles_both_viewers");
                    clear(); npc.removeTrait(PlayerFilter.class); human.removeTrait(PlayerFilter.class);
                }
                case 6 -> {
                    check(alice.spawns(npc.getEntity()) == 1 && bob.spawns(human.getEntity()) == 1, "trait_removal_restores_visibility");
                    clear(); var filter = npc.getOrAddTrait(PlayerFilter.class);
                    filter.addPlayer(alice.player.getUUID()); filter.setApplyRange(1);
                }
                case 7 -> {
                    check(watchers(npc.getEntity()).contains(alice.player) && alice.removals(npc.getEntity()) == 0, "filter_ignored_outside_apply_range");
                    clear(); npc.getTrait(PlayerFilter.class).setApplyRange(12);
                }
                case 8 -> {
                    check(alice.removals(npc.getEntity()) == 1, "live_apply_range_hides_stationary_viewer");
                    clear(); npc.getTrait(PlayerFilter.class).setPlayerFilter(p -> deny); deny = false;
                }
                case 9 -> {
                    check(alice.spawns(npc.getEntity()) == 1, "custom_predicate_restores_viewer");
                    clear(); deny = true;
                }
                case 10 -> {
                    check(alice.removals(npc.getEntity()) == 1 && bob.removals(npc.getEntity()) == 1, "external_predicate_change_hides_without_recalculate");
                    check(helpers.stream().allMatch(e -> watchers(e).isEmpty()), "dynamic_predicate_hides_helper_chain");
                    clear(); deny = false;
                }
                case 11 -> {
                    check(alice.spawns(npc.getEntity()) == 1 && bob.spawns(npc.getEntity()) == 1, "external_predicate_change_restores_without_recalculate");
                    clear(); npc.addTrait(new ClickRedirectTrait(human)); human.addTrait(new ClickRedirectTrait(npc));
                }
                case 12 -> {
                    check(watchers(npc.getEntity()).isEmpty() && watchers(human.getEntity()).isEmpty(), "redirect_cycle_rejects_world_tracking");
                    clear(); npc.removeTrait(ClickRedirectTrait.class); human.removeTrait(ClickRedirectTrait.class);
                }
                case 13 -> {
                    check(alice.spawns(npc.getEntity()) == 1 && bob.spawns(npc.getEntity()) == 1, "redirect_removal_restores_tracking");
                    clear(); alice.player.setPos(150, -60, 4);
                }
                case 14 -> {
                    check(alice.removals(npc.getEntity()) == 1 && !watchers(npc.getEntity()).contains(alice.player), "native_range_exit_unpairs");
                    check(alice.removals(ordinary) == 1, "non_npc_native_range_exit_preserved");
                    clear(); alice.player.setPos(6, -60, 4);
                }
                case 15 -> {
                    check(alice.spawns(npc.getEntity()) == 1 && watchers(npc.getEntity()).contains(alice.player), "native_range_return_pairs_after_chunk_watch");
                    clear(); alice.player.teleportTo(server.getLevel(Level.NETHER), 6, 64, 4, Set.of(), 0, 0);
                }
                case 16 -> {
                    check(!watchers(npc.getEntity()).contains(alice.player), "other_dimension_excluded_from_refresh");
                    clear(); change(npc, "Other dimension");
                }
                case 17 -> {
                    check(alice.spawns(npc.getEntity()) == 0 && alice.metadata(npc.getEntity()) == 0, "other_dimension_gets_no_pairing_or_updates");
                }
                case 18 -> {
                    check(!level.isPositionEntityTicking(new BlockPos(84, -60, 4)), "watched_remote_chunk_is_outside_simulation");
                    distant = registry.createNPC(EntityType.COW, "Outside simulation");
                    distant.getOrAddTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                    clear(); check(distant.spawn(new Location(level, 84, -60, 4)), "non_ticking_npc_spawn");
                }
                case 19 -> {
                    check(bob.spawns(distant.getEntity()) == 0 && watchers(distant.getEntity()).isEmpty(), "initial_denial_outside_simulation");
                    clear(); distant.getTrait(PlayerFilter.class).clear();
                }
                case 20 -> {
                    check(!level.isPositionEntityTicking(distant.getEntity().blockPosition()), "remote_npc_still_not_entity_ticking");
                    check(bob.spawns(distant.getEntity()) == 1 && watchers(distant.getEntity()).contains(bob.player), "clear_restores_stationary_non_ticking_npc");
                    clear(); distant.getTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                }
                case 21 -> {
                    check(bob.removals(distant.getEntity()) == 1 && watchers(distant.getEntity()).isEmpty(), "live_denial_unpairs_non_ticking_npc");
                    LoggerFactory.getLogger("citizens").info("[WORLDVISIBILITYAUDIT] COMPLETE {} checks", passed); done = true;
                }
            }
        } catch (Throwable failure) { done = true; LoggerFactory.getLogger("citizens").error("[WORLDVISIBILITYAUDIT] FAILED phase " + phase, failure); }
        if (done) {
            try {
                if (npc != null) npc.destroy(); if (human != null) human.destroy(); if (distant != null) distant.destroy(); if (ordinary != null) ordinary.discard();
                for (Actor actor : actors) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[WORLDVISIBILITYAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    private static Set<ServerPlayer> watchers(Entity entity) {
        return new HashSet<>(((ServerLevel) entity.level()).getChunkSource().chunkMap.getPlayersWatching(entity));
    }
    private static void change(NPC target, String name) {
        target.getEntity().setCustomName(Component.literal(name));
        LivingEntity entity = (LivingEntity) target.getEntity();
        entity.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(entity.getMainHandItem().is(Items.DIAMOND) ? Items.GOLD_INGOT : Items.DIAMOND));
    }
    private static void clear() { for (Actor actor : actors) { actor.pump(); actor.packets.clear(); } }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[WORLDVISIBILITYAUDIT] PASS {}", label); }

    private static Actor actor(MinecraftServer server, String name, double x) {
        var defaults = ClientInformation.createDefault();
        var information = new ClientInformation(defaults.language(), 6, defaults.chatVisibility(), defaults.chatColors(),
                defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing());
        Actor actor = new Actor(); actor.player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), information);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("world-visibility-capture", new ChannelOutboundHandlerAdapter() {
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
