package net.citizensnpcs.npc.ai;

import net.citizensnpcs.api.npc.NPC;

/**
 * Implemented by every entity class Citizens creates, so that an entity can be traced back to its NPC.
 * <p>
 * Upstream additionally tags entities with Bukkit metadata keys ({@code "NPC"}, {@code "NPC-ID"}, {@code "NPC-NAME"}),
 * because other plugins read those. NeoForge has no equivalent metadata system and mods would use this interface
 * directly, so the tagging is dropped and this is the single source of truth.
 */
public interface NPCHolder {
    NPC getNPC();
}
