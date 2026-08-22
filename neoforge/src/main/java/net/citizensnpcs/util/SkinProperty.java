package net.citizensnpcs.util;

import java.util.Collection;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

/**
 * A single {@code textures} property from a {@link GameProfile} — the base64 payload and its Mojang signature.
 * <p>
 * Upstream reaches every authlib accessor through {@code MethodHandle}s because a Bukkit build has to cope with both
 * the old class-shaped {@code Property} and the newer record. The port compiles against exactly one authlib, so those
 * handles are gone and the calls are direct.
 */
public class SkinProperty {
    public final String name;
    public final String signature;
    public final String value;

    public SkinProperty(String name, String value, String signature) {
        this.name = name;
        this.value = value;
        this.signature = signature;
    }

    /**
     * @return a copy of {@code profile} carrying this skin, with any existing {@code textures} property removed first —
     *         a duplicate crashes the client
     */
    public GameProfile applyProperties(GameProfile profile) {
        GameProfileWrapper wrapper = GameProfileWrapper.fromMojangProfile(profile);
        wrapper.properties.removeAll(TEXTURES_KEY);
        wrapper.properties.put(TEXTURES_KEY, this);
        return wrapper.applyProperties(profile);
    }

    public Property toMojang() {
        return new Property(name, value, signature);
    }

    public static SkinProperty fromMojang(Property property) {
        return property == null ? null : new SkinProperty(property.name(), property.value(), property.signature());
    }

    public static SkinProperty fromMojangProfile(GameProfile profile) {
        if (profile == null)
            return null;
        Collection<Property> textures = profile.getProperties().get(TEXTURES_KEY);
        return textures.isEmpty() ? null : fromMojang(textures.iterator().next());
    }

    /** The GameProfile property name that carries a player's skin and cape. */
    public static final String TEXTURES_KEY = "textures";
}
