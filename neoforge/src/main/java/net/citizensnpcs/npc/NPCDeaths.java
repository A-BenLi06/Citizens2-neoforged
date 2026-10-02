package net.citizensnpcs.npc;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.CurrentLocation;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.schedulers.SchedulerTask;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.trait.HomeTrait;
import net.citizensnpcs.trait.SentinelTrait;
import net.citizensnpcs.util.NPCVisibility;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/** Accepted native deaths own their drops, detached visuals and pending respawns. */
public final class NPCDeaths {
    private static final Map<LivingEntity, Death> active = new IdentityHashMap<>();
    private static final Map<CitizensNPC, Death> pending = new IdentityHashMap<>();
    private static final Map<Entity, Death> visuals = new IdentityHashMap<>();

    private NPCDeaths() { }

    public static void duringDeath(LivingEntity entity, DamageSource source, Runnable nativeDeath) {
        NPC owner = NPCRegistries.lookup(entity);
        if (!(owner instanceof CitizensNPC npc) || !npc.ownsEntity(npc.controller(), entity)) {
            nativeDeath.run();
            return;
        }
        if (active.containsKey(entity)) return;
        Death death = new Death(npc, entity, source);
        active.put(entity, death);
        try {
            nativeDeath.run();
        } finally {
            try {
                if (death.accepted) finish(death);
            } finally {
                active.remove(entity, death);
            }
        }
    }

    /** Called only after NeoForge's cancellation and the native duplicate-death guards. */
    public static void accepted(LivingEntity entity) {
        Death death = active.get(entity);
        if (death == null || death.accepted || !death.ownsEntity()) return;
        death.accepted = true;
        death.location = Location.of(entity);
        death.sentinel = death.npc.getTraitNullable(SentinelTrait.class);
        if (entity instanceof EntityHumanNPC human) human.markDeathAccepted();
    }

    public static boolean isAccepted(Entity entity) {
        Death death = active.get(entity);
        return death != null && death.accepted;
    }

    public static void drops(LivingDropsEvent event) {
        Death death = active.get(event.getEntity());
        if (death != null) {
            notifyDrops(event, death);
            return;
        }
        NPC owner = NPCRegistries.lookup(event.getEntity());
        if (!(owner instanceof CitizensNPC npc) || !npc.ownsEntity(npc.controller(), event.getEntity())) return;
        // Some native entities (notably armor stands) publish drops without entering LivingEntity.die.
        // Preserve their existing drop event contract. This scope owns notification only: their native
        // destruction method, rather than the ordinary twenty-tick death path, still owns removal.
        Death notification = new Death(npc, event.getEntity(), event.getSource());
        notification.accepted = true;
        active.put(event.getEntity(), notification);
        try {
            notifyDrops(event, notification);
        } finally {
            active.remove(event.getEntity(), notification);
        }
    }

    private static void notifyDrops(LivingDropsEvent event, Death death) {
        if (!death.accepted || death.notified || !death.ownsEntity()) return;
        List<ItemStack> stacks = new ArrayList<>();
        Map<ItemStack, ItemEntity> original = new IdentityHashMap<>();
        if (death.npc.data().get(NPC.Metadata.DROPS_ITEMS, false)) {
            for (ItemEntity item : event.getDrops()) {
                stacks.add(item.getItem());
                original.put(item.getItem(), item);
            }
        }
        death.notified = true;
        NeoForge.EVENT_BUS.post(new NPCDeathEvent(death.npc, event.getSource(), stacks));
        event.getDrops().clear();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            ItemEntity item = original.remove(stack);
            if (item == null) {
                item = new ItemEntity(death.entity.level(), death.entity.getX(), death.entity.getY(),
                        death.entity.getZ(), stack.copy());
                item.setDefaultPickUpDelay();
            }
            event.getDrops().add(item);
        }
    }

    private static void finish(Death death) {
        if (!death.ownsEntity()) return;
        try {
            if (!death.notified) {
                death.notified = true;
                // Some native deaths intentionally skip loot. Notify lifecycle listeners without inventing loot.
                NeoForge.EVENT_BUS.post(new NPCDeathEvent(death.npc, death.source, new ArrayList<>()));
            }
        } finally {
            if (death.ownsEntity()) retire(death);
        }
    }

    private static void retire(Death death) {
        CitizensNPC npc = death.npc;
        invalidate(npc);
        death.viewers = NPCVisibility.viewers(death.entity);
        pending.put(npc, death);
        visuals.put(death.entity, death);
        death.visual = () -> death.entity.remove(Entity.RemovalReason.KILLED);
        if (death.sentinel != null && npc.getTraitNullable(SentinelTrait.class) == death.sentinel) {
            if (death.sentinel.getRespawnTime() == 0) npc.getOrAddTrait(Spawned.class).setSpawned(false);
        }
        npc.despawn(DespawnReason.DEATH);
        if (!death.isPending()) return;
        schedule(death, 20, death::finishVisual);
        SentinelTrait sentinel = death.sentinel;
        if (sentinel != null && npc.getTraitNullable(SentinelTrait.class) == sentinel) {
            int delay = sentinel.getRespawnTime();
            Location spawn = sentinel.getSpawnPoint();
            if (spawn != null) npc.getOrAddTrait(CurrentLocation.class).setLocation(spawn.clone());
            if (delay < 0) {
                schedule(death, 1, () -> { if (death.canRespawn() && npc.getTraitNullable(SentinelTrait.class) == sentinel) npc.destroy(); });
            } else if (delay > 0) {
                // The original timer checks its counter before increasing it, on ten-tick boundaries.
                long ticks = (((long) delay + 9) / 10 + 1) * 10;
                schedule(death, ticks, () -> {
                    if (!death.canRespawn() || npc.getTraitNullable(SentinelTrait.class) != sentinel) return;
                    Location at = sentinel.getSpawnPoint();
                    if (at == null) at = npc.getStoredLocation();
                    if (at != null) npc.spawn(at.clone(), SpawnReason.RESPAWN);
                });
            }
        }
        int delay = npc.data().get(NPC.Metadata.RESPAWN_DELAY, -1);
        if (delay >= 0) schedule(death, (long) delay + 20, () -> {
            if (!death.canRespawn()) return;
            HomeTrait home = npc.getTraitNullable(HomeTrait.class);
            Location at = home == null ? null : home.getHomeLocation();
            npc.spawn(at == null ? death.location.clone() : at.clone(), SpawnReason.TIMED_RESPAWN);
        });
    }

    public static void retainVisual(Entity entity, Runnable cleanup) {
        Death death = active.get(entity);
        if (death != null && pending.get(death.npc) == death) death.visual = cleanup;
        else cleanup.run();
    }

    public static boolean isVisual(Entity entity) { return visuals.containsKey(entity); }

    /** Only the viewers present at death retain the corpse; a new viewer never acquires it. */
    public static boolean isVisualVisibleTo(Entity entity, ServerPlayer viewer) {
        Death death = visuals.get(entity);
        return death != null && death.viewers.stream().anyMatch(player -> player == viewer)
                && !viewer.hasDisconnected() && viewer.level() == entity.level()
                && viewer.getServer().getPlayerList().getPlayer(viewer.getUUID()) == viewer
                && !death.npc.isHiddenFrom(viewer);
    }

    /** A manual spawn must retire the previous visual before reusing its UUID/profile. */
    public static void beforeSpawn(CitizensNPC npc) {
        Death death = pending.get(npc);
        if (death != null) death.finishVisual();
    }

    public static void invalidate(NPC npc) {
        Death death = pending.remove(npc);
        if (death == null) return;
        death.tasks.forEach(SchedulerTask::cancel);
        death.finishVisual();
    }

    public static void clear() {
        for (CitizensNPC npc : List.copyOf(pending.keySet())) invalidate(npc);
    }

    private static void schedule(Death death, long delay, Runnable action) {
        death.remaining++;
        death.tasks.add(CitizensAPI.getScheduler().runTaskLater(() -> {
            try {
                if (death.isPending()) action.run();
            } finally {
                if (--death.remaining == 0) pending.remove(death.npc, death);
            }
        }, delay));
    }

    private static final class Death {
        final CitizensNPC npc;
        final LivingEntity entity;
        final EntityController controller;
        final DamageSource source;
        final List<SchedulerTask> tasks = new ArrayList<>();
        boolean accepted, notified;
        Location location;
        SentinelTrait sentinel;
        List<ServerPlayer> viewers = List.of();
        Runnable visual;
        int remaining;

        Death(CitizensNPC npc, LivingEntity entity, DamageSource source) {
            this.npc = npc; this.entity = entity; this.source = source; controller = npc.controller();
        }

        boolean ownsEntity() { return npc.ownsEntity(controller, entity); }
        boolean isPending() { return pending.get(npc) == this; }
        boolean canRespawn() {
            // A failed virtual spawn may rebuild the same base controller's packet wrapper.
            // Explicit controller changes invalidate this death; wrapper allocation does not.
            return isPending() && npc.getEntity() == null
                    && npc.getOwningRegistry() != null && npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc;
        }
        void finishVisual() {
            Runnable cleanup = visual;
            visual = null;
            visuals.remove(entity, this);
            if (cleanup != null) cleanup.run();
        }
    }
}
