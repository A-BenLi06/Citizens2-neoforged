package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LegacyBukkitMetaTypesTest {
    @BeforeAll static void bootstrap() { Bootstrap.bootStrap(); }

    @Test void actualBukkitColorAndPotionObjectsBecomeTheirOriginalNestedMaps() throws Exception {
        var leather = LegacyBukkitData.read(LegacyBukkitMetaTest.fixture("leather"));
        assertEquals(Map.of("==", "Color", "ALPHA", 255, "RED", 0x12, "GREEN", 0x34, "BLUE", 0x56), leather.get("color"));
        var potion = LegacyBukkitData.read(LegacyBukkitMetaTest.fixture("potion"));
        var effects = (List<?>) potion.get("custom-effects"); var speed = (Map<?, ?>) effects.getFirst();
        assertEquals("PotionEffect", speed.get("==")); assertEquals(1, speed.get("effect"));
        assertEquals(123, speed.get("duration")); assertEquals(2, speed.get("amplifier"));
        assertEquals(true, speed.get("ambient")); assertEquals(false, speed.get("has-particles")); assertEquals(true, speed.get("has-icon"));
    }

    @Test void writablePagesRemainLiteralWithLineBreaksAndNativePersistence() throws Exception {
        DataKey key = key("writable", "writable_book"); ItemStack stack = ItemStorage.loadItemStack(key);
        assertNotNull(stack); var pages = stack.get(DataComponents.WRITABLE_BOOK_CONTENT).getPages(false).toList();
        assertEquals(List.of("{\"text\":\"literal JSON\"}", "Line one\nLine two &a<red>"), pages);
        ItemStorage.saveItem(key, stack); assertTrue(ItemStack.matches(stack, ItemStorage.loadItemStack(key)));
    }

    @Test void signedBooksKeepTitleAuthorGenerationAndPageComponents() throws Exception {
        DataKey key = key("written", "written_book"); ItemStack stack = ItemStorage.loadItemStack(key);
        assertNotNull(stack); var content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        assertEquals("Migration notes", content.title().raw()); assertEquals("Narrator", content.author());
        assertEquals(2, content.generation()); assertFalse(content.resolved());
        assertEquals("2", content.getPages(false).getFirst().getStyle().getClickEvent().getValue());
        assertEquals("Second\nline", content.getPages(false).get(1).getString());
        ItemStorage.saveItem(key, stack); assertTrue(ItemStack.matches(stack, ItemStorage.loadItemStack(key)));
    }

    @Test void recipeBookKeepsLiteralNamesEvenForUnavailableDatapackRecipes() throws Exception {
        DataKey key = key("recipes", "knowledge_book"); ItemStack stack = ItemStorage.loadItemStack(key);
        assertNotNull(stack); assertEquals(List.of(ResourceLocation.parse("minecraft:crafting_table"),
                ResourceLocation.parse("audit:unavailable_recipe")), stack.get(DataComponents.RECIPES));
        ItemStorage.saveItem(key, stack); assertTrue(ItemStack.matches(stack, ItemStorage.loadItemStack(key)));
    }

    @Test void potionDefaultsDoNotInventABasePotionOrChangeOldEffectFlags() throws Exception {
        DataKey key = key("potion-defaults", "potion"); ItemStack stack = ItemStorage.loadItemStack(key);
        assertNotNull(stack); var contents = stack.get(DataComponents.POTION_CONTENTS);
        assertTrue(contents.potion().isEmpty()); assertTrue(contents.customColor().isEmpty());
        var effect = contents.customEffects().getFirst(); assertEquals(MobEffects.MOVEMENT_SPEED, effect.getEffect());
        assertEquals(40, effect.getDuration()); assertEquals(0, effect.getAmplifier());
        assertFalse(effect.isAmbient()); assertTrue(effect.isVisible()); assertTrue(effect.showIcon());
    }

    @Test void unsupportedOrOversizedSubtypeDataIsRetainedInsteadOfTruncated() throws Exception {
        for (var entry : Map.of("unknown-effect", "potion", "bad-effect-level", "potion", "oversize-book", "writable_book",
                "latent-book-fields", "writable_book", "missing-trim", "iron_chestplate", "written", "stone").entrySet()) {
            DataKey key = key(entry.getKey(), entry.getValue()); Object before = key.copy().getRaw("");
            StoredItems<Integer> stored = new StoredItems<>(); assertNull(stored.load(0, key), entry.getKey());
            assertTrue(stored.contains(0)); stored.save(0, key, null); assertEquals(before, key.getRaw(""));
        }
    }

    private static DataKey key(String fixture, String item) throws Exception {
        DataKey key = new MemoryDataKey().getRelative("item"); key.setString("type", item);
        key.setString("meta.encoded-meta", LegacyBukkitMetaTest.fixture(fixture)); return key;
    }
}
