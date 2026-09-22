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
        specialTypes(server);
        structuredItems(server);
        fireworks(server);
    }

    private static void fireworks(MinecraftServer server) throws Exception {
        for (String[] sample : List.of(new String[]{"fireworks", "firework_rocket"}, new String[]{"firework-star", "firework_star"},
                new String[]{"empty-firework", "firework_rocket"}, new String[]{"empty-firework-star", "firework_star"})) {
            DataKey key = item(sample[0], sample[1]); Object before = key.copy().getRaw(""); var stack = ItemStorage.loadItemStack(key);
            check(stack != null, sample[0] + "_available"); check(before.equals(key.getRaw("")), sample[0] + "_source_unchanged");
            ItemStorage.saveItem(key, stack); check(ItemStack.matches(stack, ItemStorage.loadItemStack(key)), sample[0] + "_native_resave");
        }
        var structured = new YamlStorage(Path.of(System.getProperty("citizens.audit.legacyMetaFixtures"), "structured-fireworks.yml").toFile());
        check(structured.load(), "structured_firework_fixture_loaded");
        for (String name : List.of("rocket", "empty")) {
            DataKey key = structured.getKey(name).copy(); Object before = key.copy().getRaw(""); var stack = ItemStorage.loadItemStack(key);
            check(stack != null, "structured_firework_" + name + "_available"); check(before.equals(key.getRaw("")), "structured_firework_" + name + "_source_unchanged");
            ItemStorage.saveItem(key, stack); check(ItemStack.matches(stack, ItemStorage.loadItemStack(key)), "structured_firework_" + name + "_native_resave");
        }
        var structuredRocket = ItemStorage.loadItemStack(structured.getKey("rocket")).get(DataComponents.FIREWORKS);
        check(structuredRocket.flightDuration() == 3 && structuredRocket.explosions().getFirst().shape() == net.minecraft.world.item.component.FireworkExplosion.Shape.BURST
                && structuredRocket.explosions().getLast().shape() == net.minecraft.world.item.component.FireworkExplosion.Shape.CREEPER,
                "structured_firework_order_and_flight");

        ItemStack star = ItemStorage.loadItemStack(item("firework-star", "firework_star")); ItemStack beforeStar = star.copy();
        var rocketRecipe = new net.minecraft.world.item.crafting.FireworkRocketRecipe(net.minecraft.world.item.crafting.CraftingBookCategory.MISC);
        var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, List.of(new ItemStack(Items.PAPER), new ItemStack(Items.GUNPOWDER), star));
        check(rocketRecipe.matches(input, server.overworld()), "migrated_star_matches_native_rocket_recipe");
        ItemStack crafted = rocketRecipe.assemble(input, server.registryAccess());
        check(crafted.is(Items.FIREWORK_ROCKET) && crafted.getCount() == 3 && crafted.get(DataComponents.FIREWORKS).flightDuration() == 1
                && crafted.get(DataComponents.FIREWORKS).explosions().equals(List.of(star.get(DataComponents.FIREWORK_EXPLOSION))),
                "native_recipe_carries_exact_migrated_star");
        var fadeRecipe = new net.minecraft.world.item.crafting.FireworkStarFadeRecipe(net.minecraft.world.item.crafting.CraftingBookCategory.MISC);
        var fadeInput = net.minecraft.world.item.crafting.CraftingInput.of(2, 1, List.of(star, new ItemStack(Items.BLUE_DYE)));
        check(fadeRecipe.matches(fadeInput, server.overworld()), "migrated_star_matches_native_fade_recipe");
        var faded = fadeRecipe.assemble(fadeInput, server.registryAccess()).get(DataComponents.FIREWORK_EXPLOSION);
        check(faded.shape() == star.get(DataComponents.FIREWORK_EXPLOSION).shape() && faded.hasTrail() && faded.hasTwinkle()
                && faded.colors().equals(star.get(DataComponents.FIREWORK_EXPLOSION).colors())
                && faded.fadeColors().getInt(0) == net.minecraft.world.item.DyeColor.BLUE.getFireworkColor(), "native_fade_recipe_preserves_explosion_fields");
        check(ItemStack.matches(beforeStar, star), "native_recipes_do_not_mutate_star_template");

        ItemStack stack = ItemStorage.loadItemStack(item("fireworks", "firework_rocket"));
        var rocket = new net.minecraft.world.entity.projectile.FireworkRocketEntity(server.overworld(), 8, -59, 8, stack);
        var target = EntityType.COW.create(server.overworld()); target.setPos(8, -60, 8);
        var restored = EntityType.FIREWORK_ROCKET.create(server.overworld());
        try {
            check(server.overworld().addFreshEntity(rocket) && server.overworld().addFreshEntity(target), "migrated_rocket_and_target_spawn");
            check(ItemStack.matches(stack, rocket.getItem()), "native_rocket_keeps_exact_migrated_stack");
            var tag = new net.minecraft.nbt.CompoundTag(); rocket.addAdditionalSaveData(tag);
            check(tag.getInt("LifeTime") >= 30 && tag.getInt("LifeTime") <= 41, "native_rocket_flight_uses_migrated_power");
            restored.readAdditionalSaveData(tag);
            check(ItemStack.matches(rocket.getItem(), restored.getItem()), "native_rocket_entity_save_load_keeps_effects");
            float health = target.getHealth(); tag.putInt("Life", 0); tag.putInt("LifeTime", 0); rocket.readAdditionalSaveData(tag); rocket.tick();
            check(rocket.isRemoved(), "native_rocket_expires_and_explodes");
            check(target.getHealth() < health, "native_migrated_explosion_damages_target");
        } finally { rocket.discard(); target.discard(); restored.discard(); }

        for (String[] invalid : List.of(new String[]{"firework-bad-power", "firework_rocket"}, new String[]{"firework-too-many", "firework_rocket"},
                new String[]{"firework-empty-colors", "firework_star"}, new String[]{"firework-unknown-shape", "firework_star"},
                new String[]{"firework-bad-field", "firework_star"}, new String[]{"fireworks", "stone"}, new String[]{"firework-star", "firework_rocket"})) {
            DataKey key = item(invalid[0], invalid[1]); Object before = key.copy().getRaw(""); var stored = new StoredItems<Integer>();
            check(stored.load(0, key) == null && stored.contains(0), invalid[0] + "_" + invalid[1] + "_retained_unavailable");
            stored.save(0, key, null); check(before.equals(key.getRaw("")), invalid[0] + "_" + invalid[1] + "_source_retained");
        }
        DataKey invalid = structured.getKey("invalid").copy(); Object before = invalid.copy().getRaw(""); var stored = new StoredItems<Integer>();
        check(stored.load(0, invalid) == null && stored.contains(0), "structured_invalid_firework_unavailable");
        stored.save(0, invalid, null); check(before.equals(invalid.getRaw("")), "structured_invalid_firework_retained");
    }

    private static void structuredItems(MinecraftServer server) throws Exception {
        var source = new YamlStorage(Path.of(System.getProperty("citizens.audit.legacyMetaFixtures"), "structured.yml").toFile());
        check(source.load(), "structured_fixture_loaded");
        for (String name : List.of("common", "enchanted", "stored", "leather", "writable", "written", "potion", "uncraftable")) {
            DataKey key = source.getKey(name).copy(); Object before = key.copy().getRaw("");
            ItemStack stack = ItemStorage.loadItemStack(key);
            check(stack != null && !stack.isEmpty(), "structured_" + name + "_available");
            check(before.equals(key.getRaw("")), "structured_" + name + "_source_unchanged");
            ItemStorage.saveItem(key, stack);
            check(!key.keyExists("meta") && !key.keyExists("enchantments") && ItemStack.matches(stack, ItemStorage.loadItemStack(key)),
                    "structured_" + name + "_native_resave");
        }
        var enchants = server.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        var sharpness = enchants.getHolderOrThrow(Enchantments.SHARPNESS);
        ItemStack blade = ItemStorage.loadItemStack(source.getKey("enchanted"));
        check(blade.get(DataComponents.ENCHANTMENTS).getLevel(sharpness) == 5
                && blade.get(DataComponents.ENCHANTMENTS).getLevel(enchants.getHolderOrThrow(Enchantments.MENDING)) == 1,
                "structured_enchantments_resolve_native_holders");
        check(blade.getHoverName().getString().equals("Structured blade"), "structured_name_coexists_with_enchantments");
        var rootOnly = source.getKey("enchanted").copy(); rootOnly.removeKey("meta");
        check(ItemStorage.loadItemStack(rootOnly).get(DataComponents.ENCHANTMENTS).getLevel(sharpness) == 5, "structured_root_enchantments_without_meta");
        check(ItemStorage.loadItemStack(source.getKey("stored")).get(DataComponents.STORED_ENCHANTMENTS).getLevel(sharpness) == 3,
                "structured_stored_enchantment_holders");
        var dye = ItemStorage.loadItemStack(source.getKey("leather")).get(DataComponents.DYED_COLOR);
        check(dye.rgb() == 0x123456 && !dye.showInTooltip(), "structured_dye_and_tooltip");
        ItemStack potion = ItemStorage.loadItemStack(source.getKey("potion"));
        var contents = potion.get(DataComponents.POTION_CONTENTS);
        check(contents.potion().orElseThrow().unwrapKey().orElseThrow().location().toString().equals("minecraft:long_swiftness")
                && contents.customEffects().size() == 2, "structured_extended_potion_and_replaced_effect");
        var cow = EntityType.COW.create(server.overworld());
        try {
            potion.getItem().finishUsingItem(potion.copy(), server.overworld(), cow);
            var speed = cow.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
            check(speed != null && speed.getDuration() == 123 && speed.getAmplifier() == 2 && speed.isVisible() && speed.showIcon(),
                    "structured_native_potion_effect_application");
        } finally { cow.discard(); }

        var registry = CitizensAPI.createNamedNPCRegistry("structured-items", new net.citizensnpcs.api.npc.MemoryNPCDataStore());
        NPC npc = registry.createNPC(EntityType.ZOMBIE, "StructuredItems");
        try {
            npc.getOrAddTrait(Inventory.class).setItem(0, blade);
            check(npc.spawn(new Location(server.overworld(), 2, -60, 1)), "structured_npc_spawn");
            npc.despawn(DespawnReason.RELOAD);
            NPC copy = npc.copy();
            try { check(ItemStack.matches(blade, copy.getOrAddTrait(Inventory.class).getContents()[0]), "structured_npc_copy_preserves_enchantments"); }
            finally { copy.destroy(); }
        } finally { npc.destroy(); }

        for (String invalid : List.of("unknown", "bad-index", "bad-boolean", "bad-integer", "bad-potion", "bad-effect",
                "bad-subtypes", "bad-nested", "latent-book", "material-data", "unsupported", "unknown-enchantment", "duplicate-enchantments")) {
            DataKey key = source.getKey(invalid).copy(); Object before = key.copy().getRaw("");
            StoredItems<Integer> stored = new StoredItems<>(); check(stored.load(0, key) == null && stored.contains(0), "structured_" + invalid + "_unavailable");
            stored.save(0, key, null); check(before.equals(key.getRaw("")), "structured_" + invalid + "_retained");
        }
        var potionMappings = new java.util.Properties();
        try (var input = BukkitMetaRuntimeAudit.class.getResourceAsStream("/citizens/legacy-potion-data.properties")) { potionMappings.load(input); }
        for (String combination : potionMappings.stringPropertyNames()) {
            String[] parts = combination.split("\\.");
            DataKey key = new MemoryDataKey(); key.setString("type", "potion"); key.setString("meta.potion.data.type", parts[0]);
            key.setBoolean("meta.potion.data.extended", Boolean.parseBoolean(parts[1]));
            key.setBoolean("meta.potion.data.upgraded", Boolean.parseBoolean(parts[2]));
            ItemStack stack = ItemStorage.loadItemStack(key); check(stack != null, "structured_original_potion_combination_" + combination);
        }
        var effectMappings = new java.util.Properties();
        try (var input = BukkitMetaRuntimeAudit.class.getResourceAsStream("/citizens/legacy-potion-effect-names.properties")) { effectMappings.load(input); }
        for (String name : effectMappings.stringPropertyNames()) {
            DataKey key = new MemoryDataKey(); key.setString("type", "potion"); key.setString("meta.potion.effects.0.type", name);
            key.setInt("meta.potion.effects.0.duration", 40); key.setInt("meta.potion.effects.0.amplifier", 0);
            check(ItemStorage.loadItemStack(key) != null, "structured_original_effect_name_" + name);
        }
    }

    private static void specialTypes(MinecraftServer server) throws Exception {
        for (String[] sample : List.of(new String[]{"leather", "leather_chestplate"}, new String[]{"leather", "leather_horse_armor"},
                new String[]{"trimmed", "iron_chestplate"}, new String[]{"colored-trimmed", "leather_chestplate"},
                new String[]{"writable", "writable_book"}, new String[]{"written", "written_book"}, new String[]{"recipes", "knowledge_book"},
                new String[]{"potion", "potion"}, new String[]{"potion", "splash_potion"}, new String[]{"potion", "lingering_potion"}, new String[]{"potion", "tipped_arrow"})) {
            DataKey key = item(sample[0], sample[1]); Object before = key.copy().getRaw("");
            ItemStack stack = ItemStorage.loadItemStack(key); String label = sample[0] + "_" + sample[1];
            check(stack != null && !stack.isEmpty(), label + "_available");
            check(before.equals(key.getRaw("")), label + "_source_preserved");
            ItemStorage.saveItem(key, stack); check(ItemStack.matches(stack, ItemStorage.loadItemStack(key)), label + "_native_round_trip");
        }
        ItemStack leather = ItemStorage.loadItemStack(item("leather", "leather_chestplate"));
        check(leather.get(DataComponents.DYED_COLOR).rgb() == 0x123456 && !leather.get(DataComponents.DYED_COLOR).showInTooltip(), "actual_rgb_and_dye_tooltip");
        ItemStack trimmed = ItemStorage.loadItemStack(item("trimmed", "iron_chestplate"));
        var trim = trimmed.get(DataComponents.TRIM);
        check(trim.material().unwrapKey().orElseThrow().location().toString().equals("minecraft:gold")
                && trim.pattern().unwrapKey().orElseThrow().location().toString().equals("minecraft:sentry"), "actual_trim_registry_holders");
        var trimData = DataComponents.TRIM.codecOrThrow().encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), trim).getOrThrow();
        check(!((net.minecraft.nbt.CompoundTag) trimData).getBoolean("show_in_tooltip"), "trim_tooltip_hidden");
        ItemStack colored = ItemStorage.loadItemStack(item("colored-trimmed", "leather_chestplate"));
        check(colored.get(DataComponents.DYED_COLOR).rgb() == 0x654321 && colored.has(DataComponents.TRIM), "color_and_trim_coexist");

        var registry = CitizensAPI.createNamedNPCRegistry("bukkit-meta-types", new net.citizensnpcs.api.npc.MemoryNPCDataStore());
        NPC npc = registry.createNPC(EntityType.ZOMBIE, "TypedMetadata");
        try {
            npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.Equipment.class).set(
                    net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.CHESTPLATE, colored);
            check(npc.spawn(new Location(server.overworld(), 2, -60, 1)), "colored_trim_npc_spawn");
            check(ItemStack.matches(colored, ((net.minecraft.world.entity.LivingEntity) npc.getEntity()).getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)),
                    "native_entity_wears_exact_colored_trim");
            NPC copy = npc.copy();
            try { check(ItemStack.matches(colored, copy.getOrAddTrait(net.citizensnpcs.api.trait.trait.Equipment.class).get(
                    net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.CHESTPLATE)), "copied_equipment_keeps_typed_components"); }
            finally { copy.destroy(); }
        } finally { npc.destroy(); }

        ItemStack potion = ItemStorage.loadItemStack(item("potion", "potion"));
        var contents = potion.get(DataComponents.POTION_CONTENTS);
        check(contents.potion().orElseThrow().unwrapKey().orElseThrow().location().toString().equals("minecraft:long_swiftness")
                && contents.customColor().orElseThrow() == 0x336699 && contents.customEffects().size() == 2, "potion_base_color_and_effects");
        var speed = contents.customEffects().getFirst();
        check(speed.getEffect().equals(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED) && speed.getDuration() == 123
                && speed.getAmplifier() == 2 && speed.isAmbient() && !speed.isVisible() && speed.showIcon(), "potion_custom_effect_flags");
        var cow = EntityType.COW.create(server.overworld()); cow.setPos(3, -60, 1); server.overworld().addFreshEntity(cow);
        try {
            ItemStack remainder = potion.getItem().finishUsingItem(potion.copy(), server.overworld(), cow);
            check(ItemStack.matches(potion, remainder), "native_nonplayer_potion_use_keeps_stack");
            var actual = cow.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
            check(actual != null && actual.getDuration() == 123 && actual.getAmplifier() == 2
                    && !actual.isVisible() && actual.showIcon(), "native_consumption_applies_custom_effect");
            var night = cow.getEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION);
            check(night != null && night.isInfiniteDuration() && !night.showIcon(), "native_consumption_preserves_infinite_effect");
            ItemStack tipped = ItemStorage.loadItemStack(item("potion", "tipped_arrow"));
            var arrow = new net.minecraft.world.entity.projectile.Arrow(server.overworld(), cow, tipped, new ItemStack(Items.BOW));
            check(arrow.getColor() == 0x336699, "native_arrow_reads_custom_potion_color"); arrow.discard();
        } finally { cow.discard(); }
        var player = new net.neoforged.neoforge.common.util.FakePlayer(server.overworld(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "MetaAudit"));
        player.setPos(3, -60, 1);
        try {
            ItemStack consumed = potion.copy();
            ItemStack bottle = potion.getItem().finishUsingItem(consumed, server.overworld(), player);
            check(consumed.isEmpty() && bottle.is(Items.GLASS_BOTTLE) && bottle.getCount() == 1,
                    "native_player_potion_consumption_returns_bottle");
            var actual = player.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
            check(actual != null && actual.getDuration() == 123 && actual.getAmplifier() == 2
                    && !actual.isVisible() && actual.showIcon(), "native_player_consumption_applies_custom_effect");
        } finally { player.removeAllEffects(); player.discard(); }
        ItemStack signed = ItemStorage.loadItemStack(item("written", "written_book"));
        var book = signed.get(DataComponents.WRITTEN_BOOK_CONTENT);
        var resolved = book.resolve(server.createCommandSourceStack(), null);
        check(resolved != null && resolved.resolved() && resolved.getPages(false).getFirst().getStyle().getClickEvent().getValue().equals("2")
                && resolved.getPages(false).get(1).getString().equals("Second\nline"), "native_signed_book_resolution_keeps_pages");

        for (var invalid : java.util.Map.of("unknown-effect", "potion", "bad-effect-level", "potion", "missing-trim", "iron_chestplate",
                "oversize-book", "writable_book", "latent-book-fields", "writable_book", "written", "stone").entrySet()) {
            DataKey key = item(invalid.getKey(), invalid.getValue()); Object before = key.copy().getRaw("");
            StoredItems<Integer> stored = new StoredItems<>(); check(stored.load(0, key) == null && stored.contains(0), invalid.getKey() + "_typed_unavailable");
            stored.save(0, key, null); check(before.equals(key.getRaw("")), invalid.getKey() + "_typed_retained");
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
