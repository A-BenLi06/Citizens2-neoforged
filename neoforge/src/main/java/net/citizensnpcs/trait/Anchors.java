package net.citizensnpcs.trait;

import java.util.ArrayList;
import java.util.List;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Anchor;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Named locations attached to an NPC, used by commands and by waypoint providers.
 * <p>
 * The on-disk form is unchanged: a numbered list of {@code name;world;x;y;z} strings. An anchor whose dimension is not
 * loaded yet is kept in an unresolved form rather than dropped, and resolved when that dimension loads — the same reason
 * upstream watches for world loads.
 */
@TraitName("anchors")
public class Anchors extends Trait {
    private final List<Anchor> anchors = new ArrayList<>();

    public Anchors() {
        super("anchors");
    }

    public boolean addAnchor(String name, Location location) {
        Anchor newAnchor = new Anchor(name, location);
        if (anchors.contains(newAnchor))
            return false;
        anchors.add(newAnchor);
        return true;
    }

    public Anchor getAnchor(String name) {
        for (Anchor anchor : anchors) {
            if (anchor.getName().equalsIgnoreCase(name))
                return anchor;
        }
        return null;
    }

    public List<Anchor> getAnchors() {
        return anchors;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        anchors.clear();
        for (DataKey sub : key.getRelative("list").getIntegerSubKeys()) {
            String raw = sub.getString("");
            String[] parts = raw.split(";", 2);
            if (parts.length < 2 || parts[1].split(";").length < 4) {
                Messaging.warn("Skipping invalid anchor", sub.name(), "on NPC", npc, "- expected name;world;x;y;z");
                continue;
            }
            // Anchor holds the unresolved "world;x;y;z" itself and resolves it on load(), so an anchor naming a
            // dimension that does not exist yet survives instead of being dropped
            Anchor anchor = new Anchor(parts[0], parts[1]);
            anchor.load();
            anchors.add(anchor);
        }
    }

    public boolean removeAnchor(Anchor anchor) {
        return anchors.remove(anchor);
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("list");
        DataKey list = key.getRelative("list");
        for (int i = 0; i < anchors.size(); i++) {
            list.setString(String.valueOf(i), anchors.get(i).stringValue());
        }
    }

    /** Resolves anchors that named a dimension which had not loaded yet. */
    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel))
            return;
        for (Anchor anchor : anchors) {
            if (!anchor.isLoaded()) {
                anchor.load();
            }
        }
    }
}
