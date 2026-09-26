package net.citizensnpcs.trait;

import java.util.UUID;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.PathStrategy;
import net.citizensnpcs.api.ai.flocking.Flocker;
import net.citizensnpcs.api.ai.flocking.RadiusNPCFlock;
import net.citizensnpcs.api.ai.flocking.SeparationBehavior;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.TeleportCause;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.npc.ai.MCTargetStrategy;
import net.minecraft.server.level.ServerPlayer;
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
    private PathStrategy navigation;
    private boolean retired;
    private boolean starting;
    private long revision;
    @Persist
    private UUID followingUUID;
    @Persist
    private double margin = -1;
    @Persist
    private boolean protect;

    public FollowTrait() {
        super("followtrait");
    }

    private void releaseNavigation() {
        PathStrategy previous = navigation;
        navigation = null;
        if (npc != null && previous != null && npc.getNavigator().getPathStrategy() == previous)
            npc.getNavigator().cancelNavigation();
    }

    /** Follows this entity, or stops following when given null. */
    public void follow(Entity follow) {
        if (follow != null && npc != null && follow == npc.getEntity())
            throw new IllegalArgumentException("An NPC cannot follow itself");
        followingUUID = follow == null ? null : follow.getUUID();
        entity = null;
        revision++;
        releaseNavigation();
    }

    public Entity getFollowing() {
        return entity;
    }

    public double getFollowingMargin() {
        return margin;
    }

    public UUID getFollowingUUID() { return followingUUID; }
    public long getRevision() { return revision; }

    /** Whether the NPC is spawned and has actually resolved its target. */
    public boolean isActive() {
        return current() && validTarget(entity);
    }

    public boolean isEnabled() {
        return followingUUID != null;
    }

    public boolean isProtecting() {
        return protect;
    }

    @Override
    public void onDespawn() {
        retired = true;
        revision++;
        entity = null;
        flock = null;
        releaseNavigation();
    }

    @Override public void onRemove() { onDespawn(); }
    @Override public void onAttach() { retired = false; }

    /**
     * Fights back for the followed entity. The shooter is targeted rather than the arrow, which is what a player would
     * expect. Native damage sources identify the causing entity separately from a direct projectile.
     */
    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!isActive() || !protect || event.getEntity() != entity)
            return;
        Entity damager = event.getSource().getEntity();
        if (damager == null) damager = event.getSource().getDirectEntity();
        if (damager instanceof Projectile projectile && projectile.getOwner() != null) {
            damager = projectile.getOwner();
        }
        if (damager == null || !damager.isAlive() || damager.isRemoved() || damager == npc.getEntity()
                || damager == entity || damager.level() != npc.getEntity().level()) return;
        startNavigation(damager, true);
    }

    @Override
    public void onSpawn() {
        retired = false;
        revision++;
        entity = null;
        releaseNavigation();
        flock = new Flocker(npc, new RadiusNPCFlock(4, 4), new SeparationBehavior(1));
    }

    @Override
    public void run() {
        if (starting) return;
        if (!current() || followingUUID == null) {
            entity = null;
            releaseNavigation();
            return;
        }
        if (!validTarget(entity)) {
            entity = resolveTarget();
            releaseNavigation();
            if (!current() || !validTarget(entity)) return;
        }
        if (npc.getEntity().level() != entity.level()) {
            releaseNavigation();
            if (!current() || !validTarget(entity)) return;
            if (Setting.FOLLOW_ACROSS_WORLDS.asBoolean()) {
                npc.teleport(Location.of(entity), TeleportCause.PLUGIN);
            }
            return;
        }
        if (!npc.getNavigator().isNavigating()) {
            startNavigation(entity, false);
        } else if (npc.getNavigator().getPathStrategy() == navigation && !npc.getNavigator().isPaused() && flock != null) {
            flock.run();
        }
    }

    public void setFollowingMargin(double margin) {
        if (!Double.isFinite(margin) || margin < 0 && margin != -1)
            throw new IllegalArgumentException("Following margin must be finite and nonnegative, or -1 for the default");
        this.margin = margin;
        revision++;
        releaseNavigation();
    }

    public void setProtecting(boolean protect) {
        this.protect = protect;
        revision++;
        releaseNavigation();
    }

    /** Original public setter spelling. */
    public void setProtect(boolean protect) { setProtecting(protect); }

    private boolean current() {
        return !retired && npc != null && npc.isSpawned() && npc.getTraitNullable(FollowTrait.class) == this;
    }

    private boolean validTarget(Entity target) {
        if (target == null || target.isRemoved() || !target.isAlive() || target == npc.getEntity()
                || followingUUID == null || !followingUUID.equals(target.getUUID())) return false;
        var owner = NPCRegistries.lookup(target);
        if (owner != null) return owner != npc && owner.isSpawned() && owner.getEntity() == target
                && owner.getOwningRegistry() != null && owner.getOwningRegistry().getByUniqueId(owner.getUniqueId()) == owner;
        if (target instanceof ServerPlayer player) return !player.hasDisconnected()
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
        return target.level().getEntity(target.getId()) == target;
    }

    private Entity resolveTarget() {
        Entity target = EntityUtil.getEntity(followingUUID);
        if (validTarget(target)) return target;
        for (var registry : CitizensAPI.getNPCRegistries()) {
            var owner = registry.getByUniqueId(followingUUID);
            if (owner != null && validTarget(owner.getEntity())) return owner.getEntity();
            // Player NPC profile UUIDs can differ from their persisted Citizens UUID. Virtual entities never enter
            // the world's UUID index, so resolve their actual native identity through their owning registry too.
            for (var candidate : registry) {
                if (followingUUID.equals(candidate.getMinecraftUniqueId()) && validTarget(candidate.getEntity()))
                    return candidate.getEntity();
            }
        }
        return null;
    }

    private void startNavigation(Entity target, boolean aggressive) {
        if (starting || !current()) return;
        long expected = revision;
        Entity handle = npc.getEntity();
        PathStrategy[] created = new PathStrategy[1];
        starting = true;
        try {
            npc.getNavigator().setTarget(params -> {
                // Configure before NavigationBeginEvent, which may replace the route or retire this trait.
                if (!aggressive && Double.isFinite(margin) && margin > 0) params.distanceMargin(margin);
                created[0] = new MCTargetStrategy(npc, target, aggressive, params);
                navigation = created[0];
                return created[0];
            });
        } finally { starting = false; }
        if ((!current() || revision != expected || npc.getEntity() != handle) && navigation == created[0])
            releaseNavigation();
    }
}
