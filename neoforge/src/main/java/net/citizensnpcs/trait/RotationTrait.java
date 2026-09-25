package net.citizensnpcs.trait;

import java.util.Collection;
import java.util.HashSet;
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
import net.citizensnpcs.api.util.EntityUtil;
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
 * Vanilla's public rotation packets are enough to send these, so no packet library is needed — but vanilla's <em>own</em>
 * rotation broadcasts are not suppressed, so a viewer under a packet session can see one frame of the real rotation when
 * the entity itself turns. Upstream avoids that by rewriting outgoing packets through packetevents;
 * {@link PacketRotationSession#onPacketOverwritten()} is the hook that will restore it when packet rewriting lands with
 * {@code PacketNPC}.
 * </ul>
 */
@TraitName("rotationtrait")
public class RotationTrait extends Trait {
    private final RotationParams globalParameters = new RotationParams();
    private final RotationSession globalSession = new RotationSession(globalParameters);
    private CopyOnWriteArrayList<PacketRotationSession> packetSessions = new CopyOnWriteArrayList<>();
    private final Map<UUID, PacketRotationSession> packetSessionsByUUID = new ConcurrentHashMap<>();

    public RotationTrait() {
        super("rotationtrait");
    }

    public void clearPacketSessions() {
        for (PacketRotationSession session : packetSessions) {
            session.end();
        }
        for (PacketRotationSession session : packetSessionsByUUID.values()) {
            session.end();
        }
        packetSessions.clear();
        packetSessionsByUUID.clear();
    }

    /**
     * Starts a rotation that only the viewers the parameters accept will see.
     * <p>
     * A session with a UUID filter is keyed by those UUIDs, so asking for the same player twice replaces the old session
     * rather than stacking a second one on top of it.
     */
    public PacketRotationSession createPacketSession(RotationParams params) {
        if (params.filter == null && params.uuidFilter == null)
            throw new IllegalArgumentException("a packet session needs a viewer filter, or it would affect nobody");
        PacketRotationSession session = new PacketRotationSession(new RotationSession(params));
        if (params.uuidFilter != null) {
            for (UUID uuid : params.uuidFilter) {
                PacketRotationSession previous = packetSessionsByUUID.put(uuid, session);
                if (previous != null && previous != session) {
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
        PacketRotationSession byUUID = packetSessionsByUUID.get(player.getUUID());
        if (byUUID != null && byUUID.isActive())
            return byUUID;
        for (PacketRotationSession session : packetSessions) {
            if (session.isActive() && session.accepts(player))
                return session;
        }
        return null;
    }

    /**
     * @return the session that moves the real entity
     */
    public RotationSession getPhysicalSession() {
        return globalSession;
    }

    /** Drops any packet session for this player, so it goes back to seeing the real rotation. */
    public void resetPlayerToPhysicalSession(UUID uuid) {
        PacketRotationSession session = packetSessionsByUUID.remove(uuid);
        if (session != null) {
            session.end();
        }
    }

    /** Release this exact owner's session and restore native angles for its current viewers. */
    public void releasePacketSession(PacketRotationSession session) {
        Entity entity = npc.isSpawned() ? npc.getEntity() : null;
        var viewers = entity == null ? java.util.List.<ServerPlayer>of() : NPCVisibility.viewers(entity).stream()
                .filter(viewer -> getPacketSession(viewer) == session).toList();
        session.end();
        packetSessions.remove(session);
        packetSessionsByUUID.values().removeIf(current -> current == session);
        for (ServerPlayer viewer : viewers) {
            // A later UUID or general session owns this view; do not reset it when an earlier owner exits.
            if (getPacketSession(viewer) != null || !NPCVisibility.isTracked(entity, viewer)) continue;
            viewer.connection.send(new ClientboundMoveEntityPacket.Rot(entity.getId(),
                    PacketRotationTriple.degreesToByte(entity.getYRot()),
                    PacketRotationTriple.degreesToByte(entity.getXRot()), entity.onGround()));
            viewer.connection.send(new ClientboundRotateHeadPacket(entity,
                    PacketRotationTriple.degreesToByte(entity.getYHeadRot())));
        }
    }

    @Override
    public void run() {
        if (!npc.isSpawned())
            return;
        if (npc.data().get(NPC.Metadata.RESET_PITCH_ON_TICK, false)) {
            npc.getEntity().setXRot(0);
        }
        Set<PacketRotationSession> ran = new HashSet<>();
        for (PacketRotationSession session : packetSessions) {
            if (ran.add(session)) {
                session.run(npc.getEntity());
            }
        }
        for (PacketRotationSession session : packetSessionsByUUID.values()) {
            if (ran.add(session)) {
                session.run(npc.getEntity());
            }
        }
        packetSessions.removeIf(s -> !s.isActive());
        packetSessionsByUUID.values().removeIf(s -> !s.isActive());
        // while navigating, the navigator owns the rotation - fighting it would make the NPC walk sideways
        if (npc.getNavigator().isNavigating())
            return;
        globalSession.run(new EntityRotation(npc.getEntity()));
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
        private final RotationSession session;
        private volatile PacketRotationTriple triple;

        PacketRotationSession(RotationSession session) {
            this.session = session;
        }

        public boolean accepts(ServerPlayer player) {
            return session.params.accepts(player);
        }

        public void end() {
            ended = true;
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
            return !ended && session.isActive();
        }

        /**
         * Tells the session that vanilla has just sent its own rotation to the viewers, so the next tick must resend even
         * if the rotation has barely moved. Nothing calls this yet — outgoing packet rewriting arrives with
         * {@code PacketNPC}.
         */
        public void onPacketOverwritten() {
            if (triple != null) {
                triple.record();
            }
        }

        void run(Entity entity) {
            if (triple == null) {
                triple = new PacketRotationTriple(entity);
            }
            session.run(triple);
            if (!session.isActive()) {
                triple = null;
            }
        }
    }

    /**
     * A rotation that is sent rather than applied. Only resent once it has drifted by more than a degree in total, since
     * the packets are byte-quantised anyway and a smaller change would not be visible.
     */
    private static class PacketRotationTriple extends EntityRotation {
        private volatile float lastBodyYaw;
        private volatile float lastHeadYaw;
        private volatile float lastPitch;

        PacketRotationTriple(Entity entity) {
            super(entity);
        }

        @Override
        void apply(Function<ServerPlayer, Boolean> filter) {
            if (Math.abs(lastBodyYaw - bodyYaw) + Math.abs(lastHeadYaw - headYaw) + Math.abs(pitch - lastPitch) <= 1)
                return;
            ClientboundMoveEntityPacket.Rot rot = new ClientboundMoveEntityPacket.Rot(entity.getId(),
                    degreesToByte(bodyYaw), degreesToByte(pitch), entity.onGround());
            ClientboundRotateHeadPacket head = new ClientboundRotateHeadPacket(entity, degreesToByte(headYaw));
            for (ServerPlayer viewer : EntityUtil.getNearbyVisiblePlayers(entity, VIEW_RANGE)) {
                if (filter != null && !Boolean.TRUE.equals(filter.apply(viewer))) {
                    continue;
                }
                viewer.connection.send(rot);
                viewer.connection.send(head);
            }
            record();
        }

        void record() {
            lastBodyYaw = bodyYaw;
            lastHeadYaw = headYaw;
            lastPitch = pitch;
        }

        private static byte degreesToByte(float degrees) {
            return (byte) net.minecraft.util.Mth.floor(degrees * 256.0F / 360.0F);
        }

        /** Far enough to cover any viewer that can see the entity at all; the tracking range is smaller than this. */
        private static final double VIEW_RANGE = 128;
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
