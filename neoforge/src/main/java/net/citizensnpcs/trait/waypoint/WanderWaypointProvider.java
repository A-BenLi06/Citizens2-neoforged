package net.citizensnpcs.trait.waypoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.goals.WanderGoal;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Durations;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Wanders inside a box around the NPC, or inside a set of boxes placed by hand.
 * <p>
 * Upstream also accepts a WorldGuard region id as the boundary. WorldGuard is one of the integrations §2 drops, and the
 * capability it provided — confining the wander to an area — is already covered by the region centres, so the field and
 * its editor command are gone rather than kept as something that can never resolve. {@link WanderGoal.Builder#filter}
 * remains for anyone who wants to plug a different notion of "allowed area" back in.
 */
public class WanderWaypointProvider implements WaypointProvider {
    private List<AABB> boxes = new ArrayList<>();
    private WanderGoal currentGoal;
    @Persist
    private int delay = -1;
    private NPC npc;
    @Persist
    private boolean pathfind = true;
    private boolean paused;
    @Persist
    private final List<Location> regionCentres = new ArrayList<>();
    @Persist
    private int xrange = 25;
    @Persist
    private int yrange = 3;

    public void addRegionCentre(Location centre) {
        regionCentres.add(centre);
        recalculateBoxes();
    }

    public void addRegionCentres(Collection<Location> centres) {
        regionCentres.addAll(centres);
        recalculateBoxes();
    }

    public int getDelay() {
        return delay;
    }

    /** The list is live: changing it recalculates the boundary boxes, as upstream's forwarding list does. */
    public List<Location> getRegionCentres() {
        return new RecalculatingList();
    }

    public int getXRange() {
        return xrange;
    }

    public int getYRange() {
        return yrange;
    }

    public boolean isPathfind() {
        return pathfind;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void load(DataKey key) {
        recalculateBoxes();
    }

    @Override
    public void onRemove() {
        if (currentGoal == null)
            return;
        currentGoal.pause();
        npc.getDefaultBehaviorController().removeBehavior(currentGoal);
        currentGoal = null;
    }

    @Override
    public void onSpawn(NPC npc) {
        this.npc = npc;
        if (currentGoal == null) {
            currentGoal = WanderGoal.builder(npc).xrange(xrange).yrange(yrange).pathfind(pathfind)
                    .regions(() -> regionCentres.isEmpty() ? null : boxes).delay(delay).build();
            if (paused) {
                currentGoal.pause();
            }
        } else {
            npc.getDefaultBehaviorController().removeBehavior(currentGoal);
        }
        npc.getDefaultBehaviorController().addBehavior(currentGoal);
    }

    public void removeRegionCentre(Location centre) {
        regionCentres.remove(centre);
        recalculateBoxes();
    }

    public void removeRegionCentres(Collection<Location> centres) {
        regionCentres.removeAll(centres);
        recalculateBoxes();
    }

    @Override
    public void save(DataKey key) {
    }

    public void setDelay(int delay) {
        this.delay = delay;
        if (currentGoal != null) {
            currentGoal.setDelay(delay);
        }
    }

    public void setPathfind(boolean pathfind) {
        this.pathfind = pathfind;
        if (currentGoal != null) {
            currentGoal.setPathfind(pathfind);
        }
    }

    @Override
    public void setPaused(boolean paused) {
        this.paused = paused;
        if (currentGoal == null)
            return;
        if (paused) {
            currentGoal.pause();
        } else {
            currentGoal.unpause();
        }
    }

    public void setXYRange(int xrange, int yrange) {
        this.xrange = xrange;
        this.yrange = yrange;
        recalculateBoxes();
        if (currentGoal != null) {
            currentGoal.setXYRange(xrange, yrange);
        }
    }

    private void recalculateBoxes() {
        List<AABB> rebuilt = new ArrayList<>(regionCentres.size());
        for (Location centre : regionCentres) {
            rebuilt.add(new AABB(centre.getBlockX() - xrange, centre.getBlockY() - yrange, centre.getBlockZ() - xrange,
                    centre.getBlockX() + xrange + 1, centre.getBlockY() + yrange + 1, centre.getBlockZ() + xrange + 1));
        }
        boxes = rebuilt;
    }

    @Override
    public WaypointEditor createEditor(CommandSourceStack sender, CommandContext args) {
        ServerPlayer player = sender.getPlayer();
        if (player == null)
            return null;
        return new WanderWaypointEditor(player);
    }

    /**
     * Typed commands set the ranges, the delay and whether to pathfind; turning region editing on shows a marker at each
     * centre, which is then placed by left-clicking a block and removed by right-clicking the marker.
     */
    private class WanderWaypointEditor extends WaypointEditor {
        private boolean editingRegions;
        private final EntityMarkers<Location> markers = new EntityMarkers<>();
        private final Map<Entity, Location> markerLocations = new HashMap<>();
        private final ServerPlayer player;

        private WanderWaypointEditor(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public void begin() {
            Messaging.sendTr(player.createCommandSourceStack(), Messages.WANDER_WAYPOINTS_BEGIN,
                    pathfind ? "<green>" : "<red>");
            setPaused(true);
        }

        @Override
        public void end() {
            Messaging.sendTr(player.createCommandSourceStack(), Messages.WANDER_WAYPOINTS_END);
            editingRegions = false;
            setPaused(false);
            markers.destroyMarkers();
            markerLocations.clear();
        }

        @Override
        protected boolean onChat(ServerPlayer chatter, String rawMessage) {
            if (chatter != player)
                return false;
            String message = rawMessage.toLowerCase().trim();
            if (message.startsWith("xrange") || message.startsWith("yrange")) {
                String[] split = message.split(" ", 2);
                if (split.length > 1) {
                    try {
                        int range = Math.max(0, Integer.parseInt(split[1].trim()));
                        CitizensAPI.getScheduler().runTask(() -> {
                            if (message.startsWith("xrange")) {
                                setXYRange(range, yrange);
                            } else {
                                setXYRange(xrange, range);
                            }
                            Messaging.sendTr(player.createCommandSourceStack(), Messages.WANDER_WAYPOINTS_RANGE_SET,
                                    xrange, yrange);
                        });
                    } catch (NumberFormatException ex) {
                        // upstream swallows this too; the range simply stays as it was
                    }
                }
                return true;
            }
            if (message.startsWith("regions")) {
                CitizensAPI.getScheduler().runTask(this::toggleRegionEditing);
                return true;
            }
            if (message.startsWith("delay")) {
                String[] split = message.split(" ", 2);
                if (split.length > 1) {
                    CitizensAPI.getScheduler().runTask(() -> {
                        setDelay(Durations.toTicks(Durations.parse(split[1].trim())));
                        Messaging.sendTr(player.createCommandSourceStack(), Messages.WANDER_WAYPOINTS_DELAY_SET, delay);
                    });
                }
                return true;
            }
            if (message.startsWith("pathfind")) {
                CitizensAPI.getScheduler().runTask(() -> {
                    setPathfind(!pathfind);
                    begin();
                });
                return true;
            }
            return false;
        }

        @Override
        protected boolean onLeftClickBlock(ServerPlayer clicker, ServerLevel level, BlockPos pos) {
            if (clicker != player || !editingRegions || !npc.isSpawned() || level != npc.getStoredLocation().getWorld())
                return false;
            Location at = Location.fromBlockPos(level, pos.above());
            if (regionCentres.contains(at))
                return true;
            regionCentres.add(at);
            Entity marker = markers.createMarker(at, at);
            if (marker != null) {
                markerLocations.put(marker, at);
            }
            recalculateBoxes();
            Messaging.sendTr(player.createCommandSourceStack(), Messages.WANDER_WAYPOINTS_ADDED_REGION, formatLoc(at),
                    regionCentres.size());
            return true;
        }

        @Override
        protected boolean onRightClickEntity(ServerPlayer clicker, Entity entity) {
            if (clicker != player || !editingRegions)
                return false;
            // upstream tags each marker with Bukkit metadata; a map from marker entity to centre does the same job
            Location at = markerLocations.remove(entity);
            if (at == null)
                return false;
            regionCentres.remove(at);
            markers.removeMarker(at);
            recalculateBoxes();
            Messaging.sendTr(player.createCommandSourceStack(), Messages.WANDER_WAYPOINTS_REMOVED_REGION, formatLoc(at),
                    regionCentres.size());
            return true;
        }

        private String formatLoc(Location location) {
            return String.format("[[%d]], [[%d]], [[%d]]", location.getBlockX(), location.getBlockY(),
                    location.getBlockZ());
        }

        private void toggleRegionEditing() {
            editingRegions = !editingRegions;
            if (!editingRegions) {
                markers.destroyMarkers();
                markerLocations.clear();
                Messaging.sendTr(player.createCommandSourceStack(), Messages.WANDER_WAYPOINTS_REGION_EDITING_STOP);
                return;
            }
            for (Location centre : regionCentres) {
                Entity marker = markers.createMarker(centre, centre);
                if (marker != null) {
                    markerLocations.put(marker, centre);
                }
            }
            Messaging.sendTr(player.createCommandSourceStack(), Messages.WANDER_WAYPOINTS_REGION_EDITING_START);
        }
    }

    /** A view of the region centres that keeps the boundary boxes in step, as upstream's forwarding list does. */
    private class RecalculatingList extends java.util.AbstractList<Location> {
        @Override
        public void add(int index, Location element) {
            regionCentres.add(index, element);
            recalculateBoxes();
        }

        @Override
        public Location get(int index) {
            return regionCentres.get(index);
        }

        @Override
        public Location remove(int index) {
            Location removed = regionCentres.remove(index);
            recalculateBoxes();
            return removed;
        }

        @Override
        public Location set(int index, Location element) {
            Location previous = regionCentres.set(index, element);
            recalculateBoxes();
            return previous;
        }

        @Override
        public int size() {
            return regionCentres.size();
        }
    }
}
