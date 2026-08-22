package net.citizensnpcs.npc.ai;

import net.citizensnpcs.api.ai.AbstractPathStrategy;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.TargetType;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Moves straight at the destination without pathfinding at all.
 * <p>
 * Used where a route would be pointless or actively wrong: a flying NPC crossing open air, and the close-range phase of
 * entity targeting once {@link NavigatorParameters#straightLineTargetingDistance()} is reached, where recomputing a path
 * every tick against a moving target costs more than it is worth.
 * <p>
 * Terrain is ignored, with one concession kept from upstream: a walking NPC whose next step would rise above its current
 * block scans downwards for something to stand on, so it hugs the ground over uneven terrain instead of trying to walk
 * through the air.
 */
public class StraightLineNavigationStrategy extends AbstractPathStrategy {
    private Location destination;
    private final NPC npc;
    private final NavigatorParameters params;
    private final Entity target;

    public StraightLineNavigationStrategy(NPC npc, Entity target, NavigatorParameters params) {
        super(TargetType.LOCATION);
        this.params = params;
        this.target = target;
        this.npc = npc;
        destination = params.entityTargetLocationMapper().apply(target);
    }

    public StraightLineNavigationStrategy(NPC npc, Location dest, NavigatorParameters params) {
        super(TargetType.LOCATION);
        this.params = params;
        this.target = null;
        this.npc = npc;
        destination = dest;
    }

    @Override
    public Location getCurrentDestination() {
        return destination;
    }

    @Override
    public Iterable<Vec3> getPath() {
        return null;
    }

    @Override
    public Location getTargetAsLocation() {
        return destination;
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean update() {
        if (getCancelReason() != null || !npc.isSpawned())
            return true;
        Entity entity = npc.getEntity();
        Location current = Location.of(entity);
        if (current.getWorld() != destination.getWorld())
            return true;
        if (params.withinMargin(current, destination)) {
            if (npc.isFlyable()) {
                entity.setDeltaMovement(Vec3.ZERO);
            }
            return true;
        }
        if (target != null) {
            destination = params.entityTargetLocationMapper().apply(target);
        }
        Vec3 step = nextStep(current, entity);
        double dx = step.x - current.getX();
        double dy = step.y - current.getY();
        double dz = step.z - current.getZ();
        if (npc.isFlyable()) {
            flyTowards(entity, current, dx, dy, dz);
        } else if (entity instanceof Mob mob && entity.getType() != EntityType.ARMOR_STAND) {
            mob.getMoveControl().setWantedPosition(step.x, step.y, step.z, params.speedModifier());
        } else {
            pushTowards(entity, current, step, dx, dy, dz);
        }
        return false;
    }

    /**
     * One block towards the destination, dropped onto the ground for a walking NPC so it follows the surface.
     */
    private Vec3 nextStep(Location current, Entity entity) {
        Vec3 from = new Vec3(current.getX(), current.getY(), current.getZ());
        Vec3 to = new Vec3(destination.getX(), destination.getY(), destination.getZ());
        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() < 1.0E-8)
            return to;
        Vec3 step = from.add(delta.normalize());
        if (npc.isFlyable() || Mth.floor(step.y) <= current.getBlockY())
            return step;

        BlockPos pos = BlockPos.containing(step.x, step.y, step.z);
        while (pos.getY() > current.getBlockY()
                && !MinecraftBlockExaminer.canStandOn(destination.getWorld().getBlockState(pos.below()))) {
            pos = pos.below();
            if (pos.getY() <= destination.getWorld().getMinBuildHeight()) {
                pos = BlockPos.containing(step.x, step.y, step.z);
                break;
            }
        }
        return new Vec3(step.x, pos.getY(), step.z);
    }

    /**
     * Eases the entity velocity towards the target rather than snapping it, which is what keeps flight from looking
     * jerky, and turns the body to follow. An ender dragon is left alone: its rotation is driven by its own segmented
     * body animation and forcing the yaw makes it spin.
     */
    private void flyTowards(Entity entity, Location current, double dx, double dy, double dz) {
        Vec3 velocity = entity.getDeltaMovement();
        double motX = velocity.x + (Math.signum(dx) * 0.5D - velocity.x) * 0.1;
        double motY = velocity.y + (Math.signum(dy) - velocity.y) * 0.1;
        double motZ = velocity.z + (Math.signum(dz) * 0.5D - velocity.z) * 0.1;
        entity.setDeltaMovement(new Vec3(motX, motY, motZ).scale(params.speed()));

        if (entity.getType() == EntityType.ENDER_DRAGON || !(entity instanceof LivingEntity living))
            return;
        float targetYaw = (float) (Math.atan2(motZ, motX) * 180.0D / Math.PI) - 90.0F;
        float normalised = Mth.wrapDegrees(targetYaw - current.getYaw());
        living.yya = 0.5F;
        float yaw = current.getYaw() + normalised;
        living.setYRot(yaw);
        living.setYHeadRot(yaw);
        living.yBodyRot = yaw;
    }

    /** For anything without a move control: shove it along, with the upward nudge that lets it clear a step. */
    private void pushTowards(Entity entity, Location current, Vec3 step, double dx, double dy, double dz) {
        Vec3 direction = new Vec3(dx, dy, dz);
        if (direction.lengthSqr() < 1.0E-8)
            return;
        Vec3 velocity = direction.normalize().scale(0.2 * params.speedModifier());
        boolean inLiquid = MinecraftBlockExaminer
                .isLiquidOrWaterlogged(current.getWorld().getBlockState(entity.blockPosition()));
        double xzDistance = Math.sqrt(dx * dx + dz * dz);
        if (dy >= 1 && xzDistance <= 2.75 || dy >= 0.2 && inLiquid) {
            velocity = velocity.add(0, 0.75, 0);
        }
        entity.setDeltaMovement(velocity);
        entity.hasImpulse = true;
        npc.faceLocation(new Location(current.getWorld(), step.x, step.y, step.z));
    }
}
