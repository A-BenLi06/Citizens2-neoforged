package net.citizensnpcs.editor;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

/**
 * Horses, donkeys, llamas and camels carry their own container, so equipping one means opening that rather than a menu
 * Citizens built. Adding the {@link Inventory} trait first is what makes the contents persist.
 */
public class HorseEquipper implements Equipper {
    @Override
    public void equip(ServerPlayer equipper, NPC toEquip) {
        toEquip.getOrAddTrait(Inventory.class);
        if (toEquip.getEntity() instanceof AbstractHorse horse) {
            horse.openCustomInventoryScreen(equipper);
        }
    }
}
