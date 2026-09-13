package net.citizensnpcs.commands;

import static org.junit.jupiter.api.Assertions.*;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.trait.versioned.ArmadilloTrait;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import org.junit.jupiter.api.Test;

class EntityCommandParsingTest {
    @Test void armadilloAcceptsLegacyAndNativeStateNamesWithoutDefaultingInvalidCommands() throws Exception {
        var value = new EntityTraitCommands.ArmadilloStateValue();
        assertEquals(Armadillo.ArmadilloState.ROLLING, value.validate(null, null, null, "ROLLING_UP"));
        assertEquals(Armadillo.ArmadilloState.UNROLLING, value.validate(null, null, null, "ROLLING_OUT"));
        assertEquals(Armadillo.ArmadilloState.ROLLING, value.validate(null, null, null, "rolling"));
        assertThrows(CommandException.class, () -> value.validate(null, null, null, "not-a-state"));
        assertEquals(Armadillo.ArmadilloState.IDLE, ArmadilloTrait.parse("not-a-state"));
    }

    @Test void rgbAndRgbaKeepTheirChannelsAndRejectOverflow() throws Exception {
        var value = new EntityTraitCommands.PackedColorValue();
        assertEquals(0xFF112233, value.validate(null, null, null, "0x112233"));
        assertEquals(0xFF112233, value.validate(null, null, null, "17,34,51"));
        assertEquals(0x40112233, value.validate(null, null, null, "17,34,51,64"));
        for (String invalid : new String[] {"256,0,0", "0,-1,0", "1,2", "1,2,3,4,5", "-1", "0x1000000"})
            assertThrows(CommandException.class, () -> value.validate(null, null, null, invalid));
    }
}
