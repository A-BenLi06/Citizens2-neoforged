package net.citizensnpcs.trait;

import java.util.UUID;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.util.NPCMounts;
import net.minecraft.world.entity.Entity;

/** Persists a requested NPC mount and retries when both entities can actually ride together. */
@TraitName("mounttrait")
public class MountTrait extends Trait {
    private long checkingRevision = -1;
    private Entity observedVehicle;
    private boolean retired;
    private long revision;
    private boolean suspended;
    private TransferPause transferPause;
    @Persist("mountedon")
    private UUID uuid;

    public MountTrait() {
        super("mounttrait");
    }

    public void checkMounted() {
        if (!active() || uuid == null || checkingRevision == revision || isTransferPaused())
            return;
        NPC other = CitizensAPI.getNPCRegistry().getByUniqueIdGlobal(uuid);
        if (other == null || !other.isSpawned())
            return;
        Entity rider = npc.getEntity();
        Entity previousVehicle = rider.getVehicle();
        Entity target = other.getEntity();
        long request = revision;
        long previous = checkingRevision;
        checkingRevision = request;
        try {
            NPCMounts.mount(rider, target, () -> request == revision && active() && npc.getEntity() == rider);
        } finally {
            checkingRevision = previous;
            if (request == revision && active() && npc.getEntity() == rider) {
                Entity actual = rider.getVehicle();
                // A native listener can choose a different vehicle without calling this trait. Leave that
                // change observable to run(), so it is adopted instead of overwritten on the next tick.
                observedVehicle = actual == null || actual == target || actual == previousVehicle ? actual : previousVehicle;
            }
        }
    }

    /** Returns the actual NPC vehicle, never a pending or canceled mount request. */
    public UUID getMountedOn() {
        if (!active())
            return null;
        NPC mount = NPCRegistries.lookup(npc.getEntity().getVehicle());
        return mount == null ? null : mount.getUniqueId();
    }

    private boolean active() {
        return !retired && !suspended && npc != null && npc.isSpawned()
                && npc.getTraitNullable(MountTrait.class) == this;
    }

    @Override
    public void onAttach() {
        retired = false;
        suspended = false;
    }

    @Override
    public void onDespawn() {
        suspended = true;
        long request = ++revision;
        Entity rider = npc.getEntity();
        NPCMounts.dismount(rider, () -> revision == request && npc.getEntity() == rider);
        observedVehicle = null;
    }

    @Override
    public void onRemove() {
        retired = true;
        onDespawn();
    }

    @Override
    public void onSpawn() {
        suspended = false;
        observedVehicle = null;
        checkMounted();
    }

    @Override
    public void run() {
        if (!active() || isTransferPaused())
            return;
        Entity vehicle = npc.getEntity().getVehicle();
        if (vehicle != observedVehicle) {
            observedVehicle = vehicle;
            NPC mount = NPCRegistries.lookup(vehicle);
            if (mount != null) {
                // Adopt an external change, but do not let an unchanged old vehicle overwrite a pending switch
                // or restore a request explicitly cleared while a mod refused the dismount.
                uuid = mount.getUniqueId();
                revision++;
            }
        }
        checkMounted();
    }

    public void setMountedOn(UUID uuid) {
        this.uuid = uuid;
        revision++;
        if (active()) observedVehicle = npc.getEntity().getVehicle();
        checkMounted();
    }

    public void unmount() {
        uuid = null;
        long request = ++revision;
        if (!active())
            return;
        Entity rider = npc.getEntity();
        Entity previousVehicle = rider.getVehicle();
        NPCMounts.dismount(rider, () -> request == revision && active() && npc.getEntity() == rider);
        if (request == revision && active() && npc.getEntity() == rider) {
            Entity actual = rider.getVehicle();
            observedVehicle = actual == null || actual == previousVehicle ? actual : previousVehicle;
        }
    }

    /** Holds automatic remounting while a transfer owns the actual passenger tree. New user requests supersede it. */
    public TransferPause pauseForTransfer() {
        transferPause = new TransferPause(revision);
        return transferPause;
    }

    private boolean isTransferPaused() {
        return transferPause != null && transferPause.isCurrent();
    }

    public final class TransferPause implements AutoCloseable {
        private final long request;
        private final NPC previousMount = NPCRegistries.lookup(npc.getEntity().getVehicle());

        private TransferPause(long request) { this.request = request; }

        public boolean isCurrent() {
            return transferPause == this && revision == request && active();
        }

        @Override
        public void close() {
            if (transferPause != this) return;
            if (revision == request && active()) {
                observedVehicle = npc.getEntity().getVehicle();
                NPC actual = NPCRegistries.lookup(observedVehicle);
                if (actual != null && actual != previousMount) {
                    uuid = actual.getUniqueId();
                    revision++;
                }
            }
            transferPause = null;
        }
    }
}
