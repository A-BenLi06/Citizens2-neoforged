package net.citizensnpcs.trait;

import java.util.Locale;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.WolfVariant;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * A wolf NPC's collar colour, breed, and its tamed, sitting, angry and interested states.
 * <p>
 * The breed is stored by name, as upstream does — it reaches Bukkit's registry-backed variant class by reflection, which
 * here is a plain registry lookup. Wolf variants live in a <em>datapack</em> registry rather than
 * {@code BuiltInRegistries}, so the lookup needs the server; breeds added by a datapack or another mod work unchanged.
 * <p>
 * "Angry" is vanilla's persistent anger timer. Upstream also points the wolf at itself to make the anger stick; that is a
 * Bukkit-side quirk of {@code setAngry} and is not needed here, so it is left out rather than copied.
 */
@TraitName("wolfmodifiers")
public class WolfModifiers extends Trait {
    @Persist
    private boolean angry;
    @Persist("collarColor")
    private DyeColor collarColor = DyeColor.RED;
    @Persist
    private boolean interested;
    @Persist
    private boolean sitting;
    @Persist
    private boolean tamed;
    @Persist
    private String variant;

    public WolfModifiers() {
        super("wolfmodifiers");
    }

    public DyeColor getCollarColor() {
        return collarColor;
    }

    public String getVariant() {
        return variant;
    }

    public boolean isAngry() {
        return angry;
    }

    public boolean isInterested() {
        return interested;
    }

    public boolean isSitting() {
        return sitting;
    }

    public boolean isTamed() {
        return tamed;
    }

    @Override
    public void onSpawn() {
        updateModifiers();
    }

    private void updateModifiers() {
        if (!(npc.getCosmeticEntity() instanceof Wolf wolf))
            return;
        if (collarColor != null) {
            wolf.setCollarColor(collarColor);
        }
        // Bukkit's setSitting writes both, and only the ordered-to-sit flag is persisted by vanilla
        wolf.setOrderedToSit(sitting);
        wolf.setInSittingPose(sitting);
        wolf.setRemainingPersistentAngerTime(angry ? ANGRY_TICKS : 0);
        wolf.setIsInterested(interested);
        Holder<WolfVariant> resolved = parseVariant(variant);
        if (resolved != null) {
            wolf.setVariant(resolved);
        }
        wolf.setTame(tamed, false);
    }

    public void setAngry(boolean angry) {
        this.angry = angry;
        updateModifiers();
    }

    public void setCollarColor(DyeColor color) {
        collarColor = color;
        updateModifiers();
    }

    public void setInterested(boolean interested) {
        this.interested = interested;
        updateModifiers();
    }

    public void setSitting(boolean sitting) {
        this.sitting = sitting;
        updateModifiers();
    }

    public void setTamed(boolean tamed) {
        this.tamed = tamed;
        updateModifiers();
    }

    public void setVariant(String variant) {
        this.variant = variant;
        updateModifiers();
    }

    /**
     * @return the breed, or null when the name is empty, matches nothing, or no server is running
     */
    public static Holder<WolfVariant> parseVariant(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return null;
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        return id == null ? null
                : server.registryAccess().registryOrThrow(Registries.WOLF_VARIANT).getHolder(id)
                        .map(h -> (Holder<WolfVariant>) h).orElse(null);
    }

    /** Long enough that the wolf stays angry while the trait keeps re-asserting it. */
    private static final int ANGRY_TICKS = 400;
}
