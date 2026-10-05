package net.citizensnpcs.trait.shop;

import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in fixture access to the real shop session owner. */
public final class ReloadShopProbe {
    public static AtomicInteger failOnClose(NPCShop shop) {
        AtomicInteger calls = new AtomicInteger();
        shop.trackSession(calls, counter -> {
            counter.incrementAndGet();
            throw new IllegalStateException("expected shop session cleanup failure");
        });
        return calls;
    }
}
