package net.citizensnpcs.trait.waypoint;

import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persistable;
import net.minecraft.commands.CommandSourceStack;

/** A strategy for deciding where an NPC walks next, and how its route is edited. */
public interface WaypointProvider extends Persistable {
    /**
     * @param sender
     *            the player to link the editor with
     * @param args
     *            the command arguments, for providers that take options
     * @return the editor, or null when this provider has none
     */
    WaypointEditor createEditor(CommandSourceStack sender, CommandContext args);

    boolean isPaused();

    /** Bind context without starting movement; also used while editing an unspawned NPC. */
    default void onAttach(NPC npc) { }

    /** Explicit pause requests, including requests that repeat the current value. */
    default long getPauseRevision() { return -1; }

    /** Changes that invalidate an in-flight waypoint sequence. */
    default long getRouteRevision() { return 0; }

    /** Called when the provider is removed from the NPC. */
    void onRemove();

    void onSpawn(NPC npc);

    void setPaused(boolean paused);

    /** A provider whose route is a plain list, which lets callers read it without knowing the provider. */
    interface EnumerableWaypointProvider extends WaypointProvider {
        Iterable<Waypoint> waypoints();
    }
}
