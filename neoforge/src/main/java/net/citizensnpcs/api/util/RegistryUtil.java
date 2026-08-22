package net.citizensnpcs.api.util;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Maps Java types to the {@link Registry} that holds them, so that persisted values can round-trip through a
 * {@code namespace:path} string.
 * <p>
 * Upstream uses Bukkit's {@code Keyed} marker interface plus {@code Bukkit.getRegistry(Class)} for the same job.
 * Minecraft's registry objects carry no such marker — {@link net.minecraft.world.item.Item Item} is a plain class — so
 * the type-to-registry mapping has to be explicit. Only the registries Citizens actually persists are listed; adding a
 * new persistable registry type means adding a line to the static block.
 */
public class RegistryUtil {
    private RegistryUtil() {
    }

    /** @return the value registered under {@code key}, or null if the type is not registry-backed or the key is absent */
    public static Object get(Class<?> type, String key) {
        Registry<?> registry = getRegistry(type);
        if (registry == null)
            return null;
        ResourceLocation location = parseKey(key);
        return location == null ? null : registry.get(location);
    }

    /** @return the registry holding instances of {@code type}, or null if there is none */
    public static Registry<?> getRegistry(Class<?> type) {
        for (Map.Entry<Class<?>, Registry<?>> entry : BUILT_IN.entrySet()) {
            if (entry.getKey().isAssignableFrom(type))
                return entry.getValue();
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return null;
        for (Map.Entry<Class<?>, ResourceKey<? extends Registry<?>>> entry : DATAPACK.entrySet()) {
            if (entry.getKey().isAssignableFrom(type))
                return server.registryAccess().registry(entry.getValue()).orElse(null);
        }
        return null;
    }

    public static boolean isRegistryKeyed(Class<?> type) {
        return getRegistry(type) != null;
    }

    /**
     * @return the {@code namespace:path} key for {@code value}, or null if it is not a registry value
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static String keyOf(Object value) {
        if (value == null)
            return null;
        Registry registry = getRegistry(value.getClass());
        if (registry == null)
            return null;
        ResourceLocation location = registry.getKey(value);
        return location == null ? null : location.toString();
    }

    /**
     * Accepts both {@code namespace:path} and a bare {@code path} (assumed {@code minecraft:}), matching what upstream's
     * {@code SpigotUtil.getKey} accepts.
     */
    public static ResourceLocation parseKey(String key) {
        if (key == null || key.isEmpty())
            return null;
        Optional<ResourceLocation> parsed = ResourceLocation.read(key.toLowerCase(java.util.Locale.ROOT)).result();
        return parsed.orElse(null);
    }

    private static final Map<Class<?>, Registry<?>> BUILT_IN = new IdentityHashMap<>();
    /**
     * Registries that live in the datapack-driven {@code RegistryAccess} rather than {@code BuiltInRegistries}, so they
     * can only be resolved while a server is running.
     */
    private static final Map<Class<?>, ResourceKey<? extends Registry<?>>> DATAPACK = new IdentityHashMap<>();
    static {
        BUILT_IN.put(net.minecraft.world.item.Item.class, BuiltInRegistries.ITEM);
        BUILT_IN.put(net.minecraft.world.level.block.Block.class, BuiltInRegistries.BLOCK);
        BUILT_IN.put(net.minecraft.world.entity.EntityType.class, BuiltInRegistries.ENTITY_TYPE);
        BUILT_IN.put(net.minecraft.sounds.SoundEvent.class, BuiltInRegistries.SOUND_EVENT);
        BUILT_IN.put(net.minecraft.world.effect.MobEffect.class, BuiltInRegistries.MOB_EFFECT);
        BUILT_IN.put(net.minecraft.world.entity.ai.attributes.Attribute.class, BuiltInRegistries.ATTRIBUTE);
        BUILT_IN.put(net.minecraft.world.entity.npc.VillagerProfession.class, BuiltInRegistries.VILLAGER_PROFESSION);
        BUILT_IN.put(net.minecraft.world.entity.npc.VillagerType.class, BuiltInRegistries.VILLAGER_TYPE);
        BUILT_IN.put(net.minecraft.world.entity.animal.CatVariant.class, BuiltInRegistries.CAT_VARIANT);
        BUILT_IN.put(net.minecraft.world.entity.animal.FrogVariant.class, BuiltInRegistries.FROG_VARIANT);
        BUILT_IN.put(net.minecraft.world.item.alchemy.Potion.class, BuiltInRegistries.POTION);

        DATAPACK.put(net.minecraft.world.item.enchantment.Enchantment.class, Registries.ENCHANTMENT);
        DATAPACK.put(net.minecraft.world.entity.decoration.PaintingVariant.class, Registries.PAINTING_VARIANT);
    }
}
