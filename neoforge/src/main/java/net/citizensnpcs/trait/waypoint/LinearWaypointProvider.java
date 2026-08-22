package net.citizensnpcs.trait.waypoint;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.PathStrategy;
import net.citizensnpcs.api.ai.tree.Behavior;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Location;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

/**
 * An ordered list of {@link Waypoint}s the NPC walks between, either looping back to the start or turning round and
 * coming back.
 * <p>
 * Routes can be cached: the path worked out between two waypoints is kept and replayed, which removes the pathfinding
 * cost from a fixed patrol. A cached route is re-verified before use, because the world may have changed under it.
 */
public class LinearWaypointProvider implements EnumerableWaypointProvider {
    private final Map<SourceDestinationPair, Iterable<Vec3>> cachedPaths = new HashMap<>();
    @Persist
    private boolean cachePaths = Setting.DEFAULT_CACHE_WAYPOINT_PATHS.asBoolean();
    @Persist
    private boolean cycle;
    private LinearWaypointGoal currentGoal;
    private NPC npc;
    @Persist
    private boolean pathfind = true;
    @Persist(value = "points", reify = true, valueType = Waypoint.class)
    private final List<Waypoint> waypoints = new ArrayList<>();

    public LinearWaypointProvider() {
    }

    public void addWaypoint(Waypoint waypoint) {
        waypoints.add(waypoint);
        if (currentGoal != null) {
            currentGoal.onProviderChanged();
        }
    }

    public boolean cachePaths() {
        return cachePaths;
    }

    public boolean cycleWaypoints() {
        return cycle;
    }

    public Waypoint getCurrentWaypoint() {
        return currentGoal == null ? null : currentGoal.currentDestination;
    }

    @Override
    public boolean isPaused() {
        return currentGoal != null && currentGoal.isPaused();
    }

    @Override
    public void load(DataKey key) {
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
            currentGoal = new LinearWaypointGoal();
            npc.getDefaultBehaviorController().addBehavior(currentGoal);
        }
    }

    public boolean pathfind() {
        return pathfind;
    }

    @Override
    public void save(DataKey key) {
    }

    public void setCachePaths(boolean cachePaths) {
        this.cachePaths = cachePaths;
        if (currentGoal != null) {
            currentGoal.onProviderChanged();
        }
    }

    public void setCycle(boolean cycle) {
        this.cycle = cycle;
        if (currentGoal != null) {
            currentGoal.onProviderChanged();
        }
    }

    public void setPathfind(boolean pathfind) {
        this.pathfind = pathfind;
    }

    @Override
    public void setPaused(boolean paused) {
        if (currentGoal != null) {
            currentGoal.setPaused(paused);
        }
    }

    /** The live, modifiable route. Every mutation tells the goal to restart its iterator. */
    @Override
    public Iterable<Waypoint> waypoints() {
        return new AbstractList<Waypoint>() {
            @Override
            public void add(int index, Waypoint waypoint) {
                waypoints.add(index, waypoint);
                mod();
            }

            @Override
            public void clear() {
                waypoints.clear();
                mod();
            }

            @Override
            public Waypoint get(int index) {
                return waypoints.get(index);
            }

            @Override
            public Waypoint remove(int index) {
                Waypoint removed = waypoints.remove(index);
                mod();
                return removed;
            }

            @Override
            public Waypoint set(int index, Waypoint element) {
                Waypoint previous = waypoints.set(index, element);
                mod();
                return previous;
            }

            @Override
            public int size() {
                return waypoints.size();
            }

            private void mod() {
                cachedPaths.clear();
                if (currentGoal != null) {
                    currentGoal.onProviderChanged();
                }
            }
        };
    }

    @Override
    public WaypointEditor createEditor(CommandSourceStack sender, CommandContext args) {
        if (args.hasFlag('h')) {
            addFromSender(sender, args);
            return null;
        }
        if (args.hasValueFlag("at")) {
            try {
                Location at = args.parseLocation(args.getFlag("at"));
                if (at != null) {
                    waypoints.add(new Waypoint(at));
                }
            } catch (CommandException ex) {
                Messaging.sendError(sender, ex.getMessage());
            }
            return null;
        }
        if (args.hasFlag('c')) {
            waypoints.clear();
            cachedPaths.clear();
            return null;
        }
        if (args.hasFlag('l')) {
            if (!waypoints.isEmpty()) {
                waypoints.remove(waypoints.size() - 1);
            }
            return null;
        }
        if (args.hasFlag('p')) {
            setPaused(!isPaused());
            return null;
        }
        if (args.hasFlag('k')) {
            cachePaths = !cachePaths;
            return null;
        }
        if (args.hasFlag('f')) {
            pathfind = !pathfind;
            return null;
        }
        ServerPlayer player = sender.getPlayer();
        if (player == null) {
            Messaging.sendErrorTr(sender, CommandMessages.MUST_BE_INGAME);
            return null;
        }
        return new LinearWaypointEditor(player);
    }

    private void addFromSender(CommandSourceStack sender, CommandContext args) {
        try {
            Location at = args.getSenderLocation();
            if (at != null) {
                waypoints.add(new Waypoint(at));
            }
        } catch (CommandException ex) {
            Messaging.sendError(sender, ex.getMessage());
        }
    }

    /**
     * Left click a block to add a waypoint, right click to remove the last one. Right-clicking a marker selects it, and
     * right-clicking the selected one removes it; a new waypoint is inserted before the selection rather than appended.
     */
    private class LinearWaypointEditor extends WaypointEditor {
        private boolean editing = true;
        private boolean inTriggerEditor;
        private final EntityMarkers<Waypoint> markers = new EntityMarkers<>();
        private final ServerPlayer player;
        private Waypoint selectedWaypoint;
        private boolean showingMarkers = true;

        private LinearWaypointEditor(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public void begin() {
            Messaging.sendTr(player.createCommandSourceStack(), Messages.LINEAR_WAYPOINT_EDITOR_BEGIN);
            if (showingMarkers) {
                createWaypointMarkers();
            }
        }

        @Override
        public void end() {
            if (!editing)
                return;
            editing = false;
            if (inTriggerEditor) {
                ChatPrompts.abandon(player);
                inTriggerEditor = false;
            }
            Messaging.sendTr(player.createCommandSourceStack(), Messages.LINEAR_WAYPOINT_EDITOR_END);
            markers.destroyMarkers();
        }

        @Override
        public Waypoint getCurrentWaypoint() {
            if (waypoints.isEmpty() || !editing)
                return null;
            return selectedWaypoint == null ? waypoints.get(waypoints.size() - 1) : selectedWaypoint;
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
            if (message.equalsIgnoreCase("clear")) {
                CitizensAPI.getScheduler().runTask(this::clearWaypoints);
                return true;
            }
            if (message.equalsIgnoreCase("toggle path") || message.equalsIgnoreCase("markers")) {
                CitizensAPI.getScheduler().runTask(this::toggleMarkers);
                return true;
            }
            if (message.equalsIgnoreCase("cycle")) {
                CitizensAPI.getScheduler().runTask(() -> {
                    cycle = !cycle;
                    Messaging.sendTr(player.createCommandSourceStack(), cycle
                            ? Messages.LINEAR_WAYPOINT_EDITOR_CYCLE_SET : Messages.LINEAR_WAYPOINT_EDITOR_CYCLE_UNSET);
                });
                return true;
            }
            if (message.equalsIgnoreCase("pathfind")) {
                CitizensAPI.getScheduler().runTask(() -> {
                    pathfind = !pathfind;
                    Messaging.sendTr(player.createCommandSourceStack(),
                            pathfind ? Messages.LINEAR_WAYPOINT_EDITOR_PATHFIND_SET
                                    : Messages.LINEAR_WAYPOINT_EDITOR_PATHFIND_UNSET);
                });
                return true;
            }
            if (message.equalsIgnoreCase("here")) {
                CitizensAPI.getScheduler().runTask(() -> {
                    addWaypointAndReport(Location.of(player));
                    onWaypointsModified();
                });
                return true;
            }
            return false;
        }

        @Override
        protected boolean onLeftClickBlock(ServerPlayer clicker, ServerLevel level, BlockPos pos) {
            if (clicker != player || !npc.isSpawned() || level != npc.getStoredLocation().getWorld())
                return false;
            Location at = Location.fromBlockPos(level, pos.above());
            Location prev = getLastWaypoint();
            if (prev != null && prev.getWorld() == at.getWorld()) {
                double distance = at.distance(prev);
                double maxDistance = npc.getNavigator().getDefaultParameters().range();
                if (distance > maxDistance) {
                    Messaging.sendErrorTr(player.createCommandSourceStack(),
                            Messages.LINEAR_WAYPOINT_EDITOR_RANGE_EXCEEDED, distance, maxDistance, "<red>");
                    return true;
                }
            }
            addWaypointAndReport(at);
            onWaypointsModified();
            return true;
        }

        @Override
        protected boolean onRightClickBlock(ServerPlayer clicker, ServerLevel level, BlockPos pos) {
            if (clicker != player || waypoints.isEmpty() || clicker.isShiftKeyDown() || !npc.isSpawned()
                    || level != npc.getStoredLocation().getWorld())
                return false;
            removeWaypoint(waypoints.size() - 1);
            Messaging.sendTr(player.createCommandSourceStack(), Messages.LINEAR_WAYPOINT_EDITOR_REMOVED_WAYPOINT,
                    waypoints.size());
            onWaypointsModified();
            return true;
        }

        @Override
        protected boolean onRightClickEntity(ServerPlayer clicker, Entity entity) {
            if (clicker != player || !showingMarkers || waypoints.isEmpty())
                return false;
            // the marker sits a block above its waypoint, so compare against one block below the entity
            Vec3 clicked = entity.position().subtract(0, 1, 0);
            int slot = -1;
            double minDistance = Double.MAX_VALUE;
            for (int i = 0; i < waypoints.size(); i++) {
                Location at = waypoints.get(i).getLocation();
                double distance = clicked.distanceToSqr(at.getX(), at.getY(), at.getZ());
                if (distance < minDistance) {
                    minDistance = distance;
                    slot = i;
                }
            }
            if (slot == -1)
                return false;
            if (selectedWaypoint != null && waypoints.get(slot) == selectedWaypoint) {
                removeWaypoint(slot);
                Messaging.sendTr(player.createCommandSourceStack(), Messages.LINEAR_WAYPOINT_EDITOR_REMOVED_WAYPOINT,
                        waypoints.size());
                onWaypointsModified();
                return true;
            }
            selectedWaypoint = waypoints.get(slot);
            Messaging.sendTr(player.createCommandSourceStack(), Messages.WAYPOINT_EDITOR_SELECTED_WAYPOINT,
                    formatLoc(selectedWaypoint.getLocation()));
            return true;
        }

        private void addWaypointAndReport(Location at) {
            Waypoint element = new Waypoint(at);
            int index = waypoints.indexOf(selectedWaypoint);
            if (index != -1) {
                waypoints.add(index, element);
            } else {
                waypoints.add(element);
            }
            if (showingMarkers) {
                markers.createMarker(element, element.getLocation());
            }
            Messaging.sendTr(player.createCommandSourceStack(), Messages.LINEAR_WAYPOINT_EDITOR_ADDED_WAYPOINT,
                    formatLoc(at), waypoints.size());
        }

        private void clearWaypoints() {
            waypoints.clear();
            onWaypointsModified();
            markers.destroyMarkers();
            Messaging.sendTr(player.createCommandSourceStack(), Messages.LINEAR_WAYPOINT_EDITOR_WAYPOINTS_CLEARED);
        }

        private void createWaypointMarkers() {
            for (Waypoint waypoint : waypoints) {
                markers.createMarker(waypoint, waypoint.getLocation());
            }
        }

        private String formatLoc(Location location) {
            return String.format("[[%d]], [[%d]], [[%d]]", location.getBlockX(), location.getBlockY(),
                    location.getBlockZ());
        }

        private Location getLastWaypoint() {
            return waypoints.size() <= 1 ? null : waypoints.get(waypoints.size() - 1).getLocation();
        }

        private void onWaypointsModified() {
            cachedPaths.clear();
            if (currentGoal != null) {
                currentGoal.onProviderChanged();
            }
            if (inTriggerEditor && getCurrentWaypoint() != null) {
                getCurrentWaypoint().describeTriggers(player.createCommandSourceStack());
            }
        }

        private Waypoint removeWaypoint(int index) {
            Waypoint waypoint = waypoints.remove(index);
            if (showingMarkers) {
                markers.removeMarker(waypoint);
            }
            if (waypoint == selectedWaypoint) {
                selectedWaypoint = null;
            }
            return waypoint;
        }

        private void toggleMarkers() {
            showingMarkers = !showingMarkers;
            if (showingMarkers) {
                createWaypointMarkers();
                Messaging.sendTr(player.createCommandSourceStack(), Messages.WAYPOINT_EDITOR_SHOWING_MARKERS);
            } else {
                markers.destroyMarkers();
                Messaging.sendTr(player.createCommandSourceStack(), Messages.WAYPOINT_EDITOR_NOT_SHOWING_MARKERS);
            }
        }
    }

    /** Walks the route, one waypoint at a time, and fires the waypoint's triggers on arrival. */
    private class LinearWaypointGoal implements Behavior {
        private boolean ascending = true;
        private Waypoint currentDestination;
        private ListIterator<Waypoint> itr;
        private boolean paused;

        public boolean isPaused() {
            return paused;
        }

        public void onProviderChanged() {
            itr = getUnsafeIterator();
            if (currentDestination != null && npc != null && npc.getNavigator().isNavigating()) {
                npc.getNavigator().cancelNavigation();
            }
        }

        @Override
        public void reset() {
            currentDestination = null;
        }

        @Override
        public BehaviorStatus run() {
            if (paused || !getNavigator().isNavigating())
                return BehaviorStatus.SUCCESS;
            return BehaviorStatus.RUNNING;
        }

        public void setPaused(boolean pause) {
            paused = pause;
            if (!pause || currentDestination == null)
                return;
            if (npc != null && npc.getNavigator().isNavigating()) {
                npc.getNavigator().cancelNavigation();
            }
            if (itr != null && itr.hasPrevious()) {
                itr.previous();
            }
        }

        @Override
        public boolean shouldExecute() {
            if (paused || currentDestination != null || !npc.isSpawned() || getNavigator().isNavigating())
                return false;
            ensureItr();
            if (!itr.hasNext())
                return false;

            Waypoint next = itr.next();
            Location npcLoc = npc.getStoredLocation();
            if (npcLoc.getWorld() != next.getLocation().getWorld()
                    || npc.getNavigator().getLocalParameters().withinMargin(npcLoc, next.getLocation()))
                return false;

            currentDestination = next;
            if (cachePaths) {
                replayCachedPath(npcLoc);
            }
            if (!getNavigator().isNavigating()) {
                Location target = currentDestination.getLocation();
                Location centre = Util.getCenterLocation(target.getWorld(), target.getBlockPos());
                // Upstream has these two branches the other way round, so an NPC told "will now pathfind" walks in a
                // straight line and vice versa - its message and its behaviour contradict each other. Swapped here so
                // that pathfind == true really pathfinds, which is also the documented default.
                if (pathfind) {
                    getNavigator().setTarget(centre);
                } else {
                    getNavigator().setStraightLineTarget(centre);
                }
            }
            PathStrategy strategy = getNavigator().getPathStrategy();
            getNavigator().getLocalParameters().addSingleUseCallback(cancelReason -> {
                Waypoint waypoint = currentDestination;
                currentDestination = null;
                if (cancelReason != null || waypoint == null)
                    return;
                waypoint.onReach(npc);
                if (cachePaths && strategy != null && strategy.getPath() != null) {
                    List<Vec3> path = new ArrayList<>();
                    strategy.getPath().forEach(path::add);
                    if (!path.isEmpty()) {
                        cachedPaths.put(new SourceDestinationPair(npcLoc, waypoint), path);
                    }
                }
            });
            return true;
        }

        private void ensureItr() {
            if (itr == null) {
                itr = getUnsafeIterator();
            } else if (!itr.hasNext()) {
                itr = getNewIterator();
            }
        }

        private Navigator getNavigator() {
            return npc.getNavigator();
        }

        /** Asks listeners for the next route once this one runs out, defaulting to another lap. */
        private ListIterator<Waypoint> getNewIterator() {
            LinearWaypointsCompleteEvent event = new LinearWaypointsCompleteEvent(LinearWaypointProvider.this,
                    getUnsafeIterator());
            NeoForge.EVENT_BUS.post(event);
            return event.getNextWaypoints();
        }

        /**
         * Walks the list without copying it, so edits made while the NPC is en route are picked up. When cycling, the
         * direction flips each lap so the NPC retraces its steps instead of jumping back to the start.
         */
        private ListIterator<Waypoint> getUnsafeIterator() {
            ascending = !cycle || !ascending;
            return new ListIterator<Waypoint>() {
                int idx = ascending ? 0 : waypoints.size() - 1;

                @Override
                public void add(Waypoint e) {
                    waypoints.add(e);
                }

                @Override
                public boolean hasNext() {
                    return ascending ? idx < waypoints.size() : idx >= 0;
                }

                @Override
                public boolean hasPrevious() {
                    return ascending ? idx > 0 : idx < waypoints.size() - 1;
                }

                @Override
                public Waypoint next() {
                    return ascending ? waypoints.get(idx++) : waypoints.get(idx--);
                }

                @Override
                public int nextIndex() {
                    return idx;
                }

                @Override
                public Waypoint previous() {
                    return ascending ? waypoints.get(--idx) : waypoints.get(++idx);
                }

                @Override
                public int previousIndex() {
                    return ascending ? idx - 1 : idx + 1;
                }

                @Override
                public void remove() {
                    waypoints.remove(Math.max(0, ascending ? idx - 1 : idx + 1));
                }

                @Override
                public void set(Waypoint e) {
                    waypoints.set(Math.max(0, Math.min(waypoints.size() - 1, idx)), e);
                }
            };
        }

        /** Reuses a stored path, dropping it when the world no longer supports it. */
        private void replayCachedPath(Location npcLoc) {
            SourceDestinationPair key = new SourceDestinationPair(npcLoc, currentDestination);
            Iterable<Vec3> cached = cachedPaths.get(key);
            if (cached == null)
                return;
            if (!cached.iterator().hasNext() || !key.verify(npcLoc.getWorld(), cached)) {
                cachedPaths.remove(key);
                return;
            }
            getNavigator().setTarget(cached);
        }
    }

    /** Identifies a cached route by where it started and where it was going. */
    private static class SourceDestinationPair {
        private final Vec3 from;
        private final Vec3 to;

        private SourceDestinationPair(Location npcLoc, Waypoint to) {
            this(new Vec3(npcLoc.getBlockX(), npcLoc.getBlockY(), npcLoc.getBlockZ()),
                    new Vec3(to.getLocation().getX(), to.getLocation().getY(), to.getLocation().getZ()));
        }

        private SourceDestinationPair(Vec3 from, Vec3 to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            SourceDestinationPair other = (SourceDestinationPair) obj;
            return Objects.equals(from, other.from) && Objects.equals(to, other.to);
        }

        @Override
        public int hashCode() {
            return 31 * (31 + (from == null ? 0 : from.hashCode())) + (to == null ? 0 : to.hashCode());
        }

        /** A cached route is only usable while every step of it still has something to stand on. */
        private boolean verify(ServerLevel level, Iterable<Vec3> cached) {
            for (Vec3 step : cached) {
                if (!MinecraftBlockExaminer
                        .canStandOn(level.getBlockState(BlockPos.containing(step.x, step.y - 1, step.z))))
                    return false;
            }
            return true;
        }
    }
}
