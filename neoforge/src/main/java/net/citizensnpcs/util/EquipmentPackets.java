package net.citizensnpcs.util;

import java.util.ArrayList;
import java.util.List;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.MirrorTrait;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Apply mirrored equipment to actual native packets, including an otherwise equipment-free initial pairing. */
public final class EquipmentPackets {
    private EquipmentPackets() { }

    public static Packet<?> rewrite(Entity entity, ServerPlayer viewer, Packet<?> packet) {
        if (!(packet instanceof ClientboundBundlePacket) && !(packet instanceof ClientboundSetEquipmentPacket))
            return packet;
        NPC npc = NPCRegistries.lookup(entity);
        MirrorTrait mirror = npc == null ? null : npc.getTraitNullable(MirrorTrait.class);
        if (mirror == null) return packet;
        if (packet instanceof ClientboundSetEquipmentPacket equipment) {
            if (equipment.getEntity() != entity.getId()) return packet;
            ClientboundSetEquipmentPacket projected = mirror.createEquipmentPacket(viewer);
            return projected == null ? packet : projected;
        }
        ClientboundBundlePacket bundle = (ClientboundBundlePacket) packet;
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        boolean spawned = false, hasEquipment = false;
        ClientboundSetEquipmentPacket projected = null;
        for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
            if (child instanceof ClientboundAddEntityPacket spawn && spawn.getId() == entity.getId()) spawned = true;
            if (child instanceof ClientboundSetEquipmentPacket equipment && equipment.getEntity() == entity.getId()) {
                if (projected == null) projected = mirror.createEquipmentPacket(viewer);
                if (projected == null) return packet;
                packets.add(projected);
                hasEquipment = true;
            } else {
                packets.add(child);
            }
        }
        if (spawned && !hasEquipment) {
            projected = mirror.createEquipmentPacket(viewer);
            if (projected != null) packets.add(projected);
        }
        return projected == null ? packet : new ClientboundBundlePacket(packets);
    }
}
