package net.citizensnpcs.trait;

import java.util.UUID;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.npc.NPCRegistries;
import net.minecraft.world.entity.Entity;

/**
 * Persists the NPC this one is riding, and remounts it whenever both are spawned.
 * <p>
 * Only another NPC can be the mount, since the target is looked up by NPC UUID — that is upstream's design too, and it
 * is what makes the mount survive a restart: a plain entity has no stable identity across a reload.
 * <p>
 * Upstream detects "am I riding an NPC" by testing the vehicle for {@code NPCHolder}. The port's entities are vanilla
 * instances with no such interface, so the vehicle is resolved through {@link NPCRegistries} instead.
 */
@TraitName("mounttrait")
public class MountTrait extends Trait {
    private UUID currentMount;
    @Persist("mountedon")
    private UUID uuid;

    public MountTrait() {
        super("mounttrait");
    }

    public void checkMounted() {
        if (uuid == null || uuid.equals(currentMount) || !npc.isSpawned())
            return;
        NPC other = CitizensAPI.getNPCRegistry().getByUniqueIdGlobal(uuid);
        if (other != null && other.isSpawned()) {
            npc.getEntity().startRiding(other.getEntity(), true);
            currentMount = uuid;
        }
    }

    public UUID getMountedOn() {
        return currentMount;
    }

    @Override
    public void onDespawn() {
        if (currentMount == null)
            return;
        if (npc.isSpawned()) {
            npc.getEntity().stopRiding();
        }
        currentMount = null;
    }

    @Override
    public void onRemove() {
        onDespawn();
    }

    @Override
    public void run() {
        if (!npc.isSpawned())
            return;
        Entity vehicle = npc.getEntity().getVehicle();
        if (vehicle == null) {
            if (currentMount != null) {
                currentMount = null;
            }
        } else {
            NPC mount = NPCRegistries.lookup(vehicle);
            if (mount != null) {
                // something else mounted us in the meantime; adopt it so the state persists
                setMountedOn(mount.getUniqueId());
            }
        }
        checkMounted();
    }

    public void setMountedOn(UUID uuid) {
        this.uuid = uuid;
        checkMounted();
    }

    public void unmount() {
        if (currentMount == null)
            return;
        onDespawn();
        uuid = null;
    }
}
