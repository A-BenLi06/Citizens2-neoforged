package net.citizensnpcs.trait;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HologramItemTest {
    private static HolderLookup.Provider registries;
    @BeforeAll static void bootstrap() {
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test void legacyMaterialsAndColorsUseRegistryIdentities() {
        var item = parse("before <item:DIAMOND SWORD:dark_red> after");
        assertTrue(item.stack().is(Items.DIAMOND_SWORD));
        assertEquals(ChatFormatting.DARK_RED, item.color());
        assertEquals(1, item.stack().getCount());
        assertTrue(parse("<item:minecraft:stone:BLUE>").stack().is(Items.STONE));
        assertNull(parse("<item:stone:reset>").color());
    }

    @Test void nativeComponentsKeepQuotedDelimitersAndCase() {
        var item = parse("<item:minecraft:stone[minecraft:custom_name='\"A > B\"']>");
        assertEquals("A > B", item.stack().get(DataComponents.CUSTOM_NAME).getString());
        assertNull(item.color());
        assertEquals(7, parse("<item:stone:custom_model_data=7>").stack().get(DataComponents.CUSTOM_MODEL_DATA).value());
    }

    @Test void invalidIdentitiesModifiersAndPartialParsesCannotBecomePlainItems() {
        for (String raw : new String[]{"<item:missing:stone>", "<item:stone:missing>", "<item:air>",
                "<item:stone[custom_model_data=7]junk>", "<item:stone[unknown:component=1]>",
                "<item:stone:italic>", "<item:stone:custom_model_data=oops>", "<item:stone", "plain text", "<item:>"})
            assertNull(HologramItem.parse(raw, registries), raw);
    }

    @Test void multipleLinesUseFirstItemWithoutMutatingTheirMarkup() {
        String raw = "<item:stone><item:diamond>";
        assertTrue(parse(raw).stack().is(Items.STONE));
        assertEquals("<item:stone><item:diamond>", raw);
        assertTrue(HologramItem.containsItem("<item:unavailable>"));
        assertFalse(HologramItem.containsItem(null));
    }

    private static HologramItem.Definition parse(String raw) {
        var parsed = HologramItem.parse(raw, registries);
        assertNotNull(parsed, raw);
        return parsed;
    }
}
