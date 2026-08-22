package net.citizensnpcs.api.ai.event;

import net.citizensnpcs.api.ai.Navigator;

/**
 * A navigation that ended early. Extends {@link NavigationCompleteEvent} so that listeners watching for "navigation
 * finished" see cancellations too, as upstream does.
 */
public class NavigationCancelEvent extends NavigationCompleteEvent {
    private final CancelReason reason;

    public NavigationCancelEvent(Navigator navigator, CancelReason reason) {
        super(navigator);
        this.reason = reason;
    }

    public CancelReason getCancelReason() {
        return reason;
    }
}
