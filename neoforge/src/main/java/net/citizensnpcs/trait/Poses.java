package net.citizensnpcs.trait;

import java.util.HashMap;
import java.util.LinkedHashMap;
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

    public Poses() {
        super("poses");
    }

    public boolean addPose(String name, Location location) {
        return addPose(name, location, false);
    }

    public boolean addPose(String name, Location location, boolean isDefault) {
        String key = name.toLowerCase();
        if (poses.containsKey(key))
            return false;
        poses.put(key, new Pose(key, location.getPitch(), location.getYaw()));
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
        Pose pose = poses.get(name.toLowerCase());
        if (pose != null) {
            assumePose(pose.getYaw(), pose.getPitch());
        }
    }

    private void assumePose(float yaw, float pitch) {
        if (!npc.isSpawned()) {
            npc.spawn(npc.getStoredLocation(), SpawnReason.COMMAND);
        }
        if (!npc.isSpawned())
            return;
        npc.getOrAddTrait(RotationTrait.class).getPhysicalSession().rotateToHave(yaw, pitch);
    }

    public String getDefaultPose() {
        return defaultPose;
    }

    public Pose getPose(String name) {
        return name == null ? null : poses.get(name.toLowerCase());
    }

    public Map<String, Pose> getPoses() {
        return new HashMap<>(poses);
    }

    public boolean hasPose(String name) {
        return getPose(name) != null;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        poses.clear();
        for (DataKey sub : key.getRelative("list").getIntegerSubKeys()) {
            String[] parts = sub.getString("").split(";");
            if (parts.length < 3) {
                Messaging.warn("Skipping invalid pose", sub.name(), "on NPC", npc, "- expected name;pitch;yaw");
                continue;
            }
            try {
                poses.put(parts[0].toLowerCase(),
                        new Pose(parts[0], Float.parseFloat(parts[1]), Float.parseFloat(parts[2])));
            } catch (NumberFormatException e) {
                Messaging.warn("Skipping invalid pose", sub.name(), "on NPC", npc, "-", e.getMessage());
            }
        }
    }

    public boolean removePose(String name) {
        return name != null && poses.remove(name.toLowerCase()) != null;
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
    }

    public void setDefaultPose(String name) {
        defaultPose = name == null ? null : name.toLowerCase();
    }
}
