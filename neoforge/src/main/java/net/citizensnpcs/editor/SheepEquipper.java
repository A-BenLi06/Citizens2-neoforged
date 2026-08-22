package net.citizensnpcs.editor;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.trait.SheepTrait;
import net.citizensnpcs.trait.WoolColor;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Sheep are equipped the way a player would treat a real one: shears toggle the sheared state, a dye recolours the wool
 * and is consumed, and an empty hand resets it to white.
 * <p>
 * Upstream tests for a material whose name contains {@code INK_SAC} and reads a legacy {@code Dye} material data. Every
 * dye is its own {@link DyeItem} here, which carries the colour directly.
 */
public class SheepEquipper implements Equipper {
    @Override
    public void equip(ServerPlayer equipper, NPC toEquip) {
        ItemStack hand = equipper.getItemInHand(InteractionHand.MAIN_HAND);
        if (hand.is(Items.SHEARS)) {
            boolean sheared = toEquip.getOrAddTrait(SheepTrait.class).toggleSheared();
            Messaging.sendTr(equipper.createCommandSourceStack(),
                    sheared ? Messages.SHEARED_SET : Messages.SHEARED_STOPPED, toEquip.getName());
            return;
        }
        if (hand.getItem() instanceof DyeItem dye) {
            WoolColor trait = toEquip.getOrAddTrait(WoolColor.class);
            DyeColor colour = dye.getDyeColor();
            if (trait.getColor() == colour)
                return;
            trait.setColor(colour);
            Messaging.sendTr(equipper.createCommandSourceStack(), Messages.EQUIPMENT_EDITOR_SHEEP_COLOURED,
                    toEquip.getName(), Util.prettyEnum(colour));
            hand.shrink(1);
            return;
        }
        toEquip.getOrAddTrait(WoolColor.class).setColor(DyeColor.WHITE);
        Messaging.sendTr(equipper.createCommandSourceStack(), Messages.EQUIPMENT_EDITOR_SHEEP_COLOURED,
                toEquip.getName(), "white");
    }
}
