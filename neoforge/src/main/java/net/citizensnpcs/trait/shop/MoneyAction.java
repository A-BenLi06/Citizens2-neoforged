package net.citizensnpcs.trait.shop;

import java.util.function.Consumer;

import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.EconomyProvider;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * A cost or reward paid in money.
 * <p>
 * Upstream reads Vault's {@code Economy} service. Vault is dropped, so this goes through {@link EconomyProvider} — and
 * with no provider registered a money cost is <em>unaffordable</em> rather than free, so a paid shop does not start giving
 * its goods away on a server that has no economy installed.
 */
public class MoneyAction extends NPCShopAction {
    @Persist
    public double money;

    public MoneyAction() {
    }

    public MoneyAction(double cost) {
        money = cost;
    }

    @Override
    public String describe() {
        return EconomyProvider.describe(money);
    }

    @Override
    public int getMaxRepeats(Entity entity, InventoryMultiplexer inventory) {
        if (!(entity instanceof ServerPlayer player) || money <= 0)
            return -1;
        EconomyProvider economy = EconomyProvider.getProvider();
        return economy == null ? 0 : (int) Math.floor(economy.getBalance(player) / money);
    }

    @Override
    public Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        if (money <= 0)
            return Transaction.success();
        if (!(entity instanceof ServerPlayer player))
            return Transaction.fail();
        EconomyProvider economy = EconomyProvider.getProvider();
        if (economy == null)
            return unavailable();
        double amount = money * repeats;
        return Transaction.create(() -> storage.isUnlimited() || storage.getBalance() - amount >= 0, () -> {
            if (economy.deposit(player, amount)) {
                storage.setBalance(storage.getBalance() - amount);
            } else {
                throw new IllegalStateException("Economy rejected NPC shop deposit of " + amount);
            }
        }, () -> {
            if (!economy.withdraw(player, amount))
                throw new IllegalStateException("Economy rejected NPC shop deposit rollback of " + amount);
            storage.setBalance(storage.getBalance() + amount);
        });
    }

    @Override
    public Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        if (money <= 0)
            return Transaction.success();
        if (!(entity instanceof ServerPlayer player))
            return Transaction.fail();
        EconomyProvider economy = EconomyProvider.getProvider();
        if (economy == null)
            return unavailable();
        double amount = money * repeats;
        return Transaction.create(() -> economy.getBalance(player) >= amount, () -> {
            if (economy.withdraw(player, amount)) {
                storage.setBalance(storage.getBalance() + amount);
            } else {
                throw new IllegalStateException("Economy rejected NPC shop withdrawal of " + amount);
            }
        }, () -> {
            if (!economy.deposit(player, amount))
                throw new IllegalStateException("Economy rejected NPC shop payment refund of " + amount);
            storage.setBalance(storage.getBalance() - amount);
        });
    }

    /** No economy is installed, so a money cost cannot be met - and must not be waived. */
    private static Transaction unavailable() {
        return Transaction.create(() -> {
            Messaging.severe("An NPC tried to charge money but no economy is registered;"
                    + " an economy mod must call EconomyProvider.setProvider");
            return false;
        }, () -> {
        }, () -> {
        });
    }

    public static class MoneyActionGUI implements GUI {
        @Override
        public boolean canUse(ServerPlayer entity) {
            return PermissionUtil.hasPermission(entity, "citizens.npc.shop.editor.actions.edit-money");
        }

        @Override
        public InventoryMenuPage createEditor(NPCShopAction previous, Consumer<NPCShopAction> callback) {
            MoneyAction action = previous == null ? new MoneyAction() : (MoneyAction) previous;
            return InputMenus.filteredStringSetter(() -> Double.toString(action.money), input -> {
                try {
                    double result = Double.parseDouble(input);
                    if (result < 0)
                        return false;
                    action.money = result;
                } catch (NumberFormatException ex) {
                    return false;
                }
                callback.accept(action);
                return true;
            });
        }

        @Override
        public ItemStack createMenuItem(NPCShopAction previous) {
            return Util.createItem("minecraft:gold_ingot", "Money", previous == null ? null : previous.describe());
        }

        @Override
        public boolean manages(NPCShopAction action) {
            return action instanceof MoneyAction;
        }
    }
}
