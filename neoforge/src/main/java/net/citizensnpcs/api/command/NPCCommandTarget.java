package net.citizensnpcs.api.command;

import net.citizensnpcs.api.npc.NPC;

/** Exact default target for one synchronous native command dispatch. */
public final class NPCCommandTarget {
    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();

    private NPCCommandTarget() { }

    private record Invocation(NPC npc, String command) { }

    /** Explicit commands also enter a scope, so nested dispatch cannot inherit an outer default. */
    public static void dispatch(NPC target, String command, Runnable dispatch) {
        if (target != null && !isCurrent(target)) return;
        Invocation previous = CURRENT.get();
        try {
            if (target == null) CURRENT.remove();
            else CURRENT.set(new Invocation(target, command));
            dispatch.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** Consumed before command callbacks; unrelated or nested commands retain their own targeting rules. */
    public static NPC consume(String command) {
        Invocation invocation = CURRENT.get();
        if (invocation == null || !invocation.command().equals(command)) return null;
        CURRENT.remove();
        return invocation.npc();
    }

    static boolean isCurrent(NPC npc) {
        var registry = npc.getOwningRegistry();
        return registry != null && registry.getById(npc.getId()) == npc
                && registry.getByUniqueId(npc.getUniqueId()) == npc;
    }
}
