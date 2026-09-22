package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.FireworkExplosion.Shape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyFireworksTest {
    @BeforeAll static void bootstrap() { Bootstrap.bootStrap(); }

    @Test void actualBukkitFireworkObjectsUseTheFireworkAliasAndNestedColors() throws Exception {
        var raw = LegacyBukkitData.read(LegacyBukkitMetaTest.fixture("fireworks"));
        var effects = (List<?>) raw.get("firework-effects"); assertEquals(5, effects.size());
        var first = (Map<?, ?>) effects.getFirst(); assertEquals("Firework", first.get("=="));
        assertEquals("BALL", first.get("type")); assertEquals(true, first.get("trail")); assertEquals(false, first.get("flicker"));
        assertEquals(Map.of("==", "Color", "ALPHA", 255, "RED", 0x12, "GREEN", 0x34, "BLUE", 0x56), ((List<?>) first.get("colors")).getFirst());
    }

    @Test void allFiveShapesUseTheirNamesRatherThanDifferentEnumOrdinals() throws Exception {
        var key = encoded("fireworks", "firework_rocket"); Object before = key.copy().getRaw("");
        var stack = ItemStorage.loadItemStack(key); assertNotNull(stack);
        var fireworks = stack.get(DataComponents.FIREWORKS); assertEquals(2, fireworks.flightDuration());
        assertEquals(List.of(Shape.SMALL_BALL, Shape.LARGE_BALL, Shape.STAR, Shape.BURST, Shape.CREEPER), fireworks.explosions().stream().map(e -> e.shape()).toList());
        for (int i = 0; i < 5; i++) {
            var effect = fireworks.explosions().get(i);
            assertArrayEquals(new int[]{0x123456, 0xfedcba}, effect.colors().toIntArray());
            assertArrayEquals(new int[]{0x010203}, effect.fadeColors().toIntArray());
            assertEquals(i % 2 == 0, effect.hasTrail()); assertEquals(i % 2 != 0, effect.hasTwinkle());
        }
        assertTrue(stack.has(DataComponents.HIDE_ADDITIONAL_TOOLTIP)); assertEquals(before, key.getRaw(""));
        roundTrip(key, stack);
    }

    @Test void starKeepsItsExactExplosionThroughNativePersistence() throws Exception {
        var key = encoded("firework-star", "firework_star"); var stack = ItemStorage.loadItemStack(key); assertNotNull(stack);
        var effect = stack.get(DataComponents.FIREWORK_EXPLOSION); assertEquals(Shape.STAR, effect.shape());
        assertArrayEquals(new int[]{0xabcdef}, effect.colors().toIntArray()); assertArrayEquals(new int[]{0x010203}, effect.fadeColors().toIntArray());
        assertTrue(effect.hasTrail()); assertTrue(effect.hasTwinkle()); roundTrip(key, stack);
    }

    @Test void absentFireworkDataDoesNotInventFlightOrExplosion() throws Exception {
        var rocket = ItemStorage.loadItemStack(encoded("empty-firework", "firework_rocket")); assertNotNull(rocket);
        assertEquals(0, rocket.get(DataComponents.FIREWORKS).flightDuration()); assertTrue(rocket.get(DataComponents.FIREWORKS).explosions().isEmpty());
        var star = ItemStorage.loadItemStack(encoded("empty-firework-star", "firework_star")); assertNotNull(star);
        assertFalse(star.has(DataComponents.FIREWORK_EXPLOSION));
    }

    @Test void structuredRocketSortsEffectsAndColorsAndUsesOldFlagDefaults(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("fireworks.yml");
        try (var input = LegacyFireworksTest.class.getResourceAsStream("/legacy-meta/structured-fireworks.yml")) { Files.copy(input, file); }
        var yaml = new YamlStorage(file.toFile()); assertTrue(yaml.load());
        var key = yaml.getKey("rocket"); Object before = key.copy().getRaw("");
        var stack = ItemStorage.loadItemStack(key); assertNotNull(stack); var fireworks = stack.get(DataComponents.FIREWORKS);
        assertEquals(3, fireworks.flightDuration()); assertEquals(List.of(Shape.BURST, Shape.CREEPER), fireworks.explosions().stream().map(e -> e.shape()).toList());
        var first = fireworks.explosions().getFirst(); assertArrayEquals(new int[]{0x123456, 0xfedcba}, first.colors().toIntArray());
        assertTrue(first.hasTrail()); assertFalse(first.hasTwinkle()); assertTrue(first.fadeColors().isEmpty());
        var last = fireworks.explosions().getLast(); assertFalse(last.hasTrail()); assertTrue(last.hasTwinkle());
        assertEquals(before, key.getRaw("")); roundTrip(key, stack);
    }

    @Test void invalidStreamsAndWrongItemsRetainTheOriginalDefinition() throws Exception {
        for (String[] sample : List.of(new String[]{"firework-bad-power", "firework_rocket"}, new String[]{"firework-too-many", "firework_rocket"},
                new String[]{"firework-empty-colors", "firework_star"}, new String[]{"firework-unknown-shape", "firework_star"},
                new String[]{"firework-bad-field", "firework_star"}, new String[]{"fireworks", "stone"}, new String[]{"firework-star", "firework_rocket"}))
            retained(encoded(sample[0], sample[1]));
    }

    @Test void invalidStructuredFieldsAndValuesCannotDegradeTheItem() {
        for (Map<String, Object> rocket : List.of(Map.<String, Object>of("power", -1), Map.<String, Object>of("power", 128),
                Map.<String, Object>of("effects", List.of(Map.of("type", "BALL", "colors", List.of(Map.of("rgb", -1))))),
                Map.<String, Object>of("effects", List.of(Map.of("type", "STAR", "colors", List.of()))),
                Map.<String, Object>of("effects", List.of(Map.of("type", "STAR", "colors", List.of(Map.of("rgb", 1, "extra", true)))))))
            retained(structured(rocket));
    }

    @Test void sourcePowerAndNativeExplosionLimitsAreAcceptedExactly() {
        var effect = Map.of("type", "STAR", "colors", List.of(Map.of("rgb", 0x123456)));
        var key = structured(Map.of("power", 127, "effects", Collections.nCopies(256, effect)));
        var stack = ItemStorage.loadItemStack(key); assertNotNull(stack);
        assertEquals(127, stack.get(DataComponents.FIREWORKS).flightDuration()); assertEquals(256, stack.get(DataComponents.FIREWORKS).explosions().size());
        roundTrip(key, stack); retained(structured(Map.of("effects", Collections.nCopies(257, effect))));
    }

    private static DataKey encoded(String sample, String type) throws Exception {
        var key = new MemoryDataKey(); key.setString("type", type); key.setString("meta", LegacyBukkitMetaTest.fixture(sample)); return key;
    }
    private static DataKey structured(Map<String, Object> firework) {
        var key = new MemoryDataKey(); key.setString("type", "firework_rocket"); key.setRaw("meta.firework", firework); return key;
    }
    private static void roundTrip(DataKey key, ItemStack stack) {
        ItemStorage.saveItem(key, stack); assertTrue(ItemStack.matches(stack, ItemStorage.loadItemStack(key)));
    }
    private static void retained(DataKey key) {
        key.setBoolean("editable_components.edited", true); key.setString("editable_components.display_name", "pending");
        Object before = key.copy().getRaw(""); var stored = new StoredItems<Integer>();
        assertNull(stored.load(0, key)); assertTrue(stored.contains(0)); stored.save(0, key, null); assertEquals(before, key.getRaw(""));
    }
}
