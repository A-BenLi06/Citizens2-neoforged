package net.citizensnpcs;

import net.citizensnpcs.trait.SentinelTrait;
import net.citizensnpcs.api.event.NPCKnockbackEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MapMaker;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.EntityTargetNPCEvent;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCDamageEvent;
import net.citizensnpcs.api.event.NPCNeedsRespawnEvent;
import net.citizensnpcs.api.event.NPCRemoveEvent;
import net.citizensnpcs.api.event.SpawnReason;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.trait.Controllable;
import net.citizensnpcs.trait.Controllable.MovementController;
import net.citizensnpcs.trait.CommandTrait;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.CurrentLocation;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.util.ChunkCoord;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.HologramTrait.HologramRenderer;
import net.citizensnpcs.trait.TargetableTrait;
import net.citizensnpcs.trait.versioned.EnderDragonTrait;
import net.citizensnpcs.trait.versioned.SnowmanTrait;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Translates platform events into Citizens' own NPC events.
 * <p>
 * Upstream's {@code EventListen} is around 1100 lines because Bukkit fires a great many events Citizens has to filter
 * and correlate. This holds the handlers that have landed so far; the mapping table for the rest is in the port plan.
 */
public class EventListen {
    /**
     * Right-clicks arrive as two packets for one click — {@code INTERACT_AT} followed by {@code INTERACT} — so NeoForge
     * fires both {@link PlayerInteractEvent.EntityInteractSpecific} and {@link PlayerInteractEvent.EntityInteract}.
     * Bukkit collapses those into a single {@code PlayerInteractEntityEvent}, and traits are written expecting one click
     * to mean one event. Keep each player's results for the server tick so interleaved packets cannot repeat a command
     * or allow vanilla to handle an interaction that Citizens already consumed.
     */
    // Entity equality uses the numeric ID, which vanilla reuses for a replacement player on respawn.
    private final Map<ServerPlayer, TickClicks> rightClicks = new MapMaker().weakKeys().makeMap();

    private static class TickClicks {
        final int tick;
        final Int2ObjectMap<Boolean> handled = new Int2ObjectOpenHashMap<>();

        TickClicks(int tick) {
            this.tick = tick;
        }
    }

    /**
     * NPCs waiting for their chunk to come back, keyed by the chunk they belong to.
     * <p>
     * An NPC whose chunk is not loaded has to stay despawned, which is what upstream's {@code ChunkUnloadEvent} handler is
     * for. Leaving it spawned is expensive rather than merely untidy: a spawned NPC is a live entity, a live entity pins
     * its chunk's entity storage, and that drags every other entity stored in the chunk into memory and onto the tick
     * list. Measured on a real world holding ~400k saved entities, 200 NPCs scattered across it took the loaded entity
     * count from 100 to over 10,000 and the server from 20 TPS to 4.
     */
    private final ListMultimap<ChunkCoord, NPC> toRespawn = ArrayListMultimap.create(64, 4);

    /** Remembers an NPC that could not spawn because its chunk is not loaded. */
    @SubscribeEvent
    public void onNPCNeedsRespawn(NPCNeedsRespawnEvent event) {
        ChunkCoord coord = event.getChunkCoord();
        if (coord == null || toRespawn.containsEntry(coord, event.getNPC()))
            return;
        toRespawn.put(coord, event.getNPC());
    }

    /** A deleted NPC must not be resurrected by a later chunk load. */
    @SubscribeEvent
    public void onNPCRemove(NPCRemoveEvent event) {
        for (ChunkCoord coord : new ArrayList<>(toRespawn.keySet())) {
            toRespawn.remove(coord, event.getNPC());
        }
    }

    /** Drops the whole queue, for {@code /citizens reload}, which reloads every NPC from disk anyway. */
    public void clearRespawnQueue() {
        toRespawn.clear();
    }

    /**
     * Brings back the NPCs belonging to a chunk once it is loaded.
     * <p>
     * Deferred by a tick deliberately: NeoForge fires this before the chunk is promoted to {@code FULL}, and spawning an
     * entity into it here risks a chunk-loading deadlock — the event's own javadoc says so.
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        ChunkPos pos = event.getChunk().getPos();
        ChunkCoord coord = new ChunkCoord(level.dimension(), pos.x, pos.z);
        if (toRespawn.get(coord).isEmpty())
            return;
        CitizensAPI.getScheduler().runTaskLater(() -> respawnAllAt(coord), 1);
    }

    private void respawnAllAt(ChunkCoord coord) {
        for (NPC npc : new ArrayList<>(toRespawn.get(coord))) {
            toRespawn.remove(coord, npc);
            if (npc.getOwningRegistry() == null || npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) != npc
                    || npc.isSpawned()) {
                continue;
            }
            Location at = npc.getOrAddTrait(CurrentLocation.class).getLocation();
            if (at == null) {
                continue;
            }
            if (!npc.spawn(at, SpawnReason.CHUNK_LOAD)) {
                // queue it again rather than losing it - a spawn can fail for reasons that will not last
                toRespawn.put(coord, npc);
            }
        }
    }

    /**
     * Despawns the NPCs standing in a chunk that is going away, and remembers them for when it comes back.
     * <p>
     * Only the saved registry is walked. NPCs in the temporary registry are hologram lines and the like, owned by a trait
     * on a parent NPC: {@code HologramTrait} drops them in its own {@code onDespawn}, so despawning them here as well
     * would fight that and could respawn a line whose parent is gone.
     */
    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        ChunkPos pos = event.getChunk().getPos();
        List<NPC> leaving = null;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) {
                continue;
            }
            Entity entity = npc.getEntity();
            if (entity == null || entity.level() != level || entity.chunkPosition().x != pos.x
                    || entity.chunkPosition().z != pos.z) {
                continue;
            }
            if (leaving == null) {
                leaving = new ArrayList<>(2);
            }
            leaving.add(npc);
        }
        if (leaving == null)
            return;
        ChunkCoord coord = new ChunkCoord(level.dimension(), pos.x, pos.z);
        for (NPC npc : leaving) {
            // despawn() pre-cancels itself when the NPC is meant to keep its chunk loaded, and returns false when
            // anything else vetoes it; in both cases the NPC stays up and must not be queued for a respawn
            if (npc.despawn(DespawnReason.CHUNK_UNLOAD) && !toRespawn.containsEntry(coord, npc)) {
                toRespawn.put(coord, npc);
            }
        }
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        handleRightClick(event.getEntity(), event.getTarget(), result -> {
            event.setCancellationResult(result);
            event.setCanceled(true);
        });
    }

    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        handleRightClick(event.getEntity(), event.getTarget(), result -> {
            event.setCancellationResult(result);
            event.setCanceled(true);
        });
    }

    private void handleRightClick(net.minecraft.world.entity.player.Player clicker, Entity target,
            java.util.function.Consumer<InteractionResult> cancel) {
        if (!(clicker instanceof ServerPlayer player))
            return;
        NPC npc = resolveClicked(target);
        if (npc == null)
            return;
        int tick = player.getServer().getTickCount();
        TickClicks clicks = rightClicks.get(player);
        if (clicks == null || clicks.tick != tick) {
            clicks = new TickClicks(tick);
            rightClicks.put(player, clicks);
        }
        Boolean previous = clicks.handled.get(target.getId());
        if (previous != null) {
            if (previous) cancel.accept(InteractionResult.SUCCESS);
            return;
        }
        // Reserve before event/command dispatch: reentrant callbacks must not dispatch a second Citizens click.
        clicks.handled.put(target.getId(), Boolean.TRUE);
        NPCRightClickEvent clickEvent = new NPCRightClickEvent(npc, player);
        NeoForge.EVENT_BUS.post(clickEvent);
        if (clickEvent.isCanceled()) {
            cancel.accept(InteractionResult.SUCCESS);
            return;
        }
        if (npc.hasTrait(CommandTrait.class)) {
            npc.getTraitNullable(CommandTrait.class).dispatch(player, CommandTrait.Hand.RIGHT);
            // the click ran commands, so the item in hand must not also be used
            clickEvent.setDelayedCancellation(true);
        }
        if (clickEvent.isDelayedCancellation()) {
            // a trait handled the click, so vanilla must not also run its own interaction for it
            cancel.accept(InteractionResult.SUCCESS);
        }
        clicks.handled.put(target.getId(), Boolean.valueOf(clickEvent.isDelayedCancellation()));
    }

    /**
     * The NPC a click on this entity is meant for.
     * <p>
     * Hologram lines are NPCs of their own stacked on top of the real one, so a player aiming at a floating nameplate
     * hits the hologram. {@link ClickRedirectTrait} on the helper names the NPC the player was actually aiming at.
     */
    private NPC resolveClicked(Entity target) {
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(target);
        if (npc == null)
            return null;
        ClickRedirectTrait redirect = npc.getTraitNullable(ClickRedirectTrait.class);
        return redirect != null && redirect.getRedirectToNPC() != null ? redirect.getRedirectToNPC() : npc;
    }

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        NPC npc = resolveClicked(event.getTarget());
        if (npc == null)
            return;
        NPCLeftClickEvent clickEvent = new NPCLeftClickEvent(npc, player);
        NeoForge.EVENT_BUS.post(clickEvent);
        if (clickEvent.isCanceled()) {
            event.setCanceled(true);
            return;
        }
        if (npc.hasTrait(CommandTrait.class)) {
            npc.getTraitNullable(CommandTrait.class).dispatch(player, CommandTrait.Hand.LEFT);
        }
    }

    /**
     * Decides whether an NPC is allowed to change the world around it.
     * <p>
     * Two traits need this, for the same underlying reason: the behaviour they gate lives in an entity's own
     * {@code aiStep} rather than in a goal or a brain behaviour, so switching an NPC's AI off does not stop it — and
     * vanilla happens to consult the mob-griefing hook at exactly the point that has to be vetoed.
     * <ul>
     * <li>A snow golem's trail of snow. Upstream cancels Bukkit's {@code EntityBlockFormEvent}; with no trait present it
     * falls back to whether the NPC uses vanilla AI, which is matched here.
     * <li>An ender dragon breaking the blocks it flies through. Upstream gives the dragon its own entity subclass and
     * runs vanilla's wall check only when the trait asks for it, so with no trait the walls survive — hence no
     * vanilla-AI fallback for this one.
     * </ul>
     * Every other entity keeps the game rule's own answer.
     */
    @SubscribeEvent
    public void onMobGriefing(EntityMobGriefingEvent event) {
        boolean golem = event.getEntity() instanceof SnowGolem;
        if (!golem && !(event.getEntity() instanceof EnderDragon))
            return;
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getEntity());
        if (npc == null)
            return;
        if (golem) {
            SnowmanTrait trait = npc.getTraitNullable(SnowmanTrait.class);
            event.setCanGrief(trait != null ? trait.shouldFormSnow() : npc.useMinecraftAI());
        } else {
            EnderDragonTrait trait = npc.getTraitNullable(EnderDragonTrait.class);
            event.setCanGrief(trait != null && trait.isDestroyWalls());
        }
    }

    /**
     * Enforces {@link TargetableTrait}: whether a mob is allowed to take this NPC as its target.
     * <p>
     * Upstream hooks Bukkit's {@code EntityTargetEvent}, which fires for the target being set on anything; the NeoForge
     * counterpart is the change-target event on the <em>targeter</em>, so both directions are handled from one place —
     * the NPC gaining a targeter, and an NPC losing the one it had.
     */
    @SubscribeEvent
    public void onChangeTarget(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof Mob targeter))
            return;
        LivingEntity newTarget = event.getNewAboutToBeSetTarget();
        NPC previous = newTarget == targeter.getTarget() ? null
                : CitizensAPI.getNPCRegistry().getNPC(targeter.getTarget());
        if (previous != null) {
            previous.getOrAddTrait(TargetableTrait.class).removeTargeter(targeter.getUUID());
        }
        NPC npc = newTarget == null ? null : CitizensAPI.getNPCRegistry().getNPC(newTarget);
        if (npc == null)
            return;
        EntityTargetNPCEvent targetEvent = new EntityTargetNPCEvent(npc, targeter);
        targetEvent.setCanceled(!npc.getOrAddTrait(TargetableTrait.class).isTargetable());
        NeoForge.EVENT_BUS.post(targetEvent);
        if (targetEvent.isCanceled()) {
            event.setCanceled(true);
            return;
        }
        npc.getOrAddTrait(TargetableTrait.class).addTargeter(targeter.getUUID());
    }

    /**
     * Raises {@link NPCDeathEvent} while an NPC's death drops are being decided.
     * <p>
     * NeoForge hands over item <em>entities</em>; Bukkit's equivalent list holds stacks, and that is the shape traits are
     * written against. The entities are therefore unwrapped into a stack list, offered to listeners, and rebuilt — which
     * keeps removals and edits working, not just additions.
     */
    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getEntity());
        if (npc == null)
            return;
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemEntity item : event.getDrops()) {
            stacks.add(item.getItem());
        }
        List<ItemStack> before = new ArrayList<>(stacks);
        NeoForge.EVENT_BUS.post(new NPCDeathEvent(npc, event.getSource(), stacks));
        if (stacks.equals(before))
            return;
        LivingEntity dying = event.getEntity();
        event.getDrops().clear();
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                event.getDrops()
                        .add(new ItemEntity(dying.level(), dying.getX(), dying.getY(), dying.getZ(), stack));
            }
        }
    }

    /**
     * Clicks made by a player riding a controllable NPC go to that NPC's control scheme — which is how the flying ones
     * are paused, and how the air controller descends.
     * <p>
     * Upstream registers a listener per trait instance for this; one dispatcher is enough, and it means a click is only
     * looked at when the player is actually riding something.
     */
    @SubscribeEvent
    public void onRiderLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        MovementController controller = controllerOf(event.getEntity());
        if (controller != null) {
            controller.leftClick();
        }
    }

    @SubscribeEvent
    public void onRiderLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        MovementController controller = controllerOf(event.getEntity());
        if (controller != null) {
            controller.leftClick();
        }
    }

    @SubscribeEvent
    public void onRiderRightClickItem(PlayerInteractEvent.RightClickItem event) {
        MovementController controller = controllerOf(event.getEntity());
        if (controller != null) {
            controller.rightClick();
        }
    }

    @SubscribeEvent
    public void onRiderRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        MovementController controller = controllerOf(event.getEntity());
        if (controller != null) {
            controller.rightClick();
        }
    }

    /**
     * @return the control scheme of the controllable NPC this player is riding, or null if they are not riding one
     */
    private MovementController controllerOf(net.minecraft.world.entity.player.Player clicker) {
        Entity vehicle = clicker.getVehicle();
        if (vehicle == null)
            return null;
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(vehicle);
        if (npc == null)
            return null;
        Controllable controllable = npc.getTraitNullable(Controllable.class);
        return controllable == null || !controllable.isEnabled() ? null : controllable.getController();
    }

    /**
     * Raises {@link NPCDamageEvent} before an NPC takes damage, so a trait can change or refuse it.
     * <p>
     * Upstream listens to Bukkit's {@code EntityDamageEvent}. The NeoForge equivalent that is both cancellable and lets
     * the amount be changed is the incoming-damage event, which fires before armour and enchantments are applied — the
     * same point Bukkit's event describes.
     */
    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getEntity());
        if (npc == null)
            return;
        NPCDamageEvent damageEvent = new NPCDamageEvent(npc, event.getSource(), event.getAmount());
        NeoForge.EVENT_BUS.post(damageEvent);
        if (damageEvent.isCanceled()) {
            event.setCanceled(true);
            return;
        }
        if (damageEvent.getDamage() != event.getAmount()) {
            event.setAmount(damageEvent.getDamage());
        }
        // a guard fights back at whoever hit it, which for a guard with no target rules is the only way it ever fights
        if (npc.hasTrait(SentinelTrait.class)) {
            npc.getOrAddTrait(SentinelTrait.class).onDamaged(event.getSource().getEntity());
        }
    }

    /** Temporary permission grants must not outlive the session that was given them. */
    @SubscribeEvent
    public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        PermissionUtil.clearTemporary(event.getEntity().getUUID());
        rightClicks.remove(event.getEntity());
        if (event.getEntity() instanceof ServerPlayer player) net.citizensnpcs.util.PacketMounts.forget(player);
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public void onItemPickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre event) {
        NPC itemNPC = net.citizensnpcs.npc.NPCRegistries.lookup(event.getItemEntity());
        NPC collector = net.citizensnpcs.npc.NPCRegistries.lookup(event.getPlayer());
        if (itemNPC != null || collector != null && !collector.data().get(NPC.Metadata.PICKUP_ITEMS, false))
            event.setCanPickup(net.neoforged.neoforge.common.util.TriState.FALSE);
    }

    /**
     * Applies {@code /npc knockback} and NPC protection to vanilla's knockback.
     * <p>
     * Without this the command was a silent no-op: it writes {@link NPC.Metadata#KNOCKBACK} and nothing read it. Protection
     * did not cover knockback either — {@code setInvulnerable} stops damage, but a shove, an explosion or a sweep attack
     * still moves an invulnerable entity.
     * <p>
     * Protection decides first and the metadata overrides it, which is upstream's order: it lets {@code /npc knockback
     * --explicit true} re-enable knockback on an NPC that is otherwise protected.
     */
    @SubscribeEvent
    public void onLivingKnockBack(LivingKnockBackEvent event) {
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getEntity());
        if (npc == null)
            return;

        NPCKnockbackEvent knockback = new NPCKnockbackEvent(npc, event.getStrength(), event.getRatioX(),
                event.getRatioZ());
        knockback.setCanceled(npc.isProtected());
        if (npc.data().has(NPC.Metadata.KNOCKBACK)) {
            knockback.setCanceled(!npc.data().get(NPC.Metadata.KNOCKBACK, true));
        }
        NeoForge.EVENT_BUS.post(knockback);
        if (knockback.isCanceled()) {
            event.setCanceled(true);
            return;
        }
        event.setStrength(knockback.getStrength());
        event.setRatioX(knockback.getRatioX());
        event.setRatioZ(knockback.getRatioZ());
    }

    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(event.getTarget());
        if (npc == null)
            return;
        // Pairing succeeded; cancellable admission happens before the native spawn packets.
        if (npc.data().get(NPC.Metadata.HOLOGRAM_RENDERER) instanceof HologramRenderer renderer) {
            renderer.onSeenByPlayer(npc, player);
        }
    }
}
