package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.camel.Camel;

/**
 * A camel NPC pose.
 * <p>
 * Vanilla stores the pose as the sign of a last-pose-change tick rather than as a value, but it exposes the three
 * transitions Citizens needs, so the trait keeps upstream's enum and save key verbatim. The two guards upstream uses
 * ({@code isStanding} / {@code isPanicking}) do not exist in 1.21.1 and are expressed through the tick instead:
 * standing is "not sitting", and a finished panic stand is "not sitting and no longer animating".
 */
@TraitName("cameltrait")
public class CamelTrait extends Trait {
    @Persist
    private CamelPose pose;

    public CamelTrait() {
        super("cameltrait");
    }

    public CamelPose getPose() {
        return pose;
    }

    @Override
    public void run() {
        if (pose == null || !(npc.getCosmeticEntity() instanceof Camel camel))
            return;
        switch (pose) {
            case STANDING:
                // self-guarded, so the stand-up sound plays once rather than every tick
                camel.standUp();
                return;
            case SITTING:
                camel.sitDown();
                return;
            case PANIC:
                // unlike the other two this one is unguarded, and re-firing it would emit a game event every tick
                if (camel.isCamelSitting() || camel.isInPoseTransition()) {
                    camel.standUpInstantly();
                }
                return;
        }
    }

    public void setPose(CamelPose pose) {
        this.pose = pose;
    }

    public enum CamelPose {
        PANIC,
        SITTING,
        STANDING
    }
}
