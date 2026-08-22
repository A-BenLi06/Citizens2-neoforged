package net.citizensnpcs.api.ai.event;

import net.citizensnpcs.api.ai.Navigator;

public class NavigationCompleteEvent extends NavigationEvent {
    public NavigationCompleteEvent(Navigator navigator) {
        super(navigator);
    }
}
