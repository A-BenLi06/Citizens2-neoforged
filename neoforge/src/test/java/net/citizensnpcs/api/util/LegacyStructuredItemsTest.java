package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyStructuredItemsTest {
    @BeforeAll static void bootstrap() { Bootstrap.bootStrap(); }

    @Test void commonFieldsKeepLiteralTextAndNumericOrderThroughYamlAndNativeSave(@TempDir Path directory) throws Exception {
        DataKey key = fixture(directory, "common"); Object before = key.copy().getRaw("");
        ItemStack stack = ItemStorage.loadItemStack(key); assertNotNull(stack);
        assertEquals(2, stack.getCount()); assertEquals(11, stack.getDamageValue());
        assertEquals("{\"text\":\"literal name\"}", stack.getHoverName().getString());
        assertEquals(List.of("First\nline", "Second", "tenth &a<red>"), stack.get(DataComponents.LORE).lines().stream().map(c -> c.getString()).toList());
        assertEquals(217, stack.get(DataComponents.CUSTOM_MODEL_DATA).value());
        assertEquals(4, stack.get(DataComponents.REPAIR_COST)); assertFalse(stack.get(DataComponents.UNBREAKABLE).showInTooltip());
        assertEquals(before, key.getRaw("")); ItemStorage.saveItem(key, stack);
        assertFalse(key.keyExists("meta")); assertTrue(ItemStack.matches(stack, ItemStorage.loadItemStack(key)));
    }

    @Test void signedStructuredPagesUsePlainTextSettersRatherThanJsonParsing(@TempDir Path directory) throws Exception {
        var stack = ItemStorage.loadItemStack(fixture(directory, "written")); assertNotNull(stack);
        var content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        assertEquals("Legacy notes", content.title().raw()); assertEquals("Narrator", content.author());
        assertEquals(0, content.generation()); assertFalse(content.resolved());
        assertEquals(List.of("First", "{\"text\":\"literal page\"}", "Last\nline"), content.getPages(false).stream().map(c -> c.getString()).toList());
        assertEquals(0xffaa00, content.getPages(false).getFirst().getSiblings().getFirst().getStyle().getColor().getValue());
    }

    @Test void writablePagesKeepTheirCharactersAndEmptyOptionalFields(@TempDir Path directory) throws Exception {
        var stack = ItemStorage.loadItemStack(fixture(directory, "writable")); assertNotNull(stack);
        assertEquals(List.of("{\"text\":\"literal page\"}", "second"), stack.get(DataComponents.WRITABLE_BOOK_CONTENT).getPages(false).toList());
    }

    @Test void repeatedEffectsReplaceTheirValueInTheOriginalPosition(@TempDir Path directory) throws Exception {
        var key = fixture(directory, "potion");
        // Base potions need live registries; custom effects use the builtin registry in these unit tests.
        key.removeKey("meta.potion.data");
        var stack = ItemStorage.loadItemStack(key); assertNotNull(stack);
        var contents = stack.get(DataComponents.POTION_CONTENTS); assertTrue(contents.potion().isEmpty());
        assertEquals(2, contents.customEffects().size());
        var effect = contents.customEffects().getFirst();
        assertEquals(MobEffects.MOVEMENT_SPEED, effect.getEffect()); assertEquals(123, effect.getDuration());
        assertEquals(2, effect.getAmplifier()); assertTrue(effect.isAmbient()); assertTrue(effect.isVisible()); assertTrue(effect.showIcon());
        assertEquals(MobEffects.NIGHT_VISION, contents.customEffects().get(1).getEffect()); assertTrue(contents.customEffects().get(1).isInfiniteDuration());
    }

    @Test void explicitUncraftablePotionHasNoInventedBase(@TempDir Path directory) throws Exception {
        var stack = ItemStorage.loadItemStack(fixture(directory, "uncraftable")); assertNotNull(stack);
        assertTrue(stack.get(DataComponents.POTION_CONTENTS).potion().isEmpty());
    }

    @Test void invalidAndUnsupportedRecordsSurviveWithPendingEdits(@TempDir Path directory) throws Exception {
        for (String sample : List.of("unknown", "bad-index", "bad-boolean", "bad-integer", "bad-potion", "bad-effect",
                "bad-subtypes", "bad-nested", "latent-book", "material-data", "unsupported", "unknown-enchantment", "duplicate-enchantments")) {
            DataKey key = fixture(directory, sample); key.setBoolean("editable_components.edited", true);
            key.setString("editable_components.display_name", "pending"); Object before = key.copy().getRaw("");
            StoredItems<Integer> stored = new StoredItems<>(); assertNull(stored.load(0, key), sample);
            assertTrue(stored.contains(0)); stored.save(0, key, null); assertEquals(before, key.getRaw(""), sample);
        }
    }

    @Test void encodedMetaKeepsOriginalPrecedenceWithoutFallingBackOnFailure(@TempDir Path directory) throws Exception {
        DataKey key = fixture(directory, "common");
        key.setRaw("enchantments", Map.of("DAMAGE_ALL", 3));
        key.setString("meta.encoded-meta", LegacyBukkitMetaTest.fixture("empty"));
        var stack = ItemStorage.loadItemStack(key); assertNotNull(stack);
        assertNull(stack.get(DataComponents.CUSTOM_NAME)); assertTrue(stack.get(DataComponents.ENCHANTMENTS).isEmpty());
        key.setString("meta.encoded-meta", "corrupt"); assertNull(ItemStorage.loadItemStack(key));
    }

    @Test void explicitEditsAndDeserializationHooksApplyAfterCompleteConversion(@TempDir Path directory) throws Exception {
        DataKey key = fixture(directory, "common"); key.setString("editable_components.display_name", "edited");
        assertEquals("{\"text\":\"literal name\"}", ItemStorage.loadItemStack(key).getHoverName().getString());
        key.setBoolean("editable_components.edited", true);
        AtomicInteger calls = new AtomicInteger();
        try {
            ItemStorage.setHooks(null, (root, stack) -> { assertEquals("edited", stack.getHoverName().getString()); calls.incrementAndGet(); });
            assertNotNull(ItemStorage.loadItemStack(key)); assertEquals(1, calls.get()); assertFalse(key.getBoolean("editable_components.edited"));
            key.setRaw("meta", Map.of("unknown", "keep")); assertNull(ItemStorage.loadItemStack(key)); assertEquals(1, calls.get());
        } finally { ItemStorage.setHooks(null, null); }
    }

    private static DataKey fixture(Path directory, String name) throws Exception {
        Path file = directory.resolve(name + ".yml");
        try (var input = LegacyStructuredItemsTest.class.getResourceAsStream("/legacy-meta/structured.yml")) {
            assertNotNull(input); Files.copy(input, file);
        }
        YamlStorage storage = new YamlStorage(file.toFile()); assertTrue(storage.load()); return storage.getKey(name);
    }
}
