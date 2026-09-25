package net.citizensnpcs.trait;

import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.Persistable;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.util.Util;
import net.citizensnpcs.util.NPCVisibility;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

/**
 * Turns an NPC's head and body towards something, a few degrees per tick rather than all at once.
 * <p>
 * Two kinds of session share the same interpolation code:
 * <ul>
 * <li>The <b>physical</b> session moves the real entity, so every player sees the same rotation. This is what
 * {@link LookClose} and {@link Poses} drive.
 * <li>A <b>packet</b> session moves the NPC only for chosen viewers, by sending them rotation packets of their own.
 * Native pairing and broadcasts are projected onto the selected session for each admitted viewer.
 * </ul>
 */
@TraitName("rotationtrait")
public class RotationTrait extends Trait {
    private final RotationParams globalParameters = new RotationParams();
    private final RotationSession globalSession = new RotationSession(globalParameters);
    private final CopyOnWriteArrayList<PacketRotationSession> packetSessions = new CopyOnWriteArrayList<>();
    private final Map<UUID, PacketRotationSession> packetSessionsByUUID = new ConcurrentHashMap<>();

    private final Map<ServerPlayer, PacketRotation> delivered = new IdentityHashMap<>();
    private final Set<ServerPlayer> resolving = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private long packetRevision;

    public RotationTrait() {
        super("rotationtrait");
    }

    public void clearPacketSessions() {
        if (!hasPacketSessions() && delivered.isEmpty()) return;
        Entity entity = npc != null && npc.isSpawned() ? npc.getEntity() : null;
        List<ServerPlayer> viewers = entity == null ? List.of() : NPCVisibility.viewers(entity).stream()
                .filter(viewer -> delivered.containsKey(viewer) || getPacketSession(viewer) != null).toList();
        packetRevision++;
        for (PacketRotationSession session : packetSessions) session.ended = true;
        for (PacketRotationSession session : packetSessionsByUUID.values()) session.ended = true;
        packetSessions.clear();
        packetSessionsByUUID.clear();
        delivered.clear();
        for (ServerPlayer viewer : viewers) restore(entity, viewer);
    }

    @Override public void onDespawn() {
        clearPacketSessions();
        globalSession.cancel(globalSession.revision());
    }

    @Override public void onRemove() { onDespawn(); }

    /**
     * Starts a rotation that only the viewers the parameters accept will see.
     * <p>
     * A session with a UUID filter is keyed by those UUIDs, so asking for the same player twice replaces the old session
     * rather than stacking a second one on top of it.
     */
    public PacketRotationSession createPacketSession(RotationParams params) {
        if (params.filter == null && params.uuidFilter == null)
            throw new IllegalArgumentException("a packet session needs a viewer filter, or it would affect nobody");
        PacketRotationSession session = new PacketRotationSession(this, new RotationSession(params));
        packetRevision++;
        if (params.uuidFilter != null) {
            for (UUID uuid : params.uuidFilter) {
                PacketRotationSession previous = packetSessionsByUUID.put(uuid, session);
                if (previous != null && previous != session && !packetSessionsByUUID.containsValue(previous)) {
                    previous.end();
                }
            }
        } else {
            packetSessions.add(session);
        }
        return session;
    }

    public RotationParams getGlobalParameters() {
        return globalParameters;
    }

    /**
     * @return the packet session covering this player, or null when it is seeing the real rotation
     */
    public PacketRotationSession getPacketSession(ServerPlayer player) {
        // User predicates may change ownership or recurse. Never return a stale decision from such a callback.
        if (!resolving.add(player)) return null;
        long revision = packetRevision;
        try {
            PacketRotationSession byUUID = packetSessionsByUUID.get(player.getUUID());
            if (byUUID != null && byUUID.isActive() && byUUID.accepts(player))
                return revision == packetRevision && byUUID.isActive() ? byUUID : null;
            for (PacketRotationSession session : packetSessions) {
                if (session.isActive() && session.accepts(player))
                    return revision == packetRevision && session.isActive() ? session : null;
                if (revision != packetRevision) return null;
            }
            return null;
        } finally { resolving.remove(player); }
    }

    public boolean hasPacketSessions() { return !packetSessions.isEmpty() || !packetSessionsByUUID.isEmpty(); }

    /** Quantised state shared by native packet projection and supplemental delivery. */
    public record PacketRotation(PacketRotationSession owner, byte yaw, byte pitch, byte headYaw) { }

    public PacketRotation getPacketRotation(ServerPlayer viewer) {
        Entity entity = npc != null && npc.isSpawned() ? npc.getEntity() : null;
        if (entity == null) return null;
        PacketRotationSession session = getPacketSession(viewer);
        if (session == null || npc.getEntity() != entity || entity.isRemoved()
                || npc.getTraitNullable(RotationTrait.class) != this) return null;
        session.initialize(entity);
        return new PacketRotation(session, degreesToByte(session.getBodyYaw()), degreesToByte(session.getPitch()),
                degreesToByte(session.getHeadYaw()));
    }

    /** Pairing contains all three angles; it is already the first delivery to this client entity. */
    public void recordPairing(ServerPlayer viewer, PacketRotation rotation) { delivered.put(viewer, rotation); }

    public void forgetViewer(ServerPlayer viewer) { delivered.remove(viewer); }

    /**
     * @return the session that moves the real entity
     */
    public RotationSession getPhysicalSession() {
        return globalSession;
    }

    /** Remove only this UUID's override; other viewers of a shared session retain their ownership. */
    public void resetPlayerToPhysicalSession(UUID uuid) {
        PacketRotationSession removed = packetSessionsByUUID.remove(uuid);
        if (removed == null) return;
        packetRevision++;
        if (!packetSessionsByUUID.containsValue(removed)) removed.ended = true;
        if (npc != null && npc.isSpawned()) {
            for (ServerPlayer viewer : NPCVisibility.viewers(npc.getEntity())) {
                if (viewer.getUUID().equals(uuid)) deliver(npc.getEntity(), viewer, true);
            }
        }
    }

    /** Release an exact owner, immediately showing the next session or the native entity angles. */
    public void releasePacketSession(PacketRotationSession session) {
        if (session == null || session.owner != this) return;
        Entity entity = npc != null && npc.isSpawned() ? npc.getEntity() : null;
        List<ServerPlayer> viewers = entity == null ? List.of() : NPCVisibility.viewers(entity).stream()
                .filter(viewer -> getPacketSession(viewer) == session).toList();
        session.ended = true;
        packetRevision++;
        packetSessions.remove(session);
        packetSessionsByUUID.values().removeIf(current -> current == session);
        for (ServerPlayer viewer : viewers) deliver(entity, viewer, true);
    }

    private void restore(Entity entity, ServerPlayer viewer) {
        if (!NPCVisibility.isTracked(entity, viewer)) return;
        viewer.connection.send(new ClientboundMoveEntityPacket.Rot(entity.getId(), degreesToByte(entity.getYRot()),
                degreesToByte(entity.getXRot()), entity.onGround()));
        viewer.connection.send(new ClientboundRotateHeadPacket(entity, degreesToByte(entity.getYHeadRot())));
    }

    private void deliver(Entity entity, ServerPlayer viewer, boolean force) {
        PacketRotation rotation = getPacketRotation(viewer);
        if (npc.getEntity() != entity || !NPCVisibility.isTracked(entity, viewer)) {
            delivered.remove(viewer);
            return;
        }
        PacketRotation previous = delivered.get(viewer);
        if (rotation == null) {
            delivered.remove(viewer);
            if (force || previous != null) restore(entity, viewer);
        } else if (force || !rotation.equals(previous)) {
            viewer.connection.send(new ClientboundMoveEntityPacket.Rot(entity.getId(), rotation.yaw(), rotation.pitch(),
                    entity.onGround()));
            viewer.connection.send(new ClientboundRotateHeadPacket(entity, rotation.headYaw()));
            delivered.put(viewer, rotation);
        }
    }

    @Override
    public void run() {
        if (npc == null || !npc.isSpawned()) return;
        Entity entity = npc.getEntity();
        if (npc.data().get(NPC.Metadata.RESET_PITCH_ON_TICK, false)) entity.setXRot(0);
        Set<PacketRotationSession> sessions = new HashSet<>(packetSessions);
        sessions.addAll(packetSessionsByUUID.values());
        for (PacketRotationSession session : sessions) {
            session.run(entity);
            if (npc.getEntity() != entity || entity.isRemoved()) return;
        }
        packetSessions.removeIf(s -> !s.isActive());
        packetSessionsByUUID.values().removeIf(s -> !s.isActive());
        // While navigating, the navigator owns the physical rotation.
        if (!npc.getNavigator().isNavigating()) globalSession.run(new EntityRotation(entity));
        if (!hasPacketSessions() && delivered.isEmpty()) return;
        List<ServerPlayer> viewers = NPCVisibility.viewers(entity);
        delivered.keySet().removeIf(viewer -> !viewers.contains(viewer));
        for (ServerPlayer viewer : viewers) deliver(entity, viewer, false);
    }

    public static byte degreesToByte(float degrees) {
        return (byte) net.minecraft.util.Mth.floor(degrees * 256.0F / 360.0F);
    }

    private Location getEyeLocation() {
        Entity entity = npc.getEntity();
        Location at = Location.of(entity);
        at.setY(at.getY() + entity.getEyeHeight());
        return at;
    }

    /** The rotation of the real entity: reading it back means a session picks up wherever the entity is now. */
    private static class EntityRotation extends RotationTriple {
        protected final Entity entity;

        EntityRotation(Entity entity) {
            super(bodyYawOf(entity), entity.getYHeadRot(), entity.getXRot());
            this.entity = entity;
        }

        @Override
        void apply(Function<ServerPlayer, Boolean> filter) {
            if (entity instanceof LivingEntity living) {
                // the previous-tick value is overwritten too, or the client interpolates the body round from where it
                // used to be and the turn looks like a lurch
                living.yBodyRotO = bodyYaw;
                living.setYBodyRot(bodyYaw);
                living.setYHeadRot(Util.clamp(headYaw));
            }
            entity.setYRot(bodyYaw);
            entity.setXRot(pitch);
        }

        /**
         * Only living entities keep a body yaw separate from their rotation; for anything else the two are the same value.
         */
        static float bodyYawOf(Entity entity) {
            return entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot();
        }
    }

    /** A rotation shown to some viewers only, pushed to them as packets. */
    public static class PacketRotationSession {
        private volatile boolean ended;
        private boolean finishing;
        private final RotationTrait owner;
        private final RotationSession session;
        private volatile PacketRotationTriple triple;

        PacketRotationSession(RotationTrait owner, RotationSession session) {
            this.owner = owner;
            this.session = session;
        }

        public boolean accepts(ServerPlayer player) {
            return session.params.accepts(player);
        }

        public void end() {
            owner.releasePacketSession(this);
        }

        public float getBodyYaw() {
            return triple == null ? 0 : triple.bodyYaw;
        }

        public float getHeadYaw() {
            return triple == null ? 0 : triple.headYaw;
        }

        public float getPitch() {
            return triple == null ? 0 : triple.pitch;
        }

        public RotationSession getSession() {
            return session;
        }

        public boolean isActive() {
            return !ended && (finishing || session.isActive());
        }

        /** Compatibility notification; delivery is now tracked per viewer, not globally per session. */
        public void onPacketOverwritten() { }

        void initialize(Entity entity) {
            if (triple == null) triple = new PacketRotationTriple(entity);
        }

        void run(Entity entity) {
            if (ended) return;
            if (finishing && !session.isActive()) { ended = true; return; }
            finishing = false;
            if (!session.isActive()) return;
            initialize(entity);
            session.run(triple);
            // Keep the final frame through this tick's native broadcasts; fall back on the following tick.
            finishing = !session.isActive();
        }
    }

    /** Interpolation state only. The trait delivers the winning state once per viewer. */
    private static class PacketRotationTriple extends EntityRotation {
        PacketRotationTriple(Entity entity) {
            super(entity);
            // The client starts from entity yaw; a living entity's body-animation yaw is a different native field.
            bodyYaw = entity.getYRot();
        }
        @Override void apply(Function<ServerPlayer, Boolean> filter) { }
    }

    /** How a session should turn: how fast, whether the body follows, and which viewers it applies to. */
    public static class RotationParams implements Persistable, Cloneable {
        private Function<ServerPlayer, Boolean> filter;
        @Persist
        private boolean headOnly;
        @Persist
        private boolean immediate;
        @Persist
        private boolean linkedBody;
        @Persist
        private boolean lockPitch;
        @Persist
        private float maxPitchPerTick = 10;
        @Persist
        private float maxYawPerTick = 40;
        private volatile boolean persist = false;
        private float[] pitchRange = { -180, 180 };
        private Collection<UUID> uuidFilter;
        private float[] yawRange = { -180, 180 };

        public boolean accepts(ServerPlayer player) {
            return filter == null || Boolean.TRUE.equals(filter.apply(player));
        }

        @Override
        public RotationParams clone() {
            try {
                RotationParams copy = (RotationParams) super.clone();
                copy.pitchRange = pitchRange.clone();
                copy.yawRange = yawRange.clone();
                return copy;
            } catch (CloneNotSupportedException e) {
                return null;
            }
        }

        public String describe() {
            if (immediate)
                return "immediately moves to target rotation";
            return "rotates" + (headOnly ? " head " : "") + "to target moving [[" + maxPitchPerTick
                    + "]] degrees in pitch and [[" + maxYawPerTick + "]] degrees in yaw per tick";
        }

        public RotationParams filter(Function<ServerPlayer, Boolean> filter) {
            this.filter = filter;
            return this;
        }

        public RotationParams headOnly(boolean headOnly) {
            this.headOnly = headOnly;
            return this;
        }

        public RotationParams immediate(boolean immediate) {
            this.immediate = immediate;
            return this;
        }

        public RotationParams linkedBody(boolean linked) {
            linkedBody = linked;
            return this;
        }

        @Override
        public void load(DataKey key) {
            if (key.keyExists("yawRange")) {
                yawRange = parseRange(key.getString("yawRange"), yawRange);
            }
            if (key.keyExists("pitchRange")) {
                pitchRange = parseRange(key.getString("pitchRange"), pitchRange);
            }
        }

        public RotationParams lockPitch(boolean lockPitch) {
            this.lockPitch = lockPitch;
            return this;
        }

        public RotationParams maxPitchPerTick(float val) {
            maxPitchPerTick = val;
            return this;
        }

        public RotationParams maxYawPerTick(float val) {
            maxYawPerTick = val;
            return this;
        }

        /** A persistent session stays active with nothing to do, rather than ending when it reaches its target. */
        public RotationParams persist(boolean persist) {
            this.persist = persist;
            return this;
        }

        public RotationParams pitchRange(float[] val) {
            pitchRange = val;
            return this;
        }

        public float rotateHeadYawTowards(float yaw, float targetYaw) {
            return Util.clamp(rotateTowards(yaw, targetYaw, maxYawPerTick), yawRange[0], yawRange[1], 360);
        }

        public float rotatePitchTowards(float pitch, float targetPitch) {
            return Util.clamp(rotateTowards(pitch, targetPitch, maxPitchPerTick), pitchRange[0], pitchRange[1], 360);
        }

        private float rotateTowards(float current, float target, float maxRotPerTick) {
            float diff = Util.clamp(target - current);
            return current + Math.max(-maxRotPerTick, Math.min(maxRotPerTick, diff));
        }

        @Override
        public void save(DataKey key) {
            writeRange(key, "pitchRange", pitchRange);
            writeRange(key, "yawRange", yawRange);
        }

        public RotationParams uuidFilter(Collection<UUID> uuids) {
            uuidFilter = uuids;
            filter = p -> uuids.contains(p.getUUID());
            return this;
        }

        public RotationParams uuidFilter(UUID... uuids) {
            return uuidFilter(new HashSet<>(Set.of(uuids)));
        }

        public RotationParams yawRange(float[] val) {
            yawRange = val;
            return this;
        }

        private static float[] parseRange(String raw, float[] fallback) {
            String[] parts = raw.split(",");
            if (parts.length != 2)
                return fallback;
            try {
                return new float[] { Float.parseFloat(parts[0]), Float.parseFloat(parts[1]) };
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }

        private static void writeRange(DataKey key, String name, float[] range) {
            if (range[0] != -180 || range[1] != 180) {
                key.setString(name, range[0] + "," + range[1]);
            } else {
                key.removeKey(name);
            }
        }
    }

    /** One rotation in progress: where it is aiming, and how far along it is. */
    public class RotationSession {
        private final RotationParams params;
        private boolean cancelled;
        private long revision;
        private volatile int t = -1;
        private Supplier<Float> targetPitch = () -> 0F;
        private Supplier<Float> targetYaw = () -> 0F;

        RotationSession(RotationParams params) {
            this.params = params;
        }

        long revision() { return revision; }

        /** A former caller cannot cancel a rotation subsequently issued by another trait. */
        void cancel(long expected) {
            if (revision == expected) { t = -1; cancelled = true; }
        }

        public float getTargetPitch() {
            return targetPitch.get();
        }

        /**
         * Two entity types are drawn facing away from their yaw, so their target is offset to match what a player sees.
         */
        public float getTargetYaw() {
            EntityType<?> type = npc.getEntity().getType();
            if (type == EntityType.PHANTOM)
                return Util.clamp(targetYaw.get() + 45);
            if (type == EntityType.ENDER_DRAGON)
                return Util.clamp(targetYaw.get() - 180);
            return targetYaw.get();
        }

        public boolean isActive() {
            return !cancelled && (params.persist || t >= 0);
        }

        /** Turns to face an entity — its eyes, if it has any. */
        public void rotateToFace(Entity target) {
            Location at = Location.of(target);
            if (target instanceof LivingEntity living) {
                at.setY(at.getY() + living.getEyeHeight());
            }
            rotateToFace(at);
        }

        /**
         * Turns to face a point. The target is a supplier rather than a fixed angle so that the NPC keeps facing the same
         * point as it moves, which is what upstream does too.
         */
        public void rotateToFace(Location target) {
            cancelled = false;
            revision++;
            t = 0;
            targetPitch = params.lockPitch ? () -> getEyeLocation().getPitch() : () -> {
                Location from = getEyeLocation();
                double dx = target.getX() - from.getX();
                double dy = target.getY() - from.getY();
                double dz = target.getZ() - from.getZ();
                double flat = Math.sqrt(dx * dx + dz * dz);
                return (float) -Math.toDegrees(Math.atan2(dy, flat));
            };
            targetYaw = () -> {
                Location from = getEyeLocation();
                return (float) Math.toDegrees(Math.atan2(target.getZ() - from.getZ(), target.getX() - from.getX()))
                        - 90.0F;
            };
        }

        /** Turns to a fixed yaw and pitch. */
        public void rotateToHave(float yaw, float pitch) {
            cancelled = false;
            revision++;
            t = 0;
            targetYaw = () -> yaw;
            targetPitch = params.lockPitch ? () -> getEyeLocation().getPitch() : () -> pitch;
        }

        void run(RotationTriple rot) {
            if (!isActive())
                return;
            rot.headYaw = params.immediate ? getTargetYaw()
                    : Util.clamp(params.rotateHeadYawTowards(rot.headYaw, getTargetYaw()));
            if (!params.headOnly) {
                keepBodyWithinHeadArc(rot);
            }
            rot.pitch = params.immediate ? getTargetPitch() : params.rotatePitchTowards(rot.pitch, getTargetPitch());
            t++;
            if (params.linkedBody) {
                rot.bodyYaw = rot.headYaw;
            }
            if (Math.abs(rot.pitch - getTargetPitch()) + Math.abs(rot.headYaw - getTargetYaw()) < 0.1) {
                t = -1;
                if (!params.headOnly) {
                    rot.bodyYaw = rot.headYaw;
                }
            }
            rot.apply(params.filter);
        }

        /**
         * Drags the body along only once the head has turned more than 20 degrees away from it, so an NPC glancing
         * sideways does not swivel its whole body. The wrapping is why this is more than a clamp: the arc can straddle
         * ±180.
         */
        private void keepBodyWithinHeadArc(RotationTriple rot) {
            float lo = Util.clamp(rot.headYaw - 20);
            float hi = Util.clamp(rot.headYaw + 20);
            if (hi < 0 && lo > 0) {
                float swap = hi;
                hi = lo;
                lo = swap;
            }
            float body = Util.clamp(rot.bodyYaw);
            boolean contained = hi > 0 && lo < 0 ? body >= hi || body <= lo : body >= lo && body <= hi;
            if (!contained) {
                rot.bodyYaw = Math.abs(body - lo) > Math.abs(body - hi) ? hi : lo;
            }
        }
    }

    private static abstract class RotationTriple {
        float bodyYaw, headYaw, pitch;

        RotationTriple(float bodyYaw, float headYaw, float pitch) {
            this.bodyYaw = bodyYaw;
            this.headYaw = headYaw;
            this.pitch = pitch;
        }

        abstract void apply(Function<ServerPlayer, Boolean> filter);
    }
}
