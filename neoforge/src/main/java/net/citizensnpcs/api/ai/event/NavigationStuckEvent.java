package net.citizensnpcs.api.ai.event;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.StuckAction;

/**
 * Fired when a navigation reports itself stuck, before the {@link StuckAction} runs. A listener can swap the action or
 * clear it entirely by passing null, which leaves the navigation to be cancelled.
 */
public class NavigationStuckEvent extends NavigationEvent {
    private StuckAction action;

    public NavigationStuckEvent(Navigator navigator, StuckAction action) {
        super(navigator);
        this.action = action;
    }

    public StuckAction getAction() {
        return action;
    }

    public void setAction(StuckAction action) {
        this.action = action;
    }
}
