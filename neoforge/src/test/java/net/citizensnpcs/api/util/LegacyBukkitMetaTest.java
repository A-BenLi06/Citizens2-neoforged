package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LegacyBukkitMetaTest {
    @BeforeAll static void bootstrap() { Bootstrap.bootStrap(); }

    static String fixture(String name) throws IOException {
        try (var stream = LegacyBukkitMetaTest.class.getResourceAsStream("/legacy-meta/" + name + ".base64")) {
            assertNotNull(stream); return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void originalBukkitWriterAndBothGuavaSchemasDecodeToTheSameMap() throws Exception {
        Map<String, Object> rich = LegacyBukkitData.read(fixture("rich"));
        assertEquals(rich, LegacyBukkitData.read(fixture("rich-guava25")));
        assertEquals("ItemMeta", rich.get("==")); assertEquals(217, rich.get("custom-model-data"));
        assertEquals(Map.of("DAMAGE_ALL", 5, "MENDING", 1), rich.get("enchants"));
        assertEquals(List.of("HIDE_ENCHANTS", "HIDE_UNBREAKABLE", "HIDE_ATTRIBUTES"), rich.get("ItemFlags"));
        assertEquals(Map.of("a", 1, "b", List.of("same", "same")), LegacyBukkitData.read(fixture("collections")).get("payload"));
    }

    @Test void plainBukkitStringsKeepAmpersandsAndMarkupLiteralAndRoundTrip() throws Exception {
        DataKey key = key(fixture("plain")); Object before = key.copy().getRaw("");
        ItemStack stack = ItemStorage.loadItemStack(key);
        assertNotNull(stack); assertEquals("Legacy &b<red>", stack.getHoverName().getString());
        assertEquals(0x55ff55, stack.getHoverName().getSiblings().getFirst().getStyle().getColor().getValue());
        assertEquals("literal &a<red>", stack.get(DataComponents.LORE).lines().get(1).getString());
        assertEquals(before, key.getRaw(""));
        ItemStorage.saveItem(key, stack); assertFalse(key.keyExists("meta"));
        assertTrue(ItemStack.matches(stack, ItemStorage.loadItemStack(key)));
    }

    @Test void editingViewsDoNotOverrideMetadataUntilMarkedEdited() throws Exception {
        for (String path : List.of("meta", "meta.encoded-meta")) {
            DataKey key = new MemoryDataKey().getRelative("item"); key.setString("type", "stone"); key.setString(path, fixture("empty"));
            key.setString("editable_components.display_name", "stale view");
            assertNull(ItemStorage.loadItemStack(key).get(DataComponents.CUSTOM_NAME));
            key.setBoolean("editable_components.edited", true);
            assertEquals("stale view", ItemStorage.loadItemStack(key).getHoverName().getString());
            assertFalse(key.getBoolean("editable_components.edited"));
        }
    }

    @Test void legacyHexColorAndResetArePreserved() throws Exception {
        var name = ItemStorage.loadItemStack(key(fixture("hex"))).getHoverName();
        assertEquals("Hex Reset", name.getString());
        assertEquals(0x12ab0f, name.getSiblings().getFirst().getStyle().getColor().getValue());
        assertNull(name.getSiblings().get(1).getStyle().getColor());
        var reset = name.getSiblings().get(1).getStyle(); assertEquals(reset, reset.withItalic(false));
    }

    @Test void legacyUrlsKeepTheOriginalClickTargetAndPunctuation() throws Exception {
        var name = ItemStorage.loadItemStack(key(fixture("links"))).getHoverName();
        assertEquals("Visit example.com!", name.getString());
        assertEquals("http://example.com", name.getSiblings().get(1).getStyle().getClickEvent().getValue());
        assertNull(name.getSiblings().get(2).getStyle().getClickEvent());
    }

    @Test void unsupportedOrInvalidMetadataKeepsItsWholeDefinitionAndPendingEdit() throws Exception {
        for (String name : List.of("unknown", "invalid", "collections", "cycle", "rich", "book")) {
            DataKey key = key(fixture(name)); // rich needs live enchantment registries; book is intentionally on a sword.
            key.setBoolean("editable_components.edited", true); key.setString("editable_components.display_name", "pending");
            Object original = key.copy().getRaw("");
            StoredItems<Integer> stored = new StoredItems<>(); assertNull(stored.load(0, key));
            assertTrue(stored.contains(0)); stored.save(0, key, null); assertEquals(original, key.getRaw(""));
        }
    }

    @Test void truncationTrailingDataAndOversizedLengthsAreRejected() throws Exception {
        byte[] bytes = Base64.getDecoder().decode(fixture("plain"));
        for (int length : List.of(0, 3, 12, bytes.length - 1)) {
            assertThrows(IllegalArgumentException.class, () -> LegacyBukkitData.read(Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, length))));
        }
        assertThrows(IllegalArgumentException.class, () -> LegacyBukkitData.read(Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, bytes.length + 1))));
        assertThrows(IllegalArgumentException.class, () -> LegacyBukkitData.read("not base64"));
        byte[] longString = {(byte)0xac,(byte)0xed,0,5,0x7c,0x7f,-1,-1,-1,-1,-1,-1,-1};
        assertThrows(IllegalArgumentException.class, () -> LegacyBukkitData.read(Base64.getEncoder().encodeToString(longString)));
    }

    @Test void streamClassesAreNeverInstantiatedOrGivenReadCallbacks() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(new Callback()); }
        assertThrows(IllegalArgumentException.class, () -> LegacyBukkitData.read(Base64.getEncoder().encodeToString(bytes.toByteArray())));
        assertFalse(Callback.called);
    }

    @Test void registeredReadersRemainAuthoritativeAndNullRestoresBuiltin() throws Exception {
        DataKey key = key(fixture("plain"));
        try {
            ItemStorage.setLegacyItemMetaReader((raw, stack) -> { stack.set(DataComponents.DAMAGE, 9); return false; });
            assertNull(ItemStorage.loadItemStack(key));
            ItemStorage.setLegacyItemMetaReader((raw, stack) -> { stack.set(DataComponents.DAMAGE, 9); return true; });
            assertEquals(9, ItemStorage.loadItemStack(key).getDamageValue());
        } finally { ItemStorage.setLegacyItemMetaReader(null); }
        assertEquals("Legacy &b<red>", ItemStorage.loadItemStack(key).getHoverName().getString());
    }

    private static DataKey key(String encoded) {
        DataKey key = new MemoryDataKey().getRelative("item"); key.setString("type", "diamond_sword"); key.setString("meta", encoded); return key;
    }
    private static class Callback implements Serializable {
        private static final long serialVersionUID = 1L; static boolean called;
        private void readObject(ObjectInputStream in) { called = true; }
    }
}
