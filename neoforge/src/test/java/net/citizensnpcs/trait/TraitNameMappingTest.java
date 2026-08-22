package net.citizensnpcs.trait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.animal.Rabbit;

/**
 * Covers the two traits whose stored values had to be re-mapped because vanilla names them differently from Bukkit.
 * Getting these wrong silently changes an NPC's appearance on load, which is exactly the kind of thing a save-format
 * test should catch.
 */
public class TraitNameMappingTest {
    @BeforeAll
    public static void bootstrap() {
        Bootstrap.bootStrap();
    }

    @Test
    public void rabbitLegacyNamesMap() {
        assertEquals(Rabbit.Variant.WHITE_SPLOTCHED, RabbitType.parse("BLACK_AND_WHITE"));
        assertEquals(Rabbit.Variant.SALT, RabbitType.parse("SALT_AND_PEPPER"));
        assertEquals(Rabbit.Variant.EVIL, RabbitType.parse("THE_KILLER_BUNNY"));
    }

    @Test
    public void rabbitSharedNamesStillWork() {
        assertEquals(Rabbit.Variant.BROWN, RabbitType.parse("BROWN"));
        assertEquals(Rabbit.Variant.WHITE, RabbitType.parse("WHITE"));
        assertEquals(Rabbit.Variant.GOLD, RabbitType.parse("gold"));
    }

    @Test
    public void rabbitUnknownFallsBackToBrown() {
        assertEquals(Rabbit.Variant.BROWN, RabbitType.parse("NOT_A_RABBIT"));
        assertEquals(Rabbit.Variant.BROWN, RabbitType.parse(""));
    }

    @Test
    public void villagerProfessionNamesResolve() {
        assertEquals(BuiltInRegistries.VILLAGER_PROFESSION.get(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("librarian")),
                VillagerProfession.parse("LIBRARIAN"));
        assertNotNull(VillagerProfession.parse("FARMER"));
    }

    @Test
    public void villagerLegacyProfessionNamesMap() {
        assertEquals(VillagerProfession.parse("ARMORER"), VillagerProfession.parse("BLACKSMITH"));
        assertEquals(VillagerProfession.parse("CLERIC"), VillagerProfession.parse("PRIEST"));
        assertEquals(VillagerProfession.parse("TOOLSMITH"), VillagerProfession.parse("SMITH"));
        assertEquals(VillagerProfession.parse("FARMER"), VillagerProfession.parse("NORMAL"));
    }

    @Test
    public void villagerUnknownProfessionIsNull() {
        assertNull(VillagerProfession.parse("NOT_A_PROFESSION"));
    }
}
