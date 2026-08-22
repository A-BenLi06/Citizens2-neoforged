package net.citizensnpcs.trait.waypoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.tree.Behavior;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.astar.AStarGoal;
import net.citizensnpcs.api.astar.AStarMachine;
import net.citizensnpcs.api.astar.AStarNode;
import net.citizensnpcs.api.astar.Agent;
import net.citizensnpcs.api.astar.Plan;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.trait.waypoint.WaypointProvider.EnumerableWaypointProvider;
import net.citizensnpcs.trait.waypoint.triggers.TriggerEditPrompt;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * A set of destinations the NPC picks between at random, plus optional guide waypoints that mark the way.
 * <p>
 * The guides are what make this different from a linear route: an A* search runs over the waypoint graph, so a "realistic"
 * NPC can walk between houses by following waypoints laid along the roads rather than cutting across the terrain.
 * <p>
 * Upstream indexes the waypoints in a {@code ch.ethz.globis.phtree} PH-tree to answer "which waypoints are within range".
 * That library is not available here, and an NPC has tens of waypoints rather than the millions a PH-tree is built for, so
 * the same query is a linear scan — which for these sizes is faster anyway, and removes the last of the two dropped
 * spatial-index dependencies.
 */
public class GuidedWaypointProvider implements EnumerableWaypointProvider {
    private GuidedGoal currentGoal;
    private final List<Waypoint> destinations = new ArrayList<>();
    private float distance = -1;
    private final List<Waypoint> guides = new ArrayList<>();
    private NPC npc;
    private boolean paused;

    public void addDestination(Waypoint waypoint) {
        destinations.add(waypoint);
        onWaypointsChanged();
    }

    public void addDestinations(Collection<Waypoint> waypoints) {
        destinations.addAll(waypoints);
        onWaypointsChanged();
    }

    public void addGuide(Waypoint helper) {
        guides.add(helper);
        onWaypointsChanged();
    }

    public void addGuides(Collection<Waypoint> helpers) {
        guides.addAll(helpers);
        onWaypointsChanged();
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void load(DataKey key) {
        // the keys were renamed at some point; both spellings are still read so an old save keeps working
        DataKey destinationKey = key.keyExists("availablewaypoints") ? key.getRelative("availablewaypoints")
                : key.getRelative("destinations");
        for (DataKey root : destinationKey.getIntegerSubKeys()) {
            Waypoint waypoint = PersistenceLoader.load(Waypoint.class, root);
            if (waypoint != null) {
                destinations.add(waypoint);
            }
        }
        DataKey guideKey = key.keyExists("helperwaypoints") ? key.getRelative("helperwaypoints")
                : key.getRelative("guides");
        for (DataKey root : guideKey.getIntegerSubKeys()) {
            Waypoint waypoint = PersistenceLoader.load(Waypoint.class, root);
            if (waypoint != null) {
                guides.add(waypoint);
            }
        }
        if (key.keyExists("distance")) {
            distance = (float) key.getDouble("distance");
        }
        onWaypointsChanged();
    }

    @Override
    public void onRemove() {
        if (currentGoal == null)
            return;
        currentGoal.onProviderChanged();
        npc.getDefaultBehaviorController().removeBehavior(currentGoal);
        currentGoal = null;
    }

    @Override
    public void onSpawn(NPC npc) {
        this.npc = npc;
        if (currentGoal == null) {
            currentGoal = new GuidedGoal();
            npc.getDefaultBehaviorController().addBehavior(currentGoal);
        }
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("availablewaypoints");
        DataKey root = key.getRelative("destinations");
        for (int i = 0; i < destinations.size(); i++) {
            PersistenceLoader.save(destinations.get(i), root.getRelative(i));
        }
        key.removeKey("helperwaypoints");
        root = key.getRelative("guides");
        for (int i = 0; i < guides.size(); i++) {
            PersistenceLoader.save(guides.get(i), root.getRelative(i));
        }
        if (distance != -1) {
            key.setDouble("distance", distance);
        }
    }

    @Override
    public void setPaused(boolean paused) {
        this.paused = paused;
        if (currentGoal != null) {
            currentGoal.onProviderChanged();
        }
    }

    /** Destinations and guides together. */
    @Override
    public Iterable<Waypoint> waypoints() {
        List<Waypoint> all = new ArrayList<>(destinations.size() + guides.size());
        all.addAll(destinations);
        all.addAll(guides);
        return all;
    }

    private void onWaypointsChanged() {
        if (currentGoal != null) {
            currentGoal.onProviderChanged();
        }
    }

    /**
     * Every waypoint within {@code range} of a point.
     *
     * @param includeDestinations
     *            whether destinations count as steps, which they do everywhere except the first hop
     */
    private List<Waypoint> within(Location of, double range, boolean includeDestinations) {
        List<Waypoint> found = new ArrayList<>();
        double rangeSquared = range * range;
        for (Waypoint candidate : guides) {
            if (candidate.getLocation().distanceSquared(of) <= rangeSquared) {
                found.add(candidate);
            }
        }
        if (includeDestinations) {
            for (Waypoint candidate : destinations) {
                if (candidate.getLocation().distanceSquared(of) <= rangeSquared) {
                    found.add(candidate);
                }
            }
        }
        return found;
    }

    /** Picks a random destination, plans a route through the guides, then walks it one waypoint at a time. */
    private class GuidedGoal implements Behavior {
        private GuidedPlan plan;
        private Waypoint target;

        public void onProviderChanged() {
            if (plan == null)
                return;
            reset();
            if (npc.getNavigator().isNavigating()) {
                npc.getNavigator().cancelNavigation();
            }
        }

        @Override
        public void reset() {
            plan = null;
            target = null;
        }

        @Override
        public BehaviorStatus run() {
            if (plan != null && plan.isComplete()) {
                target.onReach(npc);
                plan = null;
            }
            if (plan == null)
                return BehaviorStatus.SUCCESS;
            if (npc.getNavigator().isNavigating())
                return BehaviorStatus.RUNNING;

            Waypoint current = plan.getCurrentWaypoint();
            Location at = current.getLocation();
            npc.getNavigator().setTarget(Util.getCenterLocation(at.getWorld(), at.getBlockPos()));
            npc.getNavigator().getLocalParameters().addSingleUseCallback(cancelReason -> {
                if (plan != null) {
                    plan.update(npc);
                }
            });
            return BehaviorStatus.RUNNING;
        }

        @Override
        public boolean shouldExecute() {
            if (paused || destinations.isEmpty() || !npc.isSpawned() || npc.getNavigator().isNavigating())
                return false;
            target = destinations.get(Util.getFastRandom().nextInt(destinations.size()));
            if (target.getLocation().getWorld() != npc.getStoredLocation().getWorld()) {
                target = null;
                return false;
            }
            plan = ASTAR.runFully(new PathfinderGoal(target),
                    new PathfinderNode(null, new Waypoint(npc.getStoredLocation())));
            return plan != null;
        }
    }

    /** The route the waypoint-graph search produced. */
    private static class GuidedPlan implements Plan {
        private int index;
        private final Waypoint[] path;

        private GuidedPlan(Iterable<PathfinderNode> nodes) {
            List<Waypoint> collected = new ArrayList<>();
            for (PathfinderNode node : nodes) {
                collected.add(node.waypoint);
            }
            path = collected.toArray(new Waypoint[0]);
        }

        private Waypoint getCurrentWaypoint() {
            return path[index];
        }

        @Override
        public boolean isComplete() {
            return index >= path.length;
        }

        @Override
        public void update(Agent agent) {
            index++;
        }
    }

    private static class PathfinderGoal implements AStarGoal<PathfinderNode> {
        private final Waypoint dest;

        private PathfinderGoal(Waypoint dest) {
            this.dest = dest;
        }

        @Override
        public float g(PathfinderNode from, PathfinderNode to) {
            return (float) from.distance(to.waypoint);
        }

        @Override
        public float getInitialCost(PathfinderNode node) {
            return h(node);
        }

        @Override
        public float h(PathfinderNode from) {
            return (float) from.distance(dest);
        }

        @Override
        public boolean isFinished(PathfinderNode node) {
            return node.waypoint.equals(dest);
        }
    }

    /** One waypoint in the graph search. Its neighbours are the waypoints within range of it. */
    private class PathfinderNode extends AStarNode {
        private final Waypoint waypoint;

        private PathfinderNode(PathfinderNode parent, Waypoint waypoint) {
            super(parent);
            this.waypoint = waypoint;
        }

        @Override
        public Plan buildPlan() {
            return new GuidedPlan(this.<PathfinderNode> orderedPath());
        }

        public double distance(Waypoint dest) {
            return waypoint.distance(dest);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            return Objects.equals(waypoint, ((PathfinderNode) obj).waypoint);
        }

        @Override
        public Iterable<AStarNode> getNeighbours() {
            // the first hop may only use guides, so an NPC standing next to two destinations does not jump straight to
            // the wrong one; after that destinations are legitimate stepping stones
            boolean includeDestinations = getParent() != null;
            double range = distance == -1 ? npc.getNavigator().getDefaultParameters().range() : distance;
            List<Waypoint> found = within(waypoint.getLocation(), range, includeDestinations);
            List<AStarNode> neighbours = new ArrayList<>(found.size());
            for (Waypoint candidate : found) {
                neighbours.add(new PathfinderNode(this, candidate));
            }
            return neighbours;
        }

        @Override
        public int hashCode() {
            return 31 + (waypoint == null ? 0 : waypoint.hashCode());
        }

        @Override
        public String toString() {
            return "GuidedNode [" + waypoint + "]";
        }
    }

    private static final AStarMachine<PathfinderNode, GuidedPlan> ASTAR = AStarMachine.createWithDefaultStorage();

    @Override
    public WaypointEditor createEditor(CommandSourceStack sender, CommandContext args) {
        ServerPlayer player = sender.getPlayer();
        if (player == null) {
            Messaging.sendErrorTr(sender, CommandMessages.MUST_BE_INGAME);
            return null;
        }
        return new GuidedWaypointEditor(player);
    }

    /**
     * Left click a block to add a guide waypoint, or sneak and left click to add a destination — destinations glow so the
     * two are told apart. Right-clicking a marker selects it, and right-clicking the selected one removes it.
     */
    private class GuidedWaypointEditor extends WaypointEditor {
        private boolean inTriggerEditor;
        private final EntityMarkers<Waypoint> markers = new EntityMarkers<>();
        private final Map<Entity, Waypoint> markerWaypoints = new HashMap<>();
        private final ServerPlayer player;
        private Waypoint selected;
        private boolean showPath = true;

        private GuidedWaypointEditor(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public void begin() {
            Messaging.sendTr(player.createCommandSourceStack(), Messages.GUIDED_WAYPOINT_EDITOR_BEGIN);
            if (showPath) {
                createWaypointMarkers();
            }
        }

        @Override
        public void end() {
            Messaging.sendTr(player.createCommandSourceStack(), Messages.GUIDED_WAYPOINT_EDITOR_END);
            markers.destroyMarkers();
            markerWaypoints.clear();
            if (inTriggerEditor) {
                ChatPrompts.abandon(player);
                inTriggerEditor = false;
            }
            selected = null;
        }

        @Override
        public Waypoint getCurrentWaypoint() {
            return selected;
        }

        @Override
        protected boolean onChat(ServerPlayer chatter, String rawMessage) {
            if (chatter != player)
                return false;
            String message = rawMessage.trim();
            if (message.equalsIgnoreCase("triggers")) {
                CitizensAPI.getScheduler().runTask(() -> {
                    inTriggerEditor = true;
                    setPaused(true);
                    TriggerEditPrompt.start(player, this);
                });
                return true;
            }
            if (message.equalsIgnoreCase("toggle path")) {
                CitizensAPI.getScheduler().runTask(this::togglePath);
                return true;
            }
            if (message.equalsIgnoreCase("clear")) {
                CitizensAPI.getScheduler().runTask(() -> {
                    destinations.clear();
                    guides.clear();
                    markers.destroyMarkers();
                    markerWaypoints.clear();
                    selected = null;
                    onWaypointsChanged();
                });
                return true;
            }
            if (message.startsWith("distance ")) {
                try {
                    double parsed = Double.parseDouble(message.replace("distance ", "").trim());
                    if (parsed > 0) {
                        CitizensAPI.getScheduler().runTask(() -> {
                            distance = (float) parsed;
                            Messaging.sendTr(player.createCommandSourceStack(),
                                    Messages.GUIDED_WAYPOINT_EDITOR_DISTANCE_SET, parsed);
                        });
                    }
                } catch (NumberFormatException ex) {
                    // upstream lets this escape the chat handler; swallowing it is kinder
                }
                return true;
            }
            return false;
        }

        @Override
        protected boolean onLeftClickBlock(ServerPlayer clicker, ServerLevel level, BlockPos pos) {
            if (clicker != player || !npc.isSpawned() || level != npc.getStoredLocation().getWorld())
                return false;
            for (Waypoint existing : waypoints()) {
                if (existing.getLocation().getBlockPos().equals(pos)) {
                    Messaging.sendTr(player.createCommandSourceStack(), Messages.GUIDED_WAYPOINT_EDITOR_ALREADY_TAKEN);
                    return true;
                }
            }
            Waypoint element = new Waypoint(Location.fromBlockPos(level, pos));
            boolean destination = clicker.isShiftKeyDown();
            if (destination) {
                destinations.add(element);
                Messaging.sendTr(player.createCommandSourceStack(), Messages.GUIDED_WAYPOINT_EDITOR_ADDED_AVAILABLE);
            } else {
                guides.add(element);
                Messaging.sendTr(player.createCommandSourceStack(), Messages.GUIDED_WAYPOINT_EDITOR_ADDED_GUIDE);
            }
            if (showPath) {
                createMarker(element, destination);
            }
            selected = element;
            onWaypointsChanged();
            return true;
        }

        @Override
        protected boolean onRightClickEntity(ServerPlayer clicker, Entity entity) {
            if (clicker != player)
                return false;
            // upstream keys the marker to its waypoint by storing a hash code in NPC metadata; a map is exact
            Waypoint point = markerWaypoints.get(entity);
            if (point == null)
                return false;
            if (selected != point) {
                selected = point;
                Messaging.sendTr(player.createCommandSourceStack(), Messages.WAYPOINT_EDITOR_SELECTED_WAYPOINT,
                        point.getLocation());
                return true;
            }
            markers.removeMarker(point);
            markerWaypoints.remove(entity);
            if (!destinations.remove(point)) {
                guides.remove(point);
            }
            selected = null;
            onWaypointsChanged();
            return true;
        }

        private void createMarker(Waypoint waypoint, boolean destination) {
            Entity entity = markers.createMarker(waypoint, waypoint.getLocation().clone().add(0, 1, 0));
            if (entity == null)
                return;
            markerWaypoints.put(entity, waypoint);
            if (!destination)
                return;
            NPC marker = CitizensAPI.getTemporaryNPCRegistry().getNPC(entity);
            if (marker != null) {
                marker.data().set(NPC.Metadata.GLOWING, true);
            }
        }

        private void createWaypointMarkers() {
            for (Waypoint waypoint : guides) {
                createMarker(waypoint, false);
            }
            for (Waypoint waypoint : destinations) {
                createMarker(waypoint, true);
            }
        }

        private void togglePath() {
            showPath = !showPath;
            if (showPath) {
                createWaypointMarkers();
                Messaging.sendTr(player.createCommandSourceStack(), Messages.WAYPOINT_EDITOR_SHOWING_MARKERS);
            } else {
                markers.destroyMarkers();
                markerWaypoints.clear();
                Messaging.sendTr(player.createCommandSourceStack(), Messages.WAYPOINT_EDITOR_NOT_SHOWING_MARKERS);
            }
        }
    }
}
