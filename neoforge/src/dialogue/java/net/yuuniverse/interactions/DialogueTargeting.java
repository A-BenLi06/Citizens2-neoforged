package net.yuuniverse.interactions;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.NPCVisibility;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Native view selection, including Citizens entities absent from the level's entity lookup. */
final class DialogueTargeting {
    // The original swap-hand trigger searches up to four blocks, independently of proximity-entry settings.
    private static final double RANGE = 4;

    private DialogueTargeting() { }

    static boolean isCurrentPlayer(ServerPlayer player) {
        return player.getServer() != null && player.getServer().isSameThread() && !player.isRemoved()
                && player.isAlive() && !player.isSpectator() && player.connection != null
                && !player.hasDisconnected() && player.connection.getConnection().isConnected()
                && player.connection.getConnection().getPacketListener() == player.connection
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && NPCRegistries.lookup(player) == null;
    }

    static Target pick(ServerPlayer player) {
        if (!isCurrentPlayer(player)) return null;
        Vec3 eye = player.getEyePosition();
        // The move packet updates yaw immediately; LivingEntity's head yaw only catches up on tick.
        Vec3 direction = direction(player);
        Vec3 limit = eye.add(direction.scale(RANGE));
        HitResult block = clipBlocks(player, eye, limit);
        Vec3 end = block.getType() == HitResult.Type.MISS ? limit : block.getLocation();
        double blockDistance = eye.distanceToSqr(end);
        AABB search = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1);
        Map<Entity, Geometry> scene = scene(player, search);
        var worldHit = ProjectileUtil.getEntityHitResult(player, eye, end, search,
                entity -> eligible(entity, player), blockDistance);
        Entity selected = worldHit == null ? null : worldHit.getEntity();
        double closest = worldHit == null ? blockDistance : eye.distanceToSqr(worldHit.getLocation());

        // Include passengers as well as roots. Snapshot before filters, which may invoke external callbacks.
        for (Entity entity : scene.keySet()) {
            if (PacketNPC.getInteractionTarget(entity.getId(), player) != entity
                    || !search.intersects(entity.getBoundingBox()) || !eligible(entity, player)) continue;
            AABB bounds = entity.getBoundingBox().inflate(entity.getPickRadius());
            boolean inside = bounds.contains(eye);
            Vec3 hit = inside ? eye : bounds.clip(eye, end).orElse(null);
            if (hit == null || !inside && entity.getRootVehicle() == player.getRootVehicle()
                    && !entity.canRiderInteract()) continue;
            double distance = eye.distanceToSqr(hit);
            if (distance < closest) {
                selected = entity;
                closest = distance;
            }
        }
        if (selected == null || closest >= blockDistance || !eligible(selected, player)
                || !player.level().getWorldBorder().isWithinBounds(selected.blockPosition())) return null;
        NPC hitNpc = NPCRegistries.lookup(selected);
        if (hitNpc == null) return null;
        NPC npc = hitNpc;

        // Hologram hit targets use the same parent relationship as ordinary Citizens clicks.
        Set<NPC> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (true) {
            if (!visited.add(npc) || !current(npc, npc.getEntity(), player)) return null;
            ClickRedirectTrait redirect = npc.getTraitNullable(ClickRedirectTrait.class);
            if (redirect == null || redirect.getRedirectToNPC() == null) break;
            npc = redirect.getRedirectToNPC();
        }
        Entity targetEntity = npc.getEntity();
        return eligible(selected, player) && current(npc, targetEntity, player)
                && owned(hitNpc, selected, player) && owned(npc, targetEntity, player)
                && player.level().getWorldBorder().isWithinBounds(targetEntity.blockPosition())
                && isCurrentPlayer(player)
                && eye.equals(player.getEyePosition()) && direction.equals(direction(player))
                // No visibility callback runs after this spatial check. An earlier ray must not survive
                // a filter moving another entity, replacing a redirect or adding an occluding block.
                && scene.equals(scene(player, search)) && sameBlockHit(block, clipBlocks(player, eye, limit))
                ? new Target(selected, npc, targetEntity) : null;
    }

    private static Map<Entity, Geometry> scene(ServerPlayer player, AABB search) {
        Map<Entity, Geometry> entities = new LinkedHashMap<>();
        for (Entity entity : player.level().getEntities(player, search)) entities.put(entity, geometry(entity));
        for (NPCRegistry registry : CitizensAPI.getNPCRegistries()) {
            for (NPC npc : registry) {
                Entity entity = npc.getEntity();
                if (entity != null && entity != player && entity.level() == player.level()
                        && search.intersects(entity.getBoundingBox()) && PacketNPC.isPacketEntity(entity))
                    entities.put(entity, geometry(entity));
            }
        }
        return entities;
    }

    private static Geometry geometry(Entity entity) {
        NPC owner = NPCRegistries.lookup(entity);
        ClickRedirectTrait redirect = owner == null ? null : owner.getTraitNullable(ClickRedirectTrait.class);
        return new Geometry(entity.getBoundingBox(), entity.getPickRadius(), entity.getRootVehicle(),
                entity.canRiderInteract(), entity.isAlive(), entity.isPickable(), entity.isSpectator(), owner,
                redirect == null ? null : redirect.getRedirectToNPC());
    }

    private static HitResult clipBlocks(ServerPlayer player, Vec3 eye, Vec3 limit) {
        return player.level().clip(new ClipContext(eye, limit, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    private static boolean sameBlockHit(HitResult before, HitResult after) {
        return before.getType() == after.getType() && before.getLocation().equals(after.getLocation());
    }

    private record Geometry(AABB bounds, float pickRadius, Entity vehicle, boolean riderInteract,
            boolean alive, boolean pickable, boolean spectator, NPC owner, NPC redirect) { }

    private static Vec3 direction(ServerPlayer player) {
        return player.calculateViewVector(player.getXRot(), player.getYRot());
    }

    private static boolean eligible(Entity entity, ServerPlayer player) {
        if (entity == player || !entity.isAlive() || entity.isRemoved() || entity.level() != player.level()
                || entity.isSpectator() || !entity.isPickable()) return false;
        NPC npc = NPCRegistries.lookup(entity);
        return npc == null || current(npc, entity, player);
    }

    private static boolean current(NPC npc, Entity entity, ServerPlayer player) {
        if (!owned(npc, entity, player) || !NPCVisibility.isTracked(entity, player)) return false;
        // A visibility predicate may have replaced or removed the owner while being evaluated.
        return owned(npc, entity, player);
    }

    private static boolean owned(NPC npc, Entity entity, ServerPlayer player) {
        return entity != null && npc.getEntity() == entity && npc.isSpawned() && entity.isAlive() && !entity.isRemoved()
                && entity.level() == player.level() && npc.getOwningRegistry() != null
                && npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc
                && NPCRegistries.lookup(entity) == npc;
    }

    record Target(Entity hit, NPC npc, Entity entity) {
        boolean matches(Target other) {
            return other != null && hit == other.hit && npc == other.npc && entity == other.entity;
        }
    }
}
