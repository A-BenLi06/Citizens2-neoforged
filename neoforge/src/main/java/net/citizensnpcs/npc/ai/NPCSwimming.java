package net.citizensnpcs.npc.ai;

import java.util.HashMap;
import java.util.Map;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.ai.PathStrategy;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.trait.Gravity;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** Citizens' optional water movement and buoyancy, independent of the swimming animation pose. */
public final class NPCSwimming {
    private static final Map<EntityType<?>, Boolean> WATER_TYPES = new HashMap<>();

    private NPCSwimming() { }

    /** Uses the reference default for aquatic NPCs whose Minecraft AI is disabled. */
    public static boolean isEnabled(NPC npc, ServerLevel level) {
        if (npc.data().has(NPC.Metadata.SWIM)) return npc.data().get(NPC.Metadata.SWIM);
        if (npc.useMinecraftAI()) return false;
        Entity entity = npc.getEntity();
        if (entity != null) return MinecraftBlockExaminer.isWaterMob(entity);
        // Commands also work before spawn. Inspect an unregistered native instance once per registered entity type.
        return WATER_TYPES.computeIfAbsent(npc.getOrAddTrait(MobType.class).getType(), type -> {
            Entity sample = type.create(level);
            if (sample == null) return false;
            boolean water = MinecraftBlockExaminer.isWaterMob(sample);
            sample.discard();
            return water;
        });
    }

    public static void update(NPC npc, Entity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !isEnabled(npc, level)
                || !MinecraftBlockExaminer.isLiquid(level.getBlockState(entity.blockPosition()))) return;
        if (npc.getNavigator().isNavigating()) {
            float multiplier = npc.data().get(NPC.Metadata.WATER_SPEED_MODIFIER, Setting.NPC_WATER_SPEED_MODIFIER.asFloat());
            entity.setDeltaMovement(entity.getDeltaMovement().scale(multiplier));
            PathStrategy strategy = npc.getNavigator().getPathStrategy();
            Location destination = strategy == null ? null : strategy.getCurrentDestination();
            if (destination == null || destination.getY() > entity.getY()) trySwim(entity);
        } else {
            Gravity gravity = npc.getTraitNullable(Gravity.class);
            if (gravity == null || gravity.hasGravity()) trySwim(entity);
        }
    }

    private static void trySwim(Entity entity) {
        if (entity.isInWater() && Util.getFastRandom().nextFloat() <= 0.85F) {
            float power = MinecraftBlockExaminer.isWaterMob(entity) ? 0.02F : 0.04F;
            entity.setDeltaMovement(entity.getDeltaMovement().add(0, power, 0));
        }
    }
}
