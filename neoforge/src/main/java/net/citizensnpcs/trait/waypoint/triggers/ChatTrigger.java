package net.citizensnpcs.trait.waypoint.triggers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

/** Says something on reaching the waypoint, either to everyone in the level or to players within a radius. */
public class ChatTrigger implements WaypointTrigger {
    @Persist(required = true)
    private List<String> lines;
    @Persist
    private double radius = -1;

    public ChatTrigger() {
    }

    public ChatTrigger(double radius, Collection<String> chatLines) {
        this.radius = radius;
        this.lines = new ArrayList<>(chatLines);
    }

    @Override
    public String description() {
        return String.format("[[Chat]] [radius %.1f, %s]", radius, lines == null ? "" : String.join(", ", lines));
    }

    @Override
    public void onWaypointReached(NPC npc, Location waypoint) {
        if (!npc.isSpawned() || lines == null || lines.isEmpty())
            return;
        Iterable<ServerPlayer> recipients = radius <= 0
                ? ((ServerLevel) npc.getEntity().level()).players()
                : EntityUtil.getNearbyVisiblePlayers(npc.getEntity(), radius);
        for (ServerPlayer player : recipients) {
            for (String line : lines) {
                Messaging.send(player.createCommandSourceStack(), line);
            }
        }
    }
}
