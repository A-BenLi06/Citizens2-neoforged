package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.Panda;

/**
 * A panda NPC's two genes and its poses. Gene names match Bukkit.
 * <p>
 * Bukkit's "rolling" is vanilla's on-back flag. Upstream wraps the rolling, sneezing and eating calls in a caught
 * throwable because older Bukkit releases lacked them; against a single target version that fallback is dead code.
 */
@TraitName("pandatrait")
public class PandaTrait extends Trait {
    @Persist
    private boolean eating;
    @Persist
    private Panda.Gene hiddenGene;
    @Persist
    private Panda.Gene mainGene = Panda.Gene.NORMAL;
    @Persist
    private boolean rolling;
    @Persist
    private boolean sitting;
    @Persist
    private boolean sneezing;

    public PandaTrait() {
        super("pandatrait");
    }

    public Panda.Gene getHiddenGene() {
        return hiddenGene;
    }

    public Panda.Gene getMainGene() {
        return mainGene;
    }

    public boolean isEating() {
        return eating;
    }

    public boolean isRolling() {
        return rolling;
    }

    public boolean isSitting() {
        return sitting;
    }

    public boolean isSneezing() {
        return sneezing;
    }

    @Override
    public void run() {
        if (!(npc.getCosmeticEntity() instanceof Panda panda))
            return;
        if (mainGene != null) {
            panda.setMainGene(mainGene);
        }
        if (hiddenGene != null) {
            panda.setHiddenGene(hiddenGene);
        }
        panda.sit(sitting);
        panda.setOnBack(rolling);
        panda.sneeze(sneezing);
        panda.eat(eating);
    }

    public void setEating(boolean eating) {
        this.eating = eating;
    }

    public void setHiddenGene(Panda.Gene gene) {
        hiddenGene = gene;
    }

    public void setMainGene(Panda.Gene gene) {
        mainGene = gene;
    }

    public void setRolling(boolean rolling) {
        this.rolling = rolling;
    }

    public void setSitting(boolean sitting) {
        this.sitting = sitting;
    }

    public void setSneezing(boolean sneezing) {
        this.sneezing = sneezing;
    }
}
