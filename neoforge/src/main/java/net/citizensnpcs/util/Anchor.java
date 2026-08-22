package net.citizensnpcs.util;

import java.util.Objects;

import net.citizensnpcs.api.persistence.LocationPersister;
import net.citizensnpcs.api.util.Location;
import net.minecraft.server.level.ServerLevel;

/**
 * A named {@link Location}.
 * <p>
 * Two changes from upstream: the location type, and commons-lang's {@code EqualsBuilder}/{@code HashCodeBuilder} (a
 * Bukkit-provided library) give way to {@link Objects}. Equality is still by name alone, as upstream has it.
 * <p>
 * The stored string form is unchanged — {@code world;x;y;z} — and the world part is resolved through
 * {@link LocationPersister#resolve}, so anchors in an existing saves.yml keep working.
 */
public class Anchor {
    private Location location;
    private final String name;

    // Needed for Anchors defined that can't currently have a valid 'Location'
    private final String unloaded_value;

    public Anchor(String name, Location location) {
        this.location = location;
        this.name = name;
        this.unloaded_value = worldIdOf(location) + ';' + location.getX() + ';' + location.getY() + ';'
                + location.getZ();
    }

    // Allow construction of anchor for unloaded worlds
    public Anchor(String name, String unloaded_value) {
        this.location = null;
        this.unloaded_value = unloaded_value;
        this.name = name;
    }

    @Override
    public boolean equals(Object object) {
        if (object == null)
            return false;
        if (object == this)
            return true;
        if (object.getClass() != getClass())
            return false;

        return Objects.equals(name, ((Anchor) object).name);
    }

    public Location getLocation() {
        return location;
    }

    public String getName() {
        return name;
    }

    /**
     * Returns a String[] of the 'world_name, x, y, z' information needed to create the Location that is associated with
     * the Anchor, in that order.
     *
     * @return a String array of the anchor's location data
     */
    public String[] getUnloadedValue() {
        return unloaded_value.split(";");
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    public boolean isLoaded() {
        return location != null;
    }

    /**
     * Attempts to load the unloaded value of the stored {@link Location}.
     *
     * @see #getUnloadedValue()
     * @return whether the unloaded value could be loaded
     */
    public boolean load() {
        try {
            final String[] parts = getUnloadedValue();
            ServerLevel level = LocationPersister.resolve(parts[0]);
            if (level != null) {
                this.location = new Location(level, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]));
            }
        } catch (final Exception e) {
            // Still not able to be loaded
        }
        return location != null;
    }

    /**
     * @return A string representation for use in saves.yml
     */
    public String stringValue() {
        return name + ';' + unloaded_value;
    }

    @Override
    public String toString() {
        final String[] parts = getUnloadedValue();
        return "Anchor{Name='" + name + "';World='" + parts[0] + "';Location='" + parts[1] + ',' + parts[2] + ','
                + parts[3] + "';}";
    }

    private static String worldIdOf(Location location) {
        ServerLevel level = location.getWorld();
        return level == null ? "" : level.dimension().location().toString();
    }
}
