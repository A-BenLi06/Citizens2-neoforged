package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.npc.SimpleNPCDataStore;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.ItemStorage;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.api.util.StoredItemList;
import net.citizensnpcs.trait.ItemFrameTrait;
import net.citizensnpcs.trait.shop.ItemAction;
import net.citizensnpcs.trait.shop.NPCShopStorage;
import net.citizensnpcs.trait.shop.NPCShopItem;
import net.citizensnpcs.trait.DropsTrait.ItemDrop;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Reuses the same on-disk NPC through real process restarts without/with/without the actual NetMusic provider. */
@EventBusSubscriber(modid = "citizens")
public final class ItemRecoveryRuntimeAudit {
    private static final List<String> PATHS = List.of("traits.inventory.5", "traits.equipment.helmet",
            "traits.equipment.cosmetic_off_hand", "traits.itemframe.item", "itemprovider");
    private static final String SONG = "{id:'netmusic:music_cd',count:1,components:{'netmusic:song_info':"
            + "{url:'https://example.invalid/recovery',name:'Recovery song',time_second:31}}}";
    private static boolean done, forced;
    private static int passed;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        var server = event.getServer(); var level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; }
        if (!level.areEntitiesLoaded(ChunkPos.asLong(0, 0)) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
        done = true;
        try {
            if (!Files.isRegularFile(Path.of("item-recovery-audit-fixture.txt")) || CitizensAPI.getNPCRegistry().iterator().hasNext())
                throw new AssertionError("The recovery audit requires its own empty fixture");
            run(server);
            LoggerFactory.getLogger("citizens").info("[ITEMRECOVERYAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[ITEMRECOVERYAUDIT] FAILED", failure); }
        finally { server.halt(false); }
    }

    private static void run(MinecraftServer server) throws Exception {
        String phase = System.getProperty("citizens.audit.itemRecoveryPhase");
        boolean present = BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse("netmusic:music_cd"));
        check(present == phase.equals("present"), "actual_provider_matches_phase_" + phase);
        var file = Path.of("recovery.yml").toAbsolutePath();
        boolean seed = !Files.exists(file);
        if (seed && !phase.equals("absent")) throw new AssertionError("First run must seed absent-provider data");
        var yaml = new YamlStorage(file.toFile());
        var store = new SimpleNPCDataStore(yaml);
        NPCRegistry registry = CitizensAPI.createNamedNPCRegistry("item-recovery", store);
        if (seed) {
            NPC template = registry.createNPC(EntityType.ZOMBIE, "ItemRecovery");
            template.getOrAddTrait(Inventory.class); template.getOrAddTrait(Equipment.class); template.getOrAddTrait(ItemFrameTrait.class);
            var snapshot = new MemoryDataKey(); template.saveSnapshot(snapshot);
            for (String path : PATHS) {
                snapshot.getRelative(path).setString("nbt", SONG);
                snapshot.getRelative(path).setRaw("provider-data", Map.of("literal.key", List.of("keep", "unchanged")));
            }
            ItemStorage.saveItem(snapshot.getRelative("traits.inventory.1"), new ItemStack(Items.DIAMOND, 2));
            template.destroy();
            yaml.getKey("npc.10").setRaw("", snapshot.getRaw(""));
            yaml.save();
        }
        check(yaml.load(), "source_yaml_loaded_through_storage");
        if (!yaml.getKey("action").keyExists()) {
            yaml.getKey("action.items.0").setString("nbt", SONG);
            ItemStorage.saveItem(yaml.getKey("action.items.1"), new ItemStack(Items.DIAMOND, 2));
            yaml.getKey("stock.inventory").setRaw("", yaml.getKey("action.items").copy().getRaw(""));
            yaml.getKey("stock").setBoolean("unlimited", false);
        }
        Object originalAction = yaml.getKey("action.items.0").copy().getRaw("");
        if (!yaml.getKey("drop").keyExists()) {
            yaml.getKey("drop.drop").setString("nbt", SONG); yaml.getKey("drop").setDouble("chance", 0.75);
            yaml.getKey("display.display").setString("nbt", SONG);
        }
        ItemDrop drop = PersistenceLoader.load(ItemDrop.class, yaml.getKey("drop"));
        NPCShopItem display = PersistenceLoader.load(NPCShopItem.class, yaml.getKey("display"));
        check(drop != null && drop.isUnavailable() != present && drop.getChance() == 0.75, "drop_preserves_definition_or_recovers_provider");
        check(display != null && display.hasUnresolvedDisplay() != present, "shop_display_preserves_definition_or_recovers_provider");
        PersistenceLoader.save(drop, yaml.getKey("drop")); PersistenceLoader.save(display, yaml.getKey("display"));
        ItemAction action = PersistenceLoader.load(ItemAction.class, yaml.getKey("action"));
        NPCShopStorage stock = PersistenceLoader.load(NPCShopStorage.class, yaml.getKey("stock"));
        check(action != null && action.items.size() == 2 && StoredItemList.hasUnavailable(action.items) != present,
                "action_list_preserves_missing_position_or_recovers_provider");
        check(stock != null && stock.getInventory().size() == 2 && stock.hasUnavailableItems() != present,
                "limited_stock_preserves_missing_position_or_recovers_provider");
        var transactionInventory = new InventoryMultiplexer(new SimpleContainer(9));
        check(action.grant(new NPCShopStorage(), null, transactionInventory, 1).isPossible() == present,
                "action_reward_requires_complete_provider_data");
        PersistenceLoader.save(action, yaml.getKey("action")); PersistenceLoader.save(stock, yaml.getKey("stock"));
        if (present) check(ItemStack.isSameItemSameComponents(action.items.get(0),
                ItemStorage.loadItemStack(yaml.getKey("action.items.0"))), "action_native_components_persist");
        else check(originalAction.equals(yaml.getKey("action.items.0").getRaw("")), "action_unavailable_record_persists");
        DataKey original = yaml.getKey("npc.10").copy();
        store.loadInto(registry);
        NPC npc = registry.getById(10);
        check(npc != null, "npc_loaded_with_original_identity");
        var inventory = npc.getOrAddTrait(Inventory.class); var equipment = npc.getOrAddTrait(Equipment.class);
        var frame = npc.getOrAddTrait(ItemFrameTrait.class);
        check(inventory.getContents()[1].getCount() == 2, "available_inventory_slot_unchanged");
        check(inventory.getUnresolvedSlots().contains(5) != present, "inventory_exposes_unresolved_slot");
        check(equipment.getUnresolvedSlots().contains(EquipmentSlot.HELMET) != present, "equipment_exposes_unresolved_slot");
        check(equipment.getUnresolvedCosmeticSlots().contains(EquipmentSlot.OFF_HAND) != present, "cosmetic_equipment_exposes_unresolved_slot");
        check(frame.hasUnresolvedItem() != present, "frame_exposes_unresolved_item");
        if (present) {
            for (ItemStack item : List.of(inventory.getContents()[5], equipment.get(EquipmentSlot.HELMET),
                    equipment.getCosmetic(EquipmentSlot.OFF_HAND), frame.getItem(), npc.getItemProvider().get())) {
                var nativeType = Class.forName("com.github.tartaricacid.netmusic.item.ItemMusicCD");
                Object song = nativeType.getMethod("getSongInfo", ItemStack.class).invoke(null, item);
                check(song != null && song.getClass().getField("songName").get(song).equals("Recovery song")
                        && song.getClass().getField("songTime").getInt(song) == 31, "native_song_accessor_recovers_owner_" + passed);
            }
        } else {
            check(inventory.getContents()[5] == null && equipment.get(EquipmentSlot.HELMET) == null
                    && equipment.getCosmetic(EquipmentSlot.OFF_HAND) == null && frame.getItem() == null
                    && npc.getItemProvider().get().isEmpty(), "unavailable_records_never_become_replacement_items");
        }
        check(npc.spawn(new Location(server.overworld(), 1, -60, 1)), "native_entity_spawn_succeeds");
        npc.despawn(DespawnReason.PLUGIN);
        var snapshot = new MemoryDataKey(); npc.saveSnapshot(snapshot);
        for (String path : PATHS) {
            if (present) check(ItemStack.isSameItemSameComponents(ItemStorage.loadItemStack(original.getRelative(path)),
                    ItemStorage.loadItemStack(snapshot.getRelative(path))), "snapshot_preserves_native_components_" + path);
            else check(original.getRaw(path).equals(snapshot.getRaw(path)), "snapshot_preserves_unavailable_record_" + path);
        }
        NPC copy = npc.copy(); var copied = new MemoryDataKey(); copy.saveSnapshot(copied);
        for (String path : PATHS) check(snapshot.getRaw(path).equals(copied.getRaw(path)), "npc_copy_preserves_record_" + path);
        copy.destroy();
        store.store(npc); store.saveToDiskImmediate();
        var reloaded = new YamlStorage(file.toFile()); check(reloaded.load(), "owner_save_reopens_from_disk");
        ItemAction restoredAction = PersistenceLoader.load(ItemAction.class, reloaded.getKey("action"));
        check(PersistenceLoader.load(ItemDrop.class, reloaded.getKey("drop")).isUnavailable() != present, "drop_survives_disk_reload");
        check(PersistenceLoader.load(NPCShopItem.class, reloaded.getKey("display")).hasUnresolvedDisplay() != present, "shop_display_survives_disk_reload");
        check(restoredAction != null && restoredAction.items.size() == 2
                && StoredItemList.hasUnavailable(restoredAction.items) != present, "action_list_survives_real_disk_reload");
        for (String path : PATHS) {
            if (present) check(ItemStack.isSameItemSameComponents(ItemStorage.loadItemStack(snapshot.getRelative(path)),
                    ItemStorage.loadItemStack(reloaded.getKey("npc.10").getRelative(path))), "disk_preserves_native_components_" + path);
            else check(snapshot.getRaw(path).equals(reloaded.getKey("npc.10").getRaw(path)), "disk_preserves_record_" + path);
        }

        // Edits run on a copy so the persisted source remains available for the following process.
        NPC edited = npc.copy();
        edited.getOrAddTrait(Inventory.class).setItem(5, null);
        edited.getOrAddTrait(Equipment.class).set(EquipmentSlot.HELMET, null);
        edited.getOrAddTrait(Equipment.class).setCosmetic(EquipmentSlot.OFF_HAND, null);
        edited.getOrAddTrait(ItemFrameTrait.class).setItem(null);
        edited.setItemProvider(() -> ItemStack.EMPTY);
        var cleared = new MemoryDataKey(); edited.saveSnapshot(cleared);
        for (String path : PATHS) check(path.equals("itemprovider")
                ? !ItemStorage.readItem(cleared.getRelative(path)).unavailable() && ItemStorage.loadItemStack(cleared.getRelative(path)) == null
                : !cleared.keyExists(path), "explicit_clear_removes_record_" + path);
        NPC emptyCopy = edited.copy();
        check(emptyCopy.getItemProvider().get().isEmpty(), "cleared_provider_stays_empty_after_snapshot_reload");
        emptyCopy.destroy();
        edited.destroy();
        NPC replaced = npc.copy();
        replaced.getOrAddTrait(Inventory.class).setItem(5, new ItemStack(Items.EMERALD));
        replaced.getOrAddTrait(Equipment.class).set(EquipmentSlot.HELMET, new ItemStack(Items.GOLDEN_HELMET));
        replaced.getOrAddTrait(Equipment.class).setCosmetic(EquipmentSlot.OFF_HAND, new ItemStack(Items.SHIELD));
        replaced.getOrAddTrait(ItemFrameTrait.class).setItem(new ItemStack(Items.CLOCK));
        replaced.setItemProvider(() -> new ItemStack(Items.COMPASS));
        var changed = new MemoryDataKey(); replaced.saveSnapshot(changed);
        for (String path : PATHS) check(ItemStorage.loadItemStack(changed.getRelative(path)) != null
                && !changed.getString(path + ".nbt").contains("netmusic"), "explicit_replace_removes_record_" + path);
        replaced.destroy();
        // Retained data is already on disk; remove the registry from lifecycle ownership without clearing its store.
        CitizensAPI.removeNamedNPCRegistry("item-recovery");
    }

    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("citizens").info("[ITEMRECOVERYAUDIT] PASS {}", name);
    }
}
