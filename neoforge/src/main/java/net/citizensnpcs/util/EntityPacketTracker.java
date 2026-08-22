package net.citizensnpcs.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.mojang.datafixers.util.Pair;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Shows one entity to a chosen set of players by packet, without that entity being tracked by the level.
 * <p>
 * This is what makes a {@code PacketNPC} possible: the entity is never added to the world, so vanilla's chunk map never
 * builds a tracker for it and never ticks it, and everything a viewer sees comes from here instead.
 * <p>
 * The work is done by vanilla's own {@link ServerEntity} — the same class the chunk map uses per tracked entity, which
 * already knows how to produce the spawn bundle, the movement deltas, the metadata diffs and the passenger/attribute
 * updates. It is simply given a broadcast target that fans out to the linked players rather than to the chunk map's
 * watchers. Upstream needs a {@code Synchronizer} implementation here because newer Minecraft versions replaced the
 * constructor's consumer with an interface; 1.21.1 still takes the plain {@code Consumer<Packet<?>>}, so no adapter is
 * needed.
 * <p>
 * Equipment is the one thing {@link ServerEntity} does not diff for a living entity, so it is compared here and sent as
 * a whole-slot-set packet when it changes, exactly as upstream does.
 */
public class EntityPacketTracker {
    private final Entity entity;
    private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    private final List<ServerPlayer> linked = new ArrayList<>();
    private final ServerEntity tracker;

    public EntityPacketTracker(Entity entity) {
        this.entity = entity;
        this.tracker = new ServerEntity((ServerLevel) entity.level(), entity, entity.getType().updateInterval(),
                entity.getType().trackDeltas(), this::broadcast);
    }

    /** @return the players currently being shown this entity */
    public Collection<ServerPlayer> getLinked() {
        return List.copyOf(linked);
    }

    /**
     * Starts showing the entity to a player, sending the spawn bundle immediately.
     * <p>
     * {@code unsetRemoved} is needed because an entity that was never added to a level — or that has been discarded once
     * already — is flagged removed, and {@link ServerEntity} refuses to build pairing data for a removed entity.
     */
    public void link(ServerPlayer player) {
        if (linked.contains(player))
            return;
        entity.unsetRemoved();
        linked.add(player);
        tracker.addPairing(player);
    }

    /** Sends whatever changed this tick: position, rotation, metadata, and equipment. */
    public void run() {
        if (linked.isEmpty())
            return;
        if (entity instanceof LivingEntity living) {
            boolean changed = false;
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack previous = equipment.getOrDefault(slot, ItemStack.EMPTY);
                ItemStack current = living.getItemBySlot(slot);
                if (!changed && living.equipmentHasChanged(previous, current)) {
                    changed = true;
                }
                // a copy, or the stored stack is the live one and can never differ from it again
                equipment.put(slot, current.copy());
            }
            if (changed) {
                List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    slots.add(Pair.of(slot, equipment.get(slot)));
                }
                broadcast(new ClientboundSetEquipmentPacket(entity.getId(), slots));
            }
        }
        tracker.sendChanges();
    }

    /** Stops showing the entity to a player, sending the remove packet. */
    public void unlink(ServerPlayer player) {
        if (linked.remove(player)) {
            tracker.removePairing(player);
        }
    }

    /**
     * Stops showing the entity to everyone and discards it.
     *
     * @param callback
     *            run for each player that was unlinked, before the entity is discarded
     */
    public void unlinkAll(Consumer<ServerPlayer> callback) {
        for (ServerPlayer player : new ArrayList<>(linked)) {
            unlink(player);
            if (callback != null) {
                callback.accept(player);
            }
        }
        entity.remove(Entity.RemovalReason.DISCARDED);
    }

    private void broadcast(Packet<?> packet) {
        for (ServerPlayer player : linked) {
            player.connection.send(packet);
        }
    }
}
