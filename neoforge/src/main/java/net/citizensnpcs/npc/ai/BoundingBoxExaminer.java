package net.citizensnpcs.npc.ai;

import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.PathPoint;
import net.citizensnpcs.api.util.BoundingBox;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Rejects a gap the NPC is too tall to fit through, by measuring the real collision shapes above and below rather than
 * counting whole blocks.
 * <p>
 * The block-granular rules cannot see this: a cell with a slab overhead reads as "occupied above", but whether an NPC fits
 * depends on how thick that slab is and how tall the NPC is. Off by default
 * ({@code npc.pathfinding.citizens.check-bounding-boxes}) because it costs a collision-shape lookup per candidate cell.
 * <p>
 * Upstream also reads the entity's width and never uses it; the field is dropped here rather than kept as dead weight.
 */
public class BoundingBoxExaminer implements BlockExaminer {
    private final double height;

    public BoundingBoxExaminer(Entity entity) {
        height = entity == null ? 0 : entity.getBbHeight();
    }

    @Override
    public float getCost(BlockSource source, PathPoint point) {
        return 0;
    }

    @Override
    public PassableState isPassable(BlockSource source, PathPoint point) {
        Vec3 pos = point.getVector();
        int x = Mth.floor(pos.x), y = Mth.floor(pos.y), z = Mth.floor(pos.z);
        if (MinecraftBlockExaminer.canStandIn(source.getBlockAt(x, y + 1, z))
                || !MinecraftBlockExaminer.canStandOn(source.getBlockAt(x, y - 1, z)))
            return PassableState.IGNORE;

        BoundingBox above = source.getCollisionBox(x, y + 1, z);
        BoundingBox below = source.getCollisionBox(x, y - 1, z);
        if (above == null || below == null)
            return PassableState.IGNORE;

        return above.minY - below.maxY < height ? PassableState.IMPASSABLE : PassableState.IGNORE;
    }
}
