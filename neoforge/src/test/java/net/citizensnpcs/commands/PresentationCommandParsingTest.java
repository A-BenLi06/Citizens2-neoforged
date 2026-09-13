package net.citizensnpcs.commands;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import net.citizensnpcs.trait.versioned.BossBarTrait;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ItemDisplayContext;

class PresentationCommandParsingTest {
    @Test void transformationsRejectTruncatedExtraAndNonfiniteComponents() {
        var vectors = new PresentationTraitCommands.VectorValue();
        var rotations = new PresentationTraitCommands.RotationValue();
        assertEquals(-2, vectors.validate(null, null, null, "1,-2,0.5").y);
        assertEquals(1, rotations.validate(null, null, null, "0,1,0,0").y);
        for (String input : new String[] {"1,2", "1,2,3,4", "NaN,0,1", "0,Infinity,0", "0,0,1e100"})
            assertThrows(IllegalArgumentException.class, () -> vectors.validate(null, null, null, input));
        for (String input : new String[] {"0,0,1", "0,0,0,0", "0,0,0,NaN", "0,0,0,1,2"})
            assertThrows(IllegalArgumentException.class, () -> rotations.validate(null, null, null, input));
    }

    @Test void brightnessValidatesBothNativeFourBitLightChannels() {
        var value = new PresentationTraitCommands.BrightnessValue();
        assertEquals(15, value.validate(null, null, null, "0,15").sky());
        assertEquals(15, value.validate(null, null, null, "15,0").block());
        for (String input : new String[] {"-1,0", "0,16", "1.5,2", "3", "3,4,5"})
            assertThrows(IllegalArgumentException.class, () -> value.validate(null, null, null, input));
    }

    @Test void bossbarKeepsLegacyStylesButDoesNotDefaultInvalidCommandValues() {
        var style = new PresentationTraitCommands.BarStyleValue();
        assertEquals(BossEvent.BossBarOverlay.PROGRESS, style.validate(null, null, null, "solid"));
        assertEquals(BossEvent.BossBarOverlay.NOTCHED_10, style.validate(null, null, null, "segmented_10"));
        assertEquals(BossEvent.BossBarOverlay.NOTCHED_10, style.validate(null, null, null, "notched_10"));
        assertThrows(IllegalArgumentException.class, () -> style.validate(null, null, null, "segmented_11"));
        assertEquals(BossEvent.BossBarOverlay.PROGRESS, BossBarTrait.parseStyle("unknown-saved-style"));
        var flags = new PresentationTraitCommands.BarFlagsValue();
        assertEquals(2, flags.validate(null, null, null, "darken_sky,create_fog,darken_sky").size());
        assertTrue(flags.validate(null, null, null, "").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> flags.validate(null, null, null, "create_fog,unknown"));
    }

    @Test void itemTransformAcceptsBukkitSerializedAndNativeEnumNames() {
        var transform = new PresentationTraitCommands.ItemTransformValue();
        assertEquals(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, transform.validate(null, null, null, "THIRDPERSON_RIGHTHAND"));
        assertEquals(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, transform.validate(null, null, null, "third_person_right_hand"));
        assertThrows(IllegalArgumentException.class, () -> transform.validate(null, null, null, "missing"));
    }
}
