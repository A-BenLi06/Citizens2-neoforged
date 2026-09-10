package net.citizensnpcs.trait.shop;

import java.math.BigDecimal;
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
        if (!(entity instanceof ServerPlayer player))
            return -1;
        EconomyProvider economy = EconomyProvider.getProvider();
        return affordableRepeats(economy == null ? -1 : economy.getBalance(player), money);
    }

    @Override
    public Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        if (!Double.isFinite(money) || money < 0 || repeats < 0)
            return Transaction.fail();
        if (money == 0 || repeats == 0)
            return Transaction.success();
        if (!(entity instanceof ServerPlayer player))
            return Transaction.fail();
        EconomyProvider economy = EconomyProvider.getProvider();
        if (economy == null)
            return unavailable();
        double amount = total(money, repeats);
        return Transaction.create(() -> storage.isUnlimited() || storage.getBalance() >= amount, () -> {
            double nextBalance = storage.isUnlimited() ? 0 : adjustBalance(storage.getBalance(), -amount);
            if (economy.deposit(player, amount)) {
                storage.setBalance(nextBalance);
            } else {
                throw new IllegalStateException("Economy rejected NPC shop deposit of " + amount);
            }
        }, () -> {
            double nextBalance = storage.isUnlimited() ? 0 : adjustBalance(storage.getBalance(), amount);
            if (!economy.withdraw(player, amount))
                throw new IllegalStateException("Economy rejected NPC shop deposit rollback of " + amount);
            storage.setBalance(nextBalance);
        });
    }

    @Override
    public Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        if (!Double.isFinite(money) || money < 0 || repeats < 0)
            return Transaction.fail();
        if (money == 0 || repeats == 0)
            return Transaction.success();
        if (!(entity instanceof ServerPlayer player))
            return Transaction.fail();
        EconomyProvider economy = EconomyProvider.getProvider();
        if (economy == null)
            return unavailable();
        double amount = total(money, repeats);
        return Transaction.create(() -> economy.getBalance(player) >= amount, () -> {
            double nextBalance = storage.isUnlimited() ? 0 : adjustBalance(storage.getBalance(), amount);
            if (economy.withdraw(player, amount)) {
                storage.setBalance(nextBalance);
            } else {
                throw new IllegalStateException("Economy rejected NPC shop withdrawal of " + amount);
            }
        }, () -> {
            double nextBalance = storage.isUnlimited() ? 0 : adjustBalance(storage.getBalance(), -amount);
            if (!economy.deposit(player, amount))
                throw new IllegalStateException("Economy rejected NPC shop payment refund of " + amount);
            storage.setBalance(nextBalance);
        });
    }

    static double total(double price, int repeats) {
        return exactDouble(BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(repeats)));
    }

    static double adjustBalance(double balance, double delta) {
        return exactDouble(BigDecimal.valueOf(balance).add(BigDecimal.valueOf(delta)));
    }

    private static double exactDouble(BigDecimal value) {
        double result = value.doubleValue();
        if (!Double.isFinite(result) || BigDecimal.valueOf(result).compareTo(value) != 0)
            throw new IllegalArgumentException("Money amount exceeds the economy provider's numeric range");
        return result;
    }

    static int affordableRepeats(double balance, double price) {
        if (!Double.isFinite(price) || price < 0) return 0;
        if (price == 0) return -1;
        if (!Double.isFinite(balance) || balance < 0) return 0;
        return BigDecimal.valueOf(balance).divideToIntegralValue(BigDecimal.valueOf(price))
                .min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValueExact();
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
                    if (!Double.isFinite(result) || result < 0)
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
