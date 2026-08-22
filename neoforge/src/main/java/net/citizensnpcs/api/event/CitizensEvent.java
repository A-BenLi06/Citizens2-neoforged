package net.citizensnpcs.api.event;

import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Base class for every event Citizens fires.
 * <p>
 * Upstream extends Bukkit's {@code Event}, which requires each subclass to carry the {@code HandlerList} boilerplate
 * ({@code getHandlers()}, a static {@code getHandlerList()}, a static field). NeoForge's bus dispatches on the event
 * class itself, so all of that is dropped — a subclass is just a data carrier. Cancellable events implement
 * {@link net.neoforged.bus.api.ICancellableEvent}, which supplies {@code isCanceled()}/{@code setCanceled(boolean)}.
 * <p>
 * Upstream's {@code async} flag has no counterpart: Citizens never fires these off-thread on NeoForge, so the
 * constructor taking it is gone rather than kept as a lie.
 */
public abstract class CitizensEvent extends Event {
    protected CitizensEvent() {
    }

    /**
     * Fires this event on the NeoForge event bus.
     *
     * @return this, so callers can post and inspect in one expression
     */
    public <T extends CitizensEvent> T callEvent() {
        NeoForge.EVENT_BUS.post(this);
        @SuppressWarnings("unchecked")
        T self = (T) this;
        return self;
    }
}
