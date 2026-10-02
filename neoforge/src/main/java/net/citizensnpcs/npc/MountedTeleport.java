package net.citizensnpcs.npc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.trait.MountTrait;
import net.citizensnpcs.util.NPCMounts;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Owns a complete native passenger tree while its members change entity identity and are remounted. */
final class MountedTeleport {
    private final List<Node> nodes = new ArrayList<>();
    private final Location destination;
    private boolean done;
    private boolean rollback;
    private final CitizensNPC selected;
    private final Consumer<Entity> callback;

    private MountedTeleport(Location destination, CitizensNPC selected, Consumer<Entity> callback) {
        this.destination = destination.clone(); this.selected = selected; this.callback = callback;
    }

    static void start(CitizensNPC selected, Location destination, long request, Consumer<Entity> callback) {
        MountedTeleport transfer = new MountedTeleport(destination, selected, callback);
        Entity root = selected.getEntity();
        Set<Entity> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (root.getVehicle() != null) {
            if (!visited.add(root)) return;
            root = root.getVehicle();
        }
        visited.clear();
        if (!transfer.collect(root, null, visited)) return;
        for (Node node : transfer.nodes) {
            if (node.npc instanceof CitizensNPC citizen)
                node.state = citizen.claimTreeTeleport(citizen == selected ? request : -1);
            if (node.mount != null) node.pause = node.mount.pauseForTransfer();
        }
        try {
            // Detach leaves first. If any mod rejects detachment, no member has moved yet.
            for (int i = transfer.nodes.size() - 1; i > 0; i--) {
                Node node = transfer.nodes.get(i);
                if (!transfer.isCurrent() || !transfer.topologyMatches()
                        || !NPCMounts.dismount(node.entity, transfer::isCurrent)) {
                    transfer.rollback = true;
                    break;
                }
                node.detached = true;
                node.arrived = null;
            }
        } catch (Throwable failure) {
            transfer.rollback = true;
            try { transfer.advance(); } catch (Throwable recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
        transfer.advance();
    }

    private boolean collect(Entity entity, Node parent, Set<Entity> visited) {
        if (entity.isRemoved() || !visited.add(entity) || parent != null && entity.level() != parent.entity.level()) return false;
        NPC npc = NPCRegistries.lookup(entity);
        if (npc != null && (!(npc instanceof CitizensNPC) || !npc.isSpawned() || npc.getEntity() != entity)) return false;
        Node node = new Node(entity, npc, parent);
        nodes.add(node);
        for (Entity child : entity.getPassengers()) {
            if (child.getVehicle() != entity || !collect(child, node, visited)) return false;
        }
        return true;
    }

    private boolean isCurrent() { return !done && nodes.stream().allMatch(Node::isCurrent); }

    private boolean topologyMatches() {
        for (Node node : nodes) {
            Entity vehicle = node.parent == null || node.detached ? null : node.parent.entity;
            if (node.entity.getVehicle() != vehicle) return false;
            List<Entity> children = nodes.stream().filter(child -> child.parent == node && !child.detached)
                    .map(child -> child.entity).toList();
            if (!node.entity.getPassengers().equals(children)) return false;
        }
        return true;
    }

    private void advance() {
        if (!isCurrent()) { finish(); return; }
        try {
            if (!topologyMatches()) { finish(); return; }
            boolean pending = false;
            for (Node node : nodes) {
                Location target = rollback ? node.original : destination;
                if (node.arrivedAt(target)) continue;
                if (!node.detached && node.parent != null || node.entity.isVehicle()) {
                    rollback = true;
                    pending = true;
                    break;
                }
                if (!node.move(target)) {
                    if (!rollback) {
                        rollback = true;
                        advance();
                        return;
                    }
                    pending = true;
                }
                if (!isCurrent()) { finish(); return; }
            }
            if (!pending) {
                for (Node node : nodes) {
                    if (node.parent == null || !node.detached) continue;
                    if (node.entity.getVehicle() != null) { finish(); return; }
                    NPCMounts.mount(node.entity, node.parent.entity, this::isCurrent);
                    if (!isCurrent()) { finish(); return; }
                    node.detached = node.entity.getVehicle() != node.parent.entity;
                    pending |= node.detached;
                    // Native passenger order is meaningful; do not append a later sibling before a denied one.
                    if (pending) break;
                }
            }
            if (pending) CitizensAPI.getScheduler().runTaskLater(this::advance, 1);
            else finish(!rollback);
        } catch (Throwable failure) {
            if (!rollback) {
                rollback = true;
                try { advance(); } catch (Throwable recovery) { failure.addSuppressed(recovery); }
            } else finish();
            throw failure;
        }
    }

    private void finish() {
        finish(false);
    }

    private void finish(boolean success) {
        if (done) return;
        boolean notify = success && isCurrent() && topologyMatches();
        done = true;
        restoreOwnedEdges();
        if (notify && callback != null && topologyMatches() && nodes.stream().allMatch(Node::ownsEntity))
            callback.accept(selected.getEntity());
    }

    private void restoreOwnedEdges() {
        boolean pending = false;
        try {
            for (Node node : nodes) {
                if (!node.detached || node.parent == null || !node.isCurrent() || !node.parent.isCurrent()
                        || node.entity.level() != node.parent.entity.level() || node.entity.getVehicle() != null) continue;
                // A superseding callback can invalidate only one branch after another was already detached.
                // Repair untouched edges in place without moving a newer owner or replacing its vehicle.
                NPCMounts.mount(node.entity, node.parent.entity, () -> node.isCurrent() && node.parent.isCurrent());
                node.detached = node.entity.getVehicle() != node.parent.entity;
                if (node.detached && node.isCurrent() && node.parent.isCurrent()) { pending = true; break; }
            }
            if (pending) CitizensAPI.getScheduler().runTaskLater(this::restoreOwnedEdges, 1);
        } finally {
            if (!pending) for (Node node : nodes) if (node.pause != null) node.pause.close();
        }
    }

    private final class Node {
        Entity entity;
        final NPC npc;
        final Node parent;
        final Location original;
        final MountTrait mount;
        MountTrait.TransferPause pause;
        CitizensNPC.TeleportState state;
        boolean detached;
        Location arrived;

        Node(Entity entity, NPC npc, Node parent) {
            this.entity = entity;
            this.npc = npc;
            this.parent = parent;
            this.original = Location.of(entity);
            this.arrived = original.clone();
            this.mount = npc == null ? null : npc.getTraitNullable(MountTrait.class);
        }

        boolean isCurrent() {
            return ownsEntity() && (pause == null || pause.isCurrent());
        }

        boolean ownsEntity() {
            return !entity.isRemoved() && (state == null ? NPCRegistries.lookup(entity) == null : state.isCurrent())
                    && (npc == null || npc.getTraitNullable(MountTrait.class) == mount);
        }

        boolean arrivedAt(Location target) {
            return arrived != null && entity.level() == target.getWorld() && arrived.equals(target);
        }

        boolean move(Location target) {
            Entity moved;
            if (state != null) {
                moved = state.move(target);
            } else if (entity instanceof ServerPlayer) {
                moved = entity.teleportTo(target.getWorld(), target.getX(), target.getY(), target.getZ(), Set.of(),
                        target.getYaw(), target.getPitch()) ? entity : null;
            } else if (entity.level() == target.getWorld()) {
                moved = EntityUtil.teleport(entity, target);
            } else {
                // Retain a direct reference to the native copy. Destination chunks need not expose it through
                // level.getEntity yet, and a rejected join must not destroy the source entity.
                moved = entity.getType().create(target.getWorld());
                if (moved == null) return false;
                moved.restoreFrom(entity);
                moved.moveTo(target.getX(), target.getY(), target.getZ(), target.getYaw(),
                        net.minecraft.util.Mth.clamp(target.getPitch(), -90, 90));
                moved.setYHeadRot(target.getYaw());
                if (!target.getWorld().addFreshEntity(moved) || moved.isRemoved() || !MountedTeleport.this.isCurrent()) {
                    moved.discard();
                    return false;
                }
                entity.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
            }
            if (moved == null || moved.isRemoved() || moved.level() != target.getWorld()) return false;
            entity = moved;
            arrived = target.clone();
            return true;
        }
    }
}
