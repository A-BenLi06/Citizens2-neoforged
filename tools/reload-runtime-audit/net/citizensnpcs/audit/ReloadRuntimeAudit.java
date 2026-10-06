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
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCNeedsRespawnEvent;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.trait.PacketNPC;
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
public final class ReloadRuntimeAudit {
    private static Citizens plugin;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static NPCDataStore original;
    private static Store store;
    private static NPCRegistry old, named;
    private static NPC world, virtual, queued;
    private static Entity entity, packetEntity;
    private static Probe probe;
    private static net.citizensnpcs.npc.skin.Skin originalSkin;
    private static int started, reloaded, passed, events;
    private static int templateCalls;
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
                check(Files.isRegularFile(Path.of("reload-audit-fixture.txt")), "isolated_fixture");
                original = plugin.getDefaultNPCDataStore();
                check(!plugin.getNPCRegistry().iterator().hasNext(), "empty_fixture");
                started = server.getTickCount(); level.setChunkForced(0, 0, true); forced = true;
            }
            if (server.getTickCount() - started > 400) throw new AssertionError("timeout");
            if (!level.isPositionEntityTicking(new BlockPos(4, -58, 4))) return;
            if (reloaded == 0) {
                setup(); failedReload(); successfulReload();
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
                replacementSubscriptions();
                close();
                LoggerFactory.getLogger("citizens").info("[RELOADAUDIT] COMPLETE {} checks phase=runtime", passed);
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[RELOADAUDIT] FAILED", failure); close();
        }
    }

    private static void setup() {
        store = new Store(); plugin.setDefaultNPCDataStore(store); old = plugin.getNPCRegistry();
        world = old.createNPC(EntityType.PIG, "ReloadWorld");
        virtual = old.createNPC(EntityType.PIG, "ReloadPacket"); virtual.getOrAddTrait(PacketNPC.class);
        check(world.spawn(at(4)) && virtual.spawn(at(6)), "originals_spawn");
        entity = world.getEntity(); packetEntity = virtual.getEntity();
        plugin.storeNPCs();
        probe = new Probe(); world.addTrait(probe);
        named = plugin.createNamedNPCRegistry("reload-unrelated", new MemoryNPCDataStore());
        queued = named.createNPC(EntityType.PIG, world.getUniqueId(), world.getId(), "QueuedOtherRegistry");
        queued.getOrAddTrait(PacketNPC.class);
        queued.getOrAddTrait(net.citizensnpcs.api.trait.trait.CurrentLocation.class).setLocation(at(640));
        NeoForge.EVENT_BUS.post(new NPCNeedsRespawnEvent(queued, at(640)));
        var action = new net.citizensnpcs.api.npc.templates.CommandEventAction(TemplateProbeEvent.class,
                npc -> templateCalls++);
        action.accept(world);
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(queued));
        check(templateCalls == 0, "template_event_excludes_same_uuid_in_other_registry");
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(world));
        check(templateCalls == 1, "template_event_runs_for_applied_registry");
        action.accept(queued);
    }

    private static void failedReload() throws Exception {
        originalSkin = net.citizensnpcs.npc.skin.Skin.get("");
        var editorPlayer = new net.minecraft.server.level.ServerPlayer(server, level,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "ReloadEditor"),
                net.minecraft.server.level.ClientInformation.createDefault());
        var editor = new ProbeEditor(false);
        net.citizensnpcs.editor.Editor.enterOrLeave(editorPlayer, editor);
        var prompt = ChatPrompts.begin(editorPlayer, new ChatPrompt() {
            public String getPromptText(ChatPromptSession session) { return ""; }
            public ChatPrompt acceptInput(ChatPromptSession session, String input) { return this; }
        });
        Setting.DEFAULT_NPC_LIMIT.set(37);
        var previousDuration = Setting.DEFAULT_RANDOM_LOOK_DELAY.asDuration();
        writeLimit(71);
        var oldTemplates = plugin.getTemplateRegistry();
        Path templateFolder = Files.createDirectories(plugin.getDataFolder().toPath().resolve("templates/reloadprobe"));
        Path templateFile = templateFolder.resolve("probe.yml");
        try {
            int invalidIndex = 0;
            for (String invalid : List.of("broken: [unfinished\n", "valid_prefix: {}\nbroken: 42\n",
                    "broken:\n  commands:\n    on_typo: []\n")) {
                Files.writeString(templateFile, invalid);
                rejectedReload();
                check(plugin.getTemplateRegistry() == oldTemplates && plugin.getNPCRegistry() == old
                        && Setting.DEFAULT_NPC_LIMIT.asInt() == 37 && world.getEntity() == entity && !entity.isRemoved()
                        && net.citizensnpcs.editor.Editor.getEditor(editorPlayer) == editor && ChatPrompts.isActive(prompt)
                        && net.citizensnpcs.npc.skin.Skin.get("") == originalSkin,
                        "invalid_template_preserves_all_live_state_" + invalidIndex++);
            }
        } finally { Files.writeString(templateFile, "valid: {}\n"); }
        store.afterLoad = registry -> {
            int before = templateCalls;
            NeoForge.EVENT_BUS.post(new TemplateProbeEvent(registry.getById(world.getId())));
            check(templateCalls == before + 1, "candidate_load_inherits_template_event_subscription");
            check(net.citizensnpcs.npc.skin.Skin.get("") != originalSkin, "candidate_load_uses_staged_skin_cache");
            check(Setting.DEFAULT_NPC_LIMIT.asInt() == 71, "candidate_load_sees_new_settings");
            check(plugin.getNPCRegistry() == old, "old_binding_visible_during_load");
            for (NPC npc : registry) candidates.add(npc);
            NPC partial = registry.createNPC(EntityType.PIG, "PartialCandidate"); candidates.add(partial);
            check(partial.spawn(at(12)), "partial_candidate_can_spawn");
            partial.addTrait(new Probe(true));
            throw new IllegalStateException("expected provider reload failure");
        };
        NPCLoadException failure = rejectedReload();
        int beforeTemplates = templateCalls;
        for (NPC npc : candidates) NeoForge.EVENT_BUS.post(new TemplateProbeEvent(npc));
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(world));
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(queued));
        check(templateCalls == beforeTemplates + 2, "failed_reload_rolls_back_template_subscriptions");
        check(net.citizensnpcs.npc.skin.Skin.get("") == originalSkin, "failed_provider_restores_skin_cache");
        check(Setting.DEFAULT_NPC_LIMIT.asInt() == 37
                && Setting.DEFAULT_RANDOM_LOOK_DELAY.asDuration() == previousDuration,
                "failed_reload_restores_runtime_settings_and_duration_cache");
        check(failure.getCause().getMessage().equals("expected provider reload failure"), "original_failure_preserved");
        check(plugin.getNPCRegistry() == old && old.getById(world.getId()) == world, "failed_reload_preserves_identity");
        check(failure.getCause().getSuppressed().length == 1, "candidate_cleanup_failure_suppressed");
        check(world.getEntity() == entity && !entity.isRemoved() && virtual.getEntity() == packetEntity,
                "failed_reload_preserves_live_entities");
        check(probe.removals == 0 && queued.getOwningRegistry().getById(queued.getId()) == queued,
                "failed_reload_preserves_other_ownership");
        for (NPC npc : candidates) check(npc.getEntity() == null, "failed_candidate_released_" + passed);
        check(store.clears == 0 && store.records.size() == 2, "failure_preserves_records");
        store.afterLoad = registry -> plugin.getShops().storeShops();
        NPCLoadException stale = rejectedReload();
        check(net.citizensnpcs.npc.skin.Skin.get("") == originalSkin, "failed_shop_preflight_restores_skin_cache");
        check(stale.getCause().getMessage().contains("Shop source changed"), "stale_shop_candidate_rejected");
        check(plugin.getNPCRegistry() == old && world.getEntity() == entity && !entity.isRemoved()
                && Setting.DEFAULT_NPC_LIMIT.asInt() == 37,
                "shop_publication_preflight_failure_preserves_registry_and_settings");
        check(net.citizensnpcs.editor.Editor.getEditor(editorPlayer) == editor && editor.ends == 0
                && ChatPrompts.isActive(prompt), "failed_shop_preflight_preserves_editor_and_prompt");
        store.afterLoad = null;
    }

    private static void successfulReload() throws Exception {
        veto = true; plugin.reload(); veto = false;
        check(net.citizensnpcs.npc.skin.Skin.get("") != originalSkin, "successful_reload_commits_skin_cache");
        check(Setting.DEFAULT_NPC_LIMIT.asInt() == 71, "successful_reload_keeps_new_settings");
        check(plugin.getTemplateRegistry().getTemplateByKey(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("reloadprobe", "valid")) != null,
                "successful_reload_publishes_repaired_templates");
        check(plugin.getNPCRegistry() != old, "success_publishes_new_registry");
        int beforeTemplates = templateCalls;
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(world));
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(plugin.getNPCRegistry().getById(world.getId())));
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(queued));
        check(templateCalls == beforeTemplates + 2, "successful_reload_moves_template_subscriptions");
        check(!old.iterator().hasNext() && old.getByUniqueId(world.getUniqueId()) == null, "old_indexes_released");
        check(world.getEntity() == null && entity.isRemoved() && virtual.getEntity() == null,
                "retirement_overrides_despawn_veto");
        check(probe.removals == 1, "old_trait_removed_once");
        check(store.reloads == 6 && store.clears == 0 && store.records.size() == 2, "reload_preserves_store_definitions");
    }

    private static void cleanupFailure() throws Exception {
        NPCRegistry before = plugin.getNPCRegistry();
        NPC npc = before.getById(world.getId()); Entity previousEntity = npc.getEntity();
        Probe failing = new Probe(true); npc.addTrait(failing);
        var editorPlayer = new net.minecraft.server.level.ServerPlayer(server, level,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "ThrowingEditor"),
                net.minecraft.server.level.ClientInformation.createDefault());
        var editor = new ProbeEditor(true);
        net.citizensnpcs.editor.Editor.enterOrLeave(editorPlayer, editor);
        var promptEnds = new java.util.concurrent.atomic.AtomicInteger();
        var prompt = ChatPrompts.begin(editorPlayer, new ChatPrompt() {
            public String getPromptText(ChatPromptSession session) { return ""; }
            public ChatPrompt acceptInput(ChatPromptSession session, String input) { return this; }
        }, null, session -> session.onAbandon(() -> {
            promptEnds.incrementAndGet();
            throw new IllegalStateException("expected prompt cleanup failure");
        }));
        var oldShop = plugin.getShops().addNamedShop("reload-cleanup-shop");
        var shopCloseCalls = net.citizensnpcs.trait.shop.ReloadShopProbe.failOnClose(oldShop);
        plugin.getShops().storeShops();
        plugin.getShops().saveToDiskImmediate();
        writeLimit(83);
        NPCLoadException failure = rejectedReload();
        check(Setting.DEFAULT_NPC_LIMIT.asInt() == 83, "published_cleanup_failure_keeps_new_settings");
        check(shopCloseCalls.get() == 1 && !oldShop.isActive()
                && plugin.getShops().getGlobalShop("reload-cleanup-shop") != oldShop
                && plugin.getShops().getGlobalShop("reload-cleanup-shop") != null,
                "shop_cleanup_failure_keeps_new_shop_publication");
        check(failure.getCause().getSuppressed().length == 3,
                "shop_editor_prompt_and_npc_cleanup_errors_retained");
        check(promptEnds.get() == 1 && !ChatPrompts.isActive(prompt),
                "throwing_prompt_cleanup_does_not_abort_registry_publication");
        check(editor.ends == 1 && editor.observedRegistry != before
                && editor.observedRegistry == plugin.getNPCRegistry()
                && !net.citizensnpcs.editor.Editor.hasEditor(editorPlayer),
                "throwing_editor_cleanup_does_not_abort_registry_publication");
        check(failure.getMessage().contains("previous runtime cleanup failed"), "published_cleanup_failure_distinguished");
        check(plugin.getNPCRegistry() != before && plugin.getNPCRegistry().getById(world.getId()) != null,
                "cleanup_failure_keeps_successful_publication");
        check(npc.getEntity() == null && previousEntity.isRemoved() && failing.removals == 1,
                "throwing_cleanup_still_releases_runtime");
        check(store.clears == 0 && store.records.size() == 2, "cleanup_failure_preserves_records");
        int beforeTemplates = templateCalls;
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(npc));
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(plugin.getNPCRegistry().getById(world.getId())));
        check(templateCalls == beforeTemplates + 1, "published_cleanup_failure_keeps_template_subscriptions");
    }

    private static void replacementSubscriptions() {
        NPC before = plugin.getNPCRegistry().getById(world.getId());
        Store replacement = new Store();
        replacement.records.putAll(store.records);
        List<NPC> rejected = new ArrayList<>();
        replacement.afterLoad = registry -> {
            registry.forEach(rejected::add);
            throw new IllegalStateException("expected replacement load failure");
        };
        try {
            plugin.setDefaultNPCDataStore(replacement);
            throw new AssertionError("replacement should fail");
        } catch (RuntimeException expected) { }
        int beforeTemplates = templateCalls;
        for (NPC npc : rejected) NeoForge.EVENT_BUS.post(new TemplateProbeEvent(npc));
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(before));
        check(templateCalls == beforeTemplates + 1 && plugin.getNPCRegistry() == before.getOwningRegistry(),
                "failed_store_replacement_preserves_template_subscription");
        replacement.afterLoad = null;
        plugin.setDefaultNPCDataStore(replacement);
        beforeTemplates = templateCalls;
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(before));
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(plugin.getNPCRegistry().getById(world.getId())));
        check(templateCalls == beforeTemplates + 1, "store_replacement_moves_template_subscription");
    }

    public static final class TemplateProbeEvent extends net.citizensnpcs.api.event.NPCEvent {
        public TemplateProbeEvent(NPC npc) { super(npc); }
    }

    private static NPCLoadException rejectedReload() throws Exception {
        try { plugin.reload(); } catch (NPCLoadException failure) { return failure; }
        throw new AssertionError("reload_should_fail");
    }
    private static void writeLimit(int limit) {
        var config = new YamlStorage(new java.io.File(plugin.getDataFolder(), "config.yml"));
        if (!config.load()) throw new AssertionError("Could not read fixture configuration");
        config.getKey("").setInt("npc.limits.default-limit", limit);
        config.save();
    }
    private static final class ProbeEditor extends net.citizensnpcs.editor.Editor {
        private final boolean fail;
        int ends;
        NPCRegistry observedRegistry;
        ProbeEditor(boolean fail) { this.fail = fail; }
        @Override public void begin() { }
        @Override public void end() {
            ends++;
            observedRegistry = plugin.getNPCRegistry();
            if (fail) throw new IllegalStateException("expected editor cleanup failure");
        }
    }
    private static Location at(int x) { return new Location(level, x, -58, 4); }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[RELOADAUDIT] PASS {}", label);
    }
    private static void close() {
        if (done) return; done = true; veto = false;
        try {
            if (store != null) store.afterLoad = null;
            if (named != null) { for (NPC npc : named.sorted()) npc.destroy(); plugin.removeNamedNPCRegistry(named.getName()); }
            if (original != null) plugin.setDefaultNPCDataStore(original);
            if (forced) { level.setChunkForced(0, 0, false); level.setChunkForced(40, 0, false); }
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[RELOADAUDIT] FAILED cleanup", failure); }
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
