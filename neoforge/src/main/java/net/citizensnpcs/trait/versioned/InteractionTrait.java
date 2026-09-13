package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.Interaction;

/** The size and response of an invisible interaction entity, in the original saved-data layout. */
@TraitName("interactiontrait")
public class InteractionTrait extends Trait {
    @Persist
    private Float height;
    @Persist
    private Boolean responsive;
    @Persist
    private Float width;

    public InteractionTrait() { super("interactiontrait"); }

    @Override
    public void onSpawn() {
        if (!(npc.getCosmeticEntity() instanceof Interaction interaction)) return;
        interaction.setCustomName(null);
        if (height != null) interaction.setHeight(height);
        if (width != null) interaction.setWidth(width);
        if (responsive != null) interaction.setResponse(responsive);
    }

    public Float getInteractionHeight() { return height; }
    public Float getInteractionWidth() { return width; }
    public Boolean isResponsive() { return responsive; }
    public void setInteractionHeight(Float height) { this.height = height; onSpawn(); }
    public void setInteractionWidth(Float width) { this.width = width; onSpawn(); }
    public void setResponsive(Boolean responsive) { this.responsive = responsive; onSpawn(); }
}
