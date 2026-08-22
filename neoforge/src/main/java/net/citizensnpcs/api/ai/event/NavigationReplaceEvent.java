package net.citizensnpcs.api.ai.event;

import net.citizensnpcs.api.ai.Navigator;

/**
 * Fired when a navigation ends because a new target replaced it, rather than because it failed. A subclass of
 * {@link NavigationCancelEvent} so that a listener watching for "navigation ended" still sees it.
 */
public class NavigationReplaceEvent extends NavigationCancelEvent {
    public NavigationReplaceEvent(Navigator navigator) {
        super(navigator, CancelReason.REPLACE);
    }
}
