package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class YuuniverseEconomyTest {
    @Test
    void convertsConfiguredPrecisionWithoutRounding() {
        assertEquals(123, YuuniverseEconomy.minorUnits(1.23, 2));
        assertEquals(12, YuuniverseEconomy.minorUnits(12, 0));
        assertEquals(1, YuuniverseEconomy.minorUnits(0.000000001, 9));
        for (double value : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, 1.234 })
            assertThrows(RuntimeException.class, () -> YuuniverseEconomy.minorUnits(value, 2));
    }

    @Test
    void callsReflectedLedgerWithConfiguredCurrencyAndUniqueOperationKeys() throws Exception {
        Ledger ledger = new Ledger();
        var provider = new YuuniverseEconomy(ledger, Ledger.class);
        UUID player = UUID.randomUUID();
        assertTrue(provider.change(player, 1.25, true));
        assertEquals(11.25, provider.balanceOf(player));
        assertTrue(provider.change(player, 0.25, false));
        assertEquals(11, provider.balanceOf(player));
        assertEquals(2, ledger.keys.size());
        assertEquals("credits", ledger.currency);
        assertEquals("C11.00", provider.format(11));
        assertEquals("C1.234", provider.format(1.234), "Formatting must not silently round an invalid price");
    }

    @Test
    void rejectsUnavailableLedgerAndInvalidAmountsWithoutMutation() throws Exception {
        Ledger ledger = new Ledger();
        var provider = new YuuniverseEconomy(ledger, Ledger.class);
        UUID player = UUID.randomUUID();
        assertFalse(provider.change(player, 11, false));
        assertFalse(provider.change(player, 1.001, true));
        assertTrue(provider.change(player, 0, true));
        assertEquals(0, ledger.keys.size());
        assertEquals(10, provider.balanceOf(player));
        ledger.unavailable = true;
        assertEquals(-1, provider.balanceOf(player));
        assertFalse(provider.change(player, 1, true));
    }

    public record Currency(String id, String symbol, int scale) {}

    /** The public API signatures are intentional: reflection must not require implementation-only methods. */
    public static class Ledger {
        long value = 1000;
        boolean unavailable;
        String currency;
        Set<String> keys = new HashSet<>();
        public Currency defaultCurrency() { return new Currency("credits", "C", 2); }
        public long balance(UUID player, String currency) {
            if (unavailable) throw new IllegalStateException("unavailable");
            return value;
        }
        public Object credit(UUID player, String currency, long amount, String key, String source) {
            if (unavailable) throw new IllegalStateException("unavailable");
            record(currency, key, source);
            value += amount;
            return new Object();
        }
        public Object debit(UUID player, String currency, long amount, String key, String source) {
            if (unavailable || value < amount) throw new IllegalStateException("rejected");
            record(currency, key, source);
            value -= amount;
            return new Object();
        }
        private void record(String currency, String key, String source) {
            this.currency = currency;
            assertTrue(keys.add(key));
            assertEquals("npc-transaction", source);
        }
    }
}
