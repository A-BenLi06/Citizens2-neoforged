package net.citizensnpcs.npc;

import java.util.IdentityHashMap;
import java.util.Map;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRemoveEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.util.schedulers.SchedulerTask;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Session-only lifetimes belong to the exact temporary NPC and registry that created them. */
@EventBusSubscriber(modid = "citizens")
public final class NPCLifetimes {
    private static final Map<NPC, Expiry> pending = new IdentityHashMap<>();
    private NPCLifetimes() { }

    public static void expireAfter(NPC npc, long ticks) {
        if (ticks < 0) throw new IllegalArgumentException("Lifetime must not be negative");
        cancel(npc);
        Expiry expiry = new Expiry(npc);
        pending.put(npc, expiry);
        expiry.task = CitizensAPI.getScheduler().runTaskLater(expiry, ticks);
    }

    public static void cancel(NPC npc) {
        Expiry expiry = pending.remove(npc);
        if (expiry != null && expiry.task != null) expiry.task.cancel();
    }

    @SubscribeEvent public static void removed(NPCRemoveEvent event) { cancel(event.getNPC()); }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        pending.values().forEach(expiry -> { if (expiry.task != null) expiry.task.cancel(); });
        pending.clear();
    }

    private static final class Expiry implements Runnable {
        final NPC npc;
        final NPCRegistry registry;
        SchedulerTask task;
        Expiry(NPC npc) { this.npc = npc; registry = npc.getOwningRegistry(); }
        @Override public void run() {
            if (!pending.remove(npc, this)) return;
            if (npc.getOwningRegistry() == registry && registry.getByUniqueId(npc.getUniqueId()) == npc
                    && registry.getById(npc.getId()) == npc) npc.destroy();
        }
    }
}
