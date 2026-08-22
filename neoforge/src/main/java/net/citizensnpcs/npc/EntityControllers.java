package net.citizensnpcs.npc;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;

/**
 * Chooses the {@link EntityController} for a mob type.
 * <p>
 * Upstream keys an {@code EnumMap} by Bukkit's {@code EntityType} enum and requires an explicit registration for every
 * supported mob — a type with no entry cannot be made into an NPC. Here the mapping is keyed by the registry object and
 * only holds the <i>exceptions</i>: everything else, including entities added by other mods, falls through to
 * {@link MobEntityController}. That means a freshly installed mod's mobs work as NPCs with no changes here.
 */
public class EntityControllers {
    private EntityControllers() {
    }

    /**
     * @return whether an NPC can be created for this type. Only types absent from the entity registry are rejected.
     */
    public static boolean controllerExistsForType(EntityType<?> type) {
        return type != null && BuiltInRegistries.ENTITY_TYPE.containsValue(type);
    }

    public static EntityController createForType(EntityType<?> type) {
        if (type == null)
            throw new IllegalArgumentException("null EntityType");
        Function<EntityType<?>, EntityController> special = SPECIAL_CASES.get(type);
        return special != null ? special.apply(type) : new MobEntityController(type);
    }

    /**
     * Overrides the controller for one type. Used for mobs that need real method overrides rather than post-creation
     * configuration — the fake player above all.
     */
    public static void setEntityControllerForType(EntityType<?> type,
            Function<EntityType<?>, EntityController> factory) {
        SPECIAL_CASES.put(type, factory);
    }

    private static final Map<EntityType<?>, Function<EntityType<?>, EntityController>> SPECIAL_CASES = new HashMap<>();
    static {
        // players have no EntityType factory and need a fake connection, so they get a dedicated controller
        SPECIAL_CASES.put(EntityType.PLAYER, type -> new HumanController());
    }
}
