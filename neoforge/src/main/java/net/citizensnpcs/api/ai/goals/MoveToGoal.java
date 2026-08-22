package net.citizensnpcs.api.ai.goals;

import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.ai.tree.Behavior;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;

/**
 * A sample {@link Behavior} that moves an {@link NPC} to a location and reports whether it got there.
 * <p>
 * Upstream also names Paper's {@code Goal} interface in its javadoc, because on Paper the same class can be registered as
 * a vanilla mob goal. NeoForge has no such interface, so this is a behaviour-tree node only.
 */
public class MoveToGoal implements Behavior {
    private boolean finished;
    private final NPC npc;
    private CancelReason reason;
    private final Location target;

    public MoveToGoal(NPC npc, Location target) {
        this.npc = npc;
        this.target = target;
    }

    @Override
    public void reset() {
        npc.getNavigator().cancelNavigation();
        reason = null;
        finished = false;
    }

    @Override
    public BehaviorStatus run() {
        if (finished)
            return reason == null ? BehaviorStatus.SUCCESS : BehaviorStatus.FAILURE;
        return BehaviorStatus.RUNNING;
    }

    @Override
    public boolean shouldExecute() {
        boolean executing = !npc.getNavigator().isNavigating() && target != null;
        if (executing) {
            npc.getNavigator().setTarget(target);
            npc.getNavigator().getLocalParameters().addSingleUseCallback(cancelReason -> {
                finished = true;
                reason = cancelReason;
            });
        }
        return executing;
    }
}
