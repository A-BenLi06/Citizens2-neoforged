package net.citizensnpcs.api.persistence;

import net.citizensnpcs.api.util.DataKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Replaces upstream's {@code NamespacedKeyPersister}. The on-disk form is the same {@code namespace:path} string, so
 * existing saves load unchanged.
 */
public class ResourceLocationPersister implements Persister<ResourceLocation> {
    @Override
    public ResourceLocation create(DataKey root) {
        String val = root.getString("");
        if (val == null || val.isEmpty() || val.equals("minecraft:"))
            return null;
        return ResourceLocation.read(val).result().orElse(null);
    }

    @Override
    public void save(ResourceLocation instance, DataKey root) {
        root.setString("", instance.toString());
    }
}
