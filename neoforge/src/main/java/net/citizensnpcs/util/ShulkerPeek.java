package net.citizensnpcs.util;

import net.minecraft.world.entity.monster.Shulker;

/** A temporary, quiet peek request; later native requests retain ownership, even for the same amount. */
public final class ShulkerPeek {
    public interface Access {
        int citizens$peek();
        long citizens$peekRevision();
        void citizens$peekQuietly(int amount);
    }

    private final Shulker entity;
    private final Access access;
    private int baseline;
    private int written;
    private long revision = -1;
    private boolean pending;
    private boolean released;

    public ShulkerPeek(Shulker entity, int baseline) {
        this.entity = entity;
        this.access = (Access) entity;
        this.baseline = clamp(baseline);
    }

    public boolean controls(Shulker entity) {
        return !released && this.entity == entity;
    }

    public void baseline(int amount) {
        baseline = clamp(amount);
        if (!pending) {
            written = access.citizens$peek();
            revision = access.citizens$peekRevision();
        }
    }

    public void look(double distanceSquared) {
        if (released || pending) return;
        if (revision >= 0 && !owns()) {
            baseline = clamp(access.citizens$peek());
            revision = -1;
        }
        // Clamp as a double before narrowing: large configured look ranges must never wrap a native byte.
        int amount = (int) Math.max(0, 100 - 4 * Math.floor(Math.max(0, distanceSquared)));
        // The first request also initializes vanilla's covered-armor modifier for an already closed shell.
        if (revision >= 0 && access.citizens$peek() == amount) return;
        written = amount;
        revision = access.citizens$peekRevision() + 1;
        pending = true;
        try {
            access.citizens$peekQuietly(amount);
        } finally {
            pending = false;
        }
    }

    public void release() {
        if (released) return;
        released = true;
        // Native game events occur before the metadata write. A nested release supersedes the pending write too.
        if (owns() && (pending || access.citizens$peek() != baseline)) access.citizens$peekQuietly(baseline);
    }

    private boolean owns() {
        return revision >= 0 && access.citizens$peekRevision() == revision
                && (pending || access.citizens$peek() == written);
    }

    public static int clamp(int amount) {
        return Math.max(0, Math.min(100, amount));
    }
}
