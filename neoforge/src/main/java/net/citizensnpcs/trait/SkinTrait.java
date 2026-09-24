package net.citizensnpcs.trait;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.google.common.io.BaseEncoding;
import com.mojang.authlib.GameProfile;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.api.util.TextParser;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.npc.skin.Skin;
import net.citizensnpcs.npc.skin.SkinPacketTracker;
import net.citizensnpcs.util.SkinProperty;
import net.minecraft.server.level.ServerPlayer;

/**
 * The skin shown on a player-type NPC.
 * <p>
 * A skin can come from three places, checked in this order: a texture pasted in directly
 * ({@link #setSkinPersistent(String, String, String)}), a player name to fetch from Mojang ({@link #setSkinName}), or —
 * when neither is set and {@link #fetchDefaultSkin()} is on — the NPC's own name, which is what makes
 * {@code /npc create Notch player} show Notch.
 * <p>
 * Applying a skin rewrites the entity's {@link GameProfile}, then refreshes both the profile and client entity through
 * {@link SkinPacketTracker} so the native client's cached PlayerInfo and skin lookup are replaced.
 * <p>
 * Upstream also carries {@code body}/{@code cape}/{@code elytra} texture patches and a model type for Mannequin NPCs.
 * Mannequins do not exist in 1.21.1, so those fields are left out rather than persisted and ignored.
 */
@TraitName("skintrait")
public class SkinTrait extends Trait {
    @Persist
    private boolean fetchDefaultSkin = Setting.NPC_SKIN_FETCH_DEFAULT.asBoolean();
    private String filledPlaceholder;
    @Persist
    private String signature;
    @Persist
    private String skinName;
    @Persist
    private String textureRaw;
    private int timer;
    @Persist
    private boolean updateSkins = Setting.NPC_SKIN_USE_LATEST.asBoolean();

    public SkinTrait() {
        super("skintrait");
    }

    /**
     * Stores a texture without touching the NPC's skin name or triggering a refresh. Used by the fetch path once
     * Mojang answers.
     */
    public void applyTextureInternal(String signature, String value) {
        textureRaw = value;
        this.signature = signature;
    }

    /**
     * Applies the current skin to the given entity. Called on spawn and whenever the skin changes.
     *
     * @return true when a texture was applied immediately; false when a fetch is still in flight
     */
    public boolean applyTo(EntityHumanNPC entity) {
        if (entity == null)
            return false;
        if (textureRaw != null && signature != null) {
            // an explicit texture needs no lookup, so it is never subject to Mojang rate limiting
            // the first argument is the GameProfile property name and has to be "textures" - passing the skin's own
            // name here put the texture under a key the client never reads, so every NPC drew the default skin
            SkinProperty property = new SkinProperty(SkinProperty.TEXTURES_KEY, textureRaw, signature);
            GameProfile profile = entity.getGameProfile();
            profile.getProperties().removeAll(SkinProperty.TEXTURES_KEY);
            profile.getProperties().put(SkinProperty.TEXTURES_KEY, property.toMojang());
            return true;
        }
        String source = getSkinName();
        if (source == null) {
            if (!fetchDefaultSkin)
                return false;
            source = npc.getName();
        }
        if (source == null || source.isEmpty())
            return false;
        return Skin.get(npc, TextParser.strip(source)).apply(entity);
    }

    /**
     * Placeholders in a skin name (say {@code %player%}) resolve to different players over time, so the resolved value
     * is re-checked periodically and the skin refreshed when it changes.
     */
    private boolean checkPlaceholder() {
        if (skinName == null)
            return false;
        String filled = TextParser.strip(Placeholders.replace(skinName, null, npc)).toLowerCase();
        if (!filled.equalsIgnoreCase(skinName) && !filled.equalsIgnoreCase(filledPlaceholder)) {
            filledPlaceholder = filled;
            Messaging.debug("Filled skin placeholder", filled, "from", skinName);
            return true;
        }
        return false;
    }

    /**
     * Clears skin texture and name.
     */
    public void clearTexture() {
        textureRaw = null;
        signature = null;
        skinName = null;
        filledPlaceholder = null;
    }

    /**
     * Whether to fetch the Mojang skin using the NPC's name on spawn.
     */
    public boolean fetchDefaultSkin() {
        return fetchDefaultSkin;
    }

    /**
     * @return The texture signature, or null
     */
    public String getSignature() {
        return signature;
    }

    /**
     * @return The skin name if set, or null (i.e. using the NPC's name)
     */
    public String getSkinName() {
        return filledPlaceholder != null && skinName != null ? filledPlaceholder
                : skinName == null ? null : skinName.toLowerCase();
    }

    /**
     * @return The encoded texture data, or null
     */
    public String getTexture() {
        return textureRaw;
    }

    private void onSkinChange(boolean forceUpdate) {
        if (!(npc.getCosmeticEntity() instanceof EntityHumanNPC human))
            return;
        applyTo(human);
        SkinPacketTracker.respawn(human);
    }

    @Override
    public void onSpawn() {
        if (npc.getCosmeticEntity() instanceof EntityHumanNPC human) {
            applyTo(human);
        }
    }

    @Override
    public void run() {
        if (timer-- > 0)
            return;
        timer = Setting.PLACEHOLDER_SKIN_UPDATE_FREQUENCY.asTicks();
        if (checkPlaceholder()) {
            onSkinChange(true);
        }
    }

    /**
     * @see #fetchDefaultSkin
     */
    public void setFetchDefaultSkin(boolean fetch) {
        fetchDefaultSkin = fetch;
    }

    /**
     * @see #shouldUpdateSkins()
     */
    public void setShouldUpdateSkins(boolean update) {
        updateSkins = update;
    }

    /**
     * Sets the skin name, refreshing the NPC's skin if spawned.
     */
    public void setSkinName(String name) {
        setSkinName(name, false);
    }

    /**
     * Sets the skin name, refreshing the NPC's skin if spawned.
     *
     * @param forceUpdate
     *            whether to refresh even if no data has been fetched yet
     */
    public void setSkinName(String name, boolean forceUpdate) {
        Objects.requireNonNull(name);
        setSkinNameInternal(name);
        // a named skin supersedes any pasted texture, otherwise the old texture would keep winning in applyTo
        textureRaw = null;
        signature = null;
        onSkinChange(forceUpdate);
    }

    private void setSkinNameInternal(String name) {
        skinName = TextParser.strip(name);
        filledPlaceholder = null;
    }

    /**
     * Copies the skin from an online player. Not subject to Mojang rate limiting, since the profile is already loaded.
     */
    public void setSkinPersistent(ServerPlayer player) {
        SkinProperty property = SkinProperty.fromMojangProfile(player.getGameProfile());
        if (property == null) {
            Messaging.severe("Player", player.getGameProfile().getName(), "has no skin texture to copy");
            return;
        }
        setSkinPersistent(player.getGameProfile().getName(), property.signature, property.value);
    }

    /**
     * Sets the skin data directly, refreshing the NPC if spawned.
     *
     * @param skinName
     *            Skin name or cache key
     * @param signature
     *            {@link #getSignature()}
     * @param data
     *            {@link #getTexture()}
     */
    public void setSkinPersistent(String skinName, String signature, String data) {
        Objects.requireNonNull(skinName);
        Objects.requireNonNull(signature);
        Objects.requireNonNull(data);

        setSkinNameInternal(skinName);
        String json = new String(BaseEncoding.base64().decode(data), StandardCharsets.UTF_8);
        if (!json.contains("textures"))
            throw new IllegalArgumentException("Invalid texture data");

        this.signature = signature;
        textureRaw = data;
        updateSkins = false;
        npc.data().setPersistent(Skin.CACHED_SKIN_UUID_NAME_METADATA, skinName.toLowerCase());
        onSkinChange(false);
    }

    /**
     * @return Whether the skin should be updated from Mojang periodically
     */
    public boolean shouldUpdateSkins() {
        return updateSkins;
    }
}
