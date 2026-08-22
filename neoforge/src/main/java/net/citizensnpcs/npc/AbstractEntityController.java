package net.citizensnpcs.npc;

import java.util.function.Consumer;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public abstract class AbstractEntityController implements EntityController {
    private Entity entity;

    public AbstractEntityController() {
    }

    @Override
    public void create(Location at, NPC npc) {
        entity = createEntity(at, npc);
        // upstream also writes the "NPC"/"NPC-ID"/"NPC-NAME" Bukkit metadata keys here; NPCHolder replaces them
    }

    protected abstract Entity createEntity(Location at, NPC npc);

    @Override
    public void die() {
        if (entity == null)
            return;
        entity.remove(Entity.RemovalReason.KILLED);
        entity = null;
    }

    @Override
    public Entity getEntity() {
        return entity;
    }

    @Override
    public void remove() {
        if (entity == null)
            return;
        entity.remove(Entity.RemovalReason.DISCARDED);
        entity = null;
    }

    @Override
    public void spawn(Location at, Consumer<Boolean> callback) {
        ServerLevel level = at.getWorld();
        if (level == null || entity == null) {
            callback.accept(false);
            return;
        }
        discardStaleEntity(level, entity.getUUID());
        callback.accept(level.addFreshEntity(entity));
    }

    /**
     * Removes any entity already in the level under the same UUID before the NPC's own entity is added.
     * <p>
     * An NPC's entity carries the NPC's UUID, and vanilla refuses to add a second entity with a UUID it already knows —
     * it logs {@code UUID of added entity already exists} and rejects the <em>new</em> one. So a leftover copy in the
     * world save does not just linger, it takes the NPC's place and the NPC never spawns.
     * <p>
     * Copies get into the world save whenever the process dies without a clean shutdown, since the entity is a plain
     * vanilla instance that chunk saving treats like any other mob — there is no {@code shouldBeSaved()} to override, as
     * upstream does on its entity subclasses. Clearing the stale one here makes a crash recoverable instead of
     * permanently breaking the NPC.
     */
    private void discardStaleEntity(ServerLevel level, java.util.UUID uuid) {
        Entity stale = level.getEntity(uuid);
        if (stale != null && stale != entity) {
            Messaging.debug("Discarding stale world entity for NPC uuid", uuid);
            stale.remove(Entity.RemovalReason.DISCARDED);
        }
    }
}
