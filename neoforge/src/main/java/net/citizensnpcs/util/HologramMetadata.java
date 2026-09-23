package net.citizensnpcs.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.HologramTrait.HologramRenderer;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData.DataValue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

/** Personalizes native hologram metadata without changing the shared entity or another viewer's packet. */
public final class HologramMetadata {
    private static final Map<Entity, Map<ServerPlayer, List<DataValue<?>>>> sent = new WeakHashMap<>();

    private HologramMetadata() { }

    public static Packet<?> rewrite(Entity entity, ServerPlayer viewer, Packet<?> packet) {
        NPC npc = NPCRegistries.lookup(entity);
        if (npc == null || !(npc.data().get(NPC.Metadata.HOLOGRAM_RENDERER) instanceof HologramRenderer renderer))
            return packet;
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            boolean hasMetadata = false;
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                if (child instanceof ClientboundSetEntityDataPacket metadata && metadata.id() == entity.getId())
                    hasMetadata = true;
            }
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                packets.add(rewriteMetadata(entity, npc, renderer, viewer, child));
                // Vanilla omits metadata when every shared value is at its default. A viewer override may still
                // need flags/text, but only after the client has received this entity's spawn.
                if (!hasMetadata && child instanceof ClientboundAddEntityPacket spawn && spawn.getId() == entity.getId()) {
                    List<DataValue<?>> values = values(entity, npc, renderer, viewer, List.of());
                    packets.add(new ClientboundSetEntityDataPacket(entity.getId(), values));
                    remember(entity, viewer, values);
                }
            }
            return new ClientboundBundlePacket(packets);
        }
        if (packet instanceof ClientboundSetEntityDataPacket metadata)
            return rewriteMetadata(entity, npc, renderer, viewer, metadata);
        return packet;
    }

    private static Packet<? super ClientGamePacketListener> rewriteMetadata(Entity entity, NPC npc,
            HologramRenderer renderer, ServerPlayer viewer, Packet<? super ClientGamePacketListener> packet) {
        if (!(packet instanceof ClientboundSetEntityDataPacket metadata) || metadata.id() != entity.getId())
            return packet;
        List<DataValue<?>> values = values(entity, npc, renderer, viewer, metadata.packedItems());
        List<DataValue<?>> combined = new ArrayList<>(metadata.packedItems());
        combined.removeIf(value -> values.stream().anyMatch(replacement -> replacement.id() == value.id()));
        combined.addAll(values);
        remember(entity, viewer, values);
        return new ClientboundSetEntityDataPacket(metadata.id(), combined);
    }

    /** Refresh dynamic values even when the shared entity's unresolved text has not changed. */
    public static void refresh(HologramRenderer renderer) {
        for (Entity entity : renderer.getEntities()) {
            NPC npc = NPCRegistries.lookup(entity);
            if (npc == null || !(entity.level() instanceof ServerLevel level)) continue;
            PacketNPC packet = npc.getTraitNullable(PacketNPC.class);
            Collection<ServerPlayer> viewers = packet == null ? level.getChunkSource().chunkMap.getPlayersWatching(entity)
                    : packet.getPacketTracker() == null ? List.of() : packet.getPacketTracker().getLinked();
            Map<ServerPlayer, List<DataValue<?>>> previous = sent.get(entity);
            if (previous != null) previous.keySet().retainAll(viewers);
            for (ServerPlayer viewer : viewers) {
                if (packet != null ? !packet.isViewerEligible(viewer)
                        : viewer.level() != entity.level() || viewer.hasDisconnected()
                                || level.getServer().getPlayerList().getPlayer(viewer.getUUID()) != viewer
                                || !NPCVisibility.isVisible(npc, viewer)) continue;
                List<DataValue<?>> values = values(entity, npc, renderer, viewer, List.of());
                if (previous != null && values.equals(previous.get(viewer))) continue;
                viewer.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), values));
                remember(entity, viewer, values);
            }
        }
    }

    private static List<DataValue<?>> values(Entity entity, NPC npc, HologramRenderer renderer, ServerPlayer viewer,
            List<DataValue<?>> source) {
        byte flags = entity.getEntityData().get(Entity.DATA_SHARED_FLAGS_ID);
        for (DataValue<?> value : source) {
            if (value.id() == Entity.DATA_SHARED_FLAGS_ID.id()) {
                flags = (Byte) value.value();
                break;
            }
        }
        int sneakingMask = 1 << Entity.FLAG_SHIFT_KEY_DOWN;
        flags = (byte) (renderer.isSneaking(npc, viewer) ? flags | sneakingMask : flags & ~sneakingMask);
        List<DataValue<?>> values = new ArrayList<>();
        values.add(DataValue.create(Entity.DATA_SHARED_FLAGS_ID, flags));
        String text = renderer.getPerPlayerText(npc, viewer);
        if (text != null) {
            Component component = Messaging.minecraftComponentFromRawMessage(text);
            if (entity instanceof Display.TextDisplay) {
                values.add(DataValue.create(Display.TextDisplay.DATA_TEXT_ID, component));
            } else {
                boolean visible = entity.isCustomNameVisible() && !component.getString().isEmpty();
                values.add(DataValue.create(Entity.DATA_CUSTOM_NAME,
                        component.getString().isEmpty() ? Optional.empty() : Optional.of(component)));
                values.add(DataValue.create(Entity.DATA_CUSTOM_NAME_VISIBLE, visible));
            }
        }
        return List.copyOf(values);
    }

    private static void remember(Entity entity, ServerPlayer viewer, List<DataValue<?>> values) {
        sent.computeIfAbsent(entity, key -> new IdentityHashMap<>()).put(viewer, values);
    }

    public static void forget(Entity entity, ServerPlayer viewer) {
        Map<ServerPlayer, List<DataValue<?>>> previous = sent.get(entity);
        if (previous != null) {
            previous.remove(viewer);
            if (previous.isEmpty()) sent.remove(entity);
        }
    }

    public static void forget(Entity entity) { sent.remove(entity); }
}
