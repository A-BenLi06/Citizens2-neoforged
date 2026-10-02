package net.citizensnpcs.trait;

import java.util.ArrayList;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

/**
 * Keeps the chunk an NPC stands in loaded.
 * <p>
 * Bukkit's plugin chunk ticket has an exact NeoForge counterpart in {@link TicketController}, keyed by an owner — the
 * NPC's own UUID is used, so tickets are released per NPC rather than all at once. The controller has to be registered on
 * the mod bus at startup, which {@link Citizens} does.
 * <p>
 * Upstream distinguishes a ticket from a force-load flag and carries a workaround for a Paper bug; neither applies here,
 * so a ticket is all this holds. Whether to keep the chunk loaded at all still comes from the NPC's own metadata, falling
 * back to {@code Setting.KEEP_CHUNKS_LOADED} — the same key {@code CitizensNPC.despawn} consults.
 */
@TraitName("chunktickettrait")
public class ChunkTicketTrait extends Trait {
    private static final Map<TicketKey, Set<ChunkTicketTrait>> owners = new HashMap<>();
    private TicketKey active;

    public ChunkTicketTrait() {
        super("chunktickettrait");
    }

    @Override
    public void onDespawn() {
        release();
    }

    @Override
    public void onRemove() {
        release();
    }

    @Override
    public void onSpawn() {
        run();
    }

    /**
     * Follows the NPC: a ticket only covers one chunk, so it is moved when the NPC walks into the next one.
     */
    @Override
    public void run() {
        Entity entity = npc.getEntity();
        if (!owns(entity) || !npc.data().get(NPC.Metadata.KEEP_CHUNK_LOADED, Setting.KEEP_CHUNKS_LOADED.asBoolean())) {
            release();
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) return;
        ChunkPos pos = entity.chunkPosition();
        TicketKey next = new TicketKey(level, npc.getUniqueId(), pos.x, pos.z);
        if (next.equals(active)) return;
        release();
        if (!owns(entity) || active != null || entity.level() != level || !entity.chunkPosition().equals(pos)) return;
        active = next;
        owners.computeIfAbsent(next, key -> Collections.newSetFromMap(new IdentityHashMap<>())).add(this);
        reconcile(next);
        if (!owns(entity) || !npc.data().get(NPC.Metadata.KEEP_CHUNK_LOADED, Setting.KEEP_CHUNKS_LOADED.asBoolean())) release();
    }

    private void release() {
        if (active == null)
            return;
        TicketKey previous = active;
        active = null;
        Set<ChunkTicketTrait> held = owners.get(previous);
        if (held != null && held.remove(this)) {
            if (held.isEmpty()) owners.remove(previous);
            reconcile(previous);
        }
    }

    private boolean owns(Entity entity) {
        return entity != null && !entity.isRemoved() && npc.isSpawned() && npc.getEntity() == entity
                && npc.getTraitNullable(ChunkTicketTrait.class) == this
                && npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc
                && npc.getOwningRegistry().getById(npc.getId()) == npc;
    }

    private static void reconcile(TicketKey key) {
        boolean wanted;
        do {
            wanted = owners.containsKey(key);
            CONTROLLER.forceChunk(key.level, key.owner, key.x, key.z, wanted, true);
        } while (wanted != owners.containsKey(key));
    }

    // Traits are registered as event-bus instances; global handlers need their own class registration.
    @EventBusSubscriber(modid = "citizens")
    public static final class Lifecycle {
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void stopping(ServerStoppingEvent event) {
            var remaining = new ArrayList<>(owners.keySet());
            owners.clear();
            remaining.forEach(ChunkTicketTrait::reconcile);
        }
    }

    private record TicketKey(ServerLevel level, UUID owner, int x, int z) { }

    /**
     * Drops every persisted ticket as a level loads, because {@link #onSpawn} re-acquires one for each NPC that should
     * still hold it - the trait is the source of truth, not {@code chunks.dat}.
     * <p>
     * Without this, a ticket outlives the reason it was taken: {@link #active} is a runtime field that starts out null, so
     * {@link #release} cannot let go of a ticket written by the previous run, and the NPC may since have walked away, had
     * the trait removed, lost {@link NPC.Metadata#KEEP_CHUNK_LOADED} or been deleted outright. The chunk would then stay
     * force-loaded and ticking for good.
     */
    private static void releaseStaleTickets(ServerLevel level, TicketHelper helper) {
        // keySet() is a view over the map removeAllTickets mutates
        for (UUID owner : new ArrayList<>(helper.getEntityTickets().keySet())) {
            helper.removeAllTickets(owner);
        }
    }

    /** Registered on the mod bus by {@link Citizens}; see {@code RegisterTicketControllersEvent}. */
    public static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(Citizens.MOD_ID, "npc_chunk_ticket"),
            ChunkTicketTrait::releaseStaleTickets);
}
