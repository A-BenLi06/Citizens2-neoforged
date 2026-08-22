package net.citizensnpcs.api.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Entity helpers that upstream keeps in {@code SpigotUtil}. That class is mostly a Bukkit cross-version compatibility
 * layer with no meaning here, so only the parts Citizens actually calls are carried over.
 */
public class EntityUtil {
    private EntityUtil() {
    }

    /**
     * The entity with this UUID, from any loaded dimension, or null.
     * <p>
     * Stands in for Bukkit's {@code Bukkit.getEntity(UUID)}. Vanilla indexes entities per level rather than globally, so
     * every level is asked in turn.
     */
    public static Entity getEntity(UUID uuid) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return null;
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null)
                return entity;
        }
        return null;
    }

    /**
     * Teleports an entity, moving it across dimensions when needed.
     * <p>
     * Bukkit's {@code Entity#teleport} keeps the same entity object when changing worlds, so upstream can ignore the
     * result and keep using its existing reference. Vanilla cannot: a cross-dimension
     * {@link Entity#teleportTo(ServerLevel, double, double, double, Set, float, float)} copies the entity into the
     * destination level and removes the original with
     * {@link net.minecraft.world.entity.Entity.RemovalReason#CHANGED_DIMENSION}. Callers must therefore use the
     * returned entity for any follow-up work rather than the one they passed in.
     *
     * @param entity
     *            the entity to move
     * @param location
     *            the destination
     * @return the entity at the destination — the same instance for a same-dimension move, a new instance after a
     *         dimension change, or null if the teleport failed
     */
    public static Entity teleport(Entity entity, Location location) {
        ServerLevel destination = location.getWorld();
        if (destination == null) {
            Messaging.severe("teleport failed: destination level is not loaded,", location);
            return null;
        }
        boolean crossDimension = destination != entity.level();
        if (!entity.teleportTo(destination, location.getX(), location.getY(), location.getZ(), Set.of(),
                location.getYaw(), location.getPitch()))
            return null;
        if (!crossDimension)
            return entity;
        // teleportTo discards the copy it created, so recover it from the destination level by uuid
        return destination.getEntity(entity.getUUID());
    }

    /**
     * @return the longest name the entity type can display, in characters
     */
    public static int getMaxNameLength(EntityType<?> type) {
        // upstream returns 64 only for pre-1.13 servers, which cannot run this mod
        return 256;
    }

    /**
     * Players within {@code range} blocks of the entity that can actually see it.
     * <p>
     * Stands in for upstream's {@code LocationLookup.getNearbyVisiblePlayers}. That class exists mainly to cache chunk
     * and player lookups around Bukkit's slow {@code World#getNearbyEntities}; a level query over a bounding box is
     * already cheap here, so only the visibility filtering is carried over.
     * <p>
     * Bukkit's per-player {@code Player#canSee} entity hiding has no server-side equivalent in vanilla, so that part of
     * the filter is dropped — per-viewer NPC visibility is the {@code PlayerFilter} trait's job in this port.
     */
    public static List<ServerPlayer> getNearbyVisiblePlayers(Entity base, double range) {
        return base == null ? new ArrayList<>()
                : getNearbyVisiblePlayers(base, base.getBoundingBox().inflate(range));
    }

    /**
     * The same, over an explicit box rather than a radius — for callers whose region is not a sphere, such as a
     * forcefield that is wider than it is tall.
     */
    public static List<ServerPlayer> getNearbyVisiblePlayers(Entity base, AABB box) {
        List<ServerPlayer> result = new ArrayList<>();
        if (base == null || !(base.level() instanceof ServerLevel level))
            return result;
        for (ServerPlayer player : level.players()) {
            if (player == base || !box.intersects(player.getBoundingBox()))
                continue;
            if (player.isSpectator() || player.hasEffect(MobEffects.INVISIBILITY))
                continue;
            result.add(player);
        }
        return result;
    }

    /**
     * Whether entities of this type are living entities.
     * <p>
     * Bukkit answers this from {@code EntityType#isAlive()}, a flag on its enum. Vanilla's registry object carries no
     * such flag, and its category does not stand in for one — an armour stand is a living entity in the {@code MISC}
     * category. The only reliable test is the class of an actual instance, so one is created, inspected and discarded,
     * and the answer cached. {@code MobEntityController} also feeds the cache from the entities it builds anyway, so the
     * throwaway is rarely needed in practice.
     *
     * @param level
     *            a level to build the sample instance in; when null, a type that is not already cached answers false
     */
    public static boolean isLivingType(EntityType<?> type, ServerLevel level) {
        if (type == null)
            return false;
        Boolean known = LIVING_TYPES.get(type);
        if (known != null)
            return known;
        if (level == null)
            return false;
        Entity sample = type.create(level);
        if (sample == null)
            return false;
        boolean living = sample instanceof LivingEntity;
        sample.discard();
        LIVING_TYPES.put(type, living);
        return living;
    }

    /** Records what an already-built entity says about its type, so {@link #isLivingType} need not build another. */
    public static void recordLivingType(Entity entity) {
        if (entity != null) {
            LIVING_TYPES.putIfAbsent(entity.getType(), entity instanceof LivingEntity);
        }
    }

    private static final Map<EntityType<?>, Boolean> LIVING_TYPES = new ConcurrentHashMap<>();
}
