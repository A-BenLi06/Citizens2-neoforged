package net.citizensnpcs.api.trait.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Location;
import net.minecraft.world.entity.Entity;

/**
 * Persists the current {@link Location} of the NPC, caching the last known position while despawned.
 * <p>
 * Upstream keeps a {@code worldUUID} accessor for the chunk-coordinate path, needed because a Bukkit world may be
 * unloaded while its UUID is still meaningful. Dimensions are always loaded on a running server here, so the location's
 * own level reference is enough and that accessor is gone.
 */
@TraitName("location")
public class CurrentLocation extends Trait {
    @Persist
    private float bodyYaw = Float.NaN;
    @Persist(value = "", required = true)
    private Location location = new Location(null, 0, 0, 0);

    public CurrentLocation() {
        super("location");
    }

    public float getBodyYaw() {
        return bodyYaw;
    }

    public Location getLocation() {
        return location == null || location.getWorld() == null ? null : location.clone();
    }

    @Override
    public void load(DataKey key) {
        key.removeKey("headYaw");
    }

    @Override
    public void onSpawn() {
        if (!Float.isNaN(bodyYaw)) {
            npc.getEntity().setYBodyRot(bodyYaw);
        }
    }

    @Override
    public void run() {
        if (!npc.isSpawned())
            return;
        Entity entity = npc.getEntity();
        location = Location.of(entity);
        bodyYaw = entity.getYRot();
    }

    public void setLocation(Location loc) {
        if (loc != null) {
            location = loc.clone();
        }
    }

    @Override
    public String toString() {
        return "CurrentLocation{" + location + "}";
    }
}
