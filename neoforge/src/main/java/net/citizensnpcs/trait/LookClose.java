package net.citizensnpcs.trait;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.google.common.collect.Sets;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCLookCloseChangeTargetEvent;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.trait.RotationTrait.PacketRotationSession;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Makes the NPC turn its head to follow nearby players.
 * <p>
 * The turning itself belongs to {@link RotationTrait}: this trait only decides <em>what</em> to look at and hands that
 * over, which is why the NPC eases round rather than snapping. In per-player mode each nearby player gets a rotation
 * session of their own, so everyone sees the NPC looking back at them instead of at whoever is nearest.
 * <p>
 * One deliberate omission: upstream treats players carrying a {@code vanished} metadata value as unseeable, a convention
 * set by Bukkit vanish plugins. NeoForge has no such convention, so that check is dropped rather than faked.
 */
@TraitName("lookclose")
public class LookClose extends Trait {
    @Persist("disablewhilenavigating")
    private boolean disableWhileNavigating = false;
    @Persist("enabled")
    private boolean enabled = true;
    @Persist
    private boolean enableRandomLook = false;
    private transient Predicate<Entity> entityFilter;
    @Persist
    private String filter;
    @Persist("headonly")
    private boolean headOnly;
    @Persist("linkedbody")
    private boolean linkedBody;
    private Player lookingAt;
    /** Per-viewer rotation sessions, one per nearby player, used only in per-player mode. */
    private final Map<UUID, PacketRotationSession> sessions = new HashMap<>();
    @Persist("perplayer")
    private boolean perPlayer;
    /** Rate limiter for the "nobody was targeted" diagnostic; not persisted. */
    private int debugCountdown;
    @Persist
    private int randomLookDelay = Setting.DEFAULT_RANDOM_LOOK_DELAY.asTicks();
    @Persist
    private double range = Setting.DEFAULT_LOOK_CLOSE_RANGE.asDouble();
    @Persist
    private boolean randomSwitchTargets = false;
    @Persist("realisticlooking")
    private boolean realisticLooking = Setting.DEFAULT_REALISTIC_LOOKING.asBoolean();
    @Persist("randomlookpitchrange")
    private float[] randomPitchRange = { -20, 20 };
    @Persist("randomlookyawrange")
    private float[] randomYawRange = { -20, 20 };
    private int t = randomLookDelay;

    public LookClose() {
        super("lookclose");
    }

    public void findNewTarget() {
        if (perPlayer) {
            // every nearby player gets a rotation session of their own, so each of them sees the NPC looking back at
            // them rather than at whoever happens to be nearest
            lookingAt = null;
            RotationTrait rotationTrait = npc.getOrAddTrait(RotationTrait.class);
            Set<UUID> seen = new HashSet<>();
            for (ServerPlayer player : getNearbyPlayers()) {
                PacketRotationSession session = sessions.get(player.getUUID());
                if (session == null || !session.isActive()) {
                    session = rotationTrait.createPacketSession(rotationTrait.getGlobalParameters().clone()
                            .linkedBody(linkedBody).headOnly(headOnly).uuidFilter(player.getUUID()).persist(true));
                    sessions.put(player.getUUID(), session);
                }
                session.getSession().rotateToFace(player);
                seen.add(player.getUUID());
            }
            sessions.keySet().removeIf(uuid -> {
                if (seen.contains(uuid))
                    return false;
                rotationTrait.resetPlayerToPhysicalSession(uuid);
                return true;
            });
            return;
        } else if (!sessions.isEmpty()) {
            RotationTrait rotationTrait = npc.getOrAddTrait(RotationTrait.class);
            for (UUID uuid : sessions.keySet()) {
                rotationTrait.resetPlayerToPhysicalSession(uuid);
            }
            sessions.clear();
        }
        if (lookingAt != null && !isValid(lookingAt)) {
            NPCLookCloseChangeTargetEvent event = new NPCLookCloseChangeTargetEvent(npc, lookingAt, null);
            NeoForge.EVENT_BUS.post(event);
            if (event.getNewTarget() != null && isValid(event.getNewTarget())) {
                lookingAt = event.getNewTarget();
            } else {
                lookingAt = null;
            }
        }
        Player old = lookingAt;
        if (lookingAt == null) {
            double min = Double.MAX_VALUE;
            Location npcLoc = npc.getStoredLocation();
            for (ServerPlayer player : getNearbyPlayers()) {
                double dist = player.distanceToSqr(npcLoc.getX(), npcLoc.getY(), npcLoc.getZ());
                if (dist > min)
                    continue;
                min = dist;
                lookingAt = player;
            }
        } else if (randomSwitchTargets && t <= 0) {
            List<ServerPlayer> options = getNearbyPlayers();
            if (!options.isEmpty()) {
                lookingAt = options.get(Util.getFastRandom().nextInt(options.size()));
                t = randomLookDelay;
            }
        }
        if (old != lookingAt) {
            Messaging.debug("LookClose on NPC", npc.getId(), "target", old == null ? "none" : old.getName().getString(),
                    "->", lookingAt == null ? "none" : lookingAt.getName().getString());
            NPCLookCloseChangeTargetEvent event = new NPCLookCloseChangeTargetEvent(npc, old, lookingAt);
            NeoForge.EVENT_BUS.post(event);
            if (event.getNewTarget() != null && !isValid(event.getNewTarget()))
                return;
            lookingAt = event.getNewTarget();
        }
    }

    private List<ServerPlayer> getNearbyPlayers() {
        Entity entity = npc.getEntity();
        List<ServerPlayer> online = entity.level().getServer().getPlayerList().getPlayers();
        if (online.isEmpty())
            return List.of();
        // built lazily and squared once: run() calls this on every NPC on every tick, and on all but a handful of them
        // nobody is in range, so the common answer costs no allocation at all
        List<ServerPlayer> players = null;
        double rangeSquared = range * range;
        for (ServerPlayer player : online) {
            if (player.level() != entity.level())
                continue;
            if (player.distanceToSqr(entity) > rangeSquared)
                continue;
            if (CitizensAPI.getNPCRegistry().isNPC(player))
                continue;
            if (players == null) {
                players = new ArrayList<>(4);
            }
            players.add(player);
        }
        return players == null ? List.of() : players;
    }

    private boolean isValid(Player entity) {
        if (!(entity instanceof ServerPlayer) || !entity.isAlive())
            return false;
        ServerPlayer player = (ServerPlayer) entity;
        if (entityFilter != null && !entityFilter.test(player))
            return false;
        Entity npcEntity = npc.getEntity();
        return npcEntity != null && player.level() == npcEntity.level()
                && player.distanceToSqr(npcEntity) <= range * range && !isInvisible(player);
    }

    private boolean isInvisible(ServerPlayer player) {
        return player.isSpectator() || player.hasEffect(MobEffects.INVISIBILITY) || !canSee(player);
    }

    /**
     * Whether the NPC currently has a target it can see. Used by {@link Poses} to stand down while the NPC is busy
     * looking at somebody.
     */
    public boolean canSeeTarget() {
        return lookingAt instanceof ServerPlayer player && canSee(player);
    }

    /**
     * Whether the NPC can see the target. Only does a real line-of-sight test when
     * {@link #setRealisticLooking(boolean)} is on, since ray-tracing every nearby player every tick is expensive.
     * <p>
     * Upstream also treats players carrying a {@code vanished} metadata value as unseeable, a convention set by Bukkit
     * vanish plugins. There is no such convention on NeoForge, so that check is dropped rather than faked.
     */
    private boolean canSee(ServerPlayer player) {
        if (!realisticLooking)
            return true;
        return npc.getEntity() instanceof LivingEntity living ? living.hasLineOfSight(player) : true;
    }

    private boolean isEqual(float[] array) {
        return array.length == 2 && array[0] == array[1];
    }

    private void randomLook() {
        float pitch = isEqual(randomPitchRange) ? randomPitchRange[0]
                : Util.getFastRandom().doubles(randomPitchRange[0], randomPitchRange[1]).iterator().next().floatValue();
        float yaw = isEqual(randomYawRange) ? randomYawRange[0]
                : Util.getFastRandom().doubles(randomYawRange[0], randomYawRange[1]).iterator().next().floatValue();
        npc.getOrAddTrait(RotationTrait.class).getPhysicalSession().rotateToHave(yaw, pitch);
    }

    public boolean disableWhileNavigating() {
        return disableWhileNavigating;
    }

    public void onAttach() {
    }

    @Override
    public void run() {
        if (!npc.isSpawned()) {
            lookingAt = null;
            return;
        }
        if (enableRandomLook && !npc.getNavigator().isNavigating() && lookingAt == null && t <= 0) {
            randomLook();
            t = randomLookDelay;
        }
        t--;

        if (!enabled || npc.getNavigator().isNavigating() && disableWhileNavigating()) {
            lookingAt = null;
            return;
        }
        findNewTarget();

        if (npc.getNavigator().isNavigating() || npc.getNavigator().isPaused()) {
            npc.getNavigator().setPaused(lookingAt != null);
        }
        if (lookingAt == null) {
            // the state worth reporting when an operator says "it does not look at me": somebody is close enough, and
            // yet no target was taken. Rate-limited because run() is every tick on every NPC, and gated on the debug
            // flag before the scan rather than inside Messaging.debug, so a server with debug off pays nothing for it
            if (--debugCountdown <= 0) {
                debugCountdown = 40;
                if (Messaging.isDebugging()) {
                    List<ServerPlayer> nearby = getNearbyPlayers();
                    if (!nearby.isEmpty()) {
                        Messaging.debug("LookClose on NPC", npc.getId(), "has", nearby.size(),
                                "player(s) within range", range, "but took no target; enabled=" + enabled,
                                "perPlayer=" + perPlayer, "navigating=" + npc.getNavigator().isNavigating());
                    }
                }
            }
            return;
        }

        // the actual turning is RotationTrait's job, which eases into the target a few degrees per tick instead of
        // snapping to it
        RotationTrait rot = npc.getOrAddTrait(RotationTrait.class);
        rot.getGlobalParameters().headOnly(headOnly).linkedBody(linkedBody);
        rot.getPhysicalSession().rotateToFace(lookingAt);
    }

    public void setDisableWhileNavigating(boolean disableWhileNavigating) {
        this.disableWhileNavigating = disableWhileNavigating;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setEntityFilter(Predicate<Entity> filter) {
        entityFilter = filter;
    }

    public void setHeadOnly(boolean headOnly) {
        this.headOnly = headOnly;
    }

    public void setLinkedBody(boolean linkedBody) {
        this.linkedBody = linkedBody;
    }

    public void setPerPlayer(boolean perPlayer) {
        this.perPlayer = perPlayer;
    }

    public void setRange(double range) {
        this.range = range;
    }

    /** Enables line-of-sight checks when picking a target. More computationally expensive. */
    public void setRealisticLooking(boolean realistic) {
        realisticLooking = realistic;
    }

    public boolean isRealisticLooking() {
        return realisticLooking;
    }

    public boolean isHeadOnly() {
        return headOnly;
    }

    public boolean isPerPlayer() {
        return perPlayer;
    }

    public boolean isRandomLook() {
        return enableRandomLook;
    }

    public void setRandomLook(boolean randomLook) {
        enableRandomLook = randomLook;
    }

    public double getRange() {
        return range;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean toggle() {
        enabled = !enabled;
        if (!enabled) {
            lookingAt = null;
        }
        return enabled;
    }
}
