package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.ItemStorage;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.StoredItems;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.trait.shop.ItemAction;
import net.citizensnpcs.trait.shop.NPCShopStorage;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Executes migration against live registry-backed enchantments and native owner/transaction lifecycles. */
@EventBusSubscriber(modid = "citizens")
public final class BukkitMetaRuntimeAudit {
    private static boolean done, forced;
    private static int passed;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        var server = event.getServer(); var level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; }
        if (!level.areEntitiesLoaded(ChunkPos.asLong(0, 0)) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
        done = true;
        try {
            if (!Files.isRegularFile(Path.of("bukkit-meta-audit-fixture.txt")) || CitizensAPI.getNPCRegistry().iterator().hasNext())
                throw new AssertionError("Requires an isolated empty registry");
            run(server);
            LoggerFactory.getLogger("citizens").info("[BUKKITMETAAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[BUKKITMETAAUDIT] FAILED", failure); }
        finally { server.halt(false); }
    }

    private static void run(MinecraftServer server) throws Exception {
        var enchantments = server.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        ItemStack reference = null;
        for (String name : List.of("rich", "rich-guava25")) {
            DataKey key = item(name, "diamond_sword"); Object before = key.copy().getRaw("");
            ItemStack stack = ItemStorage.loadItemStack(key);
            check(stack != null && stack.is(Items.DIAMOND_SWORD), name + "_native_item");
            check(stack.get(DataComponents.ENCHANTMENTS).getLevel(enchantments.getHolderOrThrow(Enchantments.SHARPNESS)) == 5
                    && stack.get(DataComponents.ENCHANTMENTS).getLevel(enchantments.getHolderOrThrow(Enchantments.MENDING)) == 1,
                    name + "_actual_enchantment_holders");
            check(stack.getDamageValue() == 11 && stack.get(DataComponents.CUSTOM_MODEL_DATA).value() == 217
                    && stack.get(DataComponents.REPAIR_COST) == 4, name + "_numeric_metadata");
            check(stack.getHoverName().getString().equals("Archive &a<red> 中 \u0000 😀")
                    && stack.getHoverName().getStyle().getColor().getValue() == 0xffaa00
                    && !stack.getHoverName().getStyle().isItalic(), name + "_exact_json_name");
            check(stack.get(DataComponents.LORE).lines().size() == 2
                    && stack.get(DataComponents.LORE).lines().getFirst().getStyle().isBold(), name + "_json_lore");
            var encoded = ItemStack.CODEC.encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
            var enchantmentTag = ((net.minecraft.nbt.CompoundTag) encoded).getCompound("components").getCompound("minecraft:enchantments");
            check(enchantmentTag.contains("show_in_tooltip") && !enchantmentTag.getBoolean("show_in_tooltip"), name + "_tooltip_flags_encoded");
            check(!stack.get(DataComponents.UNBREAKABLE).showInTooltip()
                    && !stack.get(DataComponents.ATTRIBUTE_MODIFIERS).showInTooltip(), name + "_native_tooltip_flags");
            check(before.equals(key.getRaw("")), name + "_source_unchanged_by_read");
            ItemStorage.saveItem(key, stack);
            check(!key.keyExists("meta") && ItemStack.matches(stack, ItemStorage.loadItemStack(key)), name + "_native_round_trip");
            if (reference != null) check(ItemStack.matches(reference, stack), "both_guava_versions_restore_identical_stack");
            reference = stack;
        }
        ItemStack book = ItemStorage.loadItemStack(item("book", "enchanted_book"));
        check(book.get(DataComponents.STORED_ENCHANTMENTS).getLevel(enchantments.getHolderOrThrow(Enchantments.SHARPNESS)) == 3,
                "enchanted_book_uses_stored_enchantments");
        DataKey editing = item("rich", "diamond_sword");
        editing.setString("editable_components.display_name", "&bEdited"); editing.setBoolean("editable_components.edited", true);
        ItemStack edited = ItemStorage.loadItemStack(editing);
        check(edited.getHoverName().getString().equals("Edited") && !editing.getBoolean("editable_components.edited")
                && edited.get(DataComponents.ENCHANTMENTS).getLevel(enchantments.getHolderOrThrow(Enchantments.SHARPNESS)) == 5,
                "pending_edit_applies_after_complete_metadata");

        var registry = CitizensAPI.createNamedNPCRegistry("bukkit-meta", new net.citizensnpcs.api.npc.MemoryNPCDataStore());
        NPC npc = registry.createNPC(EntityType.ZOMBIE, "BukkitMeta");
        Inventory inventory = npc.getOrAddTrait(Inventory.class);
        DataKey slots = new MemoryDataKey(); slots.setRaw("5", item("rich", "diamond_sword").getRaw("")); inventory.load(slots);
        check(npc.spawn(new Location(server.overworld(), 1, -60, 1)), "migrated_inventory_npc_spawn");
        npc.despawn(DespawnReason.PENDING_RESPAWN);
        check(ItemStack.matches(reference, inventory.getContents()[5]), "despawn_keeps_exact_migrated_stack");
        var yaml = new YamlStorage(Path.of("migrated.yml").toAbsolutePath().toFile()); inventory.save(yaml.getKey("inventory")); yaml.save();
        var reloaded = new YamlStorage(Path.of("migrated.yml").toAbsolutePath().toFile()); check(reloaded.load(), "yaml_reload");
        check(ItemStack.matches(reference, ItemStorage.loadItemStack(reloaded.getKey("inventory.5"))), "yaml_native_components_survive");
        NPC copy = npc.copy(); check(ItemStack.matches(reference, copy.getOrAddTrait(Inventory.class).getContents()[5]), "npc_copy_keeps_metadata");
        copy.destroy(); npc.destroy();

        DataKey actionKey = new MemoryDataKey(); actionKey.setRaw("items.0", item("rich", "diamond_sword").getRaw(""));
        ItemAction action = PersistenceLoader.load(ItemAction.class, actionKey);
        var container = new SimpleContainer(9); var im = new InventoryMultiplexer(container); var stock = new NPCShopStorage();
        var reward = action.grant(stock, null, im, 1); check(reward.isPossible(), "migrated_reward_available"); reward.run();
        check(ItemStack.matches(reference, container.getItem(0)), "migrated_reward_is_exact_native_item");
        var payment = action.take(stock, null, im, 1); check(payment.isPossible(), "migrated_cost_matches_components"); payment.run();
        check(container.isEmpty(), "migrated_cost_consumes_exact_item");
        for (String name : List.of("unknown", "invalid", "cycle")) {
            DataKey key = item(name, "diamond_sword"); Object before = key.copy().getRaw("");
            StoredItems<Integer> stored = new StoredItems<>(); check(stored.load(0, key) == null && stored.contains(0), name + "_unavailable");
            stored.save(0, key, null); check(before.equals(key.getRaw("")), name + "_retained");
        }
    }

    private static DataKey item(String name, String type) throws Exception {
        DataKey key = new MemoryDataKey().getRelative("item"); key.setString("type_key", type); key.setInt("amount", 1);
        key.setString("meta.encoded-meta", Files.readString(Path.of(System.getProperty("citizens.audit.legacyMetaFixtures"), name + ".base64")));
        return key;
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name); passed++; LoggerFactory.getLogger("citizens").info("[BUKKITMETAAUDIT] PASS {}", name);
    }
}
