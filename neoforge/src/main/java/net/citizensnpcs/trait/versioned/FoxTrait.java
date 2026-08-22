package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.Fox;

/**
 * A fox NPC's breed and poses.
 * <p>
 * Upstream persists all six poses but applies only three: interested, pouncing and faceplanted are stored and never
 * reach the entity. They are poses a player asked for and can see, so all six are applied here. Vanilla keeps the
 * sleeping and faceplanted setters package-private, hence the access widening.
 */
@TraitName("foxtrait")
public class FoxTrait extends Trait {
    @Persist
    private boolean crouching = false;
    @Persist
    private boolean faceplanted;
    @Persist
    private boolean interested;
    @Persist
    private boolean pouncing;
    @Persist
    private boolean sitting = false;
    @Persist
    private boolean sleeping = false;
    @Persist
    private Fox.Type type = Fox.Type.RED;

    public FoxTrait() {
        super("foxtrait");
    }

    public Fox.Type getType() {
        return type;
    }

    public boolean isCrouching() {
        return crouching;
    }

    public boolean isFaceplanted() {
        return faceplanted;
    }

    public boolean isInterested() {
        return interested;
    }

    public boolean isPouncing() {
        return pouncing;
    }

    public boolean isSitting() {
        return sitting;
    }

    public boolean isSleeping() {
        return sleeping;
    }

    @Override
    public void run() {
        if (!(npc.getCosmeticEntity() instanceof Fox fox))
            return;
        fox.setSitting(sitting);
        fox.setIsCrouching(crouching);
        fox.setSleeping(sleeping);
        fox.setIsInterested(interested);
        fox.setIsPouncing(pouncing);
        fox.setFaceplanted(faceplanted);
        if (type != null) {
            fox.setVariant(type);
        }
    }

    public void setCrouching(boolean crouching) {
        this.crouching = crouching;
    }

    public void setFaceplanted(boolean faceplanted) {
        this.faceplanted = faceplanted;
    }

    public void setInterested(boolean interested) {
        this.interested = interested;
    }

    public void setPouncing(boolean pouncing) {
        this.pouncing = pouncing;
    }

    public void setSitting(boolean sitting) {
        this.sitting = sitting;
    }

    public void setSleeping(boolean sleeping) {
        this.sleeping = sleeping;
    }

    public void setType(Fox.Type type) {
        this.type = type;
    }
}
