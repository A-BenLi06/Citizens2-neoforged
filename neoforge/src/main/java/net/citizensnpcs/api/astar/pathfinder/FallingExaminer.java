package net.citizensnpcs.api.astar.pathfinder;

import java.util.List;

import com.google.common.collect.ImmutableList;

import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.AdditionalNeighbourGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Offers "step off the edge and drop" as a path move, up to a configured fall distance.
 * <p>
 * The normal neighbour expansion only reaches one block down, so without this an NPC will walk the long way round a ledge
 * it could simply hop off. Each drop is offered as a single neighbour carrying two path vectors — the lip of the ledge and
 * the landing spot — so the NPC walks to the edge before falling rather than angling into the wall below.
 * <p>
 * Cost rises with the drop ({@code (dy + 1) * 2.5}), which keeps a long fall from looking like a shortcut.
 */
public class FallingExaminer implements AdditionalNeighbourGenerator {
    private final int maxFallDistance;

    public FallingExaminer(int maxFallDistance) {
        this.maxFallDistance = maxFallDistance;
    }

    @Override
    public void addNeighbours(BlockSource source, PathPoint point, List<PathPoint> neighbours) {
        Vec3 base = point.getVector();
        int baseX = Mth.floor(base.x), baseY = Mth.floor(base.y), baseZ = Mth.floor(base.z);
        if (!source.isYWithinBounds(baseY - 1))
            return;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = baseX + dx;
                int z = baseZ + dz;

                // only interesting where the NPC could step in but has nothing to stand on — i.e. over a drop
                if (!MinecraftBlockExaminer.canStandIn(source.getBlockAt(x, baseY, z))
                        || MinecraftBlockExaminer.canStandOn(source.getBlockAt(x, baseY - 1, z)))
                    continue;

                for (int dy = 2; dy <= maxFallDistance; dy++) {
                    if (!source.isYWithinBounds(baseY - dy))
                        break;

                    if (MinecraftBlockExaminer.canStandIn(source.getBlockAt(x, baseY - dy + 1, z))
                            && MinecraftBlockExaminer.canStandOn(source.getBlockAt(x, baseY - dy, z))) {
                        Vec3 landing = new Vec3(x + 0.5, baseY - dy, z + 0.5);
                        PathPoint next = point.createAtOffset(landing, (dy + 1) * 2.5f);
                        next.setPathVectors(ImmutableList.of(new Vec3(x + 0.5, baseY, z + 0.5), landing));
                        neighbours.add(next);
                        break;
                    }
                }
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
}
