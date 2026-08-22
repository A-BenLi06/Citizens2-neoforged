package net.citizensnpcs.api.ai;

import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.TeleportCause;
import net.minecraft.core.BlockPos;

/**
 * The default answer to a stuck NPC: put it where it was trying to go.
 * <p>
 * Crude, but the alternative is an NPC that stands against a wall forever. The destination itself may be unstandable, so
 * the search walks upwards from the block below it looking for a floor, and falls back to the destination as given when
 * it finds none within ten blocks.
 */
public class TeleportStuckAction implements StuckAction {
    private TeleportStuckAction() {
    }

    @Override
    public boolean run(NPC npc, Navigator navigator) {
        if (!npc.isSpawned())
            return false;
        Location base = navigator.getTargetAsLocation();
        if (base == null || base.getWorld() != npc.getStoredLocation().getWorld())
            return true; // nothing sensible to teleport to, so let navigation carry on and fail on its own terms

        BlockPos below = base.getBlockPos().below();
        BlockPos pos = below;
        for (int iterations = 0; !MinecraftBlockExaminer
                .canStandOn(base.getWorld().getBlockState(pos)); iterations++) {
            if (iterations >= MAX_ITERATIONS) {
                pos = below;
                break;
            }
            pos = pos.above();
        }
        npc.teleport(Location.fromBlockPosCentred(base.getWorld(), pos.above()), TeleportCause.PLUGIN);
        return false;
    }

    @Override
    public String toString() {
        return "TeleportStuckAction";
    }

    public static final TeleportStuckAction INSTANCE = new TeleportStuckAction();
    private static final int MAX_ITERATIONS = 10;
}
