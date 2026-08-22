package net.citizensnpcs.api.ai.event;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import net.neoforged.bus.api.Event;

public abstract class NavigationEvent extends Event {
    private final Navigator navigator;

    protected NavigationEvent(Navigator navigator) {
        this.navigator = navigator;
    }

    public Navigator getNavigator() {
        return navigator;
    }

    public NPC getNPC() {
        return navigator.getNPC();
    }
}
