package net.yuuniverse.interactions;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyItemDataTest {
    @BeforeAll static void bootstrap() { Bootstrap.bootStrap(); }
    @TempDir Path directory;

    @Test void compressedNbtPreservesTypesAndRejectsMalformedInput() throws Exception {
        var original = new CompoundTag(); original.putString("literal.key", "伍德\u0000😀"); original.putInt("value", -17);
        assertEquals(original, LegacyItemData.decode(encode(original)));
        for (String invalid : List.of("", "not base64!", Base64.getEncoder().encodeToString(new byte[] {10, 0, 0, 0})))
            assertThrows(IllegalArgumentException.class, () -> LegacyItemData.decode(invalid));
        byte[] compressed = Base64.getDecoder().decode(encode(original));
        assertThrows(IllegalArgumentException.class, () -> LegacyItemData.decode(Base64.getEncoder().encodeToString(
                java.util.Arrays.copyOf(compressed, compressed.length - 4))));
    }

    @Test void nativeVersionFixesKeepCustomNbtAndUpgradeStandardItemData() {
        var tag = new CompoundTag(); tag.putInt("Damage", 17); tag.putString("provider.data", "opaque");
        var stack = LegacyItemData.convert(ResourceLocation.parse("minecraft:diamond_sword"), tag, 3465, RegistryAccess.EMPTY);
        assertEquals(Items.DIAMOND_SWORD, stack.getItem());
        assertEquals(17, stack.get(DataComponents.DAMAGE));
        assertEquals("opaque", stack.get(DataComponents.CUSTOM_DATA).copyTag().getString("provider.data"));
        assertTrue(tag.contains("Damage"), "source compound is not rewritten");
        assertFalse(stack.get(DataComponents.CUSTOM_DATA).copyTag().contains("Damage"));
    }

    @Test void missingItemsAndUnusableDataVersionsAreRejected() {
        for (int version : List.of(0, -1, Integer.MAX_VALUE))
            assertThrows(IllegalArgumentException.class, () -> LegacyItemData.convert(ResourceLocation.parse("minecraft:paper"),
                    new CompoundTag(), version, RegistryAccess.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> LegacyItemData.convert(ResourceLocation.parse("missing:reward"),
                new CompoundTag(), 3465, RegistryAccess.EMPTY));
    }

    @Test void legacyNamesPreserveNamespaceUnderscoresAndRemoveOriginalPunctuation() {
        var keys = List.of(ResourceLocation.parse("refurbished_furniture:package"), ResourceLocation.parse("mts:mts.jerrycan"),
                ResourceLocation.parse("example:some/path-with.punctuation"));
        assertEquals(keys.get(0), CheckItem.legacyMaterial("refurbished_furniture_package", keys));
        assertEquals(keys.get(1), CheckItem.legacyMaterial("mts_mtsjerrycan", keys));
        assertEquals(keys.get(2), CheckItem.legacyMaterial("example_somepathwithpunctuation", keys));
        assertNull(CheckItem.legacyMaterial("unregistered_item", keys));
        var collisions = List.of(ResourceLocation.parse("mod:some.item"), ResourceLocation.parse("mod:someitem"));
        assertThrows(IllegalArgumentException.class, () -> CheckItem.legacyMaterial("mod_someitem", collisions));
        assertThrows(IllegalArgumentException.class, () -> CheckItem.legacyMaterial("mod_someitem", collisions.reversed()));
    }

    @Test void libraryReturnsIndependentCopiesAndInvalidDefinitionsAreUnavailable() throws Exception {
        Path file = directory.resolve("items.yml");
        var tag = new CompoundTag(); tag.putString("provider", "retained");
        Files.writeString(file, "7:\n  item:\n    type: PAPER\n    v: 3465\n    meta:\n      internal: '" + encode(tag)
                + "'\n'bad':\n  item:\n    type: PAPER\n    meta:\n      internal: invalid\n");
        var library = new ItemLibrary(); library.load(file.toFile(), RegistryAccess.EMPTY);
        assertEquals(1, library.size()); assertNull(library.get("bad", 1));
        ItemStack first = library.get("7", 3); assertNotNull(first); assertEquals(3, first.getCount());
        first.setCount(1); first.remove(DataComponents.CUSTOM_DATA);
        assertEquals("retained", library.get("7", 2).get(DataComponents.CUSTOM_DATA).copyTag().getString("provider"));
        assertEquals(2, library.get("7", 2).getCount());
    }

    @Test void invalidWholeFileReloadRetainsSnapshotWithoutRewritingSource() throws Exception {
        Path file = directory.resolve("items.yml");
        Files.writeString(file, "'one':\n  item:\n    type: PAPER\n");
        var library = new ItemLibrary(); library.load(file.toFile(), RegistryAccess.EMPTY);
        for (String invalid : List.of("[invalid]", "one: [", "one: {}\none: {}\n")) {
            Files.writeString(file, invalid); library.load(file.toFile(), RegistryAccess.EMPTY);
            assertNotNull(library.get("one", 1)); assertEquals(invalid, Files.readString(file));
        }
        Files.writeString(file, "{}\n"); library.load(file.toFile(), RegistryAccess.EMPTY);
        assertEquals(0, library.size(), "explicit empty database replaces the old snapshot");
    }

    private static String encode(CompoundTag tag) throws Exception {
        var bytes = new ByteArrayOutputStream(); NbtIo.writeCompressed(tag, bytes);
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
