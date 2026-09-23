package net.citizensnpcs.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.npc.skin.SkinPacketTracker;
import net.citizensnpcs.trait.ScoreboardTrait;
import net.citizensnpcs.util.HologramMetadata;
import net.citizensnpcs.util.PacketMounts;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;

@Mixin(ServerEntity.class)
public class ServerEntityMixin {
    @Shadow
    @Final
    private Entity entity;

    @WrapOperation(method = "addPairing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void citizens$personalizeHologramPairing(ServerGamePacketListenerImpl connection, Packet<?> packet,
            Operation<Void> original) {
        Packet<?> projected = PacketMounts.pairing(entity, connection.getPlayer(), packet);
        if (projected != null) original.call(connection, HologramMetadata.rewrite(entity, connection.getPlayer(), projected));
    }

    @Inject(method = "removePairing", at = @At("TAIL"))
    private void citizens$forgetHologramViewer(ServerPlayer viewer, CallbackInfo ci) {
        HologramMetadata.forget(entity, viewer);
    }

    @Inject(method = "removePairing", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/event/EventHooks;onStopEntityTracking(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/player/Player;)V"))
    private void citizens$forgetMountViewerBeforeCallbacks(ServerPlayer viewer, CallbackInfo ci) {
        PacketMounts.forget(entity, viewer);
    }

    /**
     * Gives a player NPC its tab-list entry before the client is told the entity exists.
     * <p>
     * A 1.21 client will not create a player entity it has no {@code PlayerInfo} for: {@code handleAddEntity} looks the
     * UUID up, warns "Server attempted to add player prior to sending player info" and drops the entity. So every
     * player-type NPC is invisible unless a {@code ClientboundPlayerInfoUpdatePacket} carrying {@code ADD_PLAYER}
     * reaches the viewer first.
     * <p>
     * There is no event early enough to do this. {@code ServerEntity.addPairing} collects the whole pairing sequence
     * into one list, sends it as a single {@code ClientboundBundlePacket}, and only then fires NeoForge's
     * {@code PlayerEvent.StartTracking} — by which point the client has already discarded the NPC. Injecting at the head
     * of {@code sendPairingData} is what puts the profile ahead of the spawn packet.
     * <p>
     * The NPC's scoreboard team also precedes pairing, so name visibility, collision and glow color are correct on
     * the first client frame. Teams still reach all online players through the trait's normal update.
     */
    @Inject(method = "sendPairingData", at = @At("HEAD"))
    private void citizens$sendProfileBeforeSpawn(ServerPlayer viewer,
            PacketAndPayloadAcceptor<ClientGamePacketListener> acceptor, CallbackInfo ci) {
        var npc = NPCRegistries.lookup(entity);
        ScoreboardTrait scoreboard = npc == null ? null : npc.getTraitNullable(ScoreboardTrait.class);
        if (scoreboard != null) {
            scoreboard.prepareForViewer(viewer);
        }
        if (entity instanceof EntityHumanNPC human) {
            // sent straight down the connection rather than through the acceptor: the acceptor's packets are buffered
            // into the bundle that addPairing sends afterwards, so anything written here arrives first either way, and
            // MirrorTrait needs the per-viewer profile swap that SkinPacketTracker does around the send
            SkinPacketTracker.sendTo(human, viewer);
        }
    }

    /**
     * Keeps player NPCs out of the map-update loop every loaded item frame runs.
     * <p>
     * {@code sendChanges} does this once every ten ticks for each tracked item frame:
     *
     * <pre>
     * for (ServerPlayer p : this.level.players()) {
     *     mapData.tickCarriedBy(p, stack);
     *     Packet&lt;?&gt; packet = mapData.getUpdatePacket(mapId, p);
     *     if (packet != null)
     *         p.connection.send(packet);
     * }
     * </pre>
     *
     * NeoForge has patched out the {@code instanceof MapItem} guard that used to wrap it, so the loop is entered for
     * every item frame in the level, map or not. {@code level.players()} contains every player NPC, and
     * {@code tickCarriedBy} is not a cheap call to make on one: it scans the whole inventory
     * ({@code Inventory.contains}) and, worse, adds the player to the map's {@code carriedBy} list on first sight and
     * then re-scans the inventory of <em>everyone</em> on that list on every later call. The list only ever grows while
     * the players on it stay loaded, so the cost is quadratic in fake players.
     * <p>
     * That showed up on the live server as {@code ServerEntity.sendChanges} 8.23% of the tick,
     * {@code MapItemSavedData.tickCarriedBy} 7.36% and {@code Inventory.contains} 6.13% — a decorative wall of maps,
     * multiplied by 61 NPCs that could never look at one.
     * <p>
     * No map marker changes. The player-marker branch inside {@code tickCarriedBy} is guarded by
     * {@code !stack.isFramed()}, and a stack reached from an item frame always is, so vanilla never drew a marker for
     * anybody through this path; the frame marker it does draw is idempotent and every real player's iteration still
     * draws it. What disappears is the inventory scanning, the {@code carriedBy} growth, and a map packet serialised for
     * a connection that throws it away. The one reachable edge is a level holding item frames and NPCs but no human at
     * all: its frame markers are now registered when a human arrives rather than continuously, which is only observable
     * to somebody in another dimension holding a copy of that same map.
     * <p>
     * The original list is returned untouched when it holds no NPCs, so a server without player-type NPCs — or any
     * level that has none in it — pays one scan and no allocation. {@code @WrapOperation} rather than {@code @Redirect}
     * so another mod wrapping the same call still gets to run.
     */
    @WrapOperation(method = "sendChanges", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"))
    private List<ServerPlayer> citizens$excludeNPCsFromMapUpdates(ServerLevel level,
            Operation<List<ServerPlayer>> original) {
        List<ServerPlayer> players = original.call(level);
        int npcs = 0;
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i) instanceof EntityHumanNPC) {
                npcs++;
            }
        }
        if (npcs == 0)
            return players;
        List<ServerPlayer> real = new ArrayList<>(players.size() - npcs);
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            if (!(player instanceof EntityHumanNPC)) {
                real.add(player);
            }
        }
        return real;
    }
}
