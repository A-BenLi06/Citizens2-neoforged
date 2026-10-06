package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import net.minecraft.resources.ResourceLocation;

class DimensionLookupTest {
    private static ResourceLocation id(String value) { return ResourceLocation.parse(value); }

    @Test void qualifiedNamesNeverFallBackToAnotherNamespace() {
        var worlds = List.of(id("minecraft:overworld"), id("mod:overworld"));
        assertNull(Util.findLevel("missing:overworld", worlds, Function.identity()));
        assertSame(worlds.get(1), Util.findLevel("mod:overworld", worlds, Function.identity()));
    }

    @Test void shorthandPrefersDefaultNamespaceOtherwiseRequiresOneMatch() {
        var first = id("first:moon"); var second = id("second:moon"); var vanilla = id("minecraft:moon");
        assertSame(first, Util.findLevel("moon", List.of(first), Function.identity()));
        assertNull(Util.findLevel("moon", List.of(first, second), Function.identity()));
        assertNull(Util.findLevel("moon", List.of(second, first), Function.identity()));
        assertSame(vanilla, Util.findLevel("moon", List.of(first, second, vanilla), Function.identity()));
        assertSame(vanilla, Util.findLevel("moon", List.of(vanilla, first, second), Function.identity()));
    }

    @Test void malformedOrMissingNamesCannotMatch() {
        var worlds = List.of(id("minecraft:overworld"));
        for (String value : new String[] { null, "", "not a world", "missing", "mod:world:extra" })
            assertNull(Util.findLevel(value, worlds, Function.identity()));
    }
}
