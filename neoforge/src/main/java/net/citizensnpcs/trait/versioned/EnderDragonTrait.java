package net.citizensnpcs.trait.versioned;

import java.util.Locale;
import java.util.function.Supplier;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;

/**
 * An ender dragon NPC's flight phase, and whether it breaks the blocks it flies through.
 * <p>
 * Bukkit's phase enum names the same eleven phases as vanilla but spells all but two of them differently, so stored
 * values go through a table. Both spellings are accepted: an unrecognised value warns and leaves the phase alone rather
 * than silently substituting the wrong one, because the Bukkit spellings could not be verified against a Bukkit jar.
 * <p>
 * Wall breaking is applied from {@link net.citizensnpcs.EventListen}, not here. Upstream suppresses it by giving the
 * dragon its own entity subclass and calling vanilla's wall check only when the trait asks for it; this port uses a
 * vanilla dragon, which does the check unconditionally, so it has to be vetoed through the mob-griefing hook — the same
 * hook vanilla itself consults there.
 */
@TraitName("enderdragontrait")
public class EnderDragonTrait extends Trait {
    @Persist
    private boolean destroyWalls;
    private DragonPhase phase;

    public EnderDragonTrait() {
        super("enderdragontrait");
    }

    public DragonPhase getPhase() {
        return phase;
    }

    public boolean isDestroyWalls() {
        return destroyWalls;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        phase = parse(key.getString("phase"));
    }

    @Override
    public void save(DataKey key) {
        key.setString("phase", phase == null ? "" : phase.name());
    }

    @Override
    public void onSpawn() {
        apply();
    }

    private void apply() {
        if (phase != null && npc.getCosmeticEntity() instanceof EnderDragon dragon) {
            dragon.getPhaseManager().setPhase(phase.vanilla());
        }
    }

    public void setDestroyWalls(boolean destroyWalls) {
        this.destroyWalls = destroyWalls;
    }

    public void setPhase(DragonPhase phase) {
        this.phase = phase;
        apply();
    }

    /** Null for an empty value, or for one that matches neither spelling — the latter also warns. */
    public static DragonPhase parse(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        String upper = raw.toUpperCase(Locale.ROOT);
        for (DragonPhase candidate : DragonPhase.values()) {
            if (candidate.name().equals(upper) || candidate.vanillaName.equals(upper))
                return candidate;
        }
        Messaging.warn("Unknown ender dragon phase '" + raw + "', leaving the dragon's phase unchanged.");
        return null;
    }

    /**
     * The eleven dragon phases under Bukkit's names, which is what saves hold.
     * <p>
     * The vanilla phase is held as a supplier because {@link EnderDragonPhase} initialises its constants by registering
     * them into a static array, and naming them from an enum's own field initialisers would load the two classes in a
     * cycle.
     */
    public enum DragonPhase {
        CIRCLING("HOLDING_PATTERN", () -> EnderDragonPhase.HOLDING_PATTERN),
        STRAFING("STRAFE_PLAYER", () -> EnderDragonPhase.STRAFE_PLAYER),
        FLY_TO_PORTAL("LANDING_APPROACH", () -> EnderDragonPhase.LANDING_APPROACH),
        LAND_ON_PORTAL("LANDING", () -> EnderDragonPhase.LANDING),
        LEAVE_PORTAL("TAKEOFF", () -> EnderDragonPhase.TAKEOFF),
        BREATH_ATTACK("SITTING_FLAMING", () -> EnderDragonPhase.SITTING_FLAMING),
        SEARCH_FOR_BREATH_ATTACK_TARGET("SITTING_SCANNING", () -> EnderDragonPhase.SITTING_SCANNING),
        ROAR_BEFORE_ATTACK("SITTING_ATTACKING", () -> EnderDragonPhase.SITTING_ATTACKING),
        CHARGE_PLAYER("CHARGING_PLAYER", () -> EnderDragonPhase.CHARGING_PLAYER),
        DYING("DYING", () -> EnderDragonPhase.DYING),
        HOVER("HOVERING", () -> EnderDragonPhase.HOVERING);

        private final Supplier<EnderDragonPhase<?>> supplier;
        private final String vanillaName;

        DragonPhase(String vanillaName, Supplier<EnderDragonPhase<?>> supplier) {
            this.vanillaName = vanillaName;
            this.supplier = supplier;
        }

        public EnderDragonPhase<?> vanilla() {
            return supplier.get();
        }
    }
}
