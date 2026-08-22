package net.citizensnpcs.api.ai;

import net.minecraft.world.entity.Entity;

public interface EntityTarget {
    /**
     * @return The {@link Entity} being targeted.
     */
    Entity getTarget();

    /**
     * @return Whether the entity target should be attacked once within range
     */
    boolean isAggressive();
}
