package net.citizensnpcs.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** The same Citizens visibility rules for native world trackers and virtual packet entities. */
public final class NPCVisibility {
    private NPCVisibility() { }

    public static boolean isVisible(Entity entity, ServerPlayer viewer) {
        NPC npc = NPCRegistries.lookup(entity);
        return npc == null || isVisible(npc, viewer);
    }

    public static boolean isVisible(NPC npc, ServerPlayer viewer) {
        Set<NPC> visited = new HashSet<>();
        for (NPC current = npc; current != null;) {
            if (!visited.add(current) || current.isHiddenFrom(viewer) || !current.isSpawned()) return false;
            ClickRedirectTrait redirect = current.getTraitNullable(ClickRedirectTrait.class);
            current = redirect == null ? null : redirect.getRedirectToNPC();
        }
        return true;
    }

    /** Called in the world tracking loop, including for stationary NPCs outside simulation distance. */
    public static void refresh(Entity entity) {
        if (NPCRegistries.lookup(entity) != null && entity.level() instanceof ServerLevel level)
            ((WorldTrackers) level.getChunkSource().chunkMap).citizens$refreshVisibility(entity);
    }

    public interface WorldTrackers {
        void citizens$refreshVisibility(Entity entity);
    }

    public interface TrackedEntity {
        void citizens$updateViewers(List<ServerPlayer> viewers);
    }
}
