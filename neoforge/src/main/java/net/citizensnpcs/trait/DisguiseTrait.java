package net.citizensnpcs.trait;

import java.util.Locale;
import java.util.UUID;

import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.npc.EntityController;
import net.citizensnpcs.npc.EntityControllers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Makes an NPC look like a different kind of entity.
 * <p>
 * A second entity of the disguise type is spawned at the NPC's position and becomes its <em>cosmetic</em> entity: that is
 * what players see and click, while the real entity keeps doing the moving. {@code CitizensNPC.getCosmeticEntity()}
 * consults this trait, which is what makes every appearance trait apply to the disguise rather than to the entity
 * underneath.
 * <p>
 * The type is stored as a registry id rather than a Bukkit enum name, the same treatment {@link MobType} needed, and
 * {@code MobType.match} is reused so an existing save written with {@code "ZOMBIE"} still reads.
 */
@TraitName("disguise")
public class DisguiseTrait extends Trait {
    private EntityController synthetic;
    private EntityType<?> type;

    public DisguiseTrait() {
        super("disguise");
    }

    public void disguiseAsType(EntityType<?> type) {
        this.type = type;
        if (npc.isSpawned()) {
            npc.despawn(DespawnReason.PENDING_RESPAWN);
            npc.spawn(npc.getStoredLocation());
        }
    }

    public Entity getCosmeticEntity() {
        return type == null || synthetic == null ? null : synthetic.getEntity();
    }

    public EntityType<?> getDisguiseType() {
        return type;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        String raw = key.getString("type");
        type = raw.isEmpty() ? null : MobType.match(raw);
    }

    @Override
    public void save(DataKey key) {
        key.setString("type", type == null ? "" : EntityType.getKey(type).toString());
    }

    @Override
    public void onDespawn() {
        if (synthetic != null) {
            synthetic.remove();
            synthetic = null;
        }
    }

    @Override
    public void onRemove() {
        onDespawn();
    }

    @Override
    public void onSpawn() {
        if (type == null)
            return;
        synthetic = EntityControllers.createForType(type);
        synthetic.create(npc.getStoredLocation(), npc);
        Entity entity = synthetic.getEntity();
        if (entity == null) {
            synthetic = null;
            return;
        }
        // The controller stamps the NPC's own UUID onto whatever it builds, which is right for the one real entity and
        // fatal for a second one: vanilla refuses to add an entity whose UUID is already in the level, so the disguise
        // would silently never appear. Upstream stamps it the same way and so has the same problem on this version.
        // The NPC association is held by entity instance rather than by UUID, so a fresh one costs nothing.
        entity.setUUID(UUID.randomUUID());
        synthetic.spawn(npc.getStoredLocation(), success -> {
            if (!Boolean.TRUE.equals(success)) {
                synthetic = null;
            }
        });
    }

    /**
     * @return the entity type this name refers to, or null — accepts a registry id or a Bukkit-style enum name
     */
    public static EntityType<?> parse(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        EntityType<?> matched = MobType.match(raw);
        return matched != null ? matched
                : BuiltInRegistries.ENTITY_TYPE.get(
                        net.minecraft.resources.ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT)));
    }
}
