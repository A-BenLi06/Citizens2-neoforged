package net.citizensnpcs.util;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.Unpooled;
import net.citizensnpcs.mixin.RotationAnglesAccessor;
import net.citizensnpcs.mixin.RotationEntityIdAccessor;
import net.citizensnpcs.mixin.SpawnHeadAngleAccessor;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.RotationTrait;
import net.citizensnpcs.trait.RotationTrait.PacketRotation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Native 1.21.1 pairing and broadcast projection, without modifying a packet shared with other viewers. */
public final class RotationPackets {
    private RotationPackets() { }

    public static Packet<?> rewrite(Entity entity, ServerPlayer viewer, Packet<?> packet) {
        if (!(packet instanceof ClientboundBundlePacket || packet instanceof ClientboundAddEntityPacket
                || packet instanceof ClientboundMoveEntityPacket move && move.hasRotation()
                || packet instanceof ClientboundRotateHeadPacket || packet instanceof ClientboundTeleportEntityPacket))
            return packet;
        var npc = NPCRegistries.lookup(entity);
        RotationTrait trait = npc == null ? null : npc.getTraitNullable(RotationTrait.class);
        if (trait == null || !trait.hasPacketSessions() || !NPCVisibility.isTracked(entity, viewer)) return packet;
        PacketRotation rotation = trait.getPacketRotation(viewer);
        if (rotation == null || !NPCVisibility.isTracked(entity, viewer)) return packet;
        return rewrite(entity, viewer, packet, trait, rotation);
    }

    @SuppressWarnings("unchecked")
    private static Packet<?> rewrite(Entity entity, ServerPlayer viewer, Packet<?> packet, RotationTrait trait,
            PacketRotation rotation) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                Packet<?> copy = rewrite(entity, viewer, child, trait, rotation);
                changed |= copy != child;
                packets.add((Packet<? super ClientGamePacketListener>) copy);
            }
            return changed ? new ClientboundBundlePacket(packets) : packet;
        }
        if (packet instanceof ClientboundMoveEntityPacket move && move.hasRotation()
                && ((RotationEntityIdAccessor) move).citizens$entityId() == entity.getId()) {
            return move.hasPosition()
                    ? new ClientboundMoveEntityPacket.PosRot(entity.getId(), move.getXa(), move.getYa(), move.getZa(),
                            rotation.yaw(), rotation.pitch(), move.isOnGround())
                    : new ClientboundMoveEntityPacket.Rot(entity.getId(), rotation.yaw(), rotation.pitch(), move.isOnGround());
        }
        if (packet instanceof ClientboundRotateHeadPacket head
                && ((RotationEntityIdAccessor) head).citizens$entityId() == entity.getId())
            return new ClientboundRotateHeadPacket(entity, rotation.headYaw());
        if (packet instanceof ClientboundTeleportEntityPacket teleport && teleport.getId() == entity.getId()) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ClientboundTeleportEntityPacket.STREAM_CODEC.encode(buffer, teleport);
                var copy = ClientboundTeleportEntityPacket.STREAM_CODEC.decode(buffer);
                ((RotationAnglesAccessor) copy).citizens$yaw(rotation.yaw());
                ((RotationAnglesAccessor) copy).citizens$pitch(rotation.pitch());
                return copy;
            } finally { buffer.release(); }
        }
        if (packet instanceof ClientboundAddEntityPacket spawn && spawn.getId() == entity.getId()) {
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), viewer.registryAccess());
            try {
                ClientboundAddEntityPacket.STREAM_CODEC.encode(buffer, spawn);
                var copy = ClientboundAddEntityPacket.STREAM_CODEC.decode(buffer);
                ((RotationAnglesAccessor) copy).citizens$yaw(rotation.yaw());
                ((RotationAnglesAccessor) copy).citizens$pitch(rotation.pitch());
                ((SpawnHeadAngleAccessor) copy).citizens$headYaw(rotation.headYaw());
                trait.recordPairing(viewer, rotation);
                return copy;
            } finally { buffer.release(); }
        }
        return packet;
    }

    public static void forget(Entity entity, ServerPlayer viewer) {
        var npc = NPCRegistries.lookup(entity);
        RotationTrait trait = npc == null ? null : npc.getTraitNullable(RotationTrait.class);
        if (trait != null) trait.forgetViewer(viewer);
    }
}
