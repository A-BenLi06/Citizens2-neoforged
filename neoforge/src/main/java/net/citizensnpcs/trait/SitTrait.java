package net.citizensnpcs.trait;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.TeleportCause;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.camel.Camel;

/**
 * Makes an NPC sit at a location.
 * <p>
 * Some entities have a real sitting pose of their own and simply need to be told to use it. Everything else has nothing
 * to sit on, so an invisible armour stand is spawned as a chair and the NPC is mounted on it — that is what puts a player
 * NPC at seat height rather than standing height.
 * <p>
 * Upstream tests for Bukkit's {@code Sittable} interface, guarded by a {@code Class.forName} probe for servers too old to
 * have it. Vanilla has no single interface for this: the pose lives on {@link TamableAnimal} for wolves, cats and parrots,
 * and on {@link Fox}, {@link Panda} and {@link Camel} as their own methods. All of them are checked, so the chair is only
 * used where it is genuinely needed.
 */
@TraitName("sittrait")
public class SitTrait extends Trait {
    private NPC chair;
    private int delay;
    @Persist
    private Location sittingAt;

    public SitTrait() {
        super("sittrait");
    }

    public boolean isSitting() {
        return sittingAt != null && sittingAt.getWorld() != null;
    }

    @Override
    public void onDespawn() {
        if (npc.isSpawned() && setNativeSittingPose(false))
            return;
        if (chair != null) {
            if (chair.isSpawned()) {
                chair.getEntity().ejectPassengers();
            }
            chair.destroy();
            chair = null;
        }
    }

    @Override
    public void onRemove() {
        onDespawn();
    }

    @Override
    public void run() {
        if (!npc.isSpawned() || !isSitting() || delay-- > 0)
            return;
        if (setNativeSittingPose(true)) {
            if (sittingAt.getWorld() != npc.getStoredLocation().getWorld()
                    || npc.getStoredLocation().distance(sittingAt) >= 0.03) {
                npc.teleport(sittingAt, TeleportCause.PLUGIN);
            }
            return;
        }
        if (chair == null) {
            chair = CitizensAPI.getTemporaryNPCRegistry().createNPC(EntityType.ARMOR_STAND, "");
            chair.getOrAddTrait(ArmorStandTrait.class).setAsHelperEntity(npc);
            if (!chair.spawn(sittingAt.clone())) {
                chair = null;
                delay = 20;
                Messaging.debug("Unable to spawn chair NPC for", npc);
                return;
            }
        }
        if (chair.isSpawned() && !chair.getEntity().getPassengers().contains(npc.getEntity())) {
            npc.getEntity().startRiding(chair.getEntity(), true);
        }
        Location chairAt = chair.getStoredLocation();
        if (chairAt != null
                && (chairAt.getWorld() != sittingAt.getWorld() || chairAt.distance(sittingAt) >= 0.03)) {
            chair.teleport(sittingAt.clone(), TeleportCause.PLUGIN);
        }
    }

    public void setSitting(Location at) {
        sittingAt = at != null ? at.clone() : null;
        if (at == null) {
            onDespawn();
        }
    }

    /**
     * @return true when the entity has a sitting pose of its own and it was applied, so no chair is needed
     */
    private boolean setNativeSittingPose(boolean sitting) {
        if (npc.getEntity() instanceof TamableAnimal tamable) {
            tamable.setOrderedToSit(sitting);
            tamable.setInSittingPose(sitting);
            return true;
        }
        if (npc.getEntity() instanceof Fox fox) {
            fox.setSitting(sitting);
            return true;
        }
        if (npc.getEntity() instanceof Panda panda) {
            panda.sit(sitting);
            return true;
        }
        if (npc.getEntity() instanceof Camel camel) {
            if (sitting && !camel.isCamelSitting()) {
                camel.sitDown();
            } else if (!sitting && camel.isCamelSitting()) {
                camel.standUp();
            }
            return true;
        }
        return false;
    }
}
