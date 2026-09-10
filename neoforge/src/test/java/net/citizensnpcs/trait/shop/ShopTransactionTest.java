package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.citizensnpcs.trait.shop.NPCShopAction.Transaction;
import org.junit.jupiter.api.Test;

class ShopTransactionTest {
    @Test
    void failedPaymentStopsRewardsAndRefundsCompletedParts() {
        List<String> steps = new ArrayList<>();
        Transaction purchase = Transaction.compose(
                Transaction.create(() -> true, () -> steps.add("debit"), () -> steps.add("refund")),
                Transaction.create(() -> true, () -> { throw new IllegalStateException("rejected"); },
                        () -> fail("Uncompleted payment must not be refunded")),
                Transaction.create(() -> true, () -> fail("Reward must not run"), () -> {}));
        assertThrows(IllegalStateException.class, purchase::run);
        assertEquals(List.of("debit", "refund"), steps);
        purchase.rollback();
        assertEquals(2, steps.size(), "Failure must not leave completed refunds eligible for a second refund");
    }

    @Test
    void rechecksSharedBalanceAfterEarlierDebit() {
        AtomicInteger balance = new AtomicInteger(10);
        Transaction debit = Transaction.create(() -> balance.get() >= 7,
                () -> balance.addAndGet(-7), () -> balance.addAndGet(7));
        Transaction purchase = Transaction.compose(debit, debit);
        assertTrue(purchase.isPossible());
        assertThrows(IllegalStateException.class, purchase::run);
        assertEquals(10, balance.get());
    }

    @Test
    void rollbackAttemptsEveryPartInReverseOrderEvenAfterRefundFailure() {
        List<Integer> refunds = new ArrayList<>();
        Transaction purchase = Transaction.compose(
                Transaction.create(() -> true, () -> {}, () -> refunds.add(1)),
                Transaction.create(() -> true, () -> {}, () -> {
                    refunds.add(2);
                    throw new IllegalStateException("refund rejected");
                }),
                Transaction.create(() -> true, () -> {}, () -> refunds.add(3)));
        purchase.run();
        assertThrows(IllegalStateException.class, purchase::rollback);
        assertEquals(List.of(3, 2, 1), refunds);
        purchase.rollback();
        assertEquals(3, refunds.size());
    }

    @Test
    void preservesOriginalFailureAndReportsRefundFailure() {
        IllegalStateException rejected = new IllegalStateException("debit rejected");
        IllegalStateException refund = new IllegalStateException("refund rejected");
        Transaction purchase = Transaction.compose(
                Transaction.create(() -> true, () -> {}, () -> { throw refund; }),
                Transaction.create(() -> true, () -> { throw rejected; }, () -> {}));
        assertSame(rejected, assertThrows(IllegalStateException.class, purchase::run));
        assertArrayEquals(new Throwable[] { refund }, rejected.getSuppressed());
    }

    @Test
    void nullOptionalPartsDoNotPreventExecution() {
        AtomicInteger value = new AtomicInteger();
        Transaction purchase = Transaction.compose(null,
                Transaction.create(() -> true, value::incrementAndGet, value::decrementAndGet));
        assertTrue(purchase.isPossible());
        purchase.run();
        assertEquals(1, value.get());
        purchase.rollback();
        assertEquals(0, value.get());
    }
}
