package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ShopSessionOwnerTest {
    @Test
    void suspensionTokensCannotResumeAnotherReplacementOrRetiredOwner() {
        ShopSessionOwner owner = new ShopSessionOwner();
        AtomicInteger closed = new AtomicInteger();
        owner.track(closed, AtomicInteger::incrementAndGet);
        Runnable first = owner.suspend();
        assertEquals(1, closed.get());
        assertFalse(owner.isActive());
        assertFalse(owner.beginTrade());
        first.run();
        Runnable second = owner.suspend();
        first.run();
        assertFalse(owner.isActive());
        second.run();
        assertTrue(owner.isActive());
        Runnable last = owner.suspend();
        owner.retire();
        last.run();
        assertFalse(owner.isActive());
        assertEquals(1, closed.get());
    }

    @Test
    void stockRetirementClosesOnlyItsSessionsAndKeepsPersistedValues() {
        NPCShop shop = new NPCShop("shared");
        NPCShopStorage stock = new NPCShopStorage();
        stock.setUnlimited(false); stock.setBalance(23);
        AtomicInteger closed = new AtomicInteger();
        stock.trackSession(closed, count -> { count.incrementAndGet(); throw new IllegalStateException("expected stock close failure"); });
        stock.trackSession(closed, AtomicInteger::incrementAndGet);
        assertThrows(IllegalStateException.class, stock::retire);
        assertEquals(2, closed.get());
        assertFalse(stock.isActive());
        assertTrue(shop.isActive());
        assertEquals(23, stock.getBalance());
        assertThrows(IllegalStateException.class, () -> stock.transact(items -> {}));
        assertThrows(IllegalStateException.class, () -> stock.createInventoryViewer(null));
    }
}
