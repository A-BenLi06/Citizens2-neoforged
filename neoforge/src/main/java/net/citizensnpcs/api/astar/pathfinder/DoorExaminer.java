package net.citizensnpcs.api.astar.pathfinder;

import java.util.List;

import net.citizensnpcs.api.astar.pathfinder.PathPoint.PathCallback;
import net.citizensnpcs.api.event.NPCOpenDoorEvent;
import net.citizensnpcs.api.event.NPCOpenGateEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.schedulers.SchedulerRunnable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * Lets a path go through a shut door or fence gate, opening it on the way in and closing it again behind the NPC.
 * <p>
 * Only registered when {@code npc.pathfinding.citizens.open-doors} is on. The door is declared passable at search time, so
 * cancelling {@link NPCOpenDoorEvent} will leave the NPC walking into a closed door — the route was already chosen.
 * <p>
 * Three things upstream has to do by hand fall out of vanilla here:
 * <ul>
 * <li>{@link DoorBlock#setOpen} sets the state, plays the right sound for that door and fires the block-open game event,
 * replacing upstream's manual sound table and its {@code SUPPORTS_SOUNDS} version probe.</li>
 * <li>Setting only the lower half is enough: vanilla's {@code updateShape} copies the open flag to the upper half, so
 * upstream's {@code getCorrectDoor} walk to find the right half is unnecessary.</li>
 * <li>A fence gate has no {@code setOpen}, but carries its own open/close sounds as fields, so the same is done inline.</li>
 * </ul>
 * Vanilla has a {@code MOB_INTERACTABLE_DOORS} tag (wooden and copper doors — not iron) that villagers use. It is
 * deliberately <em>not</em> applied: upstream's NPCs open any door including iron, this examiner only exists when an
 * administrator has explicitly asked for door opening, and quietly refusing iron doors would break paths that work
 * upstream.
 */
public class DoorExaminer implements BlockExaminer {
    @Override
    public float getCost(BlockSource source, PathPoint point) {
        return 0F;
    }

    @Override
    public PassableState isPassable(BlockSource source, PathPoint point) {
        BlockState state = source.getBlockAt(point.getVector());
        if (MinecraftBlockExaminer.isDoor(state) && isLowerHalf(state) || MinecraftBlockExaminer.isGate(state)) {
            point.addCallback(new DoorOpener());
            return PassableState.PASSABLE;
        }
        return PassableState.IGNORE;
    }

    /** A door's open flag lives on both halves; the lower one is the half a path ever stands in. */
    private static boolean isLowerHalf(BlockState state) {
        return !state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
    }

    private static class DoorOpener implements PathCallback {
        private boolean opened;

        @Override
        public void onReached(NPC npc, BlockPos point) {
            if (!npc.isSpawned())
                return;
            ServerLevel level = (ServerLevel) npc.getEntity().level();
            Location centre = new Location(level, point.getX() + 0.5, point.getY(), point.getZ() + 0.5);
            new SchedulerRunnable() {
                @Override
                public void run() {
                    if (!npc.getNavigator().isNavigating()) {
                        // navigation ended at or near the door, so shut it only if we are the ones who opened it
                        if (opened && npc.getStoredLocation().distance(centre) <= CLOSE_DISTANCE) {
                            close(npc, level, point);
                        }
                        cancel();
                        return;
                    }
                    if (npc.getStoredLocation().distance(centre) > CLOSE_DISTANCE) {
                        close(npc, level, point);
                        cancel();
                    }
                }
            }.runRegionTaskTimer(centre, 3, 1);
        }

        @Override
        public void run(NPC npc, BlockPos point, List<BlockPos> path, int index) {
            if (opened || !npc.isSpawned())
                return;
            ServerLevel level = (ServerLevel) npc.getEntity().level();
            BlockState state = level.getBlockState(point);
            if (!MinecraftBlockExaminer.isDoor(state) && !MinecraftBlockExaminer.isGate(state))
                return;
            Location centre = new Location(level, point.getX() + 0.5, point.getY(), point.getZ() + 0.5);
            if (npc.getStoredLocation().distance(centre) > OPEN_DISTANCE)
                return;
            open(npc, level, point, state);
            opened = true;
        }

        private void close(NPC npc, ServerLevel level, BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            if (!state.hasProperty(BlockStateProperties.OPEN) || !state.getValue(BlockStateProperties.OPEN))
                return;
            setOpen(npc, level, pos, state, false);
            swing(npc);
        }

        private void open(NPC npc, ServerLevel level, BlockPos pos, BlockState state) {
            if (state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN))
                return;
            if (isVetoed(npc, level, pos, MinecraftBlockExaminer.isDoor(state)))
                return;
            setOpen(npc, level, pos, state, true);
            swing(npc);
        }

        private boolean isVetoed(NPC npc, ServerLevel level, BlockPos pos, boolean door) {
            if (door) {
                NPCOpenDoorEvent event = new NPCOpenDoorEvent(npc, level, pos);
                event.callEvent();
                return event.isCanceled();
            }
            NPCOpenGateEvent event = new NPCOpenGateEvent(npc, level, pos);
            event.callEvent();
            return event.isCanceled();
        }

        private void setOpen(NPC npc, ServerLevel level, BlockPos pos, BlockState state, boolean open) {
            if (state.getBlock() instanceof DoorBlock door) {
                door.setOpen(npc.getEntity(), level, state, pos, open);
                return;
            }
            if (!(state.getBlock() instanceof FenceGateBlock gate))
                return;
            level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open),
                    Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
            level.playSound(null, pos, open ? gate.openSound : gate.closeSound, SoundSource.BLOCKS, 1.0F,
                    level.getRandom().nextFloat() * 0.1F + 0.9F);
            level.gameEvent(npc.getEntity(), open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
        }

        private void swing(NPC npc) {
            if (npc.getEntity() instanceof LivingEntity living) {
                living.swing(InteractionHand.MAIN_HAND);
            }
        }

        private static final double CLOSE_DISTANCE = 1.8;
        private static final double OPEN_DISTANCE = 2.5;
    }
}
