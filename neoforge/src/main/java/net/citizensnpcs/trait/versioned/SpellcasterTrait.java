package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.monster.SpellcasterIllager;

/**
 * The spell an illager NPC is shown casting — evokers, illusioners and their casting pose.
 * <p>
 * Vanilla's spell enum is protected, so the type cannot be named from here without widening it. The six constant names
 * match Bukkit's, so stored values read back unchanged.
 */
@TraitName("spellcastertrait")
public class SpellcasterTrait extends Trait {
    @Persist
    private SpellcasterIllager.IllagerSpell spell;

    public SpellcasterTrait() {
        super("spellcastertrait");
    }

    public SpellcasterIllager.IllagerSpell getSpell() {
        return spell;
    }

    @Override
    public void run() {
        if (spell != null && npc.getCosmeticEntity() instanceof SpellcasterIllager caster) {
            caster.setIsCastingSpell(spell);
        }
    }

    public void setSpell(SpellcasterIllager.IllagerSpell spell) {
        this.spell = spell;
    }
}
