package net.citizensnpcs.api.npc;

import java.util.Collection;
import java.util.TreeSet;

/** Allocates within the public nonnegative integer ID space, including records that could not be loaded. */
final class NPCIdAllocator {
    private NPCIdAllocator() { }

    static Integer parse(Object value) {
        if (value == null) return null;
        try {
            int id = Integer.parseInt(value.toString());
            return id < 0 ? null : id;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    static int next(NPCRegistry registry, int previous, Collection<Integer> stored) {
        TreeSet<Integer> reserved = new TreeSet<>(stored);
        for (NPC npc : registry) if (npc.getId() >= 0) reserved.add(npc.getId());
        long next = Math.max(previous, reserved.isEmpty() ? -1 : reserved.last()) + 1L;
        if (next > Integer.MAX_VALUE) next = previous == Integer.MAX_VALUE ? 0 : (long) previous + 1;
        long candidate = firstAvailable(reserved, next);
        if (candidate > Integer.MAX_VALUE) candidate = firstAvailable(reserved, 0);
        if (candidate > Integer.MAX_VALUE) throw new IllegalStateException("No NPC IDs are available");
        return (int) candidate;
    }

    private static long firstAvailable(TreeSet<Integer> reserved, long start) {
        long candidate = start;
        for (int id : reserved.tailSet((int) start)) {
            if (id != candidate) break;
            candidate++;
        }
        return candidate;
    }
}
