package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Fired before an NPC opens a fence gate on its path. Cancelling it leaves the gate shut.
 * <p>
 * Kept separate from {@link NPCOpenDoorEvent} as upstream has it: because NeoForge dispatches on the event class
 * hierarchy, making one extend the other would silently deliver gates to door listeners.
 */
public class NPCOpenGateEvent extends NPCEvent implements ICancellableEvent {
    private final ServerLevel level;
    private final BlockPos pos;

    public NPCOpenGateEvent(NPC npc, ServerLevel level, BlockPos pos) {
        super(npc);
        this.level = level;
        this.pos = pos;
    }

    public BlockPos getGate() {
        return pos;
    }

    public BlockState getGateState() {
        return level.getBlockState(pos);
    }

    public ServerLevel getLevel() {
        return level;
    }
}
