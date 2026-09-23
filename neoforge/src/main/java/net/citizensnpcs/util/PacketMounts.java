package net.citizensnpcs.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.MapMaker;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.mixin.SetPassengersPacketAccessor;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Project NPC mount packets onto entities actually paired to each viewer, preserving native passenger order. */
public final class PacketMounts {
    private static final Map<ServerPlayer, View> views = new MapMaker().weakKeys().makeMap();

    private PacketMounts() { }

    private static final class View {
        final ResourceKey<Level> dimension;
        final IntSet entities = new IntOpenHashSet();

        View(ServerPlayer player) { dimension = player.level().dimension(); }
    }

    private static View view(ServerPlayer player) {
        View view = views.get(player);
        if (view == null || !view.dimension.equals(player.level().dimension())) {
            view = new View(player);
            views.put(player, view);
        }
        return view;
    }

    /** Called in native addPairing, in the same order as the bundle's spawn and mount packets. */
    public static Packet<?> pairing(Entity entity, ServerPlayer viewer, Packet<?> packet) {
        return rewrite(entity, viewer, packet, true);
    }

    /** A null result means the NPC vehicle itself is not yet known to this viewer. */
    public static Packet<?> rewrite(Entity entity, ServerPlayer viewer, Packet<?> packet) {
        return rewrite(entity, viewer, packet, false);
    }

    @SuppressWarnings("unchecked")
    private static Packet<?> rewrite(Entity entity, ServerPlayer viewer, Packet<?> packet, boolean pairing) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                Packet<?> rewritten = rewrite(entity, viewer, child, pairing);
                if (rewritten != null) packets.add((Packet<? super ClientGamePacketListener>) rewritten);
            }
            return new ClientboundBundlePacket(packets);
        }
        if (pairing && packet instanceof ClientboundAddEntityPacket add && add.getId() == entity.getId())
            view(viewer).entities.add(entity.getId());
        if (!(packet instanceof ClientboundSetPassengersPacket passengers)) return packet;
        Entity vehicle = entity.getId() == passengers.getVehicle() ? entity : entity.getVehicle();
        if (vehicle == null || vehicle.getId() != passengers.getVehicle()) return packet;
        if (NPCRegistries.lookup(vehicle) == null
                && vehicle.getPassengers().stream().noneMatch(e -> NPCRegistries.lookup(e) != null)) return packet;
        if (!known(vehicle, viewer) || !eligible(vehicle, viewer)) return null;
        int[] filtered = Arrays.stream(passengers.getPassengers()).filter(id -> vehicle.getPassengers().stream()
                .anyMatch(child -> child.getId() == id && known(child, viewer) && eligible(child, viewer))).toArray();
        if (Arrays.equals(filtered, passengers.getPassengers())) return packet;
        ClientboundSetPassengersPacket copy = new ClientboundSetPassengersPacket(vehicle);
        ((SetPassengersPacketAccessor) copy).citizens$setPassengers(filtered);
        return copy;
    }

    private static boolean known(Entity entity, ServerPlayer viewer) {
        return entity == viewer || view(viewer).entities.contains(entity.getId());
    }

    private static boolean eligible(Entity entity, ServerPlayer viewer) {
        if (entity.isRemoved() || entity.level() != viewer.level()) return false;
        NPC npc = NPCRegistries.lookup(entity);
        if (npc == null) return true;
        PacketNPC packet = npc.getTraitNullable(PacketNPC.class);
        return PacketNPC.isPacketEntity(entity) ? packet.isViewerEligible(viewer) : NPCVisibility.isVisible(npc, viewer);
    }

    public static void forget(Entity entity, ServerPlayer viewer) {
        View view = views.get(viewer);
        if (view != null) {
            view.entities.remove(entity.getId());
            if (view.entities.isEmpty()) views.remove(viewer);
        }
    }

    public static void forget(ServerPlayer viewer) { views.remove(viewer); }

    /** Position virtual passengers through native vehicle offsets, without invoking rideTick, AI or physics. */
    public static void position(Entity passenger) {
        position(passenger, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void position(Entity passenger, Set<Entity> visited) {
        if (!visited.add(passenger)) return;
        Entity vehicle = passenger.getVehicle();
        if (vehicle == null) return;
        if (vehicle.isRemoved() || vehicle.level() != passenger.level()) {
            passenger.stopRiding();
            return;
        }
        if (PacketNPC.isPacketEntity(vehicle)) position(vehicle, visited);
        vehicle.positionRider(passenger);
    }
}
