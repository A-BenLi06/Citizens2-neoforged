package net.citizensnpcs.npc.skin;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.mojang.authlib.GameProfile;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.util.SkinProperty;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

/**
 * A player skin, fetched by name and cached.
 * <p>
 * The whole fetch pipeline is vanilla's. Upstream ships {@code ProfileFetcher}, {@code ProfileRequest} and a retry
 * queue — several hundred lines — because Bukkit gives it no async profile lookup. Minecraft already has one:
 * {@link SkullBlockEntity#fetchGameProfile(String)} returns a cached {@code CompletableFuture} and handles the HTTP
 * call, the session service and rate limiting. So this class is only the NPC-facing part: cache by name, remember which
 * entities are waiting, and apply the texture when the profile lands.
 * <p>
 * The completion runs on the HTTP thread, so the apply step is bounced back onto the main thread — touching entity state
 * off-thread is exactly the kind of bug that shows up later as a random CME.
 */
public class Skin {
    private volatile boolean isValid = true;
    private final Set<EntityHumanNPC> pending = Collections.newSetFromMap(new WeakHashMap<>());
    private volatile SkinProperty skinData;
    private volatile UUID skinId;
    private final String skinName;

    private Skin(String skinName) {
        this.skinName = skinName.toLowerCase(Locale.ROOT);
        fetch();
    }

    /**
     * Applies the skin to an entity, or queues it if the fetch has not finished.
     *
     * @return true if the texture was applied now
     */
    public boolean apply(EntityHumanNPC entity) {
        if (!isValid)
            return false;
        if (skinData == null) {
            synchronized (pending) {
                pending.add(entity);
            }
            return false;
        }
        applyTo(entity);
        return true;
    }

    private void applyTo(EntityHumanNPC entity) {
        SkinProperty property = skinData;
        if (property == null)
            return;
        GameProfile profile = entity.getGameProfile();
        profile.getProperties().removeAll(SkinProperty.TEXTURES_KEY);
        profile.getProperties().put(SkinProperty.TEXTURES_KEY, property.toMojang());

        NPC npc = entity.getNPC();
        if (npc != null && skinId != null) {
            npc.data().setPersistent(CACHED_SKIN_UUID_NAME_METADATA, skinName);
            npc.data().setPersistent(CACHED_SKIN_UUID_METADATA, skinId.toString());
        }
    }

    private void fetch() {
        if (skinName.length() < 3 || skinName.length() > 16 || skinName.startsWith("cit-")) {
            isValid = false;
            return;
        }
        SkullBlockEntity.fetchGameProfile(skinName).thenAccept(result -> {
            // completes on the profile-lookup thread; entity state must only be touched on the main thread
            CitizensAPI.getScheduler().runTask(() -> {
                if (result.isEmpty()) {
                    isValid = false;
                    Messaging.idebug(() -> "Could not find skin for '" + skinName + "'");
                    return;
                }
                setData(result.get());
            });
        });
    }

    /**
     * @return the UUID of the player the skin belongs to, or null if it has not been fetched or is invalid
     */
    public UUID getSkinId() {
        return skinId;
    }

    public String getSkinName() {
        return skinName;
    }

    public boolean hasSkinData() {
        return skinData != null;
    }

    public boolean isValid() {
        return isValid;
    }

    private void setData(GameProfile profile) {
        SkinProperty property = SkinProperty.fromMojangProfile(profile);
        if (property == null) {
            isValid = false;
            return;
        }
        skinId = profile.getId();
        skinData = property;

        synchronized (pending) {
            for (EntityHumanNPC entity : pending) {
                if (entity != null && !entity.isRemoved()) {
                    applyTo(entity);
                    // the client only re-reads the profile on respawn, so the entity is re-sent
                    SkinPacketTracker.respawn(entity);
                }
            }
            pending.clear();
        }
    }

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /**
     * @return the cached skin for the name, fetching it if this is the first request
     */
    public static Skin get(NPC npc, String skinName) {
        String cached = npc.data().get(CACHED_SKIN_UUID_NAME_METADATA, skinName);
        return get(cached);
    }

    public static Skin get(String skinName) {
        String key = skinName.toLowerCase(Locale.ROOT);
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(key, Skin::new);
        }
    }

    public static boolean hasSkin(String name) {
        synchronized (CACHE) {
            return CACHE.containsKey(name.toLowerCase(Locale.ROOT));
        }
    }

    private static final Map<String, Skin> CACHE = new HashMap<>(20);
    public static final String CACHED_SKIN_UUID_METADATA = "cached-skin-uuid";
    public static final String CACHED_SKIN_UUID_NAME_METADATA = "cached-skin-uuid-name";
}
