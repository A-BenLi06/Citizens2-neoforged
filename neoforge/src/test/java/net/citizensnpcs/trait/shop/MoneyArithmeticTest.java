package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MoneyArithmeticTest {
    @Test
    void batchAmountsPreserveDecimalPrices() {
        assertEquals(0.3, MoneyAction.total(0.1, 3));
        assertEquals(1.74, MoneyAction.total(0.29, 6));
    }

    @Test
    void affordabilityDoesNotLoseOnePurchaseToBinaryDivision() {
        assertEquals(3, MoneyAction.affordableRepeats(0.3, 0.1));
        assertEquals(3, MoneyAction.affordableRepeats(0.9, 0.3));
        assertEquals(0, MoneyAction.affordableRepeats(-1, 1));
        assertEquals(0, MoneyAction.affordableRepeats(10, Double.NaN));
        assertEquals(-1, MoneyAction.affordableRepeats(0, 0));
        assertEquals(Integer.MAX_VALUE, MoneyAction.affordableRepeats(1e20, 0.01));
    }

    @Test
    void shopTillRestoresDecimalBalancesExactly() {
        double charged = MoneyAction.adjustBalance(0.1, 0.2);
        assertEquals(0.3, charged);
        assertEquals(0.1, MoneyAction.adjustBalance(charged, -0.2));
    }

    @Test
    void unrepresentableTotalsFailInsteadOfRounding() {
        assertThrows(IllegalArgumentException.class, () -> MoneyAction.total(Double.MAX_VALUE, 2));
        assertThrows(IllegalArgumentException.class, () -> MoneyAction.adjustBalance(1e20, 0.01));
        for (double price : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY }) {
            var action = new MoneyAction(price);
            assertFalse(action.take(null, null, null, 1).isPossible());
            assertFalse(action.grant(null, null, null, 1).isPossible());
        }
        assertFalse(new MoneyAction(1).take(null, null, null, -1).isPossible());
    }
}
