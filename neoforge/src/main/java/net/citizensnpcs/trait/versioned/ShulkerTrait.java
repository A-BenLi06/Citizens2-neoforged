package net.citizensnpcs.trait.versioned;

import java.util.Optional;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.util.ShulkerPeek;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.DyeColor;

/**
 * A shulker NPC's shell colour and how far its lid is open.
 * <p>
 * Peek is only written when it changes: vanilla animates towards the stored amount and opening or closing plays a sound,
 * so writing it every tick would loop the sound. Upstream tracks the same thing with its own last-set field.
 */
@TraitName("shulkertrait")
public class ShulkerTrait extends Trait {
    @Persist("color")
    private DyeColor color = DyeColor.PURPLE;
    private int lastPeekSet = -1;
    private Shulker lastEntity;
    @Persist("peek")
    private int peek = 0;

    public ShulkerTrait() {
        super("shulkertrait");
    }

    public DyeColor getColor() {
        return color;
    }

    public int getPeek() {
        return peek;
    }

    @Override
    public void onSpawn() {
        // a fresh entity starts closed whatever the stored value was, so force the next run to write it
        lastPeekSet = -1;
        lastEntity = null;
    }

    @Override public void onDespawn() { lastEntity = null; }
    @Override public void onRemove() { lastEntity = null; }

    @Override
    public void run() {
        if (color == null) {
            color = DyeColor.PURPLE;
        }
        if (!(npc.getCosmeticEntity() instanceof Shulker shulker))
            return;
        if (lastEntity != shulker) {
            lastEntity = shulker;
            lastPeekSet = -1;
        }
        if (peek != lastPeekSet) {
            LookClose look = npc.getTraitNullable(LookClose.class);
            if (look == null || !look.setShulkerPeekBaseline(shulker, peek))
                shulker.setRawPeekAmount(ShulkerPeek.clamp(peek));
            lastPeekSet = peek;
        }
        shulker.setVariant(Optional.of(color));
    }

    public void setColor(DyeColor color) {
        this.color = color;
    }

    public void setPeek(int peek) {
        if (peek < 0 || peek > 100) throw new IllegalArgumentException("Peek must be between 0 and 100");
        this.peek = peek;
        lastPeekSet = -1;
        if (npc != null && npc.getCosmeticEntity() instanceof Shulker shulker) {
            LookClose look = npc.getTraitNullable(LookClose.class);
            if (look != null && look.setShulkerPeekBaseline(shulker, peek)) lastPeekSet = peek;
        }
    }
}
