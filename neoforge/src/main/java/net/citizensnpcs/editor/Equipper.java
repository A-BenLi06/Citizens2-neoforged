package net.citizensnpcs.editor;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.server.level.ServerPlayer;

/**
 * Equips an NPC from whatever the player is holding, for entity types configured by handing them an item rather than
 * through a menu.
 */
public interface Equipper {
    void equip(ServerPlayer equipper, NPC toEquip);
}
