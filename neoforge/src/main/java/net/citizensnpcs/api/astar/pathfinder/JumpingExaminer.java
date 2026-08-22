package net.citizensnpcs.api.astar.pathfinder;

import java.util.List;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;

import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.AdditionalNeighbourGenerator;
import net.citizensnpcs.api.astar.pathfinder.PathPoint.PathCallback;
import net.citizensnpcs.api.npc.NPC;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Offers jumps as path moves, by simulating the actual projectile arc of a jump in each of the eight horizontal
 * directions and seeing where it lands.
 * <p>
 * The simulation reproduces vanilla's own movement constants — 0.42 initial upward velocity, 0.08 gravity, the 0.98 and
 * 0.91 drag factors — so a jump is only offered where the NPC would really get there. Every block the arc passes through
 * has to be clear over the NPC's full height, and the walk from one sampled block to the next is interpolated so the arc
 * cannot tunnel through a corner between two samples.
 * <p>
 * Upstream marks this experimental and leaves it off by default; it multiplies the branching factor of the search by
 * eight, which is why.
 */
public class JumpingExaminer implements AdditionalNeighbourGenerator {
    private final Supplier<Integer> entityHeight;
    private final Supplier<Float> speed;

    public JumpingExaminer(Supplier<Integer> height, Supplier<Float> speed) {
        this.entityHeight = height;
        this.speed = speed;
    }

    @Override
    public void addNeighbours(BlockSource source, PathPoint point, List<PathPoint> neighbours) {
        Vec3 base = point.getVector();
        int baseX = Mth.floor(base.x), baseY = Mth.floor(base.y), baseZ = Mth.floor(base.z);
        int minY = baseY - 3;

        for (int i = 0; i < 8; i++) {
            double vx = DX[i] * INV_LEN[i] * speed.get();
            double vy = JUMP_VELOCITY;
            double vz = DZ[i] * INV_LEN[i] * speed.get();

            double x = baseX + 0.5 + DX[i] * INV_LEN[i] * 0.3;
            double y = baseY;
            double z = baseZ + 0.5 + DZ[i] * INV_LEN[i] * 0.3;

            int px = baseX, py = baseY, pz = baseZ;
            loop: for (int tick = 0; tick < 60; tick++) {
                x += vx;
                y += vy;
                z += vz;

                vx *= XZ_DRAG;
                vy = (vy - GRAVITY) * 0.98;
                vz *= XZ_DRAG;

                int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
                if (bx == px && by == py && bz == pz)
                    continue;

                if (by < minY || !source.isYWithinBounds(by) || !isClearColumn(source, bx, by, bz))
                    break;

                // walk block-by-block from the last sample to this one, so a fast arc cannot skip a wall
                int cx = px, cy = py, cz = pz;
                while (cx != bx || cy != by || cz != bz) {
                    if (cx != bx) {
                        cx += Integer.compare(bx, cx);
                    }
                    if (cy != by) {
                        cy += Integer.compare(by, cy);
                    }
                    if (cz != bz) {
                        cz += Integer.compare(bz, cz);
                    }
                    if (cy < minY || !source.isYWithinBounds(cy) || !isClearColumn(source, cx, cy, cz))
                        continue loop;

                    if (source.isYWithinBounds(cy - 1)
                            && MinecraftBlockExaminer.canStandOn(source.getBlockAt(cx, cy - 1, cz))) {
                        Vec3 jumpPoint = new Vec3(cx + 0.5 + DX[i] * INV_LEN[i] * 0.3, cy,
                                cz + 0.5 + DZ[i] * INV_LEN[i] * 0.3);
                        point.addCallback(new JumpCallback(jumpPoint));
                        Vec3 landing = new Vec3(cx, cy, cz);
                        PathPoint next = point.createAtOffset(landing, 1.5f);
                        next.setPathVectors(ImmutableList.of(jumpPoint, landing));
                        neighbours.add(next);
                        continue loop;
                    }
                }
                px = bx;
                py = by;
                pz = bz;
            }
        }
    }

    @Override
    public float getCost(BlockSource source, PathPoint point) {
        return 0;
    }

    @Override
    public PassableState isPassable(BlockSource source, PathPoint point) {
        return PassableState.IGNORE;
    }

    private boolean isClearColumn(BlockSource source, int x, int y, int z) {
        int height = entityHeight.get();
        for (int h = 0; h < height; h++) {
            if (!MinecraftBlockExaminer.canStandIn(source.getBlockAt(x, y + h, z)))
                return false;
        }
        return true;
    }

    /** Applies the actual upward impulse once the NPC has walked onto the take-off spot. */
    private static class JumpCallback implements PathCallback {
        private final Vec3 jumpPoint;
        private boolean reached;

        private JumpCallback(Vec3 jumpPoint) {
            this.jumpPoint = jumpPoint;
        }

        @Override
        public void onReached(NPC npc, BlockPos point) {
            reached = true;
        }

        @Override
        public void run(NPC npc, BlockPos point, List<BlockPos> path, int index) {
            if (!reached || !npc.isSpawned())
                return;
            Entity entity = npc.getEntity();
            if (entity.position().distanceTo(jumpPoint) > 0.2)
                return;
            reached = false;
            entity.setDeltaMovement(entity.getDeltaMovement().x, JUMP_VELOCITY, entity.getDeltaMovement().z);
            entity.hasImpulse = true;
        }
    }

    private static final int[] DX = { 1, -1, 0, 0, 1, 1, -1, -1 };
    private static final int[] DZ = { 0, 0, 1, -1, 1, -1, 1, -1 };
    private static final double GRAVITY = 0.08;
    private static final double[] INV_LEN = { 1.0, 1.0, 1.0, 1.0, 0.7071067811865476, 0.7071067811865476,
            0.7071067811865476, 0.7071067811865476 };
    private static final double JUMP_VELOCITY = 0.42;
    private static final double XZ_DRAG = 0.91;
}
