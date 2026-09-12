package net.citizensnpcs.trait.shop;

import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.citizensnpcs.api.util.EconomyProvider;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Isolated, opt-in probes for native shop payment rejection and compensation. */
@EventBusSubscriber(modid = "citizens")
public final class ShopRuntimeAudit {
    private static boolean ran;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || net.citizensnpcs.audit.FixtureRuntimeAudit.elapsedTicks(event.getServer()) < 35) return;
        ran = true;
        EconomyProvider previous = EconomyProvider.getProvider();
        try {
            var economy = new Wallet();
            EconomyProvider.setProvider(economy);
            var player = new FakePlayer(event.getServer().overworld(),
                    new GameProfile(UUID.randomUUID(), "ShopAudit"));
            var inventory = new InventoryMultiplexer(player.getInventory());
            var storage = new NPCShopStorage();
            storage.setUnlimited(false);
            storage.setBalance(50);
            var money = new MoneyAction(10);
            var debit = money.take(storage, player, inventory, 1);
            debit.run();
            check(economy.balance == 90 && storage.getBalance() == 60, "debit_updates_both_balances");
            debit.rollback();
            check(economy.balance == 100 && storage.getBalance() == 50, "refund_restores_both_balances");
            var credit = money.grant(storage, player, inventory, 1);
            credit.run();
            check(economy.balance == 110 && storage.getBalance() == 40, "credit_updates_both_balances");
            credit.rollback();
            check(economy.balance == 100 && storage.getBalance() == 50, "credit_rollback_restores_both_balances");
            var reward = new Reward();
            var item = new NPCShopItem();
            item.getCost().add(money);
            item.getResult().add(reward);
            var shop = new NPCShop("audit");
            economy.rejectDebit = true;
            item.onClick(shop, storage, player, inventory, false, true);
            check(reward.granted == 0 && economy.balance == 100 && storage.getBalance() == 50,
                    "rejected_debit_does_not_deliver_reward");
            economy.rejectDebit = false;
            economy.rejectCredit = true;
            item.getResult().add(money);
            item.onClick(shop, storage, player, inventory, false, true);
            check(reward.granted == 0 && economy.balance == 90 && storage.getBalance() == 60,
                    "rejected_refund_preserves_actual_balances_and_undoes_reward");
            economy.rejectCredit = false;
            economy.balance = 100;
            storage.setBalance(50);
            item.getResult().remove(money);
            item.getResult().add(new Reward() {
                @Override public Transaction grant(NPCShopStorage s, Entity e, InventoryMultiplexer i, int n) {
                    return Transaction.fail();
                }
            });
            item.onClick(shop, storage, player, inventory, false, true);
            check(reward.granted == 0 && economy.balance == 100 && storage.getBalance() == 50,
                    "unavailable_result_refunds_cost_and_earlier_reward");
            item.getResult().removeLast();
            item.onClick(shop, storage, player, inventory, false, true);
            check(reward.granted == 1 && economy.balance == 90 && storage.getBalance() == 60,
                    "successful_purchase_delivers_once");
            LoggerFactory.getLogger("citizens").info("[SHOPAUDIT] COMPLETE 8/8");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[SHOPAUDIT] FAILED", failure);
        } finally {
            EconomyProvider.setProvider(previous);
        }
    }

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("citizens").info("[SHOPAUDIT] PASS {}", name);
    }

    private static final class Wallet implements EconomyProvider {
        double balance = 100;
        boolean rejectDebit, rejectCredit;
        public double getBalance(ServerPlayer p) { return balance; }
        public String format(double amount) { return Double.toString(amount); }
        public boolean deposit(ServerPlayer p, double amount) {
            if (rejectCredit) return false;
            balance += amount;
            return true;
        }
        public boolean withdraw(ServerPlayer p, double amount) {
            if (rejectDebit || balance < amount) return false;
            balance -= amount;
            return true;
        }
    }

    private static class Reward extends NPCShopAction {
        int granted;
        public String describe() { return "audit"; }
        public int getMaxRepeats(Entity e, InventoryMultiplexer i) { return -1; }
        public Transaction grant(NPCShopStorage s, Entity e, InventoryMultiplexer i, int n) {
            return Transaction.create(() -> true, () -> granted += n, () -> granted -= n);
        }
        public Transaction take(NPCShopStorage s, Entity e, InventoryMultiplexer i, int n) {
            return Transaction.fail();
        }
    }
}
