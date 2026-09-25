package net.citizensnpcs.trait;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Pose;

/**
 * Named head directions an NPC can be told to assume, plus one that it returns to when idle.
 * <p>
 * The on-disk form is unchanged: a numbered list of {@code name;pitch;yaw} strings.
 * <p>
 * Turning goes through {@link RotationTrait}, which eases into the pose over several ticks rather than snapping to it.
 */
@TraitName("poses")
public class Poses extends Trait {
    @Persist
    private String defaultPose;
    private final Map<String, Pose> poses = new LinkedHashMap<>();
    private final List<String> invalidPoses = new ArrayList<>();

    public Poses() {
        super("poses");
    }

    public boolean addPose(String name, Location location) {
        return addPose(name, location, false);
    }

    public boolean addPose(String name, Location location, boolean isDefault) {
        if (!isValidName(name)) throw new IllegalArgumentException("Pose names must be nonblank and cannot contain semicolons");
        if (location == null) throw new IllegalArgumentException("Pose direction is required");
        validateAngles(location.getYaw(), location.getPitch());
        String key = name.toLowerCase(Locale.ROOT);
        if (poses.containsKey(key))
            return false;
        poses.put(key, new Pose(key, location.getPitch(), location.getYaw()));
        invalidPoses.removeIf(raw -> invalidName(raw).equals(key));
        if (isDefault) {
            defaultPose = key;
        }
        return true;
    }

    /** Faces the NPC the way this location faces. */
    public void assumePose(Location location) {
        assumePose(location.getYaw(), location.getPitch());
    }

    /** Faces the NPC the way the named pose does; does nothing if there is no such pose. */
    public void assumePose(String name) {
        if (name == null)
            return;
        Pose pose = poses.get(name.toLowerCase(Locale.ROOT));
        if (pose != null) {
            assumePose(pose.getYaw(), pose.getPitch());
        }
    }

    private void assumePose(float yaw, float pitch) {
        validateAngles(yaw, pitch);
        if (!npc.isSpawned()) {
            Location at = npc.getStoredLocation();
            if (at == null) return;
            npc.spawn(at, SpawnReason.COMMAND);
        }
        if (!npc.isSpawned())
            return;
        npc.getOrAddTrait(RotationTrait.class).getPhysicalSession().rotateToHave(yaw, pitch);
    }

    public String getDefaultPose() {
        return defaultPose;
    }

    public Pose getPose(String name) {
        return name == null ? null : poses.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, Pose> getPoses() {
        return new LinkedHashMap<>(poses);
    }

    public boolean hasPose(String name) {
        return getPose(name) != null;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        poses.clear();
        invalidPoses.clear();
        for (DataKey sub : key.getRelative("list").getIntegerSubKeys()) {
            String raw = sub.getString("");
            try {
                String[] parts = raw.split(";", -1);
                if (parts.length != 3 || !isValidName(parts[0]))
                    throw new IllegalArgumentException("Expected name;pitch;yaw");
                float pitch = Float.parseFloat(parts[1]), yaw = Float.parseFloat(parts[2]);
                validateAngles(yaw, pitch);
                poses.put(parts[0].toLowerCase(Locale.ROOT), new Pose(parts[0], pitch, yaw));
            } catch (IllegalArgumentException e) {
                invalidPoses.add(raw);
                Messaging.warn("Retaining inactive invalid pose", sub.name(), "on NPC", npc, "-", e.getMessage());
            }
        }
    }

    public boolean removePose(String name) {
        if (name == null) return false;
        String key = name.toLowerCase(Locale.ROOT);
        boolean removed = poses.remove(key) != null;
        return invalidPoses.removeIf(raw -> invalidName(raw).equals(key)) || removed;
    }

    /**
     * Returns to the default pose while idle. Navigating overrides it, and so does looking at a nearby player — otherwise
     * the two would fight every tick.
     */
    @Override
    public void run() {
        if (!hasPose(defaultPose) || npc.getNavigator().isNavigating())
            return;
        LookClose look = npc.getTraitNullable(LookClose.class);
        if (look != null && look.isEnabled() && look.canSeeTarget())
            return;
        assumePose(defaultPose);
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("list");
        int i = 0;
        for (Pose pose : poses.values()) {
            key.setString("list." + i++, pose.stringValue());
        }
        for (String raw : invalidPoses) key.setString("list." + i++, raw);
    }

    public void setDefaultPose(String name) {
        defaultPose = name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    public static boolean isValidName(String name) {
        return name != null && !name.isBlank() && name.indexOf(';') < 0;
    }

    private static String invalidName(String raw) {
        return raw.split(";", -1)[0].toLowerCase(Locale.ROOT);
    }

    private static void validateAngles(float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Pose angles must be finite");
    }
}
