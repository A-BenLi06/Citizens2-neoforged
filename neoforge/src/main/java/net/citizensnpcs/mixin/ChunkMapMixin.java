package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Stops a player NPC from behaving like a client that is standing there.
 * <p>
 * A player-type NPC is a {@link ServerPlayer}, so vanilla counts it as a player everywhere that asks "is anybody near
 * this chunk?" — and vanilla answers that question in three separate ways, which is why this class holds three hooks
 * rather than one.
 */
@Mixin(ChunkMap.class)
public class ChunkMapMixin {
    /**
     * Stops a player NPC from keeping the world around it loaded and ticking.
     * <p>
     * {@code updatePlayerStatus} hands every added {@code ServerPlayer} to {@code DistanceManager.addPlayer}, whose
     * {@code PlayerTicketTracker} then issues a {@code TicketType.PLAYER} ticket for every chunk within that player's
     * view distance — at {@code PLAYER_TICKET_LEVEL}, which is {@code ChunkLevel.byStatus(ENTITY_TICKING)}. So each
     * player NPC does not merely hold its own chunk: it holds a full view-distance disc of chunks at the level that
     * makes every entity inside them tick.
     * <p>
     * Measured on the live server rather than assumed. A five-minute spark profile with <em>one</em> human player online
     * showed 62 {@code minecraft:player} entities (61 of them NPCs) and 33,904–34,006 loaded chunks, against a median
     * MSPT of 82–87ms for a 50ms budget — 11–12 TPS. Entity ticking was 46.79% of the tick and villagers alone 26.92%,
     * 109 of the 131 active villagers sitting in one remote region no player had visited for hours. Citizens' own per-tick
     * work in the same profile was 0.23%. The chunks were the cost, and the NPCs were what held them.
     * <p>
     * It is also self-perpetuating: a human visits an area, its chunks load, the NPCs there spawn, each NPC anchors its
     * own disc, those discs keep the villagers and vehicles and block entities ticking, and nothing ever unloads again
     * because the anchors outlive the visit. Loaded chunks only ratchet upwards over a session, which is exactly the
     * shape of the reported symptom — 20 TPS at startup decaying to 11 after a few areas had been visited, and staying
     * there after the last player logged off.
     * <p>
     * <b>Why this hook, and why the previous conclusion about it was wrong.</b> An earlier revision of this class
     * rejected {@code skipPlayer} and hooked {@code playerIsCloseEnoughForSpawning} instead, on the evidence that
     * cancelling {@code skipPlayer} took the datapack suite from 106 passes to 82: mob-type NPCs stopped ticking,
     * because on a test server with no human player online their chunks were being held up by the player NPCs' own
     * tickets. That observation was real but the conclusion drawn from it was not. Those tickets are the defect, not a
     * load-bearing feature — the suite was passing <em>because</em> of the pathology this fixes, and the fixture has
     * been given its own {@code forceload} so it no longer depends on it.
     * <p>
     * An NPC whose chunk genuinely unloads is not lost: {@code EventListen.onChunkUnload} despawns it and remembers it
     * against the chunk, {@code onChunkLoad} brings it back, and {@code CitizensNPC.load} already refuses to spawn into
     * an unloaded chunk for the same reason. That is also what upstream does on Bukkit, where an NPC is not a player
     * and never had an anchor to lose. An NPC that must keep its chunk loaded still can, explicitly, via
     * {@code npc.chunks.always-keep-loaded} or {@code /npc chunkload} — {@link net.citizensnpcs.trait.ChunkTicketTrait}
     * takes a NeoForge ticket with {@code ticking = true}, which is independent of this and unaffected.
     * <p>
     * Restore the old behaviour with {@code npc.chunks.player-npcs-load-chunks}.
     * <p>
     * The add/remove paths stay symmetric even if that setting is changed at runtime: {@code updatePlayerStatus} adds
     * with the live {@code skipPlayer} answer but removes on the flag {@code PlayerMap} recorded at add time, so a
     * ticket taken under one setting is always released under the same one.
     */
    @Inject(method = "skipPlayer", at = @At("HEAD"), cancellable = true)
    private void citizens$npcsDoNotAnchorChunks(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof EntityHumanNPC && !Setting.PLAYER_NPCS_LOAD_CHUNKS.asBoolean()) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Stops a player NPC from making the world around it spawn mobs.
     * <p>
     * {@code ServerChunkCache.tickChunks} gates all natural spawning on
     * {@code ChunkMap.anyPlayerCloseEnoughForSpawning}, so a server whose NPCs are scattered across the map spawns and
     * then ticks mobs around every one of them forever, with nobody there to see it. Three 180-second spark profiles
     * put {@code NaturalSpawner.spawnForChunk} at 14–15% of the server tick, with {@code EntityGetter.getNearestPlayer}
     * a further 3.6% of self time — that one is a linear scan of the player list, and 196 of the ~200 entries in it were
     * NPCs.
     * <p>
     * Still needed alongside {@link #citizens$npcsDoNotAnchorChunks}, and not redundant with it.
     * {@code anyPlayerCloseEnoughForSpawning} does short-circuit on {@code DistanceManager.hasPlayersNearby}, which an
     * NPC no longer feeds — but when a human <em>is</em> nearby the guard opens, and the loop past it walks
     * {@code playerMap.getAllPlayers()}, which holds every player including the ignored ones. Without this hook an NPC
     * standing next to a human would still count against {@code getPlayersCloseForSpawning}'s per-player mob cap.
     * <p>
     * Both callers — {@code anyPlayerCloseEnoughForSpawning} (the spawn gate) and {@code getPlayersCloseForSpawning}
     * (the mob cap) — route through here and nothing else does, so one answer covers both consistently.
     * <p>
     * A guard NPC that wants mobs to spawn around it can have them back with
     * {@code npc.player-npcs-count-for-mob-spawning}.
     */
    @Inject(method = "playerIsCloseEnoughForSpawning", at = @At("HEAD"), cancellable = true)
    private void citizens$npcsDoNotAnchorMobSpawning(ServerPlayer player, ChunkPos chunkPos,
            CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof EntityHumanNPC && !Setting.PLAYER_NPCS_COUNT_FOR_MOB_SPAWNING.asBoolean()) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Stops the server computing which chunks to stream to a connection that goes nowhere.
     * <p>
     * A player NPC's {@code connection} is an {@link net.citizensnpcs.network.EmptyPacketListener} over a dummy channel,
     * so every chunk vanilla decides to send it is discarded. Reaching that decision is not free: {@code
     * updateChunkTracking} is called for every player in {@code playerMap} on every {@code ChunkMap.tick}, and whenever
     * it sees a view that does not match the NPC's current position it runs a full
     * {@code ChunkTrackingView.difference} over the view-distance disc, marking each chunk pending-to-send on the
     * NPC's {@code chunkSender} and firing NeoForge's {@code ChunkWatchEvent} for each one — which other mods listen to.
     * <p>
     * Cancelling this leaves the NPC on the {@code ChunkTrackingView.EMPTY} that {@code updatePlayerStatus} assigns
     * just before the first call, permanently. That is the honest description of a clientless entity, and vanilla reads
     * it that way everywhere it matters: {@code isChunkTracked} and {@code onChunkReadyToSend} both consult the
     * tracking view, so an empty one simply keeps NPCs out of the lists of players to send chunk packets to.
     * <p>
     * Deliberately not gated on {@code npc.chunks.player-npcs-load-chunks}. That setting decides whether an NPC anchors
     * chunks, which is a {@code DistanceManager} ticket question; the tracking view holds no tickets and loads nothing,
     * so there is no configuration under which this work is worth doing.
     */
    @Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
    private void citizens$npcsNeedNoChunkStreaming(ServerPlayer player, CallbackInfo ci) {
        if (player instanceof EntityHumanNPC) {
            ci.cancel();
        }
    }
}
