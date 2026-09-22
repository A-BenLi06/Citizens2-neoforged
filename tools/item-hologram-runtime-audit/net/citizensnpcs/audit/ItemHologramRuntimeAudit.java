package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.trait.ScoreboardTrait;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Opt-in real-tick lifecycle checks against a dedicated empty world. */
@EventBusSubscriber(modid = "citizens")
public final class ItemHologramRuntimeAudit {
    private static boolean forced, done;
    private static int phase, passed, nextTick, baseline;
    private static NPCRegistry registry;
    private static NPC parent, copy;
    private static HologramTrait trait;
    private static List<Entity> previous = List.of();

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        MinecraftServer server = event.getServer(); var level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; }
        if (!level.areEntitiesLoaded(ChunkPos.asLong(0, 0)) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
        if (server.getTickCount() < nextTick) return;
        nextTick = server.getTickCount() + 8;
        try {
            switch (phase++) {
                case 0 -> {
                    check(Files.isRegularFile(Path.of("item-hologram-audit-fixture.txt"))
                            && !CitizensAPI.getNPCRegistry().iterator().hasNext(), "isolated_empty_fixture");
                    baseline = temporaryCount();
                    registry = CitizensAPI.createNamedNPCRegistry("item-hologram-audit", new MemoryNPCDataStore());
                    parent = registry.createNPC(EntityType.COW, "Item hologram audit");
                    parent.getOrAddTrait(PlayerFilter.class);
                    parent.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
                    parent.data().set(NPC.Metadata.TRACKING_RANGE, 40);
                    trait = parent.getOrAddTrait(HologramTrait.class);
                    trait.addLine("<item:DIAMOND SWORD:dark_red>");
                    check(parent.spawn(new Location(level, 4, -60, 4)), "parent_spawn");
                }
                case 1 -> {
                    pair("initial");
                    check(item().getItem().is(Items.DIAMOND_SWORD), "legacy_material_identity");
                    check(NPCRegistries.lookup(item()).getTrait(ScoreboardTrait.class).getColor() == ChatFormatting.DARK_RED, "legacy_team_color");
                    check(!item().isCurrentlyGlowing(), "color_does_not_invent_glowing_flag");
                    CompoundTag data = new CompoundTag(); item().addAdditionalSaveData(data);
                    check(data.getShort("PickupDelay") == 32767 && data.getShort("Age") == -32768, "never_pickup_or_expire");
                    check(Math.abs(anchor().getY() - parent.getEntity().getY() - parent.getEntity().getBbHeight() - 0.21) < 0.001, "default_item_bottom_margin");
                    parent.getEntity().teleportTo(9, -60, 9);
                }
                case 2 -> {
                    check(anchor().getX() == 9 && anchor().getZ() == 9 && item().getX() == 9 && item().getZ() == 9, "both_entities_follow_parent");
                    previous = entities();
                    trait.setLine(0, "<item:minecraft:stone[minecraft:custom_name='\"A > B\"']>");
                }
                case 3 -> {
                    removed("edited_item"); pair("edited");
                    check(item().getItem().is(Items.STONE) && item().getItem().get(DataComponents.CUSTOM_NAME).getString().equals("A > B"), "exact_native_components");
                    previous = entities(); trait.setLine(0, "Ordinary text");
                }
                case 4 -> {
                    removed("item_to_text");
                    check(entities().size() == 1 && entities().getFirst() instanceof Display.TextDisplay text
                            && net.minecraft.network.chat.Component.Serializer.fromJson(text.saveWithoutId(new CompoundTag()).getString("text"),
                                    server.registryAccess()).getString().equals("Ordinary text"), "item_reverts_to_text_renderer");
                    previous = entities(); trait.setLine(0, "<item:missing:unavailable>");
                }
                case 5 -> {
                    removed("text_to_unavailable"); check(entities().isEmpty(), "unknown_item_has_no_substitute");
                    var yaml = new YamlStorage(Path.of("unavailable-line.yml").toAbsolutePath().toFile());
                    trait.save(yaml.getKey("hologram")); yaml.save();
                    var reloaded = new YamlStorage(Path.of("unavailable-line.yml").toAbsolutePath().toFile());
                    check(reloaded.load() && reloaded.getKey("hologram").getString("lines.0.text").equals("<item:missing:unavailable>"), "unavailable_markup_survives_file_save");
                    trait.load(reloaded.getKey("hologram")); trait.setLine(0, "<item:stone:custom_model_data=17>");
                }
                case 6 -> {
                    pair("recovered");
                    check(item().getItem().get(DataComponents.CUSTOM_MODEL_DATA).value() == 17, "legacy_component_tail");
                    previous = entities(); trait.setViewRange(24);
                }
                case 7 -> {
                    removed("view_range_rebuild"); pair("view_range");
                    for (Entity entity : entities()) check(NPCRegistries.lookup(entity).data().get(NPC.Metadata.TRACKING_RANGE, -1) == 24, "range_applies_to_each_helper");
                    previous = entities(); check(parent.despawn(DespawnReason.PENDING_RESPAWN), "parent_despawn");
                    removed("parent_despawn"); check(temporaryCount() == baseline, "despawn_deregisters_helpers");
                    check(filterChildren() == 0, "despawn_unregisters_filter_children");
                    check(parent.spawn(new Location(level, 5, -60, 5)), "parent_respawn");
                }
                case 8 -> {
                    pair("respawned"); copy = parent.copy();
                    check(copy != null, "copy_created");
                    if (!copy.isSpawned()) check(copy.spawn(new Location(level, 12, -60, 12)), "copy_spawn");
                    trait.addTemporaryLine("<item:diamond>", 3);
                }
                case 9 -> {
                    check(trait.getLines().size() == 1, "temporary_item_expires");
                    var other = copy.getTrait(HologramTrait.class);
                    check(other.getLines().equals(trait.getLines()) && other.getHologramEntities().size() == 2, "copy_has_independent_item_helpers");
                    check(other.getHologramEntities().stream().noneMatch(entities()::contains), "copy_entities_are_distinct");
                    copy.destroy(); copy = null;
                    check(temporaryCount() == baseline + 2, "copy_and_temporary_helpers_cleaned");
                    previous = entities(); item().discard();
                }
                case 10 -> {
                    removed("externally_removed_item"); pair("recreated_after_removal");
                    previous = entities(); trait.clear();
                    trait.addLine("<item:diamond>", new HologramTrait.ItemDisplayRenderer());
                }
                case 11 -> {
                    removed("display_transition");
                    check(entities().size() == 1 && entities().getFirst() instanceof Display.ItemDisplay display
                            && net.minecraft.world.item.ItemStack.parseOptional(server.registryAccess(), display.saveWithoutId(new CompoundTag()).getCompound("item"))
                                    .is(Items.DIAMOND) && display.getVehicle() == parent.getEntity(), "native_item_display_mount");
                    check(NPCRegistries.lookup(entities().getFirst()).getTrait(ClickRedirectTrait.class).getRedirectToNPC() == parent, "display_click_redirect");
                    var key = new MemoryDataKey(); trait.save(key);
                    check(key.getString("lines.0.renderer.type").equals("item_display"), "display_renderer_saved");
                    previous = entities(); trait.load(key);
                }
                case 12 -> {
                    removed("display_reload");
                    check(entities().size() == 1 && entities().getFirst() instanceof Display.ItemDisplay, "display_renderer_restored");
                    previous = entities(); parent.removeTrait(HologramTrait.class);
                    removed("trait_removal"); check(temporaryCount() == baseline, "trait_removal_deregisters_helpers");
                    check(filterChildren() == 0, "trait_removal_unregisters_filter_children");
                    parent.destroy(); parent = null;
                    check(temporaryCount() == baseline, "parent_destroy_leaves_no_helpers");
                    LoggerFactory.getLogger("citizens").info("[ITEMHOLOGRAMAUDIT] COMPLETE {} checks", passed);
                    done = true; server.halt(false);
                }
            }
        } catch (Throwable failure) {
            done = true;
            LoggerFactory.getLogger("citizens").error("[ITEMHOLOGRAMAUDIT] FAILED phase " + phase, failure);
            if (copy != null) copy.destroy();
            if (parent != null) parent.destroy();
            server.halt(false);
        }
    }

    private static void pair(String label) {
        check(entities().size() == 2, label + "_two_helpers");
        check(filterChildren() == 2, label + "_filter_contains_only_current_helpers");
        check(anchor().isMarker() && anchor().isInvisible() && anchor().isNoGravity(), label + "_invisible_point_anchor");
        check(item().getVehicle() == anchor(), label + "_item_rides_anchor");
        for (Entity entity : entities()) {
            NPC npc = NPCRegistries.lookup(entity);
            check(npc != null && npc.getOwningRegistry() == CitizensAPI.getTemporaryNPCRegistry(), label + "_temporary_registry");
            check(npc.getTrait(ClickRedirectTrait.class).getRedirectToNPC() == parent, label + "_click_redirect");
            check(!npc.data().get(NPC.Metadata.NAMEPLATE_VISIBLE, true) && npc.data().has(NPC.Metadata.HOLOGRAM_RENDERER), label + "_hidden_helper_name");
        }
    }
    private static List<Entity> entities() { return new ArrayList<>(trait.getHologramEntities()); }
    private static ItemEntity item() { return (ItemEntity) entities().stream().filter(e -> e instanceof ItemEntity).findFirst().orElseThrow(); }
    private static ArmorStand anchor() { return (ArmorStand) entities().stream().filter(e -> e instanceof ArmorStand).findFirst().orElseThrow(); }
    private static void removed(String label) { check(previous.stream().allMatch(Entity::isRemoved), label + "_old_entities_removed"); }
    private static int temporaryCount() { int count = 0; for (NPC ignored : CitizensAPI.getTemporaryNPCRegistry()) count++; return count; }
    private static int filterChildren() {
        try {
            var field = PlayerFilter.class.getDeclaredField("children"); field.setAccessible(true);
            return ((java.util.Set<?>) field.get(parent.getTrait(PlayerFilter.class))).size();
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[ITEMHOLOGRAMAUDIT] PASS {}", label);
    }
}
