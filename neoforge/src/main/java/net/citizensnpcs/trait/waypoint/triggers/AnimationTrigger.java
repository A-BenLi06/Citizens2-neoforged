package net.citizensnpcs.trait.waypoint.triggers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.TeleportCause;
import net.citizensnpcs.util.PlayerAnimation;
import net.minecraft.server.level.ServerPlayer;

/** Plays animations on a player-type NPC, optionally teleporting it into place first. */
public class AnimationTrigger implements WaypointTrigger {
    @Persist(required = true)
    private List<PlayerAnimation> animations = new ArrayList<>();
    @Persist
    private Location at;

    public AnimationTrigger() {
    }

    public AnimationTrigger(Collection<PlayerAnimation> animations, Location at) {
        this.animations = new ArrayList<>(animations);
        this.at = at;
    }

    @Override
    public String description() {
        List<String> names = new ArrayList<>(animations.size());
        for (PlayerAnimation animation : animations) {
            names.add(animation.name());
        }
        return String.format("[[Animation]] animating %s", String.join(", ", names));
    }

    @Override
    public void onWaypointReached(NPC npc, Location waypoint) {
        if (!(npc.getEntity() instanceof ServerPlayer player))
            return;
        if (at != null) {
            npc.teleport(at, TeleportCause.PLUGIN);
        }
        for (PlayerAnimation animation : animations) {
            animation.play(player);
        }
    }
}
