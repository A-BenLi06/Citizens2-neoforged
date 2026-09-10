package net.citizensnpcs.util;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import net.citizensnpcs.api.util.EconomyProvider;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional native Citizens economy bridge; no dialogue mod or economy classes are required to load Citizens. */
public final class YuuniverseEconomy implements EconomyProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(YuuniverseEconomy.class);
    private static YuuniverseEconomy installed;
    private final Object service;
    private final Method balance, credit, debit;
    private final String currency, symbol;
    private final int scale;

    YuuniverseEconomy(Object service, Class<?> api) throws ReflectiveOperationException {
        this.service = service;
        balance = api.getMethod("balance", UUID.class, String.class);
        credit = api.getMethod("credit", UUID.class, String.class, long.class, String.class, String.class);
        debit = api.getMethod("debit", UUID.class, String.class, long.class, String.class, String.class);
        Object definition = api.getMethod("defaultCurrency").invoke(service);
        if (definition == null) throw new IllegalStateException("Economy has no default currency");
        currency = (String) definition.getClass().getMethod("id").invoke(definition);
        symbol = (String) definition.getClass().getMethod("symbol").invoke(definition);
        scale = ((Number) definition.getClass().getMethod("scale").invoke(definition)).intValue();
        if (currency == null || currency.isBlank() || symbol == null || scale < 0 || scale > 9)
            throw new IllegalStateException("Invalid economy currency definition");
    }

    public static void install() {
        if (EconomyProvider.isAvailable() || !ModList.get().isLoaded("yuuniverse_economy")) return;
        try {
            Class<?> accessor = Class.forName("net.yuuniverse.economy.api.YuuniverseEconomyApi");
            Object service = ((Optional<?>) accessor.getMethod("current").invoke(null)).orElseThrow(
                    () -> new IllegalStateException("Economy API has not been published"));
            var provider = new YuuniverseEconomy(service, Class.forName("net.yuuniverse.economy.api.EconomyApi"));
            EconomyProvider.setProvider(provider);
            installed = provider;
            LOGGER.info("Citizens economy connected: currency {}, scale {}", provider.currency, provider.scale);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LOGGER.error("Could not connect Citizens to Yuuniverse Economy; paid actions remain unavailable", failure);
        }
    }

    public static void uninstall() {
        if (installed != null && EconomyProvider.getProvider() == installed) EconomyProvider.setProvider(null);
        installed = null;
    }

    @Override
    public boolean deposit(ServerPlayer player, double amount) {
        return change(player.getUUID(), amount, true);
    }

    @Override
    public boolean withdraw(ServerPlayer player, double amount) {
        return change(player.getUUID(), amount, false);
    }

    boolean change(UUID player, double amount, boolean deposit) {
        try {
            long minor = minorUnits(amount, scale);
            if (minor == 0) return true;
            // The API operates on existing accounts, normally created by the economy's player login listener.
            // Never retry an uncertain result: each execution gets a distinct ledger operation key.
            (deposit ? credit : debit).invoke(service, player, currency, minor,
                    "citizens:" + UUID.randomUUID(), "npc-transaction");
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOGGER.warn("NPC economy {} failed for {}: {}", deposit ? "deposit" : "withdrawal", player,
                    failure.getCause() == null ? failure.toString() : failure.getCause().toString());
            return false;
        }
    }

    @Override
    public double getBalance(ServerPlayer player) {
        return balanceOf(player.getUUID());
    }

    double balanceOf(UUID player) {
        try {
            return BigDecimal.valueOf(((Number) balance.invoke(service, player, currency)).longValue(), scale)
                    .doubleValue();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOGGER.warn("Could not read NPC economy balance for {}: {}", player, failure.toString());
            return -1;
        }
    }

    @Override
    public String format(double amount) {
        String value = Double.isFinite(amount) ? BigDecimal.valueOf(amount).setScale(
                Math.max(scale, BigDecimal.valueOf(amount).scale())).toPlainString() : Double.toString(amount);
        return symbol.isBlank() ? value + " " + currency : symbol + value;
    }

    static long minorUnits(double amount, int scale) {
        if (!Double.isFinite(amount) || amount < 0 || scale < 0 || scale > 9)
            throw new IllegalArgumentException("Invalid economy amount or currency scale");
        return BigDecimal.valueOf(amount).movePointRight(scale).longValueExact();
    }
}
