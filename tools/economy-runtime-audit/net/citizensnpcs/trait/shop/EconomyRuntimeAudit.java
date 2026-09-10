package net.citizensnpcs.trait.shop;

import java.util.Optional;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.citizensnpcs.api.util.EconomyProvider;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.citizensnpcs.util.YuuniverseEconomy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class EconomyRuntimeAudit {
    private static boolean ran;
    private static final UUID PLAYER = UUID.fromString("b9a19437-474e-4dbe-9a5b-ceb9f50b6123");

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || event.getServer().getTickCount() < 10) return;
        ran = true;
        try {
            var provider = EconomyProvider.getProvider();
            check(provider instanceof YuuniverseEconomy, "native_provider_installed");
            Object api = ((Optional<?>) Class.forName("net.yuuniverse.economy.api.YuuniverseEconomyApi")
                    .getMethod("current").invoke(null)).orElseThrow();
            Class<?> type = Class.forName("net.yuuniverse.economy.api.EconomyApi");
            var player = new FakePlayer(event.getServer().overworld(), new GameProfile(PLAYER, "EconomyAudit"));
            if (Boolean.getBoolean("citizens.economyAuditVerify")) {
                check(provider.getBalance(player) == 9.7, "balance_survives_restart");
                LoggerFactory.getLogger("citizens").info("[ECONOMYAUDIT] PERSISTENCE PASS");
                return;
            }
            Object account = type.getMethod("ensurePlayerAccount", UUID.class, String.class)
                    .invoke(api, PLAYER, "EconomyAudit");
            type.getMethod("setBalance", UUID.class, String.class, long.class, String.class, String.class)
                    .invoke(api, PLAYER, "audit", 1000L, "audit:" + UUID.randomUUID(), "isolated-test");
            check(provider.getBalance(player) == 10, "initial_balance");
            var storage = new NPCShopStorage();
            storage.setUnlimited(false);
            storage.setBalance(1);
            var inventory = new InventoryMultiplexer(player.getInventory());
            var debit = new MoneyAction(0.1).take(storage, player, inventory, 3);
            check(debit.isPossible(), "decimal_batch_affordable");
            debit.run();
            check(provider.getBalance(player) == 9.7 && storage.getBalance() == 1.3, "decimal_batch_debited");
            debit.rollback();
            check(provider.getBalance(player) == 10 && storage.getBalance() == 1, "refund_restores_ledger_and_till");
            check(!provider.withdraw(player, 11) && provider.getBalance(player) == 10, "overdraft_rejected");
            check(!provider.deposit(player, 0.001) && provider.getBalance(player) == 10, "precision_rejected");
            Object ledger = api.getClass().getMethod("ledger").invoke(api);
            String accountId = (String) account.getClass().getMethod("id").invoke(account);
            var freeze = ledger.getClass().getMethod("setAccountFrozen", String.class, boolean.class, String.class, String.class);
            freeze.invoke(ledger, accountId, true, "audit", "isolated-test");
            try {
                check(!provider.withdraw(player, 1) && provider.getBalance(player) == 10, "frozen_account_rejected");
            } finally {
                freeze.invoke(ledger, accountId, false, "audit", "isolated-test");
            }
            var missing = new FakePlayer(event.getServer().overworld(), new GameProfile(UUID.randomUUID(), "MissingAudit"));
            check(!provider.withdraw(missing, 1), "missing_account_rejected");
            debit.run();
            check(provider.getBalance(player) == 9.7, "persistence_checkpoint_written");
            LoggerFactory.getLogger("citizens").info("[ECONOMYAUDIT] COMPLETE");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[ECONOMYAUDIT] FAILED", failure);
        } finally {
            event.getServer().halt(false);
        }
    }

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("citizens").info("[ECONOMYAUDIT] PASS {}", name);
    }
}
