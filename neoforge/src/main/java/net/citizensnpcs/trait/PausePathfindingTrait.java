package net.citizensnpcs.trait;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitEventHandler;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.schedulers.SchedulerTask;

/**
 * Stops an NPC walking for a while — when a player comes close, or when one right-clicks it.
 * <p>
 * The lockout is a cooldown on re-pausing, so a player standing next to a waypointing NPC does not freeze it forever.
 */
@TraitName("pausepathfinding")
public class PausePathfindingTrait extends Trait {
    @Persist("lockoutduration")
    private int lockoutDuration = -1;
    @Persist("pauseticks")
    private int pauseTicks;
    @Persist("playerrange")
    private double playerRange = -1;
    @Persist("rightclick")
    private boolean rightclick;
    private int t;
    private SchedulerTask unpauseTaskId = null;

    public PausePathfindingTrait() {
        super("pausepathfinding");
    }

    public int getLockoutDuration() {
        return lockoutDuration;
    }

    public int getPauseDuration() {
        return pauseTicks;
    }

    public double getPlayerRangeInBlocks() {
        return playerRange;
    }

    @Override
    public void onDespawn() {
        if (unpauseTaskId != null) {
            unpauseTaskId.cancel();
            unpauseTaskId = null;
        }
    }

    @TraitEventHandler
    public void onInteract(NPCRightClickEvent event) {
        if (lockoutDuration > t || !rightclick)
            return;
        pause();
        event.setDelayedCancellation(true);
    }

    public boolean pauseOnRightClick() {
        return rightclick;
    }

    private void pause() {
        if (unpauseTaskId != null) {
            unpauseTaskId.cancel();
        }
        npc.getNavigator().cancelNavigation();
        npc.getNavigator().setPaused(true);
        unpauseTaskId = CitizensAPI.getScheduler().runTaskLater(() -> {
            unpauseTaskId = null;
            if (!npc.isSpawned())
                return;
            // upstream levels the head out on resuming, so the NPC does not walk off still looking at the ground
            npc.getEntity().setXRot(0);
            npc.getNavigator().setPaused(false);
        }, pauseTicks <= 0 ? 20 : pauseTicks);
        t = 0;
    }

    @Override
    public void run() {
        if (lockoutDuration > t++ || playerRange == -1 || !npc.isSpawned()
                || unpauseTaskId == null && !npc.getNavigator().isNavigating())
            return;
        if (!EntityUtil.getNearbyVisiblePlayers(npc.getEntity(), playerRange).isEmpty()) {
            pause();
        }
    }

    public void setLockoutDuration(int lockoutDuration) {
        this.lockoutDuration = lockoutDuration;
    }

    public void setPauseDuration(int pauseTicks) {
        this.pauseTicks = pauseTicks;
    }

    public void setPauseOnRightClick(boolean rightclick) {
        this.rightclick = rightclick;
    }

    public void setPlayerRangeInBlocks(double playerRange) {
        this.playerRange = playerRange;
    }
}
