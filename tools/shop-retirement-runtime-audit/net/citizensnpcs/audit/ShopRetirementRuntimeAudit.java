package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCNeedsRespawnEvent;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.trait.ShopTrait;
import net.citizensnpcs.trait.shop.NPCShop;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class ShopRetirementRuntimeAudit {
    private static Citizens plugin;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static NPCDataStore original;
    private static Store store;
    private static NPCRegistry old, named;
    private static NPC world, virtual, queued;
    private static Entity entity, packetEntity;
    private static Probe probe;
    private static NPCShop originalShop;
    private static int started, reloaded, passed, events;
    private static boolean done, forced, veto;
    private static final List<NPC> candidates = new ArrayList<>();

    @SubscribeEvent(receiveCanceled = true)
    public static void despawn(NPCDespawnEvent event) {
        if (veto && event.getNPC().getOwningRegistry() == old) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer(); level = server.overworld(); plugin = Citizens.getInstance();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("shop-retirement-audit-fixture.txt")), "isolated_fixture");
                original = plugin.getDefaultNPCDataStore();
                check(!plugin.getNPCRegistry().iterator().hasNext(), "empty_fixture");
                started = server.getTickCount(); level.setChunkForced(0, 0, true); forced = true;
            }
            if (server.getTickCount() - started > 400) throw new AssertionError("timeout");
            if (!level.isPositionEntityTicking(new BlockPos(4, -58, 4))) return;
            if (reloaded == 0) {
                setup(); failedShopRead(); failedConfigReload(); failedReload(); successfulReload();
                reloaded = server.getTickCount(); events = probe.events;
                level.setChunkForced(40, 0, true);
            } else if (server.getTickCount() - reloaded > 5
                    && level.isPositionEntityTicking(new BlockPos(640, -58, 4))) {
                check(queued.isSpawned(), "unrelated_pending_respawn_survives_reload");
                check(probe.events == events, "old_trait_listener_unregistered");
                for (NPC npc : candidates) check(!npc.isSpawned(), "failed_candidate_never_respawns_" + passed);
                NPCRegistry current = plugin.getNPCRegistry();
                check(current.getById(world.getId()).isSpawned(), "restored_world_spawns");
                NPC restoredVirtual = current.getById(virtual.getId());
                check(restoredVirtual.isSpawned() && level.getEntity(restoredVirtual.getEntity().getUUID()) == null,
                        "restored_packet_transport");
                cleanupFailure();
                close();
                LoggerFactory.getLogger("citizens").info("[SHOPRETIREAUDIT] COMPLETE {} checks phase=runtime", passed);
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[SHOPRETIREAUDIT] FAILED", failure); close();
        }
    }

    private static void setup() {
        store = new Store(); plugin.setDefaultNPCDataStore(store); old = plugin.getNPCRegistry();
        world = old.createNPC(EntityType.PIG, "ReloadWorld");
        virtual = old.createNPC(EntityType.PIG, "ReloadPacket"); virtual.getOrAddTrait(PacketNPC.class);
        check(world.spawn(at(4)) && virtual.spawn(at(6)), "originals_spawn");
        entity = world.getEntity(); packetEntity = virtual.getEntity();
        originalShop = world.getOrAddTrait(ShopTrait.class).getDefaultShop();
        plugin.storeNPCs();
        probe = new Probe(); world.addTrait(probe);
        named = plugin.createNamedNPCRegistry("reload-unrelated", new MemoryNPCDataStore());
        queued = named.createNPC(EntityType.PIG, world.getUniqueId(), world.getId(), "QueuedOtherRegistry");
        queued.getOrAddTrait(PacketNPC.class);
        queued.getOrAddTrait(net.citizensnpcs.api.trait.trait.CurrentLocation.class).setLocation(at(640));
        NeoForge.EVENT_BUS.post(new NPCNeedsRespawnEvent(queued, at(640)));
    }

    private static void failedConfigReload() throws Exception {
        Path path = plugin.getDataFolder().toPath().resolve("config.yml");
        byte[] original = Files.readAllBytes(path);
        try {
            Files.writeString(path, "npc: [unterminated");
            rejectedReload();
            check(plugin.getNPCRegistry() == old && world.getEntity() == entity, "bad_config_preserves_live_NPC");
            check(Files.readString(path).equals("npc: [unterminated"), "bad_config_not_overwritten");
            check(world.getOrAddTrait(ShopTrait.class).getDefaultShop() == originalShop, "bad_config_preserves_shop");
        } finally { Files.write(path, original); }
    }

    private static void failedShopRead() throws Exception {
        Path path = plugin.getDataFolder().toPath().resolve("shops.yml");
        byte[] original = Files.readAllBytes(path);
        try {
            Files.writeString(path, "global: [unterminated");
            rejectedReload();
            check(plugin.getNPCRegistry() == old && world.getEntity() == entity, "bad_shop_read_preserves_NPC");
            check(plugin.getShops().npcShops.get(world.getUniqueId().toString()) == originalShop,
                    "bad_shop_read_preserves_live_shop");
            try { plugin.getShops().storeShops(); throw new AssertionError("unreadable_shop_writable"); }
            catch (IllegalStateException expected) { check(true, "bad_shop_read_blocks_overwrite"); }
            check(Files.readString(path).equals("global: [unterminated"), "bad_shop_file_preserved");
        } finally {
            Files.write(path, original);
            check(plugin.getShops().loadFromDisk(), "shop_source_recovers_after_repair");
        }
    }

    private static void failedReload() throws Exception {
        store.afterLoad = registry -> {
            check(plugin.getNPCRegistry() == old, "old_binding_visible_during_load");
            for (NPC npc : registry) candidates.add(npc);
            NPC partial = registry.createNPC(EntityType.PIG, "PartialCandidate"); candidates.add(partial);
            check(partial.spawn(at(12)), "partial_candidate_can_spawn");
            partial.addTrait(new Probe(true));
            throw new IllegalStateException("expected provider reload failure");
        };
        NPCLoadException failure = rejectedReload();
        check(failure.getCause().getMessage().equals("expected provider reload failure"), "original_failure_preserved");
        check(plugin.getNPCRegistry() == old && old.getById(world.getId()) == world, "failed_reload_preserves_identity");
        check(failure.getCause().getSuppressed().length == 1, "candidate_cleanup_failure_suppressed");
        check(world.getEntity() == entity && !entity.isRemoved() && virtual.getEntity() == packetEntity,
                "failed_reload_preserves_live_entities");
        check(probe.removals == 0 && queued.getOwningRegistry().getById(queued.getId()) == queued,
                "failed_reload_preserves_other_ownership");
        for (NPC npc : candidates) check(npc.getEntity() == null, "failed_candidate_released_" + passed);
        check(store.clears == 0 && store.records.size() == 2, "failure_preserves_records");
        check(plugin.getShops().npcShops.get(world.getUniqueId().toString()) == originalShop,
                "failed_candidate_retirement_preserves_live_shop");
        store.afterLoad = null;
    }

    private static void successfulReload() throws Exception {
        veto = true; plugin.reload(); veto = false;
        check(plugin.getNPCRegistry() != old, "success_publishes_new_registry");
        check(!old.iterator().hasNext() && old.getByUniqueId(world.getUniqueId()) == null, "old_indexes_released");
        check(world.getEntity() == null && entity.isRemoved() && virtual.getEntity() == null,
                "retirement_overrides_despawn_veto");
        check(probe.removals == 1, "old_trait_removed_once");
        check(store.reloads == 3 && store.clears == 0 && store.records.size() == 2, "reload_preserves_store_definitions");
        check(plugin.getShops().npcShops.containsKey(world.getUniqueId().toString()), "reload_restores_shop_definition");
        NPC deleted = plugin.getNPCRegistry().createNPC(EntityType.PIG, "DeletedShop");
        deleted.getOrAddTrait(ShopTrait.class).getDefaultShop();
        deleted.destroy();
        check(!plugin.getShops().npcShops.containsKey(deleted.getUniqueId().toString()), "permanent_deletion_removes_shop");
        NPC stripped = plugin.getNPCRegistry().createNPC(EntityType.PIG, "RemovedShopTrait");
        stripped.getOrAddTrait(ShopTrait.class).getDefaultShop();
        stripped.removeTrait(ShopTrait.class);
        check(!plugin.getShops().npcShops.containsKey(stripped.getUniqueId().toString()), "trait_removal_deletes_shop");
        stripped.destroy();
    }

    private static void cleanupFailure() throws Exception {
        NPCRegistry before = plugin.getNPCRegistry();
        NPC npc = before.getById(world.getId()); Entity previousEntity = npc.getEntity();
        Probe failing = new Probe(true); npc.addTrait(failing);
        NPCLoadException failure = rejectedReload();
        check(failure.getMessage().contains("old NPC cleanup failed"), "published_cleanup_failure_distinguished");
        check(plugin.getNPCRegistry() != before && plugin.getNPCRegistry().getById(world.getId()) != null,
                "cleanup_failure_keeps_successful_publication");
        check(npc.getEntity() == null && previousEntity.isRemoved() && failing.removals == 1,
                "throwing_cleanup_still_releases_runtime");
        check(store.clears == 2 && store.records.size() == 2, "cleanup_failure_preserves_records");
        check(plugin.getShops().npcShops.containsKey(world.getUniqueId().toString()), "cleanup_failure_preserves_shop");
    }

    private static NPCLoadException rejectedReload() throws Exception {
        try { plugin.reload(); } catch (NPCLoadException failure) { return failure; }
        throw new AssertionError("reload_should_fail");
    }
    private static Location at(int x) { return new Location(level, x, -58, 4); }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[SHOPRETIREAUDIT] PASS {}", label);
    }
    private static void close() {
        if (done) return; done = true; veto = false;
        try {
            if (store != null) store.afterLoad = null;
            if (named != null) { for (NPC npc : named.sorted()) npc.destroy(); plugin.removeNamedNPCRegistry(named.getName()); }
            if (original != null) plugin.setDefaultNPCDataStore(original);
            if (world != null) check(plugin.getShops().npcShops.containsKey(world.getUniqueId().toString()),
                    "store_replacement_preserves_shop");
            if (forced) { level.setChunkForced(0, 0, false); level.setChunkForced(40, 0, false); }
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[SHOPRETIREAUDIT] FAILED cleanup", failure); }
        server.halt(false);
    }
    public static final class Probe extends Trait {
        int events, removals; boolean fail;
        public Probe() { this(false); }
        Probe(boolean fail) { super("reloadprobe"); this.fail = fail; }
        @SubscribeEvent public void tick(ServerTickEvent.Post event) { events++; }
        @Override public void onRemove(RemoveReason reason) {
            removals++; if (fail) throw new IllegalStateException("expected reload cleanup failure");
        }
    }
    private static final class Store extends MemoryNPCDataStore {
        final Map<Integer, DataKey> records = new LinkedHashMap<>();
        Consumer<NPCRegistry> afterLoad; int clears, reloads;
        @Override public void reloadFromSource() { reloads++; }
        @Override public void clearData(NPC npc) { clears++; records.remove(npc.getId()); }
        @Override public void storeAll(NPCRegistry registry) { for (NPC npc : registry) store(npc); }
        @Override public void store(NPC npc) {
            DataKey key = new MemoryDataKey(); npc.saveSnapshot(key); records.put(npc.getId(), key);
        }
        @Override public void loadInto(NPCRegistry registry) {
            for (var entry : records.entrySet()) {
                DataKey key = entry.getValue().copy();
                NPC npc = registry.createNPC(EntityType.PIG, UUID.fromString(key.getString("uuid")), entry.getKey(), key.getString("name"));
                npc.load(key);
            }
            if (afterLoad != null) afterLoad.accept(registry);
        }
    }
}
