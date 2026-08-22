package net.citizensnpcs.api.persistence;

import net.citizensnpcs.api.util.DataKey;
import net.minecraft.world.phys.Vec3;

/** Replaces upstream's {@code VectorPersister}; the on-disk {@code x/y/z} layout is unchanged. */
public class Vec3Persister implements Persister<Vec3> {
    @Override
    public Vec3 create(DataKey root) {
        if (!root.keyExists())
            return null;
        return new Vec3(root.getDouble("x"), root.getDouble("y"), root.getDouble("z"));
    }

    @Override
    public void save(Vec3 instance, DataKey root) {
        root.setDouble("x", instance.x);
        root.setDouble("y", instance.y);
        root.setDouble("z", instance.z);
    }
}
