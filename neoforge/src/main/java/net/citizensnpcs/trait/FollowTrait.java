package net.citizensnpcs.trait;

import java.util.UUID;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.ai.flocking.Flocker;
import net.citizensnpcs.api.ai.flocking.RadiusNPCFlock;
import net.citizensnpcs.api.ai.flocking.SeparationBehavior;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.TeleportCause;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Makes an NPC follow an entity, optionally fighting back on its behalf.
 * <p>
 * Several NPCs following the same target would otherwise pile into one another, so a flocker nudges them apart while they
 * are already navigating — the same reason upstream uses one.
 */
@TraitName("followtrait")
public class FollowTrait extends Trait {
    private Entity entity;
    private Flocker flock;
    @Persist
    private UUID followingUUID;
    @Persist
    private double margin = -1;
    @Persist
    private boolean protect;

    public FollowTrait() {
        super("followtrait");
    }

    private void cancelNavigationIfActive() {
        if (npc.getNavigator().isNavigating() && entity != null && npc.getNavigator().getEntityTarget() != null
                && entity == npc.getNavigator().getEntityTarget().getTarget()) {
            npc.getNavigator().cancelNavigation();
        }
    }

    /** Follows this entity, or stops following when given null. */
    public void follow(Entity follow) {
        cancelNavigationIfActive();
        followingUUID = follow == null ? null : follow.getUUID();
        entity = null;
    }

    public Entity getFollowing() {
        return entity;
    }

    public double getFollowingMargin() {
        return margin;
    }

    /** Whether the NPC is spawned and has actually resolved its target. */
    public boolean isActive() {
        return npc.isSpawned() && entity != null;
    }

    public boolean isEnabled() {
        return followingUUID != null;
    }

    public boolean isProtecting() {
        return protect;
    }

    @Override
    public void onDespawn() {
        flock = null;
    }

    /**
     * Fights back for the followed entity. The shooter is targeted rather than the arrow, which is what a player would
     * expect and what upstream does.
     */
    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!isActive() || !protect || event.getEntity() != entity)
            return;
        Entity damager = event.getSource().getEntity();
        if (damager instanceof Projectile projectile && projectile.getOwner() != null) {
            damager = projectile.getOwner();
        }
        if (damager != null) {
            npc.getNavigator().setTarget(damager, true);
        }
    }

    @Override
    public void onSpawn() {
        flock = new Flocker(npc, new RadiusNPCFlock(4, 4), new SeparationBehavior(1));
    }

    @Override
    public void run() {
        if (entity == null || !entity.isAlive()) {
            if (followingUUID == null)
                return;
            entity = EntityUtil.getEntity(followingUUID);
            if (entity == null)
                return;
        }
        if (!isActive())
            return;
        if (npc.getEntity().level() != entity.level()) {
            if (Setting.FOLLOW_ACROSS_WORLDS.asBoolean()) {
                npc.teleport(Location.of(entity), TeleportCause.PLUGIN);
            }
            return;
        }
        if (!npc.getNavigator().isNavigating()) {
            npc.getNavigator().setTarget(entity, false);
            if (margin > 0) {
                npc.getNavigator().getLocalParameters().distanceMargin(margin);
            }
        } else if (flock != null) {
            flock.run();
        }
    }

    public void setFollowingMargin(double margin) {
        this.margin = margin;
    }

    public void setProtecting(boolean protect) {
        this.protect = protect;
    }
}
