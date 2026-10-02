package net.citizensnpcs.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.NPCVisibility;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Native entity rays and nearby ordering, including virtual NPCs and their redirect helpers. */
final class NPCSelectionQuery {
    private NPCSelectionQuery() { }

    static NPC nearby(CommandSourceStack source, Location at, NPCRegistry registry, double range) {
        Vec3 point = new Vec3(at.getX(), at.getY(), at.getZ());
        ServerPlayer player = source.getPlayer();
        Vec3 eye = player == null ? point : point.add(0, player.getEyeHeight(), 0);
        Vec3 end = eye.add(at.getDirection().scale(range));
        AABB nearby = new AABB(point, point).inflate(range);
        AABB ray = new AABB(eye, end).inflate(0.1);
        List<Entity> entities = new ArrayList<>();
        AABB bounds = nearby.minmax(ray);
        // Scan loaded entities, not a range-sized grid of empty sections. Large admin ranges stay bounded.
        for (Entity entity : at.getWorld().getAllEntities())
            if (entity != source.getEntity() && !entity.isRemoved() && bounds.intersects(entity.getBoundingBox())) entities.add(entity);
        Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(entities);
        for (NPCRegistry each : CitizensAPI.getNPCRegistries()) {
            if (each == null) continue;
            for (NPC npc : each.sorted()) {
                Entity entity = npc.getEntity();
                if (entity != null && entity != source.getEntity() && entity.level() == at.getWorld()
                        && PacketNPC.isPacketEntity(entity) && seen.add(entity)) entities.add(entity);
            }
        }
        if (player != null) {
            Entity nearestHit = null;
            double nearest = Double.POSITIVE_INFINITY;
            for (Entity entity : entities) {
                if (entity.isRemoved()) continue;
                // Hidden/unpaired virtual entities are not part of this viewer's ray.
                NPC view = NPCRegistries.lookup(entity);
                if (view != null && !visible(entity, view, source)) continue;
                AABB box = entity.getBoundingBox().inflate(0.1);
                var clipped = box.clip(eye, end);
                double distance = box.contains(eye) ? 0 : clipped.isPresent() ? eye.distanceToSqr(clipped.get()) : Double.POSITIVE_INFINITY;
                if (distance < nearest) { nearest = distance; nearestHit = entity; }
            }
            NPC hit = nearestHit == null ? null : target(nearestHit, source, registry);
            if (hit != null) return hit;
        }
        entities.sort(Comparator.comparingDouble((Entity entity) -> entity.position().distanceToSqr(point)).thenComparingInt(Entity::getId));
        for (Entity entity : entities) {
            if (!nearby.intersects(entity.getBoundingBox())) continue;
            NPC candidate = target(entity, source, registry);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private static NPC target(Entity entity, CommandSourceStack source, NPCRegistry registry) {
        NPC view = NPCRegistries.lookup(entity);
        if (view == null || !visible(entity, view, source)) return null;
        Set<NPC> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        NPC target = view;
        while (target != null && visited.add(target)) {
            ClickRedirectTrait redirect = target.getTraitNullable(ClickRedirectTrait.class);
            if (redirect == null) break;
            target = redirect.getRedirectToNPC();
        }
        if (target == null || target.hasTrait(ClickRedirectTrait.class) || !target.isSpawned()
                || target.getOwningRegistry() != registry || registry.getByUniqueId(target.getUniqueId()) != target
                || registry.getById(target.getId()) != target || view.getEntity() != entity || entity.isRemoved()) return null;
        return target;
    }

    private static boolean visible(Entity entity, NPC view, CommandSourceStack source) {
        if (entity.isRemoved() || !view.isSpawned() || view.getEntity() != entity) return false;
        ServerPlayer player = source.getPlayer();
        if (player != null && player.level() == entity.level()) {
            if (!NPCVisibility.isVisible(view, player)) return false;
            if (PacketNPC.isPacketEntity(entity) && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                    && !NPCVisibility.isTracked(entity, player)) return false;
        }
        return view.getEntity() == entity && !entity.isRemoved();
    }
}
