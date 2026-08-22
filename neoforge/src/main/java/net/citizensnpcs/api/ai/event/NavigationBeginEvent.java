package net.citizensnpcs.api.ai.event;

import net.citizensnpcs.api.ai.Navigator;

public class NavigationBeginEvent extends NavigationEvent {
    public NavigationBeginEvent(Navigator navigator) {
        super(navigator);
    }
}
