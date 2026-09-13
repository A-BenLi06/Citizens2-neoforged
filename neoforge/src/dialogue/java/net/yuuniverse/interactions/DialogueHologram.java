package net.yuuniverse.interactions;

import java.util.ArrayList;
import java.util.List;
import net.citizensnpcs.util.EntityPacketTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/** Viewer-private virtual nameplates, never inserted into a world or NPC registry. */
final class DialogueHologram {
    private ServerPlayer player;
    private final ServerLevel level;
    private final Vec3 anchor;
    private final List<EntityPacketTracker> trackers = new ArrayList<>();
    private List<Component> text = List.of();

    DialogueHologram(ServerPlayer player, Entity npc) {
        this.player = player;
        level = player.serverLevel();
        // Upstream keeps the conversation's starting location, including when the NPC later moves.
        anchor = npc == null ? null : npc.position();
    }

    void show(List<Component> lines, HologramSettings settings) {
        if (!settings.enabled() || anchor == null || player.connection == null || player.serverLevel() != level) {
            close();
            return;
        }
        if (text.equals(lines)) return;
        close();
        text = lines.stream().map(Component::copy).map(Component.class::cast).toList();
        Vec3 top = settings.top(anchor, player.position(), player.getYRot(), lines.size());
        try {
            for (int i = 0; i < lines.size(); i++) {
                // Empty rows keep their layout space without creating a visible zero-width nameplate background.
                if (lines.get(i).getString().isEmpty()) continue;
                ArmorStand stand = EntityType.ARMOR_STAND.create(level);
                if (stand == null) throw new IllegalStateException("Could not create a dialogue nameplate");
                stand.setMarker(true);
                stand.setInvisible(true);
                stand.setNoGravity(true);
                stand.setInvulnerable(true);
                stand.setCustomName(lines.get(i));
                stand.setCustomNameVisible(true);
                // A marker has zero height; vanilla's name renderer adds half a block to its attachment point.
                stand.setPos(top.x, top.y - i * HologramSettings.LINE_HEIGHT - 0.5, top.z);
                EntityPacketTracker tracker = new EntityPacketTracker(stand);
                trackers.add(tracker);
                tracker.link(player);
            }
        } catch (RuntimeException failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void rebind(ServerPlayer replacement) {
        for (EntityPacketTracker tracker : trackers) tracker.unlink(player);
        player = replacement;
    }

    void refreshViewer() {
        if (player.serverLevel() != level) { close(); return; }
        if (player.connection == null) return;
        for (EntityPacketTracker tracker : trackers) tracker.link(player);
    }

    void close() {
        RuntimeException failure = null;
        for (EntityPacketTracker tracker : trackers) {
            try { tracker.unlinkAll(null); }
            catch (RuntimeException ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            }
        }
        trackers.clear();
        text = List.of();
        if (failure != null) throw failure;
    }
}
