package net.citizensnpcs.api.persistence;

import net.citizensnpcs.api.util.DataKey;
import net.minecraft.core.Rotations;

/**
 * Persists armour stand limb poses.
 * <p>
 * Replaces upstream's {@code EulerAnglePersister}, and keeps its {@code x}/{@code y}/{@code z} keys <em>and its unit</em>
 * — Bukkit's {@code EulerAngle} is radians while vanilla's {@link Rotations} is degrees, so the conversion happens here
 * rather than in the save format. An armour stand pose written by the Bukkit plugin therefore still reads back
 * correctly.
 */
public class RotationsPersister implements Persister<Rotations> {
    @Override
    public Rotations create(DataKey root) {
        if (!root.keyExists())
            return null;
        return new Rotations((float) Math.toDegrees(root.getDouble("x")), (float) Math.toDegrees(root.getDouble("y")),
                (float) Math.toDegrees(root.getDouble("z")));
    }

    @Override
    public void save(Rotations instance, DataKey root) {
        root.setDouble("x", Math.toRadians(instance.getX()));
        root.setDouble("y", Math.toRadians(instance.getY()));
        root.setDouble("z", Math.toRadians(instance.getZ()));
    }
}
