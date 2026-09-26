package net.citizensnpcs.npc.ai;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.ai.EntityTarget;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.PathStrategy;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.ai.StuckAction;
import net.citizensnpcs.api.ai.TargetType;
import net.citizensnpcs.api.ai.TeleportStuckAction;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.ai.event.NavigationBeginEvent;
import net.citizensnpcs.api.ai.event.NavigationCancelEvent;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.ai.event.NavigationReplaceEvent;
import net.citizensnpcs.api.ai.event.NavigationStuckEvent;
import net.citizensnpcs.api.ai.event.NavigatorCallback;
import net.citizensnpcs.api.astar.pathfinder.DoorExaminer;
import net.citizensnpcs.api.astar.pathfinder.FallingExaminer;
import net.citizensnpcs.api.astar.pathfinder.FlyingBlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.JumpingExaminer;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.SwimmingExaminer;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.TeleportCause;
import net.citizensnpcs.npc.ai.AStarNavigationStrategy.AStarPlanner;
import net.citizensnpcs.trait.ChunkTicketTrait;
import net.citizensnpcs.trait.RotationTrait;
import net.citizensnpcs.trait.RotationTrait.PacketRotationSession;
import net.citizensnpcs.util.ChunkCoord;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

/**
 * The NPC side of pathfinding: holds the parameters, picks a {@link PathStrategy} for each target, ticks it and turns its
 * outcome into events.
 * <p>
 * Two parameter sets are kept. {@link #getDefaultParameters()} is the NPC's own configuration and is what gets persisted;
 * each navigation clones it into {@code localParams} so that per-target examiners can be added without polluting the
 * defaults. That is why {@link #getLocalParameters()} answers differently depending on whether a navigation is running.
 * <p>
 * Strategy selection follows upstream: a flying NPC gets {@link FlyingAStarNavigationStrategy}, an NPC configured for the
 * Citizens pathfinder (or one that is not a {@link Mob}, and so has no vanilla navigation) gets
 * {@link AStarNavigationStrategy}, and everything else gets {@link MCNavigationStrategy}. The configured default is
 * {@code MINECRAFT}, as upstream, so Citizens A* is opt-in per NPC or per config.
 */
public class CitizensNavigator implements Navigator, Runnable {
    private long pauseRevision;
    private long navigationRevision;
    private boolean despawning;
    private PathStrategy ending;
    private ChunkCoord activeTicket;
    private final NavigatorParameters defaultParams = new NavigatorParameters().baseSpeed(UNINITIALISED_SPEED)
            .range(Setting.DEFAULT_PATHFINDING_RANGE.asFloat()).debug(Setting.DEBUG_PATHFINDING.asBoolean())
            .defaultAttackStrategy(CitizensNavigator::attack).attackRange(Setting.NPC_ATTACK_DISTANCE.asDouble())
            .updatePathRate(Setting.DEFAULT_PATHFINDER_UPDATE_PATH_RATE.asTicks())
            .distanceMargin(Setting.DEFAULT_DISTANCE_MARGIN.asDouble())
            .pathDistanceMargin(Setting.DEFAULT_PATH_DISTANCE_MARGIN.asDouble())
            .stationaryTicks(Setting.DEFAULT_STATIONARY_DURATION.asTicks()).stuckAction(TeleportStuckAction.INSTANCE)
            .pathfinderType(configuredPathfinderType())
            .straightLineTargetingDistance(Setting.DEFAULT_STRAIGHT_LINE_TARGETING_DISTANCE.asFloat())
            .destinationTeleportMargin(Setting.DEFAULT_DESTINATION_TELEPORT_MARGIN.asDouble())
            .fallDistance(Setting.PATHFINDER_FALL_DISTANCE.asInt());
    private PathStrategy executing;
    private int lastX, lastY, lastZ;
    private NavigatorParameters localParams = defaultParams;
    private final NPC npc;
    private boolean paused;
    private PacketRotationSession session;
    private int stationaryTicks;

    public CitizensNavigator(NPC npc) {
        this.npc = npc;
        if (npc.data().get(NPC.Metadata.DISABLE_DEFAULT_STUCK_ACTION,
                !Setting.DEFAULT_STUCK_ACTION.asString().contains("teleport"))) {
            defaultParams.stuckAction(null);
        }
        defaultParams.examiner(new MinecraftBlockExaminer(npc));
        defaultParams.examiner(new SwimmingExaminer());
        if (Setting.PATHFINDER_JUMPS.asBoolean()) {
            defaultParams.examiner(new JumpingExaminer(() -> (int) Math.ceil(npc.getEntity().getBbHeight()),
                    () -> getLocalParameters().speed()));
        }
    }

    @Override
    public void cancelNavigation() {
        navigationRevision++;
        stopNavigating(CancelReason.PLUGIN);
    }

    @Override
    public void cancelNavigation(CancelReason reason) {
        navigationRevision++;
        stopNavigating(reason);
    }

    @Override
    public boolean canNavigateTo(Location dest) {
        return canNavigateTo(dest, defaultParams.clone());
    }

    @Override
    public boolean canNavigateTo(Location dest, NavigatorParameters params) {
        if (dest == null || !npc.isSpawned() || dest.getWorld() != npc.getStoredLocation().getWorld())
            return false;
        if (params.pathfinderType().isCitizens() || !(npc.getEntity() instanceof Mob mob)) {
            if (npc.isFlyable() && !params.hasExaminer(FlyingBlockExaminer.class)) {
                params.examiner(new FlyingBlockExaminer());
            }
            // one slice of the search is enough to answer "is there any route at all" for a near destination; a far one
            // reports unreachable, which is the same answer upstream gives from a single tick of its planner
            AStarPlanner planner = new AStarPlanner(params, npc.getStoredLocation(), dest);
            planner.tick();
            return planner.getPath() != null;
        }
        return mob.getNavigation().createPath(dest.getBlockPos(), 1) != null;
    }

    @Override
    public NavigatorParameters getDefaultParameters() {
        return defaultParams;
    }

    @Override
    public EntityTarget getEntityTarget() {
        return executing instanceof EntityTarget target ? target : null;
    }

    @Override
    public NavigatorParameters getLocalParameters() {
        return isNavigating() ? localParams : defaultParams;
    }

    @Override
    public NPC getNPC() {
        return npc;
    }

    @Override
    public PathStrategy getPathStrategy() {
        return executing;
    }

    @Override
    public Location getTargetAsLocation() {
        return isNavigating() ? executing.getTargetAsLocation() : null;
    }

    @Override
    public TargetType getTargetType() {
        return isNavigating() ? executing.getTargetType() : null;
    }

    @Override
    public boolean isNavigating() {
        return executing != null && !isPaused();
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public long getPauseRevision() {
        return pauseRevision;
    }

    public void load(DataKey root) {
        if (root.keyExists("pathfindingrange")) {
            defaultParams.range((float) root.getDouble("pathfindingrange"));
        }
        if (root.keyExists("pathfindertype")) {
            defaultParams.pathfinderType(parsePathfinderType(root.getString("pathfindertype")));
        }
        if (root.keyExists("stationaryticks")) {
            defaultParams.stationaryTicks(root.getInt("stationaryticks"));
        }
        if (root.keyExists("distancemargin")) {
            defaultParams.distanceMargin(root.getDouble("distancemargin"));
        }
        if (root.keyExists("destinationteleportmargin")) {
            defaultParams.destinationTeleportMargin(root.getDouble("destinationteleportmargin"));
        }
        if (root.keyExists("updatepathrate")) {
            defaultParams.updatePathRate(root.getInt("updatepathrate"));
        }
        if (root.keyExists("falldistance")) {
            defaultParams.fallDistance(root.getInt("falldistance"));
        }
        defaultParams.speedModifier((float) root.getDouble("speedmodifier", 1F));
        defaultParams.avoidWater(root.getBoolean("avoidwater"));
        if (root.keyExists("usedefaultstuckaction") && !root.getBoolean("usedefaultstuckaction")
                && defaultParams.stuckAction() == TeleportStuckAction.INSTANCE) {
            defaultParams.stuckAction(null);
        }
    }

    public void save(DataKey root) {
        writeOrClearDouble(root, "pathfindingrange", defaultParams.range(),
                Setting.DEFAULT_PATHFINDING_RANGE.asFloat());
        writeOrClearInt(root, "stationaryticks", defaultParams.stationaryTicks(),
                Setting.DEFAULT_STATIONARY_DURATION.asTicks());
        writeOrClearDouble(root, "destinationteleportmargin", defaultParams.destinationTeleportMargin(),
                Setting.DEFAULT_DESTINATION_TELEPORT_MARGIN.asDouble());
        writeOrClearDouble(root, "distancemargin", defaultParams.distanceMargin(),
                Setting.DEFAULT_DISTANCE_MARGIN.asDouble());
        writeOrClearInt(root, "updatepathrate", defaultParams.updatePathRate(),
                Setting.DEFAULT_PATHFINDER_UPDATE_PATH_RATE.asTicks());
        // upstream compares this one against asTicks() of a plain block count, which is a copy-paste slip; the setting is
        // a distance in blocks, so it is read as an int here
        writeOrClearInt(root, "falldistance", defaultParams.fallDistance(),
                Setting.PATHFINDER_FALL_DISTANCE.asInt());
        if (defaultParams.pathfinderType() != configuredPathfinderType()) {
            root.setString("pathfindertype", defaultParams.pathfinderType().name());
        } else {
            root.removeKey("pathfindertype");
        }
        root.setDouble("speedmodifier", defaultParams.speedModifier());
        root.setBoolean("avoidwater", defaultParams.avoidWater());
        root.setBoolean("usedefaultstuckaction", defaultParams.stuckAction() == TeleportStuckAction.INSTANCE);
    }

    public void onDespawn() {
        despawning = true;
        navigationRevision++;
        stopNavigating(CancelReason.NPC_DESPAWNED);
    }

    public void onSpawn() {
        despawning = false;
        if (npc.getEntity() instanceof LivingEntity living) {
            defaultParams.baseSpeed((float) living.getAttributeValue(Attributes.MOVEMENT_SPEED));
        }
        updatePathfindingRange();
    }

    /** Ticked once per NPC update from {@code CitizensNPC.update}. */
    @Override
    public void run() {
        if (!isNavigating() || !npc.isSpawned() || despawning || isPaused())
            return;
        PathStrategy running = executing;

        Location npcLoc = npc.getStoredLocation();
        Location targetLoc = getTargetAsLocation();
        if (npcLoc == null || targetLoc == null) {
            stopNavigating(CancelReason.STUCK);
            return;
        }
        if (npcLoc.getWorld() != targetLoc.getWorld() || npcLoc.distance(targetLoc) > localParams.range()) {
            stopNavigating(CancelReason.STUCK);
            return;
        }
        if (updateStationaryStatus())
            return;

        updatePathfindingRange();

        if (localParams.destinationTeleportMargin() > 0
                && localParams.withinMargin(npcLoc, targetLoc, localParams.destinationTeleportMargin())) {
            // close enough that walking the last stretch looks worse than arriving; keep the current facing
            Location to = targetLoc.clone();
            to.setYaw(npcLoc.getYaw());
            to.setPitch(npcLoc.getPitch());
            npc.teleport(to, TeleportCause.PLUGIN);
        } else {
            boolean finished = executing.update();
            if (executing != running) return;
            if (!finished) {
                localParams.run();
                if (executing != running) return;
            }
            if (localParams.lookAtFunction() != null) {
                Location at = localParams.lookAtFunction().apply(this);
                if (executing != running) return;
                lookAt(at);
            }
            if (!finished)
                return;
        }
        if (executing != running) return;
        if (executing.getCancelReason() != null) {
            stopNavigating(executing.getCancelReason());
            return;
        }
        NavigationCompleteEvent event = new NavigationCompleteEvent(this);
        PathStrategy old = executing;
        NeoForge.EVENT_BUS.post(event);
        // a listener is allowed to start a new navigation from the completion event; only stop if it did not
        if (old == executing) {
            stopNavigating(null);
        }
    }

    @Override
    public void setPaused(boolean paused) {
        pauseRevision++;
        if (paused && isNavigating()) {
            cancelMoveDestination();
        }
        this.paused = paused;
    }

    @Override
    public void setStraightLineTarget(Entity target, boolean aggressive) {
        if (!npc.isSpawned())
            throw new IllegalStateException("npc is not spawned");
        if (target == null) {
            cancelNavigation();
            return;
        }
        setTarget(params -> {
            // a targeting distance this large means the straight-line phase never ends, which is the point
            params.straightLineTargetingDistance(100000);
            return new MCTargetStrategy(npc, target, aggressive, params);
        });
    }

    @Override
    public void setStraightLineTarget(Location target) {
        if (!npc.isSpawned())
            throw new IllegalStateException("npc is not spawned");
        if (target == null) {
            cancelNavigation();
            return;
        }
        setTarget(params -> new StraightLineNavigationStrategy(npc, target.clone(), params));
    }

    @Override
    public void setTarget(Entity target, boolean aggressive) {
        if (!npc.isSpawned())
            throw new IllegalStateException("npc is not spawned");
        if (target == null) {
            cancelNavigation();
            return;
        }
        setTarget(params -> new MCTargetStrategy(npc, target, aggressive, params));
    }

    @Override
    public void setTarget(Function<NavigatorParameters, PathStrategy> strategy) {
        if (!npc.isSpawned())
            throw new IllegalStateException("npc is not spawned");
        if (despawning) return;
        long request = ++navigationRevision;
        Entity handle = npc.getEntity();
        if (executing != null) {
            stopNavigating(CancelReason.REPLACE);
        }
        // Cancellation callbacks may submit a newer request, cancel this one or despawn the NPC.
        if (request != navigationRevision || despawning || !npc.isSpawned() || npc.getEntity() != handle) return;
        localParams = defaultParams.clone();

        if (localParams.pathfinderType().isCitizens()) {
            if (localParams.fallDistance() != -1) {
                localParams.examiner(new FallingExaminer(localParams.fallDistance()));
            }
            if (npc.data().get(NPC.Metadata.PATHFINDER_OPEN_DOORS, Setting.PATHFINDER_OPENS_DOORS.asBoolean())) {
                localParams.examiner(new DoorExaminer());
            }
            if (Setting.PATHFINDER_CHECK_BOUNDING_BOXES.asBoolean()) {
                localParams.examiner(new BoundingBoxExaminer(npc.getEntity()));
            }
        }
        updatePathfindingRange();
        PathStrategy prepared = strategy.apply(localParams);
        if (request != navigationRevision || despawning || !npc.isSpawned() || npc.getEntity() != handle) {
            if (prepared != null && prepared != executing) prepared.stop();
            return;
        }
        executing = prepared;
        stationaryTicks = 0;
        // vanilla navigation keeps steering the mob from Mob.serverAiStep, so it has to be shut off before the Citizens
        // pathfinder starts driving the same move control
        if (localParams.pathfinderType().isCitizens() && npc.getEntity() instanceof Mob mob) {
            mob.getNavigation().stop();
        }
        npc.getOrAddTrait(ChunkTicketTrait.class);
        updateTicket(executing.getTargetAsLocation());
        NeoForge.EVENT_BUS.post(new NavigationBeginEvent(this));
    }

    @Override
    public void setTarget(Iterable<Vec3> path) {
        if (!npc.isSpawned())
            throw new IllegalStateException("npc is not spawned");
        if (path == null || !path.iterator().hasNext()) {
            cancelNavigation();
            return;
        }
        setTarget(params -> {
            if (npc.isFlyable())
                return new FlyingAStarNavigationStrategy(npc, path, params);
            if (params.pathfinderType().isCitizens() || !(npc.getEntity() instanceof Mob))
                return new AStarNavigationStrategy(npc, path, params);
            return new MCNavigationStrategy(npc, path, params);
        });
    }

    @Override
    public void setTarget(Location targetIn) {
        if (!npc.isSpawned())
            throw new IllegalStateException("npc is not spawned");
        if (targetIn == null) {
            cancelNavigation();
            return;
        }
        Location target = targetIn.clone();
        setTarget(params -> {
            if (params.locationStrategyFactory() != null)
                return params.locationStrategyFactory().create(npc, params, target);
            if (npc.isFlyable())
                return new FlyingAStarNavigationStrategy(npc, target, params);
            if (params.pathfinderType().isCitizens() || !(npc.getEntity() instanceof Mob))
                return new AStarNavigationStrategy(npc, target, params);
            return new MCNavigationStrategy(npc, target, params);
        });
    }

    /**
     * Clears the move destination the strategies write to.
     * <p>
     * Upstream needs an NMS call here because its own entity subclasses hand-tick the controls. Vanilla's
     * {@code MoveControl} consumes its wanted position and returns to WAIT within the same tick, so nothing has to be
     * unset; zeroing the movement input stops the entity that vanilla already accelerated this tick.
     */
    private void cancelMoveDestination() {
        if (!npc.isSpawned())
            return;
        if (npc.getEntity() instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.setZza(0);
            mob.setXxa(0);
            mob.setYya(0);
        }
    }

    /** Shows the NPC looking somewhere other than along its path, per {@link NavigatorParameters#lookAtFunction}. */
    private void lookAt(Location at) {
        if (at == null)
            return;
        if (session == null) {
            RotationTrait trait = npc.getOrAddTrait(RotationTrait.class);
            session = trait.createPacketSession(trait.getGlobalParameters().clone().filter(p -> true).persist(true));
        }
        session.getSession().rotateToFace(at);
    }

    private void stopNavigating() {
        PathStrategy old = executing;
        executing = null;
        localParams = defaultParams;
        stationaryTicks = 0;
        if (npc.isSpawned()) {
            npc.getEntity().setDeltaMovement(Vec3.ZERO);
            cancelMoveDestination();
        }
        updateTicket(null);
        // A custom strategy may start another route from stop(). The old route must already be detached so that
        // this callback neither recurses into itself nor has its replacement erased by the old cleanup.
        if (old != null) old.stop();
    }

    /**
     * Ends the navigation, giving the stuck action a chance to rescue it first.
     *
     * @param reason
     *            why it ended, or null when it completed normally
     */
    private void stopNavigating(CancelReason reason) {
        if (!isNavigating())
            return;
        PathStrategy old = executing;
        // A callback can replace/cancel the route being ended. Release it once without dispatching its end again.
        if (ending == old) {
            stopNavigating();
            return;
        }
        PathStrategy previousEnding = ending;
        ending = old;
        try {
            if (reason == CancelReason.STUCK && Messaging.isDebugging()) {
                Messaging.debug(npc, "navigation ended, stuck", executing);
            }
            if (session != null) {
                session.end();
                session = null;
            }
            // callbacks are single-use, so they are drained before being run: a callback that starts a new navigation must
            // not see itself again on the next one
            List<NavigatorCallback> callbacks = new ArrayList<>();
            Iterator<NavigatorCallback> itr = localParams.callbacks().iterator();
            while (itr.hasNext()) {
                callbacks.add(itr.next());
                itr.remove();
            }
            for (NavigatorCallback callback : callbacks) {
                callback.onCompletion(reason);
            }
            if (old != executing) return;
            if (reason == null) {
                stopNavigating();
                return;
            }
            if (reason == CancelReason.STUCK) {
                NavigationStuckEvent event = new NavigationStuckEvent(this, localParams.stuckAction());
                NeoForge.EVENT_BUS.post(event);
                if (old != executing) return;
                StuckAction action = event.getAction();
                boolean rescued = action != null && action.run(npc, this);
                if (old != executing) return;
                if (rescued) {
                    // the action fixed it up, so carry on with the same strategy
                    stationaryTicks = 0;
                    executing.clearCancelReason();
                    return;
                }
            }
            NavigationCancelEvent event = reason == CancelReason.REPLACE ? new NavigationReplaceEvent(this)
                    : new NavigationCancelEvent(this, reason);
            NeoForge.EVENT_BUS.post(event);
            if (old == executing) {
                stopNavigating();
            }
        } finally {
            ending = previousEnding;
        }
    }

    /**
     * Vanilla derives how far it will search from the entity's follow-range attribute, so that is where the Citizens
     * pathfinding range has to be written for {@link MCNavigationStrategy} to respect it.
     */
    private void updatePathfindingRange() {
        if (!npc.isSpawned() || !(npc.getEntity() instanceof LivingEntity living))
            return;
        AttributeInstance attribute = living.getAttribute(Attributes.FOLLOW_RANGE);
        if (attribute != null && attribute.getBaseValue() != localParams.range()) {
            attribute.setBaseValue(localParams.range());
        }
    }

    /** @return true when the navigation was ended because the NPC has not moved for too long */
    private boolean updateStationaryStatus() {
        if (localParams.stationaryTicks() < 0)
            return false;
        Location current = npc.getStoredLocation();
        if (current.getY() < current.getWorld().getMinBuildHeight()
                || current.getY() >= current.getWorld().getMaxBuildHeight()) {
            // fallen out of the world; no amount of further pathfinding helps
            stopNavigating(CancelReason.STUCK);
            return true;
        }
        if (lastX == current.getBlockX() && lastY == current.getBlockY() && lastZ == current.getBlockZ()) {
            if (++stationaryTicks >= localParams.stationaryTicks()) {
                stopNavigating(CancelReason.STUCK);
                return true;
            }
        } else {
            stationaryTicks = 0;
        }
        lastX = current.getBlockX();
        lastY = current.getBlockY();
        lastZ = current.getBlockZ();
        return false;
    }

    /**
     * Keeps the destination chunk loaded for the duration of the navigation, so that a path can be found into it at all:
     * {@code LevelBlockSource} reads an unloaded chunk as air, which no route can cross.
     * <p>
     * A separate {@link TicketController} from the one {@link ChunkTicketTrait} uses. Both are keyed by the NPC UUID, so
     * sharing one would mean releasing the navigation ticket also released the trait ticket whenever the two happened to
     * cover the same chunk.
     */
    private void updateTicket(Location target) {
        ChunkCoord coord = null;
        if (target != null && target.getWorld() != null
                && (coord = new ChunkCoord(target.getWorld(), target.getChunkPos())).equals(activeTicket))
            return;

        if (activeTicket != null) {
            ServerLevel level = activeTicket.getWorld();
            if (level != null) {
                CONTROLLER.forceChunk(level, npc.getUniqueId(), activeTicket.x, activeTicket.z, false, true);
            }
        }
        activeTicket = coord;
        if (coord != null) {
            CONTROLLER.forceChunk(coord.getWorld(), npc.getUniqueId(), coord.x, coord.z, true, true);
        }
    }

    private static void writeOrClearDouble(DataKey root, String key, double value, double defaultValue) {
        if (value != defaultValue) {
            root.setDouble(key, value);
        } else {
            root.removeKey(key);
        }
    }

    private static void writeOrClearInt(DataKey root, String key, int value, int defaultValue) {
        if (value != defaultValue) {
            root.setInt(key, value);
        } else {
            root.removeKey(key);
        }
    }

    /**
     * The default attack: whatever the entity itself does when it hits something, so a weapon, its enchantments and the
     * attacker attributes all apply, and the swing is visible to onlookers.
     */
    private static boolean attack(LivingEntity attacker, LivingEntity target) {
        if (attacker instanceof Player player) {
            player.attack(target);
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        if (attacker instanceof Mob mob) {
            mob.doHurtTarget(target);
            mob.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private static PathfinderType configuredPathfinderType() {
        return parsePathfinderType(Setting.PATHFINDER_TYPE.asString());
    }

    /** A misspelt pathfinder type in the config should not stop the NPC from moving at all. */
    private static PathfinderType parsePathfinderType(String name) {
        try {
            return PathfinderType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            Messaging.severe("Unknown pathfinder type", name, "- falling back to MINECRAFT");
            return PathfinderType.MINECRAFT;
        }
    }

    /**
     * Drops every persisted navigation ticket as a level loads.
     * <p>
     * {@code forceChunk} writes its ticket into the level's {@code chunks.dat}, so a server that saves while an NPC is
     * walking somewhere restores that ticket on the next start - but navigation is not itself persisted, so
     * {@link #updateTicket} is never called for it again and the destination chunk stays force-loaded <em>and ticking</em>
     * for the rest of the world's life. One such chunk leaks per NPC that happened to be navigating at shutdown. Every
     * ticket present at load time is therefore stale by definition, which is exactly the case this callback exists for.
     */
    private static void releaseStaleTickets(ServerLevel level, TicketHelper helper) {
        // keySet() is a view over the map removeAllTickets mutates
        for (UUID owner : new ArrayList<>(helper.getEntityTickets().keySet())) {
            helper.removeAllTickets(owner);
        }
    }

    /** Registered on the mod bus by {@link Citizens} alongside {@link ChunkTicketTrait#CONTROLLER}. */
    public static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(Citizens.MOD_ID, "navigation_chunk_ticket"),
            CitizensNavigator::releaseStaleTickets);
    private static final float UNINITIALISED_SPEED = 0.3f;
}
