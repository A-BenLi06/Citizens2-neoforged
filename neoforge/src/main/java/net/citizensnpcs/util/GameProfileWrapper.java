package net.citizensnpcs.util;

import java.util.UUID;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

/**
 * A mutable view of a {@link GameProfile}'s identity and properties.
 * <p>
 * {@code GameProfile} exposes its properties through a live {@code PropertyMap}, so editing a profile in place would
 * mutate whatever else holds it — the skin code needs to build a modified copy instead. That is all this type is for;
 * upstream additionally uses it to hide the authlib class-versus-record split behind reflection, which the port does
 * not need.
 */
public class GameProfileWrapper {
    public final String name;
    public Multimap<String, SkinProperty> properties;
    public final UUID uuid;

    public GameProfileWrapper(String name, UUID uuid, Multimap<String, SkinProperty> properties) {
        this.name = name;
        this.uuid = uuid;
        this.properties = properties;
    }

    /** @return a new profile with {@code profile}'s id and name, carrying this wrapper's properties */
    public GameProfile applyProperties(GameProfile profile) {
        GameProfile result = new GameProfile(profile.getId(), profile.getName());
        for (String key : properties.keySet()) {
            for (SkinProperty property : properties.get(key)) {
                result.getProperties().put(key, property.toMojang());
            }
        }
        return result;
    }

    public static GameProfileWrapper fromMojangProfile(GameProfile profile) {
        if (profile == null)
            return null;
        Multimap<String, SkinProperty> converted = HashMultimap.create();
        for (String key : profile.getProperties().keySet()) {
            for (Property property : profile.getProperties().get(key)) {
                converted.put(key, SkinProperty.fromMojang(property));
            }
        }
        return new GameProfileWrapper(profile.getName(), profile.getId(), converted);
    }
}
