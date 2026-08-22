package net.citizensnpcs.trait.shop;

import java.util.function.Consumer;

import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/** A cost or reward paid in experience levels. */
public class ExperienceAction extends NPCShopAction {
    @Persist
    public int exp;

    public ExperienceAction() {
    }

    public ExperienceAction(int cost) {
        exp = cost;
    }

    @Override
    public String describe() {
        return exp == 1 ? exp + " level" : exp + " levels";
    }

    @Override
    public int getMaxRepeats(Entity entity, InventoryMultiplexer inventory) {
        if (!(entity instanceof ServerPlayer player) || exp <= 0)
            return -1;
        return player.experienceLevel / exp;
    }

    @Override
    public Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        if (!(entity instanceof ServerPlayer player))
            return Transaction.fail();
        int amount = exp * repeats;
        return Transaction.create(() -> true, () -> player.giveExperienceLevels(amount),
                () -> player.giveExperienceLevels(-amount));
    }

    @Override
    public Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        if (!(entity instanceof ServerPlayer player))
            return Transaction.fail();
        int amount = exp * repeats;
        return Transaction.create(() -> player.experienceLevel >= amount,
                () -> player.giveExperienceLevels(-amount), () -> player.giveExperienceLevels(amount));
    }

    public static class ExperienceActionGUI implements GUI {
        @Override
        public boolean canUse(ServerPlayer entity) {
            return PermissionUtil.hasPermission(entity, "citizens.npc.shop.editor.actions.edit-exp");
        }

        @Override
        public InventoryMenuPage createEditor(NPCShopAction previous, Consumer<NPCShopAction> callback) {
            ExperienceAction action = previous == null ? new ExperienceAction() : (ExperienceAction) previous;
            return InputMenus.filteredStringSetter(() -> Integer.toString(action.exp), input -> {
                try {
                    int result = Integer.parseInt(input);
                    if (result < 0)
                        return false;
                    action.exp = result;
                } catch (NumberFormatException ex) {
                    return false;
                }
                callback.accept(action);
                return true;
            });
        }

        @Override
        public ItemStack createMenuItem(NPCShopAction previous) {
            return Util.createItem("minecraft:experience_bottle", "XP Level",
                    previous == null ? null : previous.describe());
        }

        @Override
        public boolean manages(NPCShopAction action) {
            return action instanceof ExperienceAction;
        }
    }
}
