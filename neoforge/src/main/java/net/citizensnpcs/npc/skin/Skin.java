package net.citizensnpcs.npc.skin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.GameProfile;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.util.SkinProperty;
import net.citizensnpcs.util.MojangSkinGenerator;
import net.citizensnpcs.trait.SkinTrait;
import net.citizensnpcs.Settings.Setting;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

/**
 * A player skin, fetched by name and cached.
 * <p>
 * Java profiles use {@link SkullBlockEntity#fetchGameProfile(String)} and its native cache/session service.
 * Explicit Bedrock-prefixed names use the configured Geyser skin API. Pending recipients retain their trait revision
 * so a delayed result cannot replace a newer choice or a replacement NPC.
 * <p>
 * The completion runs on the HTTP thread, so the apply step is bounced back onto the main thread — touching entity state
 * off-thread is exactly the kind of bug that shows up later as a random CME.
 */
public class Skin {
    private volatile boolean isValid = true;
    private final Map<EntityHumanNPC, Pending> pending = new WeakHashMap<>();
    private boolean retired;
    private final long cacheGeneration = generation;
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
        if (!isValid || retired || cacheGeneration != generation)
            return false;
        if (skinData == null) {
            synchronized (pending) {
                SkinTrait trait = entity.getNPC().getTraitNullable(SkinTrait.class);
                if (trait != null) pending.put(entity, new Pending(trait, trait.getSkinRevision()));
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
            SkinTrait trait = npc.getTraitNullable(SkinTrait.class);
            if (trait != null) trait.applyTextureInternal(property.signature, property.value);
            npc.data().setPersistent(CACHED_SKIN_UUID_NAME_METADATA, skinName);
            npc.data().setPersistent(CACHED_SKIN_UUID_METADATA, skinId.toString());
        }
    }

    private void fetch() {
        String prefix = Setting.BEDROCK_SKIN_PREFIX.asString().toLowerCase(Locale.ROOT);
        boolean bedrock = !prefix.isEmpty() && skinName.startsWith(prefix);
        if (!bedrock && (skinName.length() < 3 || skinName.length() > 16 || skinName.startsWith("cit-"))) {
            isValid = false;
            return;
        }
        CompletableFuture<Optional<GameProfile>> lookup;
        if (bedrock) {
            lookup = new CompletableFuture<>();
            CitizensAPI.getScheduler().runTaskAsynchronously(() -> {
                try {
                    String name = skinName.substring(prefix.length());
                    Long xuid = MojangSkinGenerator.getXUIDFromName(name);
                    lookup.complete(xuid == null ? Optional.empty() : Optional.ofNullable(MojangSkinGenerator.getFilledGameProfileByXUID(name, xuid)));
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    lookup.completeExceptionally(failure);
                }
            });
        } else lookup = SkullBlockEntity.fetchGameProfile(skinName);
        var scheduler = CitizensAPI.getScheduler();
        lookup.whenComplete((result, failure) -> {
            // completes on the profile-lookup thread; entity state must only be touched on the main thread
            scheduler.runTask(() -> {
                if (retired || cacheGeneration != generation) return;
                if (failure != null || result == null || result.isEmpty()) {
                    isValid = false;
                    synchronized (pending) { pending.clear(); }
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
        try {
            if (property == null) throw new IllegalArgumentException();
            net.citizensnpcs.util.SkinData.textures(property.value);
        } catch (IllegalArgumentException invalid) {
            isValid = false;
            synchronized (pending) { pending.clear(); }
            return;
        }
        skinId = profile.getId();
        skinData = property;

        synchronized (pending) {
            var waiting = new HashMap<>(pending);
            pending.clear();
            for (var entry : waiting.entrySet()) {
                EntityHumanNPC entity = entry.getKey();
                Pending request = entry.getValue();
                NPC npc = entity.getNPC();
                if (!retired && cacheGeneration == generation && !entity.isRemoved() && npc != null && npc.getCosmeticEntity() == entity
                        && npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc
                        && npc.getOwningRegistry().getById(npc.getId()) == npc
                        && npc.getTraitNullable(SkinTrait.class) == request.trait
                        && request.trait.getSkinRevision() == request.revision) {
                    applyTo(entity);
                    // the client only re-reads the profile on respawn, so the entity is re-sent
                    SkinPacketTracker.respawn(entity);
                }
            }
        }
    }

    public static void clearCache() {
        synchronized (CACHE) {
            generation++;
            CACHE.values().forEach(skin -> { skin.retired = true; synchronized (skin.pending) { skin.pending.clear(); } });
            CACHE.clear();
        }
    }

    /**
     * @return the cached skin for the name, fetching it if this is the first request
     */
    public static Skin get(NPC npc, String skinName) {
        return get(skinName);
    }

    /** Refresh this name while leaving other NPCs' already pending lookups valid. */
    public static void invalidate(String name) {
        synchronized (CACHE) { CACHE.remove(name.toLowerCase(Locale.ROOT)); }
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
    private static long generation;
    private record Pending(SkinTrait trait, long revision) { }
    public static final String CACHED_SKIN_UUID_METADATA = "cached-skin-uuid";
    public static final String CACHED_SKIN_UUID_NAME_METADATA = "cached-skin-uuid-name";
}
