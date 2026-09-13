package net.yuuniverse.interactions;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/** Optional economy API bridge. Amounts use the configured currency precision; failures propagate to the session. */
public final class Economy {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");
    private static final String MOD_ID = "yuuniverse_economy";

    private Object api;
    private Method credit;
    private Method debit;
    private Method setBalance;
    private Method balance;
    private Method validateSystemShop;
    private Method openSystemShop;
    private Method resolveCurrency;
    private Method resolvePlayerAccount;
    private String currencyId;
    private int scale;

    /** Looks for the economy once, at server start. */
    public void install() {
        api = null;
        validateSystemShop = openSystemShop = null;
        resolveCurrency = resolvePlayerAccount = null;
        if (!ModList.get().isLoaded(MOD_ID)) {
            LOGGER.info("No economy mod present; dialogue \"eco\" actions will be reported rather than paid out.");
            return;
        }
        try {
            Class<?> accessor = Class.forName("net.yuuniverse.economy.api.YuuniverseEconomyApi");
            Object current = accessor.getMethod("current").invoke(null);
            Object service = current instanceof Optional<?> optional ? optional.orElse(null) : current;
            if (service == null) {
                LOGGER.warn("The economy mod is present but has published no API yet; \"eco\" actions will be"
                        + " reported rather than paid out.");
                return;
            }
            Class<?> type = Class.forName("net.yuuniverse.economy.api.EconomyApi");
            credit = type.getMethod("credit", UUID.class, String.class, long.class, String.class, String.class);
            debit = type.getMethod("debit", UUID.class, String.class, long.class, String.class, String.class);
            setBalance = type.getMethod("setBalance", UUID.class, String.class, long.class, String.class, String.class);
            balance = type.getMethod("balance", UUID.class, String.class);
            try {
                validateSystemShop = type.getMethod("validateSystemShop", UUID.class, String.class);
                openSystemShop = type.getMethod("openSystemShop", UUID.class, String.class);
            } catch (NoSuchMethodException olderProvider) {
                LOGGER.info("The economy provider has no system-shop API; monetary actions remain available.");
            }
            try {
                resolveCurrency = type.getMethod("resolveCurrency", String.class);
                resolvePlayerAccount = type.getMethod("resolvePlayerAccount", String.class);
            } catch (NoSuchMethodException olderProvider) {
                LOGGER.info("The economy provider only supports default-currency actions for online recipients.");
            }
            readDefaultCurrency(type, service);
            api = service;
            LOGGER.info("Dialogue \"eco\" actions will go through the economy mod, currency {} with scale {}.",
                    currencyId, scale);
        } catch (Throwable ex) {
            api = null;
            LOGGER.error("The economy mod is present but its API could not be reached, so \"eco\" actions will be"
                    + " reported rather than paid out: {}", ex.toString());
        }
    }

    public void systemShop(String shopId, ServerPlayer player, boolean validate) {
        if (api == null || validateSystemShop == null || openSystemShop == null)
            throw new IllegalStateException("The Yuuniverse Economy system-shop API is unavailable");
        try {
            (validate ? validateSystemShop : openSystemShop).invoke(api, player.getUUID(), shopId);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("System-shop action failed: " + shopId,
                    failure.getCause() == null ? failure : failure.getCause());
        }
    }

    /** Reads the default currency's id and scale, which is what whole-unit amounts have to be multiplied by. */
    private void readDefaultCurrency(Class<?> type, Object service) throws Exception {
        Object definition = type.getMethod("defaultCurrency").invoke(service);
        if (definition == null) {
            List<?> all = (List<?>) type.getMethod("currencyDefinitions").invoke(service);
            definition = all == null || all.isEmpty() ? null : all.get(0);
        }
        if (definition == null) {
            throw new IllegalStateException("Economy has no configured currency");
        }
        currencyId = String.valueOf(definition.getClass().getMethod("id").invoke(definition));
        Object rawScale = definition.getClass().getMethod("scale").invoke(definition);
        scale = rawScale instanceof Number number ? number.intValue() : 0;
    }

    /** Validate dependencies and amounts without modifying balances; execution failures propagate to the session. */
    public boolean handle(String[] parts, ServerPlayer player, boolean validate) {
        String root = parts[0].toLowerCase(Locale.ROOT);
        boolean inquiry = root.equals("balance") || root.equals("money");
        if (!inquiry && !root.equals("eco"))
            return false;
        if (api == null)
            throw new IllegalStateException("Dialogue economy service is unavailable");
        if (inquiry) {
            if (parts.length > 3)
                throw new IllegalArgumentException("Balance expects [player] [currency]");
            Recipient recipient = recipient(player, parts.length >= 2 ? parts[1] : null);
            Currency currency = currency(parts.length == 3 ? parts[2] : null);
            try {
                long minor = ((Number) balance.invoke(api, recipient.id(), currency.id())).longValue();
                if (!validate)
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatableWithFallback(
                            "interactions.balance", "%s: %s %s", recipient.name(),
                            BigDecimal.valueOf(minor, currency.scale()).toPlainString(), currency.id()));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Could not read dialogue balance", ex);
            }
            return true;
        }
        if (parts.length != 4 && parts.length != 5)
            throw new IllegalArgumentException("Economy action expects eco <give|take|set> <player> <amount> [currency]");
        String verb = parts[1].toLowerCase(Locale.ROOT);
        Method target = switch (verb) {
            case "give", "add", "deposit" -> credit;
            case "take", "remove", "withdraw" -> debit;
            case "set" -> setBalance;
            default -> throw new IllegalArgumentException("Unknown economy operation: " + verb);
        };
        Recipient recipient = recipient(player, parts[2]);
        Currency currency = currency(parts.length == 5 ? parts[4] : null);
        long minorUnits = minorUnits(parts[3], currency.scale());
        try {
            if (target == debit && ((Number) balance.invoke(api, recipient.id(), currency.id())).longValue() < minorUnits)
                throw new IllegalStateException("Insufficient balance for dialogue payment");
            if (validate || (minorUnits == 0 && target != setBalance))
                return true;
            // Each execution has its own ledger key. No automatic retry is made after an uncertain outcome.
            target.invoke(api, recipient.id(), currency.id(), minorUnits,
                    "interactions:" + UUID.randomUUID(), "npc-dialogue");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Dialogue economy operation failed", ex.getCause() == null ? ex : ex.getCause());
        }
        return true;
    }

    static long minorUnits(String raw, int scale) {
        BigDecimal whole = new BigDecimal(raw);
        if (whole.signum() < 0)
            throw new IllegalArgumentException("Economy amount must not be negative");
        return whole.movePointRight(scale).longValueExact();
    }

    private Recipient recipient(ServerPlayer actor, String name) {
        if (name == null) return new Recipient(actor.getUUID(), actor.getGameProfile().getName());
        if (resolvePlayerAccount != null) {
            try {
                Object account = ((Optional<?>) resolvePlayerAccount.invoke(api, name)).orElseThrow(
                        () -> new IllegalArgumentException("Unknown economy recipient: " + name));
                UUID id = (UUID) account.getClass().getMethod("ownerUuid").invoke(account);
                if (id == null) throw new IllegalArgumentException("Economy recipient is not a player account");
                return new Recipient(id, String.valueOf(account.getClass().getMethod("displayName").invoke(account)));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not resolve economy recipient " + name,
                        failure.getCause() == null ? failure : failure.getCause());
            }
        }
        ServerPlayer online = actor.getServer().getPlayerList().getPlayerByName(name);
        if (online == null) throw new IllegalArgumentException("The economy provider cannot resolve offline recipients: " + name);
        return new Recipient(online.getUUID(), online.getGameProfile().getName());
    }

    private Currency currency(String name) {
        if (name == null) return new Currency(currencyId, scale);
        if (resolveCurrency == null) throw new IllegalStateException("The economy provider cannot resolve a selected currency");
        try {
            Object definition = ((Optional<?>) resolveCurrency.invoke(api, name)).orElseThrow(
                    () -> new IllegalArgumentException("Unknown economy currency: " + name));
            return new Currency(String.valueOf(definition.getClass().getMethod("id").invoke(definition)),
                    ((Number) definition.getClass().getMethod("scale").invoke(definition)).intValue());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not resolve economy currency " + name,
                    failure.getCause() == null ? failure : failure.getCause());
        }
    }

    private record Recipient(UUID id, String name) { }
    private record Currency(String id, int scale) { }
    /** @return the player's balance in whole units, or -1 when no economy is reachable */
    public double balanceOf(ServerPlayer player) {
        if (api == null || balance == null)
            return -1;
        try {
            Object minor = balance.invoke(api, player.getUUID(), currencyId);
            long value = minor instanceof Number number ? number.longValue() : 0;
            return BigDecimal.valueOf(value).movePointLeft(scale).doubleValue();
        } catch (Throwable ex) {
            return -1;
        }
    }
}
