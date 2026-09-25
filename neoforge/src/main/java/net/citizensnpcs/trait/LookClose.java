package net.citizensnpcs.trait;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCLookCloseChangeTargetEvent;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.EntityFilters;
import net.citizensnpcs.npc.NPCRegistries;
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
    private boolean disableWhileNavigating = Setting.DISABLE_LOOKCLOSE_WHILE_NAVIGATING.asBoolean();
    @Persist("enabled")
    private boolean enabled = Setting.DEFAULT_LOOK_CLOSE.asBoolean();
    @Persist
    private boolean enableRandomLook = Setting.DEFAULT_RANDOM_LOOK_CLOSE.asBoolean();
    private transient Predicate<Entity> entityFilter;
    private transient Predicate<Entity> configuredFilter;
    @Persist
    private String filter;
    @Persist("headonly")
    private boolean headOnly;
    @Persist("linkedbody")
    private boolean linkedBody;
    private Player lookingAt;
    private boolean updating;
    private boolean removed;
    private long revision;
    private long pausedRevision = -1;
    private RotationTrait.RotationSession physicalSession;
    private long physicalRevision;
    /** Per-viewer rotation sessions, one per nearby player, used only in per-player mode. */
    private final Map<UUID, PacketRotationSession> sessions = new HashMap<>();
    @Persist("perplayer")
    private boolean perPlayer;
    @Persist("targetnpcs")
    private boolean targetNPCs;
    private RotationTrait.RotationSession randomSession;
    private long randomRevision;
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
    @Persist
    private float[] randomPitchRange = { 0, 0 };
    @Persist
    private float[] randomYawRange = { 0, 360 };
    private int t;

    public LookClose() {
        super("lookclose");
    }

    public void findNewTarget() {
        if (updating) return;
        Entity entity = npc == null ? null : npc.getEntity();
        long expected = revision;
        if (!current(entity, expected)) {
            clearTracking();
            return;
        }
        updating = true;
        try {
            if (perPlayer) {
                lookingAt = null;
                releasePhysical();
                releasePause();
                RotationTrait rotation = npc.getOrAddTrait(RotationTrait.class);
                Set<UUID> seen = new HashSet<>();
                for (ServerPlayer player : getNearbyPlayers()) {
                    if (NPCRegistries.lookup(player) != null) continue;
                    if (!current(entity, expected)) return;
                    PacketRotationSession session = sessions.get(player.getUUID());
                    if (session == null || !session.isActive()) {
                        session = rotation.createPacketSession(rotation.getGlobalParameters().clone()
                                .linkedBody(linkedBody).headOnly(headOnly).uuidFilter(player.getUUID()).persist(true));
                        sessions.put(player.getUUID(), session);
                    }
                    session.getSession().rotateToFace(player);
                    seen.add(player.getUUID());
                }
                if (!current(entity, expected)) return;
                for (UUID uuid : new ArrayList<>(sessions.keySet())) {
                    if (!seen.contains(uuid)) releaseSession(sessions.remove(uuid));
                }
                return;
            }
            releaseSessions();
            if (lookingAt != null && !isValid(lookingAt)) {
                Player next = redirected(lookingAt, null);
                if (!current(entity, expected)) return;
                lookingAt = next;
            }
            Player old = lookingAt, next = old;
            if (old == null) {
                double min = Double.MAX_VALUE;
                for (ServerPlayer player : getNearbyPlayers()) {
                    double distance = player.distanceToSqr(entity);
                    if (distance <= min) { min = distance; next = player; }
                }
            } else if (randomSwitchTargets && t <= 0) {
                List<ServerPlayer> options = getNearbyPlayers();
                options.remove(old);
                if (!options.isEmpty()) {
                    next = options.get(Util.getFastRandom().nextInt(options.size()));
                    t = randomLookDelay;
                }
            }
            if (!current(entity, expected)) return;
            if (old != next) {
                next = redirected(old, next);
                if (!current(entity, expected)) return;
                lookingAt = next;
            }
        } finally {
            updating = false;
        }
    }

    private Player redirected(Player old, Player proposed) {
        NPCLookCloseChangeTargetEvent event = new NPCLookCloseChangeTargetEvent(npc, old, proposed);
        NeoForge.EVENT_BUS.post(event);
        Player next = event.getNewTarget();
        // A null redirect suppresses selection; an invalid redirect cannot introduce an ineligible target.
        if (next == null) return null;
        if (isValid(next)) return next;
        return proposed != null && isValid(proposed) ? proposed : null;
    }

    private boolean current(Entity entity, long expected) {
        return !removed && npc != null && npc.isSpawned() && npc.getEntity() == entity && revision == expected
                && npc.getTraitNullable(LookClose.class) == this && enabled
                && !(disableWhileNavigating && npc.getNavigator().isNavigating());
    }

    private List<ServerPlayer> getNearbyPlayers() {
        Entity entity = npc.getEntity();
        List<ServerPlayer> players = new ArrayList<>();
        if (entity == null) return players;
        Set<ServerPlayer> candidates = new HashSet<>(entity.level().getServer().getPlayerList().getPlayers());
        if (targetNPCs && !perPlayer) {
            for (var registry : CitizensAPI.getNPCRegistries()) {
                for (var candidate : registry) {
                    if (candidate.isSpawned() && candidate.getEntity() instanceof ServerPlayer player) candidates.add(player);
                }
            }
        }
        for (ServerPlayer player : candidates) {
            if (perPlayer && NPCRegistries.lookup(player) != null) continue;
            if (isValid(player)) players.add(player);
        }
        return players;
    }

    private boolean isValid(Player entity) {
        if (!(entity instanceof ServerPlayer player) || npc == null || !npc.isSpawned()) return false;
        Entity npcEntity = npc.getEntity();
        if (!eligible(player, npcEntity)) return false;
        if (configuredFilter != null && (!configuredFilter.test(player) || !eligible(player, npcEntity))) return false;
        return entityFilter == null || entityFilter.test(player) && eligible(player, npcEntity);
    }

    private boolean eligible(ServerPlayer player, Entity npcEntity) {
        if (npcEntity == null || npcEntity.isRemoved() || !npc.isSpawned() || npc.getEntity() != npcEntity
                || player == npcEntity || !player.isAlive() || player.isRemoved() || !Double.isFinite(range) || range < 0
                || player.level() != npcEntity.level() || player.distanceToSqr(npcEntity) > range * range || isInvisible(player))
            return false;
        var owner = NPCRegistries.lookup(player);
        if (owner != null) {
            return targetNPCs && owner != npc && owner.isSpawned() && owner.getEntity() == player
                    && owner.getOwningRegistry() != null && owner.getOwningRegistry().getByUniqueId(owner.getUniqueId()) == owner;
        }
        return !player.hasDisconnected() && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private boolean isInvisible(ServerPlayer player) {
        return player.isSpectator() || player.isInvisible() || player.hasEffect(MobEffects.INVISIBILITY) || !canSee(player);
    }

    /**
     * Whether the NPC currently has a target it can see. Used by {@link Poses} to stand down while the NPC is busy
     * looking at somebody.
     */
    public boolean canSeeTarget() {
        return lookingAt != null && enabled && !removed && isValid(lookingAt);
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
        if (!validRandomRange(randomPitchRange) || !validRandomRange(randomYawRange) || randomLookDelay < 1) return;
        float pitch = isEqual(randomPitchRange) ? randomPitchRange[0]
                : Util.getFastRandom().doubles(randomPitchRange[0], randomPitchRange[1]).iterator().next().floatValue();
        float yaw = isEqual(randomYawRange) ? randomYawRange[0]
                : Util.getFastRandom().doubles(randomYawRange[0], randomYawRange[1]).iterator().next().floatValue();
        randomSession = npc.getOrAddTrait(RotationTrait.class).getPhysicalSession();
        randomSession.rotateToHave(yaw, pitch);
        randomRevision = randomSession.revision();
    }

    public boolean disableWhileNavigating() {
        return disableWhileNavigating;
    }

    @Override
    public void onAttach() {
        removed = false;
    }

    @Override
    public void onSpawn() {
        revision++;
        clearTracking();
        releaseRandom();
        t = 0;
        removed = false;
    }

    @Override
    public void onDespawn() {
        Player previous = lookingAt;
        removed = true;
        revision++;
        clearTracking();
        releaseRandom();
        if (previous != null) NeoForge.EVENT_BUS.post(new NPCLookCloseChangeTargetEvent(npc, previous, null));
    }

    @Override
    public void onRemove() {
        removed = true;
        revision++;
        clearTracking();
        releaseRandom();
    }

    private void clearTracking() {
        lookingAt = null;
        releaseSessions();
        releasePhysical();
        releasePause();
    }

    private void releaseSessions() {
        List<PacketRotationSession> previous = new ArrayList<>(sessions.values());
        sessions.clear();
        for (PacketRotationSession session : previous) releaseSession(session);
    }

    private void releaseSession(PacketRotationSession session) {
        RotationTrait rotation = npc == null ? null : npc.getTraitNullable(RotationTrait.class);
        if (rotation == null) session.end();
        else rotation.releasePacketSession(session);
    }

    private void releaseRandom() {
        RotationTrait.RotationSession previous = randomSession;
        randomSession = null;
        if (previous != null) previous.cancel(randomRevision);
    }

    private void releasePhysical() {
        RotationTrait.RotationSession previous = physicalSession;
        physicalSession = null;
        if (previous != null) previous.cancel(physicalRevision);
    }

    private void releasePause() {
        long previous = pausedRevision;
        pausedRevision = -1;
        if (previous >= 0 && npc.getNavigator().getPauseRevision() == previous && npc.getNavigator().isPaused())
            npc.getNavigator().setPaused(false);
    }

    @Override
    public void run() {
        if (updating) return;
        if (removed || !npc.isSpawned() || npc.getTraitNullable(LookClose.class) != this) {
            clearTracking();
            return;
        }
        if (enableRandomLook && !npc.getNavigator().isNavigating() && lookingAt == null && t <= 0) {
            randomLook();
            t = randomLookDelay;
        }
        t--;

        if (!enabled || npc.getNavigator().isNavigating() && disableWhileNavigating()) {
            clearTracking();
            return;
        }
        Entity entity = npc.getEntity();
        long expected = revision;
        findNewTarget();
        if (!current(entity, expected)) return;

        if (pausedRevision >= 0 && pausedRevision != npc.getNavigator().getPauseRevision()) pausedRevision = -1;
        if (lookingAt != null && npc.getNavigator().isNavigating()) {
            npc.getNavigator().setPaused(true);
            pausedRevision = npc.getNavigator().getPauseRevision();
        }
        if (lookingAt == null) {
            releasePhysical();
            releasePause();
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
        physicalSession = rot.getPhysicalSession();
        physicalSession.rotateToFace(lookingAt);
        physicalRevision = physicalSession.revision();
    }

    public void setDisableWhileNavigating(boolean disableWhileNavigating) {
        if (this.disableWhileNavigating == disableWhileNavigating) return;
        this.disableWhileNavigating = disableWhileNavigating;
        revision++;
        if (disableWhileNavigating) clearTracking();
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        revision++;
        if (!enabled) clearTracking();
    }

    public void setEntityFilter(Predicate<Entity> filter) {
        entityFilter = filter;
        revision++;
    }

    public void setHeadOnly(boolean headOnly) {
        if (this.headOnly == headOnly) return;
        this.headOnly = headOnly;
        revision++;
        releaseSessions();
    }

    public void setLinkedBody(boolean linkedBody) {
        if (this.linkedBody == linkedBody) return;
        this.linkedBody = linkedBody;
        revision++;
        releaseSessions();
    }

    public void setPerPlayer(boolean perPlayer) {
        if (this.perPlayer == perPlayer) return;
        this.perPlayer = perPlayer;
        revision++;
        clearTracking();
    }

    public void setRange(double range) {
        if (!Double.isFinite(range) || range < 0) throw new IllegalArgumentException("Look range must be finite and nonnegative");
        this.range = range;
        revision++;
    }

    /** Enables line-of-sight checks when picking a target. More computationally expensive. */
    public void setRealisticLooking(boolean realistic) {
        realisticLooking = realistic;
        revision++;
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
        if (!randomLook) releaseRandom();
    }

    public Player getTarget() { return lookingAt; }
    public boolean isLinkedBody() { return linkedBody; }
    public boolean targetNPCs() { return targetNPCs; }
    public boolean isRandomlySwitchingTargets() { return randomSwitchTargets; }
    public int getRandomLookDelay() { return randomLookDelay; }
    public float[] getRandomLookPitchRange() { return randomPitchRange.clone(); }
    public float[] getRandomLookYawRange() { return randomYawRange.clone(); }
    public String getFilter() { return filter; }

    public void setTargetNPCs(boolean target) {
        if (targetNPCs == target) return;
        targetNPCs = target;
        revision++;
        clearTracking();
    }

    public void setFilter(String filter) {
        Predicate<Entity> compiled = EntityFilters.parse(filter);
        this.filter = filter;
        configuredFilter = compiled;
        revision++;
        clearTracking();
    }

    public void setRandomLookDelay(int delay) {
        if (delay < 1) throw new IllegalArgumentException("Random look delay must be positive");
        randomLookDelay = delay;
        t = delay;
    }

    public void setRandomlySwitchTargets(boolean random) { randomSwitchTargets = random; }

    public void setRandomLookPitchRange(float min, float max) {
        randomPitchRange = checkedRandomRange(min, max);
    }

    public void setRandomLookYawRange(float min, float max) {
        randomYawRange = checkedRandomRange(min, max);
    }

    private static float[] checkedRandomRange(float min, float max) {
        float[] range = { min, max };
        if (!validRandomRange(range)) throw new IllegalArgumentException("Random angles must be finite and ordered");
        return range;
    }

    private static boolean validRandomRange(float[] range) {
        return range != null && range.length == 2 && Float.isFinite(range[0]) && Float.isFinite(range[1]) && range[0] <= range[1];
    }

    @Override public void load(DataKey key) throws NPCLoadException {
        revision++;
        clearTracking();
        releaseRandom();
        if (!key.keyExists("randomPitchRange") && key.keyExists("randomlookpitchrange"))
            randomPitchRange = readRange(key.getRelative("randomlookpitchrange"));
        if (!key.keyExists("randomYawRange") && key.keyExists("randomlookyawrange"))
            randomYawRange = readRange(key.getRelative("randomlookyawrange"));
        // Keep invalid saved text, but never turn a failed filter into an unrestricted target scan.
        configuredFilter = entity -> false;
        try { configuredFilter = EntityFilters.parse(filter); }
        catch (IllegalArgumentException failure) { throw new NPCLoadException(failure.getMessage()); }
        if (!validRandomRange(randomPitchRange) || !validRandomRange(randomYawRange) || randomLookDelay < 1)
            throw new NPCLoadException("Invalid saved random look range or delay");
        t = 0;
    }

    private static float[] readRange(DataKey key) {
        List<Float> values = new ArrayList<>();
        for (DataKey value : key.getIntegerSubKeys()) values.add((float) value.getDouble(""));
        float[] range = new float[values.size()];
        for (int i = 0; i < range.length; i++) range[i] = values.get(i);
        return range;
    }

    @Override public void save(DataKey key) {
        key.removeKey("randomlookpitchrange");
        key.removeKey("randomlookyawrange");
    }

    public double getRange() {
        return range;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean toggle() {
        setEnabled(!enabled);
        return enabled;
    }
}
