package net.citizensnpcs.api.util;

import net.minecraft.server.level.ServerPlayer;

/**
 * Where money comes from, for the shop and command traits.
 * <p>
 * Upstream reads Vault's {@code Economy} service directly. Vault is one of the integrations §2 drops, and NeoForge has no
 * standard economy at all, so this is the seam an economy mod plugs into — the same shape as
 * {@code PermissionUtil.GroupResolver} for Vault groups.
 * <p>
 * With nothing plugged in, a money cost is reported as <em>unaffordable</em> rather than free. A shop set up to charge
 * should not start giving its goods away because the server has no economy installed; failing tells the administrator
 * something is missing, and the alternative loses them money silently.
 */
public interface EconomyProvider {
    boolean deposit(ServerPlayer player, double amount);

    /** @return the amount as it should be shown to a player, e.g. {@code "$3.00"} */
    String format(double amount);

    double getBalance(ServerPlayer player);

    boolean withdraw(ServerPlayer player, double amount);

    /** @return the plugged-in provider, or null when the server has no economy */
    static EconomyProvider getProvider() {
        return Holder.provider;
    }

    static boolean isAvailable() {
        return Holder.provider != null;
    }

    static void setProvider(EconomyProvider newProvider) {
        Holder.provider = newProvider;
    }

    /** Formats an amount even with no provider, so a cost can still be described in a menu. */
    static String describe(double amount) {
        EconomyProvider current = Holder.provider;
        return current == null ? String.format("%.2f", amount) : current.format(amount);
    }

    /** Interfaces cannot hold mutable state, so the single registered provider lives here. */
    final class Holder {
        private Holder() {
        }

        private static volatile EconomyProvider provider;
    }
}
