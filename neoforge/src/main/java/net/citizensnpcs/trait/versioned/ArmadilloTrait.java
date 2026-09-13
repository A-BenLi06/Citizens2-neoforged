package net.citizensnpcs.trait.versioned;

import java.util.Locale;
import java.util.Map;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.world.entity.animal.armadillo.Armadillo;

/**
 * An armadillo NPC roll-up state.
 * <p>
 * Upstream declares its own four-value enum because Bukkit exposes none; vanilla has the real one, but two of the names
 * differ ({@code ROLLING_UP} / {@code ROLLING_OUT} against vanilla {@code ROLLING} / {@code UNROLLING}), so stored values
 * are mapped rather than parsed straight.
 */
@TraitName("armadillotrait")
public class ArmadilloTrait extends Trait {
    private Armadillo.ArmadilloState state = Armadillo.ArmadilloState.IDLE;

    public ArmadilloTrait() {
        super("armadillotrait");
    }

    public Armadillo.ArmadilloState getState() {
        return state;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        state = parse(key.getString("state"));
    }

    @Override
    public void save(DataKey key) {
        key.setString("state", state.name());
    }

    public static Armadillo.ArmadilloState parse(String raw) {
        Armadillo.ArmadilloState parsed = parseStrict(raw);
        return parsed == null ? Armadillo.ArmadilloState.IDLE : parsed;
    }

    public static Armadillo.ArmadilloState parseStrict(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        String upper = raw.toUpperCase(Locale.ROOT);
        Armadillo.ArmadilloState legacy = LEGACY_NAMES.get(upper);
        if (legacy != null)
            return legacy;
        try {
            return Armadillo.ArmadilloState.valueOf(upper);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static java.util.List<String> stateNames() {
        return java.util.stream.Stream.concat(java.util.Arrays.stream(Armadillo.ArmadilloState.values()).map(Enum::name),
                LEGACY_NAMES.keySet().stream()).distinct().sorted().toList();
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Armadillo armadillo && armadillo.getState() != state) {
            armadillo.switchToState(state);
        }
    }

    public void setState(Armadillo.ArmadilloState state) {
        this.state = state == null ? Armadillo.ArmadilloState.IDLE : state;
    }

    /** The two names upstream spells differently from vanilla. */
    private static final Map<String, Armadillo.ArmadilloState> LEGACY_NAMES = Map.of("ROLLING_UP",
            Armadillo.ArmadilloState.ROLLING, "ROLLING_OUT", Armadillo.ArmadilloState.UNROLLING);
}
