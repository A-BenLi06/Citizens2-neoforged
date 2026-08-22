package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Fired before an NPC opens a door on its path. Cancelling it leaves the door shut, which will normally leave the NPC
 * stuck against it — the door was already treated as passable when the path was worked out.
 * <p>
 * Upstream carries a single Bukkit {@code Block}, which bundles world and position together. Minecraft has no such type,
 * so the level and the position travel separately.
 */
public class NPCOpenDoorEvent extends NPCEvent implements ICancellableEvent {
    private final ServerLevel level;
    private final BlockPos pos;

    public NPCOpenDoorEvent(NPC npc, ServerLevel level, BlockPos pos) {
        super(npc);
        this.level = level;
        this.pos = pos;
    }

    /** @return the position of the door's lower half */
    public BlockPos getDoor() {
        return pos;
    }

    public BlockState getDoorState() {
        return level.getBlockState(pos);
    }

    public ServerLevel getLevel() {
        return level;
    }
}
