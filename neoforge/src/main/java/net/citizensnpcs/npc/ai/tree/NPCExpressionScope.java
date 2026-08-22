package net.citizensnpcs.npc.ai.tree;

import net.citizensnpcs.api.util.Location;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.EntityTarget;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.api.npc.NPC;

/**
 * Factory for creating expression scopes with NPC-related bindings.
 */
public class NPCExpressionScope {
    /**
     * Creates an expression scope with lazy bindings for NPC properties.
     *
     * @param npc
     *            the NPC to bind
     * @return a scope with NPC bindings
     */
    public static ExpressionScope createFor(NPC npc) {
        ExpressionScope scope = new ExpressionScope();

        scope.bind("npc.id", npc::getId);
        scope.bind("npc.name", npc::getFullName);
        scope.bind("npc.uuid", () -> npc.getUniqueId().toString());
        scope.bind("npc.spawned", npc::isSpawned);
        scope.bind("npc.protected", npc::isProtected);
        scope.bind("npc.flyable", npc::isFlyable);

        scope.bind("npc.x", () -> {
            Location loc = npc.getStoredLocation();
            return loc != null ? loc.getX() : 0;
        });
        scope.bind("npc.y", () -> {
            Location loc = npc.getStoredLocation();
            return loc != null ? loc.getY() : 0;
        });
        scope.bind("npc.z", () -> {
            Location loc = npc.getStoredLocation();
            return loc != null ? loc.getZ() : 0;
        });
        scope.bind("npc.yaw", () -> {
            Location loc = npc.getStoredLocation();
            return loc != null ? loc.getYaw() : 0;
        });
        scope.bind("npc.pitch", () -> {
            Location loc = npc.getStoredLocation();
            return loc != null ? loc.getPitch() : 0;
        });

        scope.bind("npc.health", () -> {
            Entity entity = npc.getEntity();
            if (entity instanceof LivingEntity) {
                return ((LivingEntity) entity).getHealth();
            }
            return 0;
        });
        scope.bind("npc.maxhealth", () -> {
            Entity entity = npc.getEntity();
            if (entity instanceof LivingEntity) {
                return ((LivingEntity) entity).getMaxHealth();
            }
            return 0;
        });
        scope.bind("npc.velocity.x", () -> {
            Entity entity = npc.getEntity();
            return entity != null ? entity.getDeltaMovement().x : 0;
        });
        scope.bind("npc.velocity.y", () -> {
            Entity entity = npc.getEntity();
            return entity != null ? entity.getDeltaMovement().y : 0;
        });
        scope.bind("npc.velocity.z", () -> {
            Entity entity = npc.getEntity();
            return entity != null ? entity.getDeltaMovement().z : 0;
        });

        // Target properties
        scope.bind("target.exists", () -> {
            EntityTarget target = npc.getNavigator().getEntityTarget();
            return target != null && target.getTarget() != null && !target.getTarget().isRemoved();
        });
        scope.bind("target.x", () -> {
            EntityTarget target = npc.getNavigator().getEntityTarget();
            if (target != null && target.getTarget() != null) {
                return target.getTarget().getX();
            }
            return 0;
        });
        scope.bind("target.y", () -> {
            EntityTarget target = npc.getNavigator().getEntityTarget();
            if (target != null && target.getTarget() != null) {
                return target.getTarget().getY();
            }
            return 0;
        });
        scope.bind("target.z", () -> {
            EntityTarget target = npc.getNavigator().getEntityTarget();
            if (target != null && target.getTarget() != null) {
                return target.getTarget().getZ();
            }
            return 0;
        });
        scope.bind("target.distance", () -> {
            EntityTarget target = npc.getNavigator().getEntityTarget();
            if (target != null && target.getTarget() != null) {
                Location npcLoc = npc.getStoredLocation();
                if (npcLoc != null) {
                    return Math.sqrt(npcLoc.distanceSquared(Location.of(target.getTarget())));
                }
            }
            return Double.MAX_VALUE;
        });
        scope.bind("target.health", () -> {
            EntityTarget target = npc.getNavigator().getEntityTarget();
            if (target != null && target.getTarget() instanceof LivingEntity) {
                return ((LivingEntity) target.getTarget()).getHealth();
            }
            return 0;
        });

        // Navigator state
        scope.bind("nav.navigating", npc.getNavigator()::isNavigating);
        scope.bind("nav.paused", npc.getNavigator()::isPaused);

        // Nearby entity count (expensive - use sparingly)
        scope.bind("nearby.count", () -> {
            if (!npc.isSpawned())
                return 0;
            Entity entity = npc.getEntity();
            return entity.level().getEntities(entity, entity.getBoundingBox().inflate(10, 10, 10)).size();
        });

        // Nearby player detection
        // upstream asks its LocationLookup cache for this; the level can answer it directly, and a behaviour tree only
        // reads the binding when an expression mentions it
        scope.bind("nearby.player", () -> nearestPlayerDistance(npc, 20) < Double.MAX_VALUE);

        // Distance to nearest player
        scope.bind("nearby.player.distance", () -> {
            double nearest = nearestPlayerDistance(npc, 20);
            return nearest < Double.MAX_VALUE ? nearest : 0;
        });

        return scope;
    }

    /**
     * @return the distance to the closest player within {@code radius}, or {@link Double#MAX_VALUE} if there is none
     */
    private static double nearestPlayerDistance(NPC npc, double radius) {
        if (!npc.isSpawned())
            return Double.MAX_VALUE;
        Entity entity = npc.getEntity();
        double nearest = Double.MAX_VALUE;
        for (Entity player : entity.level().getEntities(entity, entity.getBoundingBox().inflate(radius),
                e -> e instanceof ServerPlayer)) {
            nearest = Math.min(nearest, Math.sqrt(player.distanceToSqr(entity)));
        }
        return nearest;
    }
}
