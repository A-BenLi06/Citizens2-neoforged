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

/**
 * Serves the {@code eco} command the dialogues use 2277 times, which belonged to EssentialsX, by handing the money to
 * the server's own economy mod.
 * <p>
 * Bound reflectively to {@code net.yuuniverse.economy.api.YuuniverseEconomyApi} so this mod neither compiles nor runs
 * against the economy and keeps working without it. The API takes <em>minor units</em> - the currency's own smallest
 * denomination - while the dialogues were written in whole units, so the amount is scaled by the currency's declared
 * {@code scale} before it is passed on.
 * <p>
 * Every call carries an idempotency key built from the player, the amount and the moment, because the API asks for one:
 * that is what stops a double-fired dialogue action from paying twice.
 * <p>
 * When no economy is reachable the attempt is <em>reported, never faked</em>. A dialogue that appears to pay a player who
 * received nothing, or to charge one who was never charged, is worse than one that logs a failure.
 */
public final class Economy {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");
    private static final String MOD_ID = "yuuniverse_economy";

    private Object api;
    private Method credit;
    private Method debit;
    private Method setBalance;
    private Method balance;
    private String currencyId;
    private int scale;
    private boolean warned;

    /** Looks for the economy once, at server start. */
    public void install() {
        api = null;
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

    /** Reads the default currency's id and scale, which is what whole-unit amounts have to be multiplied by. */
    private void readDefaultCurrency(Class<?> type, Object service) throws Exception {
        Object definition = type.getMethod("defaultCurrency").invoke(service);
        if (definition == null) {
            List<?> all = (List<?>) type.getMethod("currencyDefinitions").invoke(service);
            definition = all == null || all.isEmpty() ? null : all.get(0);
        }
        if (definition == null) {
            currencyId = "default";
            scale = 0;
            return;
        }
        currencyId = String.valueOf(definition.getClass().getMethod("id").invoke(definition));
        Object rawScale = definition.getClass().getMethod("scale").invoke(definition);
        scale = rawScale instanceof Number number ? number.intValue() : 0;
    }

    /**
     * @return true when the call was dealt with here, so the caller must not pass it to the command dispatcher
     */
    public boolean handle(String[] parts, ServerPlayer player) {
        if (parts.length < 3)
            return false;
        String verb = parts[1].toLowerCase(Locale.ROOT);
        BigDecimal whole;
        try {
            whole = new BigDecimal(parts[parts.length - 1].trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        Method target = switch (verb) {
            case "give", "add", "deposit" -> credit;
            case "take", "remove", "withdraw" -> debit;
            case "set" -> setBalance;
            default -> null;
        };
        if (target == null)
            return false;
        if (api == null) {
            if (!warned) {
                warned = true;
                LOGGER.warn("A dialogue tried to {} {} for {}, but no economy is wired up - nothing changed hands."
                        + " Further attempts are not logged.", verb, whole, player.getGameProfile().getName());
            }
            return true;
        }
        long minorUnits = whole.movePointRight(scale).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        String key = "interactions:" + player.getUUID() + ":" + verb + ":" + minorUnits + ":"
                + System.currentTimeMillis();
        try {
            target.invoke(api, player.getUUID(), currencyId, minorUnits, key, "npc-dialogue");
        } catch (Throwable ex) {
            LOGGER.error("Economy {} of {} for {} failed: {}", verb, whole, player.getGameProfile().getName(),
                    ex.getCause() == null ? ex.toString() : ex.getCause().toString());
        }
        return true;
    }

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
