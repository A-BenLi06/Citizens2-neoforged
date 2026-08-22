package net.citizensnpcs.trait;

import java.util.ArrayList;
import java.util.UUID;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.util.ChunkCoord;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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
    private ChunkCoord active;

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
        if (!npc.data().get(NPC.Metadata.KEEP_CHUNK_LOADED, Setting.KEEP_CHUNKS_LOADED.asBoolean()))
            return;
        acquire();
    }

    /**
     * Follows the NPC: a ticket only covers one chunk, so it is moved when the NPC walks into the next one.
     */
    @Override
    public void run() {
        if (!npc.isSpawned())
            return;
        if (!npc.data().get(NPC.Metadata.KEEP_CHUNK_LOADED, Setting.KEEP_CHUNKS_LOADED.asBoolean())) {
            release();
            return;
        }
        if (!(npc.getEntity().level() instanceof ServerLevel level))
            return;
        ChunkCoord at = new ChunkCoord(level, npc.getEntity().chunkPosition());
        if (!at.equals(active)) {
            release();
            acquire();
        }
    }

    private void acquire() {
        if (!(npc.getEntity().level() instanceof ServerLevel level))
            return;
        ChunkPos pos = npc.getEntity().chunkPosition();
        CONTROLLER.forceChunk(level, npc.getUniqueId(), pos.x, pos.z, true, true);
        active = new ChunkCoord(level, pos);
    }

    private void release() {
        if (active == null)
            return;
        ServerLevel level = active.getWorld();
        if (level != null) {
            CONTROLLER.forceChunk(level, npc.getUniqueId(), active.x, active.z, false, true);
        }
        active = null;
    }

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
