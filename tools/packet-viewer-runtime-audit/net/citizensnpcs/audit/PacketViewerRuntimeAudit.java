package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class PacketViewerRuntimeAudit {
    private static boolean forced, done;
    private static int phase, nextTick, passed, entityId;
    private static NPCRegistry registry;
    private static NPC npc, parent, detached;
    private static Actor alice, bob, spectator;
    private static ServerPlayer retired;
    private static Entity oldEntity;
    private static PacketNPC removedTrait;
    private static final List<Actor> actors = new ArrayList<>();

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); ServerLevel level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; }
        if (!level.areEntitiesLoaded(ChunkPos.asLong(0, 0)) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
        if (server.getTickCount() < nextTick) return;
        nextTick = server.getTickCount() + 6;
        try {
            switch (phase++) {
                case 0 -> {
                    check(Files.isRegularFile(Path.of("packet-viewer-audit-fixture.txt"))
                            && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
                    alice = actor(server, "PacketAlice", UUID.randomUUID(), 6);
                    bob = actor(server, "PacketBob", UUID.randomUUID(), 40);
                    spectator = actor(server, "PacketSpectator", UUID.randomUUID(), 7);
                    spectator.player.setGameMode(GameType.SPECTATOR);
                    spectator.player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 10000));
                    registry = CitizensAPI.createNamedNPCRegistry("packet-viewer-audit", new MemoryNPCDataStore());
                    npc = registry.createNPC(EntityType.COW, "Packet audit");
                    npc.data().set(NPC.Metadata.TRACKING_RANGE, 8);
                    npc.getOrAddTrait(PacketNPC.class);
                    clear(); check(npc.spawn(new Location(level, 4, -60, 4)), "packet_spawn_without_type_reset");
                    entityId = npc.getEntity().getId();
                }
                case 1 -> {
                    check(level.getEntity(entityId) == null && level.getEntity(npc.getUniqueId()) == null, "virtual_entity_absent_from_world");
                    check(alice.spawns(entityId) == 1 && spectator.spawns(entityId) == 1 && bob.spawns(entityId) == 0, "only_initial_eligible_viewers_spawn");
                    check(viewers().size() == 2 && viewers().contains(spectator.player), "spectator_and_invisible_viewer_can_see_npc");
                    clear(); npc.getEntity().setCustomName(Component.literal("Changed"));
                    ((LivingEntity) npc.getEntity()).setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND));
                }
                case 2 -> {
                    check(alice.metadata(entityId) > 0 && spectator.metadata(entityId) > 0 && bob.metadata(entityId) == 0, "metadata_only_to_linked_viewers");
                    check(alice.equipment(entityId) > 0 && bob.equipment(entityId) == 0, "native_equipment_diff_only_to_viewers");
                    check(alice.spawns(entityId) == 0 && spectator.spawns(entityId) == 0, "stable_viewers_not_respawned");
                    clear(); alice.player.setPos(40, -60, 4); bob.player.setPos(6, -60, 4);
                }
                case 3 -> {
                    check(alice.removals(entityId) == 1 && bob.spawns(entityId) == 1, "range_exit_and_entry_reconcile");
                    check(viewers().size() == 2 && !viewers().contains(alice.player) && viewers().contains(bob.player), "range_exit_drops_reference");
                    clear(); npc.getEntity().setCustomName(Component.literal("After range exit"));
                }
                case 4 -> {
                    check(alice.metadata(entityId) == 0 && bob.metadata(entityId) > 0, "departed_viewer_receives_no_updates");
                    clear(); alice.player.setPos(6, -60, 4);
                }
                case 5 -> {
                    check(alice.spawns(entityId) == 1 && viewers().size() == 3, "range_reentry_gets_fresh_pairing");
                    clear(); npc.getOrAddTrait(PlayerFilter.class).addPlayer(alice.player.getUUID());
                    check(npc.isHiddenFrom(alice.player) && !npc.isHiddenFrom(bob.player), "default_filter_constructor_applies_rules");
                }
                case 6 -> {
                    check(alice.removals(entityId) == 1 && !viewers().contains(alice.player), "live_denylist_unlinks_viewer");
                    var key = new MemoryDataKey(); PersistenceLoader.save(npc.getTrait(PlayerFilter.class), key);
                    npc.removeTrait(PlayerFilter.class); PersistenceLoader.load(npc.getOrAddTrait(PlayerFilter.class), key);
                    check(npc.isHiddenFrom(alice.player), "reloaded_filter_preserves_rule_behavior");
                    clear(); npc.getTrait(PlayerFilter.class).setPlayers(Set.of(bob.player.getUUID())); npc.getTrait(PlayerFilter.class).setAllowlist();
                }
                case 7 -> {
                    check(viewers().size() == 1 && viewers().contains(bob.player) && spectator.removals(entityId) == 1, "allowlist_restricts_existing_viewers");
                    clear(); npc.getTrait(PlayerFilter.class).clear();
                }
                case 8 -> {
                    check(viewers().size() == 3 && alice.spawns(entityId) == 1 && spectator.spawns(entityId) == 1, "cleared_rules_allow_fresh_pairing");
                    parent = registry.createNPC(EntityType.PIG, "Filter parent"); check(parent.spawn(new Location(level, 4, -60, 5)), "redirect_parent_spawn");
                    parent.getOrAddTrait(PlayerFilter.class).addPlayer(bob.player.getUUID());
                    clear(); npc.addTrait(new ClickRedirectTrait(parent));
                }
                case 9 -> {
                    check(!viewers().contains(bob.player) && bob.removals(entityId) == 1, "parent_filter_hides_packet_helper");
                    clear(); parent.addTrait(new ClickRedirectTrait(npc));
                }
                case 10 -> {
                    check(viewers().isEmpty() && alice.removals(entityId) == 1 && spectator.removals(entityId) == 1, "redirect_cycle_rejects_visibility");
                    clear(); parent.removeTrait(ClickRedirectTrait.class); npc.removeTrait(ClickRedirectTrait.class);
                }
                case 11 -> {
                    check(viewers().size() == 3, "redirect_removal_restores_eligible_viewers");
                    clear(); npc.data().set(NPC.Metadata.TRACKING_RANGE, 0);
                }
                case 12 -> {
                    check(viewers().isEmpty() && alice.removals(entityId) == 1 && bob.removals(entityId) == 1, "range_change_unlinks_current_viewers");
                    clear(); npc.data().set(NPC.Metadata.TRACKING_RANGE, 8);
                }
                case 13 -> {
                    check(viewers().size() == 3, "range_expansion_relinks_viewers");
                    retired = bob.player; bob.player = server.getPlayerList().respawn(retired, false, Entity.RemovalReason.KILLED);
                    bob.player.setPos(6, -60, 4); clear();
                }
                case 14 -> {
                    check(viewers().contains(bob.player) && viewers().stream().noneMatch(p -> p == retired), "real_respawn_replaces_player_reference");
                    check(bob.spawns(entityId) == 1, "respawn_gets_fresh_spawn_bundle");
                    clear(); bob.player.teleportTo(server.getLevel(Level.NETHER), 6, 64, 4, Set.of(), 0, 0);
                }
                case 15 -> {
                    check(!viewers().contains(bob.player) && bob.removals(entityId) == 1, "dimension_exit_unlinks_viewer");
                    clear(); npc.getEntity().setCustomName(Component.literal("Other dimension"));
                }
                case 16 -> {
                    check(bob.metadata(entityId) == 0, "other_dimension_receives_no_updates");
                    clear(); bob.player.teleportTo(level, 6, -60, 4, Set.of(), 0, 0);
                }
                case 17 -> {
                    check(bob.spawns(entityId) == 1 && viewers().contains(bob.player), "dimension_return_relinks_viewer");
                    retired = bob.player; UUID uuid = retired.getUUID();
                    server.getPlayerList().remove(retired); bob.channel.finishAndReleaseAll();
                    bob = actor(server, "PacketBob", uuid, 6); clear();
                }
                case 18 -> {
                    check(viewers().contains(bob.player) && viewers().stream().noneMatch(p -> p == retired), "relogin_replaces_old_connection_reference");
                    check(bob.spawns(entityId) == 1, "relogin_gets_fresh_pairing");
                    clear(); server.getPlayerList().remove(bob.player); bob.channel.finishAndReleaseAll();
                }
                case 19 -> {
                    check(!viewers().contains(bob.player) && viewers().size() == 2, "disconnect_drops_reference");
                    oldEntity = npc.getEntity(); removedTrait = npc.getTrait(PacketNPC.class); clear(); npc.removeTrait(PacketNPC.class);
                }
                case 20 -> {
                    check(oldEntity.isRemoved() && removedTrait.getPacketTracker().getLinked().isEmpty(), "disable_cleans_old_virtual_entity");
                    check(npc.isSpawned() && level.getEntity(npc.getEntity().getId()) == npc.getEntity(), "disable_restores_real_world_entity");
                    oldEntity = npc.getEntity(); clear(); npc.getOrAddTrait(PacketNPC.class);
                }
                case 21 -> {
                    check(oldEntity.isRemoved() && npc.isSpawned() && level.getEntity(npc.getEntity().getId()) == null, "live_api_attach_converts_to_packet_transport");
                    entityId = npc.getEntity().getId(); check(viewers().size() == 2, "reattached_packet_trait_tracks_current_viewers");
                    removedTrait = npc.getTrait(PacketNPC.class); clear(); npc.addTrait(new PacketNPC());
                }
                case 22 -> {
                    check(removedTrait.getPacketTracker().getLinked().isEmpty(), "trait_replacement_releases_old_tracker");
                    check(viewers().size() == 2 && alice.spawns(entityId) == 1 && spectator.spawns(entityId) == 1, "replacement_tracker_pairs_each_viewer_once");
                    removedTrait = npc.getTrait(PacketNPC.class); oldEntity = npc.getEntity(); clear(); check(npc.despawn(DespawnReason.PENDING_RESPAWN), "explicit_packet_despawn");
                }
                case 23 -> {
                    check(oldEntity.isRemoved() && removedTrait.getPacketTracker().getLinked().isEmpty() && alice.removals(entityId) == 1, "despawn_removes_pairings");
                    npc.removeTrait(PacketNPC.class);
                }
                case 24 -> {
                    check(!npc.isSpawned() && npc.getEntity() == null, "removing_unspawned_packet_trait_does_not_spawn");
                    check(npc.spawn(new Location(level, 4, -60, 4)) && level.getEntity(npc.getEntity().getId()) == npc.getEntity(), "manual_spawn_unwraps_retired_controller");
                    detached = registry.createNPC(EntityType.COW, "Removed before deferred respawn");
                    detached.getOrAddTrait(PacketNPC.class); check(detached.spawn(new Location(level, 3, -60, 4)), "second_virtual_npc_spawn");
                    detached.removeTrait(PacketNPC.class); detached.destroy();
                }
                case 25 -> {
                    check(registry.getByUniqueId(detached.getUniqueId()) == null && detached.getEntity() == null, "deferred_disable_cannot_resurrect_destroyed_npc");
                    LoggerFactory.getLogger("citizens").info("[PACKETVIEWERAUDIT] COMPLETE {} checks", passed);
                    done = true;
                }
            }
        } catch (Throwable failure) { done = true; LoggerFactory.getLogger("citizens").error("[PACKETVIEWERAUDIT] FAILED phase " + phase, failure); }
        if (done) {
            try {
                if (npc != null) npc.destroy(); if (parent != null) parent.destroy();
                for (Actor actor : actors) {
                    if (server.getPlayerList().getPlayer(actor.player.getUUID()) == actor.player) server.getPlayerList().remove(actor.player);
                    actor.channel.finishAndReleaseAll();
                }
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[PACKETVIEWERAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }

    private static List<ServerPlayer> viewers() { return List.copyOf(npc.getTrait(PacketNPC.class).getPacketTracker().getLinked()); }
    private static void clear() { for (Actor actor : actors) { actor.pump(); actor.packets.clear(); } }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; LoggerFactory.getLogger("citizens").info("[PACKETVIEWERAUDIT] PASS {}", label); }

    private static Actor actor(MinecraftServer server, String name, UUID uuid, double x) {
        Actor actor = new Actor(); actor.player = new ServerPlayer(server, server.overworld(), new GameProfile(uuid, name), ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("packet-viewer-capture", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof Packet<?> packet) actor.capture(packet);
                        super.write(context, message, promise);
                    }
                });
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(actor.player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, actor.player, cookie);
        actor.player.setPos(x, -60, 4); actors.add(actor); return actor;
    }

    private static final class Actor {
        ServerPlayer player;
        EmbeddedChannel channel;
        final List<Packet<?>> packets = new ArrayList<>();
        void capture(Packet<?> packet) { if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::capture); else packets.add(packet); }
        void pump() { channel.runPendingTasks(); }
        long spawns(int id) { pump(); return packets.stream().filter(p -> p instanceof ClientboundAddEntityPacket add && add.getId() == id).count(); }
        long removals(int id) { pump(); return packets.stream().filter(p -> p instanceof ClientboundRemoveEntitiesPacket remove && remove.getEntityIds().contains(id)).count(); }
        long metadata(int id) { pump(); return packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket data && data.id() == id).count(); }
        long equipment(int id) { pump(); return packets.stream().filter(p -> p instanceof ClientboundSetEquipmentPacket equipment && equipment.getEntity() == id).count(); }
    }
}
