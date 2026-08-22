package net.citizensnpcs.trait.versioned;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.EntityUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;

/**
 * How angry a warden NPC is at particular entities.
 * <p>
 * Not persisted, matching upstream — anger is a live relationship with entities that may not exist next session.
 * <p>
 * Vanilla only offers "increase anger by", never "set anger to", so the target value is reached by increasing by the
 * difference. Bukkit's {@code Warden#setAnger} does the same thing behind its setter.
 */
@TraitName("wardentrait")
public class WardenTrait extends Trait {
    private final Map<UUID, Integer> anger = new HashMap<>();

    public WardenTrait() {
        super("wardentrait");
    }

    public void addAnger(Entity entity, int anger) {
        this.anger.put(entity.getUUID(), anger);
    }

    public void clearAnger(Entity entity) {
        anger.remove(entity.getUUID());
    }

    @Override
    public void run() {
        if (anger.isEmpty() || !(npc.getCosmeticEntity() instanceof Warden warden))
            return;
        for (Map.Entry<UUID, Integer> entry : anger.entrySet()) {
            Entity target = EntityUtil.getEntity(entry.getKey());
            if (target == null) {
                continue;
            }
            int current = warden.getAngerManagement().getActiveAnger(target);
            if (current != entry.getValue()) {
                warden.getAngerManagement().increaseAnger(target, entry.getValue() - current);
            }
        }
    }
}
