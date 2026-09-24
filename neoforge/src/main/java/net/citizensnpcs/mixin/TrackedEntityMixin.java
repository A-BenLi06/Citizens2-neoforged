package net.citizensnpcs.mixin;

import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.citizensnpcs.util.NPCVisibility;
import net.citizensnpcs.util.HologramMetadata;
import net.citizensnpcs.util.EquipmentPackets;
import net.citizensnpcs.util.PacketMounts;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Keep vanilla's tracking range/chunk rules and pairing bookkeeping authoritative. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin implements NPCVisibility.TrackedEntity {
    @Shadow @org.spongepowered.asm.mixin.Final private Entity entity;
    @Shadow @org.spongepowered.asm.mixin.Final private Set<ServerPlayerConnection> seenBy;
    @Shadow public abstract void updatePlayers(List<ServerPlayer> players);
    @Shadow public abstract void updatePlayer(ServerPlayer player);
    @Shadow public abstract void removePlayer(ServerPlayer player);

    @Override public void citizens$refreshPairing() {
        for (ServerPlayerConnection connection : List.copyOf(seenBy)) {
            if (entity.isRemoved()) return;
            if (!seenBy.contains(connection)) continue;
            ServerPlayer viewer = connection.getPlayer();
            removePlayer(viewer);
            // Stop-tracking listeners can destroy the NPC or change admission. Native updatePlayer rechecks range,
            // chunk ownership and Citizens visibility before its normal profile/entity pairing sequence.
            if (!entity.isRemoved()) updatePlayer(viewer);
        }
    }

    @WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE",
            target = "Ljava/util/Set;add(Ljava/lang/Object;)Z"))
    private boolean citizens$admitViewer(Set<ServerPlayerConnection> viewers, Object connection,
            Operation<Boolean> original) {
        ServerPlayer viewer = ((ServerPlayerConnection) connection).getPlayer();
        if (!viewers.contains(connection) && !NPCVisibility.allowPairing(entity, viewer)) return false;
        return original.call(viewers, connection);
    }

    @WrapOperation(method = "broadcast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerPlayerConnection;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void citizens$personalizeHologramUpdate(ServerPlayerConnection connection, Packet<?> packet,
            Operation<Void> original) {
        Packet<?> projected = PacketMounts.rewrite(entity, connection.getPlayer(), packet);
        if (projected != null) original.call(connection, EquipmentPackets.rewrite(entity, connection.getPlayer(),
                HologramMetadata.rewrite(entity, connection.getPlayer(), projected)));
    }

    @WrapOperation(method = "broadcastAndSend", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void citizens$projectOwnMounts(net.minecraft.server.network.ServerGamePacketListenerImpl connection,
            Packet<?> packet, Operation<Void> original) {
        Packet<?> projected = PacketMounts.rewrite(entity, connection.getPlayer(), packet);
        if (projected != null) original.call(connection, EquipmentPackets.rewrite(entity, connection.getPlayer(),
                HologramMetadata.rewrite(entity, connection.getPlayer(), projected)));
    }

    @Override public void citizens$updateViewers(List<ServerPlayer> players) {
        updatePlayers(players);
    }

    @WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;broadcastToPlayer(Lnet/minecraft/server/level/ServerPlayer;)Z"))
    private boolean citizens$visibility(Entity entity, ServerPlayer viewer, Operation<Boolean> original) {
        return original.call(entity, viewer) && NPCVisibility.isVisible(entity, viewer);
    }
}
