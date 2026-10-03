package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCCreateEvent;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class DatastoreRuntimeAudit {
    private static final List<NPCRegistry> temporary = new ArrayList<>();
    private static final String phase = System.getProperty("citizens.datastore.audit.phase", "absent");
    private static final Path providerFile = Path.of("provider-state.yml");
    private static Consumer<NPCCreateEvent> creating;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static int started = -1, reloadTick, passed;
    private static boolean done, forced;
    private static NPC oldWorld, oldVirtual;
    private static Entity oldWorldEntity, oldVirtualEntity;
    private static UUID worldUUID, virtualUUID;
    private static byte[] defaultSaved;

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void created(NPCCreateEvent event) { if (creating != null) creating.accept(event); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer(); level = server.overworld();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("datastore-audit-fixture.txt")), "isolated_fixture");
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_default_registry");
                level.setChunkForced(0, 0, true); forced = true; started = server.getTickCount();
            }
            if (server.getTickCount() - started > 400) throw new AssertionError("fixture_timeout");
            if (!level.isPositionEntityTicking(new BlockPos(4, -58, 4))) return;
            if (reloadTick == 0) {
                providerRecovery(); callbackRecovery(); allocatorAndAliases(); reloadFailure();
                reloadTick = server.getTickCount();
            } else if (server.getTickCount() - reloadTick >= 4) {
                verifyReload();
                YamlStorage completed = new YamlStorage(providerFile.toAbsolutePath().toFile());
                check(completed.load(), "completed_phase_source_reads");
                completed.getKey("").setString("audit-last-phase", phase); completed.save();
                close();
                LoggerFactory.getLogger("citizens").info("[DATASTOREAUDIT] COMPLETE {} checks phase={}", passed, phase);
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[DATASTOREAUDIT] FAILED phase=" + phase, failure);
            close();
        }
    }

    private static NPCRegistry registry(NPCDataStore store) {
        NPCRegistry registry = CitizensAPI.createNamedNPCRegistry("datastore-audit-" + temporary.size(), store);
        temporary.add(registry);
        return registry;
    }

    private static void providerRecovery() throws Exception {
        if (!Files.exists(providerFile)) {
            check(phase.equals("absent"), "provider_sequence_starts_absent");
            YamlStorage initial = new YamlStorage(providerFile.toAbsolutePath().toFile());
            record(initial, "0", "ReturningAnimal", DatastoreProviderRuntimeAudit.ID.toString());
            initial.getKey("npc.0").setMap("unavailable-payload", Map.of("literal.key", List.of("kept", "unchanged")));
            record(initial, "1", "ValidBefore", "PIG");
            record(initial, "2", "BrokenUuid", "PIG").setString("uuid", "bad-uuid");
            record(initial, "3", "ValidAfter", "Zombie Villager");
            record(initial, "4", "NoName", "PIG").removeKey("name");
            record(initial, "5", "NoTraits", "PIG").setString("traitnames", "");
            record(initial, "6", "DuplicateUuid", "PIG").setString("uuid", initial.getKey("npc.1").getString("uuid"));
            initial.getKey("").setString("npc.100", "unreadable definition");
            initial.getKey("").setInt("last-created-npc-id", 0);
            initial.save();
        }
        YamlStorage storage = new YamlStorage(providerFile.toAbsolutePath().toFile());
        check(storage.load(), "provider_source_reads");
        String previous = storage.getKey("").getString("audit-last-phase");
        if (phase.equals("present")) check(previous.equals("absent"), "present_follows_absent");
        if (phase.equals("absent-again")) check(previous.equals("present"), "second_absence_follows_present");
        var missing = storage.getKey("npc.0").getValuesDeep();
        var malformed = storage.getKey("npc.2").getValuesDeep();
        var nameless = storage.getKey("npc.4").getValuesDeep();
        var traitless = storage.getKey("npc.5").getValuesDeep();
        var duplicate = storage.getKey("npc.6").getValuesDeep();
        SimpleNPCDataStore store = new SimpleNPCDataStore(storage);
        NPCRegistry registry = registry(store);
        store.loadInto(registry);
        check(registry.getById(1) != null && registry.getById(3) != null, "valid_records_survive_rejected_neighbors");
        check(registry.getById(2) == null && registry.getById(4) == null && registry.getById(5) == null
                && registry.getById(6) == null && registry.getById(100) == null, "rejected_records_remain_unloaded");
        check(registry.getById(3).getOrAddTrait(MobType.class).getType() == EntityType.ZOMBIE_VILLAGER, "legacy_type_matches_actual_npc");
        boolean present = phase.equals("present");
        check(BuiltInRegistries.ENTITY_TYPE.containsKey(DatastoreProviderRuntimeAudit.ID) == present, "actual_provider_registration");
        check((registry.getById(0) != null) == present, "provider_controls_restoration");
        if (present) {
            NPC recovered = registry.getById(0);
            check(recovered.getUniqueId().toString().equals(missing.get("uuid")), "recovery_keeps_uuid");
            check(recovered.spawn(new Location(level, 4, -58, 4)), "recovered_mod_entity_spawns");
            check(recovered.getEntity().getType() == DatastoreProviderRuntimeAudit.type, "recovery_uses_actual_mod_entity");
            recovered.despawn();
        }
        NPC created = registry.createNPC(EntityType.PIG, "NewDuring-" + phase);
        check(created.getId() > 100, "allocation_reserves_unloaded_records");
        store.storeAll(registry); store.saveToDiskImmediate();
        YamlStorage reread = new YamlStorage(providerFile.toAbsolutePath().toFile());
        check(reread.load(), "saved_provider_state_reads");
        if (!present) check(missing.equals(reread.getKey("npc.0").getValuesDeep()), "unavailable_definition_survives_full_save");
        check(malformed.equals(reread.getKey("npc.2").getValuesDeep()), "malformed_uuid_definition_retained");
        check(nameless.equals(reread.getKey("npc.4").getValuesDeep()), "nameless_definition_retained");
        check(traitless.equals(reread.getKey("npc.5").getValuesDeep()), "traitless_definition_retained");
        check(duplicate.equals(reread.getKey("npc.6").getValuesDeep()), "duplicate_uuid_definition_retained");
        check(reread.getKey("").getString("npc.100").equals("unreadable definition"), "nonmapping_record_retained");
        check(reread.getKey("npc.0").getRaw("unavailable-payload").equals(missing.get("unavailable-payload")), "opaque_payload_survives_provider_cycle");
    }

    private static void callbackRecovery() throws Exception {
        YamlStorage storage = new YamlStorage(Path.of("callbacks.yml").toAbsolutePath().toFile());
        storage.getKey("").setRaw("", Map.of());
        record(storage, "20", "Vetoed", "PIG");
        record(storage, "21", "SaveDuringLoad", "PIG");
        record(storage, "22", "Following", "PIG");
        var before = storage.getKey("").getValuesDeep();
        SimpleNPCDataStore store = new SimpleNPCDataStore(storage);
        NPCRegistry registry = registry(store);
        boolean[] refused = { false };
        creating = event -> {
            if (event.getNPC().getOwningRegistry() != registry) return;
            if (event.getNPC().getId() == 20) event.setCanceled(true);
            if (event.getNPC().getId() == 21) {
                try { store.store(event.getNPC()); }
                catch (IllegalStateException expected) { refused[0] = true; }
            }
        };
        try { store.loadInto(registry); } finally { creating = null; }
        check(registry.getById(20) == null && registry.getById(21) != null && registry.getById(22) != null, "native_creation_veto_isolated");
        check(refused[0], "native_callback_cannot_publish_partial_state");
        check(before.equals(storage.getKey("").getValuesDeep()), "native_creation_cleanup_preserves_source");
        store.storeAll(registry);
        check(before.get("npc") instanceof Map<?, ?> map && map.get("20").equals(storage.getKey("npc.20").getValuesDeep()), "vetoed_definition_survives_later_save");
        registry.despawnNPCs(DespawnReason.RELOAD);
        store.loadInto(registry);
        check(registry.getById(20) != null, "vetoed_record_recovers_on_later_load");
        registry.getById(20).destroy();
        check(!storage.getKey("").keyExists("npc.20"), "explicit_removal_after_restoration_clears_record");
    }

    private static void allocatorAndAliases() {
        YamlStorage storage = new YamlStorage(Path.of("aliases.yml").toAbsolutePath().toFile());
        storage.getKey("").setRaw("", Map.of());
        record(storage, "01", "AmbiguousOne", "PIG");
        record(storage, "1", "AmbiguousTwo", "PIG");
        record(storage, "007", "UniqueSpelling", "PIG");
        SimpleNPCDataStore store = new SimpleNPCDataStore(storage);
        NPCRegistry registry = registry(store);
        store.loadInto(registry);
        check(registry.getById(1) == null && registry.getById(7) != null, "ambiguous_ids_do_not_overwrite_each_other");
        registry.getById(7).setName("UpdatedSpelling"); store.storeAll(registry);
        check(!storage.getKey("").keyExists("npc.7") && storage.getKey("npc.007").getString("name").equals("UpdatedSpelling"), "unique_id_spelling_survives_native_save");
        registry.getById(7).destroy();
        check(!storage.getKey("").keyExists("npc.007"), "native_removal_clears_original_spelling");
        NPCRegistry memory = registry(new MemoryNPCDataStore());
        NPC explicit = memory.createNPC(EntityType.PIG, UUID.randomUUID(), 0, "ExplicitZero");
        NPC automatic = memory.createNPC(EntityType.PIG, "Automatic");
        check(automatic.getId() != 0 && memory.getById(0) == explicit, "memory_allocator_preserves_explicit_npc");
    }

    private static void reloadFailure() throws Exception {
        Citizens plugin = Citizens.getInstance();
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        oldWorld = registry.createNPC(EntityType.PIG, "ReloadWorld");
        oldVirtual = registry.createNPC(EntityType.PIG, "ReloadVirtual");
        oldVirtual.getOrAddTrait(PacketNPC.class);
        check(oldWorld.spawn(new Location(level, 4, -58, 4)) && oldVirtual.spawn(new Location(level, 6, -58, 4)), "reload_fixture_world_and_virtual_spawn");
        oldWorldEntity = oldWorld.getEntity(); oldVirtualEntity = oldVirtual.getEntity();
        worldUUID = oldWorld.getUniqueId(); virtualUUID = oldVirtual.getUniqueId();
        plugin.storeNPCs();
        Path file = plugin.getDataFolder().toPath().resolve("saves.yml");
        defaultSaved = Files.readAllBytes(file);
        for (String invalid : List.of("[broken", "scalar root", "npc: one\nnpc: two\n", "npc: invalid-root\n", "npc:\n  1: one\n  '1': two\n")) {
            Files.writeString(file, invalid);
            boolean rejected = false;
            try { plugin.reload(); } catch (NPCLoadException expected) { rejected = true; }
            check(rejected, "invalid_reload_reports_failure_" + passed);
            assertOriginals(registry);
            check(Files.readString(file).equals(invalid), "failed_reload_does_not_rewrite_file_" + passed);
        }
        Files.writeString(file, "[broken");
        Setting.WARN_ON_RELOAD.set(false);
        boolean commandFailed = false;
        try { server.getCommands().getDispatcher().execute("citizens reload", server.createCommandSourceStack()); }
        catch (CommandSyntaxException expected) { commandFailed = true; }
        check(commandFailed, "reload_command_propagates_failure");
        assertOriginals(registry);
        Files.write(file, defaultSaved);
        plugin.reload();
        check(registry.getByUniqueId(worldUUID) != oldWorld && registry.getByUniqueId(virtualUUID) != oldVirtual, "valid_reload_replaces_npc_instances");
        check(oldWorldEntity.isRemoved() && oldWorld.getEntity() == null && oldVirtual.getEntity() == null, "valid_reload_retires_previous_entities");
    }

    private static void assertOriginals(NPCRegistry registry) {
        check(registry.getByUniqueId(worldUUID) == oldWorld && registry.getByUniqueId(virtualUUID) == oldVirtual, "failed_reload_preserves_registry_identities_" + passed);
        check(oldWorld.isSpawned() && oldVirtual.isSpawned() && oldWorld.getEntity() == oldWorldEntity
                && oldVirtual.getEntity() == oldVirtualEntity, "failed_reload_preserves_live_entities_" + passed);
        check(level.getEntity(oldWorldEntity.getUUID()) == oldWorldEntity && level.getEntity(oldVirtualEntity.getUUID()) == null,
                "failed_reload_preserves_native_transport_" + passed);
    }

    private static void verifyReload() {
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        NPC world = registry.getByUniqueId(worldUUID), virtual = registry.getByUniqueId(virtualUUID);
        check(world != null && virtual != null && world.isSpawned() && virtual.isSpawned(), "valid_reload_restores_spawned_npcs");
        check(world.getEntity().getType() == EntityType.PIG && virtual.getEntity().getType() == EntityType.PIG, "valid_reload_preserves_entity_types");
        check(level.getEntity(world.getEntity().getUUID()) == world.getEntity()
                && level.getEntity(virtual.getEntity().getUUID()) == null, "valid_reload_preserves_world_and_virtual_transport");
    }

    private static DataKey record(YamlStorage storage, String id, String name, String type) {
        DataKey key = storage.getKey("npc." + id);
        key.setString("name", name);
        key.setString("uuid", UUID.nameUUIDFromBytes(("datastore-" + name + "-" + id).getBytes(StandardCharsets.UTF_8)).toString());
        key.setString("traitnames", "type,spawned"); key.setString("traits.type", type); key.setBoolean("traits.spawned", false);
        return key;
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
        LoggerFactory.getLogger("citizens").info("[DATASTOREAUDIT] PASS {}", name);
    }

    private static void close() {
        if (done) return;
        done = true; creating = null;
        try {
            for (NPCRegistry registry : temporary) {
                registry.despawnNPCs(DespawnReason.RELOAD);
                CitizensAPI.removeNamedNPCRegistry(registry.getName());
            }
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            for (NPC npc : java.util.stream.StreamSupport.stream(registry.spliterator(), false).toList()) npc.destroy();
            if (defaultSaved != null) {
                Citizens.getInstance().storeNPCs();
                defaultSaved = null;
            }
            if (forced) level.setChunkForced(0, 0, false);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[DATASTOREAUDIT] FAILED cleanup", failure);
        }
        server.halt(false);
    }
}
