package net.citizensnpcs.npc;

import java.util.function.Consumer;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.minecraft.world.entity.Entity;

/**
 * Creates, spawns and removes the entity backing an NPC.
 * <p>
 * Upstream returns Bukkit's {@code Entity} from {@code getBukkitEntity()}; here the entity <i>is</i> the Minecraft one,
 * so the method is named {@link #getEntity()} and the CraftBukkit wrapper layer disappears entirely.
 */
public interface EntityController {
    /**
     * Builds the entity without adding it to the level. {@link #getEntity()} returns non-null afterwards.
     */
    void create(Location at, NPC npc);

    /**
     * Kills the entity, leaving the death animation to play.
     */
    void die();

    Entity getEntity();

    /**
     * Removes the entity from the level without a death animation.
     */
    void remove();

    /**
     * Adds the created entity to its level.
     *
     * @param callback
     *            receives true once the entity is in the level, false if the level rejected it — usually because the
     *            chunk is not loaded yet, in which case the caller retries later
     */
    void spawn(Location at, Consumer<Boolean> callback);
}
