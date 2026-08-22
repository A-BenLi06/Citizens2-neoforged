import io

d = "src/main/java/net/citizensnpcs/trait/versioned/"
HDR = ("package net.citizensnpcs.trait.versioned;\n\n"
       "import net.citizensnpcs.api.persistence.Persist;\n"
       "import net.citizensnpcs.api.trait.Trait;\n"
       "import net.citizensnpcs.api.trait.TraitName;\n")

files = {}

files["AllayTrait"] = HDR + """import net.minecraft.world.entity.animal.allay.Allay;

/** Whether an allay NPC is dancing, as it does near a jukebox. */
@TraitName("allaytrait")
public class AllayTrait extends Trait {
    @Persist
    private boolean dancing = false;

    public AllayTrait() {
        super("allaytrait");
    }

    public boolean isDancing() {
        return dancing;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Allay allay) {
            allay.setDancing(dancing);
        }
    }

    public void setDancing(boolean dance) {
        dancing = dance;
    }
}
"""

files["VexTrait"] = HDR + """import net.minecraft.world.entity.monster.Vex;

/** Whether a vex NPC shows its charging (attacking) pose. */
@TraitName("vextrait")
public class VexTrait extends Trait {
    @Persist("charging")
    private Boolean charging;

    public VexTrait() {
        super("vextrait");
    }

    public Boolean isCharging() {
        return charging;
    }

    @Override
    public void run() {
        if (charging != null && npc.getCosmeticEntity() instanceof Vex vex) {
            vex.setIsCharging(charging);
        }
    }

    public void setCharging(Boolean charging) {
        this.charging = charging;
    }
}
"""

files["PhantomTrait"] = HDR + """import net.minecraft.world.entity.monster.Phantom;

/** A phantom NPC size, which also scales its hitbox. */
@TraitName("phantomtrait")
public class PhantomTrait extends Trait {
    @Persist
    private int size = 1;

    public PhantomTrait() {
        super("phantomtrait");
    }

    public int getSize() {
        return size;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Phantom phantom) {
            phantom.setPhantomSize(size);
        }
    }

    public void setSize(int size) {
        this.size = size;
    }
}
"""

files["PolarBearTrait"] = HDR + """import net.minecraft.world.entity.animal.PolarBear;

/** Whether a polar bear NPC stands on its hind legs. */
@TraitName("polarbeartrait")
public class PolarBearTrait extends Trait {
    @Persist
    private boolean rearing;

    public PolarBearTrait() {
        super("polarbeartrait");
    }

    public boolean isRearing() {
        return rearing;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof PolarBear bear) {
            bear.setStanding(rearing);
        }
    }

    public void setRearing(boolean rearing) {
        this.rearing = rearing;
    }
}
"""

files["ParrotTrait"] = HDR + """import net.minecraft.world.entity.animal.Parrot;

/** A parrot NPC colour. Vanilla variant names match Bukkit, so saves read back directly. */
@TraitName("parrottrait")
public class ParrotTrait extends Trait {
    @Persist
    private Parrot.Variant variant = Parrot.Variant.BLUE;

    public ParrotTrait() {
        super("parrottrait");
    }

    public Parrot.Variant getVariant() {
        return variant;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Parrot parrot) {
            parrot.setVariant(variant);
        }
    }

    public void setVariant(Parrot.Variant variant) {
        this.variant = variant == null ? Parrot.Variant.BLUE : variant;
    }
}
"""

files["MushroomCowTrait"] = HDR + """import net.minecraft.world.entity.animal.MushroomCow;

/** A mooshroom NPC mushroom type. Vanilla names (RED / BROWN) match Bukkit. */
@TraitName("mushroomcowtrait")
public class MushroomCowTrait extends Trait {
    @Persist("variant")
    private MushroomCow.MushroomType variant;

    public MushroomCowTrait() {
        super("mushroomcowtrait");
    }

    public MushroomCow.MushroomType getVariant() {
        return variant;
    }

    @Override
    public void run() {
        if (variant != null && npc.getCosmeticEntity() instanceof MushroomCow cow) {
            cow.setVariant(variant);
        }
    }

    public void setVariant(MushroomCow.MushroomType variant) {
        this.variant = variant;
    }
}
"""

files["SnowmanTrait"] = HDR + """import net.minecraft.world.entity.animal.SnowGolem;

/**
 * Snow golem appearance. Bukkit calls a golem with no pumpkin on its head "derp"; vanilla models the same thing the
 * other way round, as a pumpkin flag.
 * <p>
 * {@code formSnow} is stored but has no effect: leaving a snow trail is vanilla AI, which a Citizens NPC has switched
 * off. It stays persisted so the setting survives a save round-trip.
 */
@TraitName("snowmantrait")
public class SnowmanTrait extends Trait {
    @Persist("derp")
    private boolean derp;
    @Persist
    private boolean formSnow;

    public SnowmanTrait() {
        super("snowmantrait");
    }

    public boolean isDerp() {
        return derp;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof SnowGolem golem) {
            golem.setPumpkin(!derp);
        }
    }

    public void setDerp(boolean derp) {
        this.derp = derp;
    }

    public void setFormSnow(boolean snow) {
        formSnow = snow;
    }

    public boolean shouldFormSnow() {
        return formSnow;
    }
}
"""

files["GoatTrait"] = HDR + """import net.minecraft.world.entity.animal.goat.Goat;

/**
 * Which horns a goat NPC has.
 * <p>
 * Vanilla only ever knocks a horn off and never puts one back, so neither flag has a setter and the synced keys are
 * written directly.
 */
@TraitName("goattrait")
public class GoatTrait extends Trait {
    @Persist
    private boolean leftHorn = true;
    @Persist
    private boolean rightHorn = true;

    public GoatTrait() {
        super("goattrait");
    }

    public boolean isLeftHorn() {
        return leftHorn;
    }

    public boolean isRightHorn() {
        return rightHorn;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Goat goat) {
            goat.getEntityData().set(Goat.DATA_HAS_LEFT_HORN, leftHorn);
            goat.getEntityData().set(Goat.DATA_HAS_RIGHT_HORN, rightHorn);
        }
    }

    public void setLeftHorn(boolean horn) {
        leftHorn = horn;
    }

    public void setRightHorn(boolean horn) {
        rightHorn = horn;
    }
}
"""

files["SnifferTrait"] = HDR + """import net.minecraft.world.entity.animal.sniffer.Sniffer;

/**
 * A sniffer NPC animation state.
 * <p>
 * Only re-asserted when it drifts: each transition restarts the animation, so writing it every tick would freeze the
 * sniffer on the first frame.
 */
@TraitName("sniffertrait")
public class SnifferTrait extends Trait {
    @Persist
    private Sniffer.State state = Sniffer.State.IDLING;

    public SnifferTrait() {
        super("sniffertrait");
    }

    public Sniffer.State getState() {
        return state;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Sniffer sniffer && sniffer.getState() != state) {
            sniffer.transitionTo(state);
        }
    }

    public void setState(Sniffer.State state) {
        this.state = state == null ? Sniffer.State.IDLING : state;
    }
}
"""

files["ArmadilloTrait"] = HDR + """import java.util.Locale;
import java.util.Map;

import net.citizensnpcs.api.exception.NPCLoadException;
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
        state = parse(key.getString("state", key.getString("")));
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("");
        key.setString("state", state.name());
    }

    public static Armadillo.ArmadilloState parse(String raw) {
        if (raw == null || raw.isEmpty())
            return Armadillo.ArmadilloState.IDLE;
        String upper = raw.toUpperCase(Locale.ROOT);
        Armadillo.ArmadilloState legacy = LEGACY_NAMES.get(upper);
        if (legacy != null)
            return legacy;
        try {
            return Armadillo.ArmadilloState.valueOf(upper);
        } catch (IllegalArgumentException ex) {
            return Armadillo.ArmadilloState.IDLE;
        }
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
"""

files["CamelTrait"] = HDR + """import net.minecraft.world.entity.animal.camel.Camel;

/**
 * Whether a camel NPC is sitting.
 * <p>
 * Upstream models three poses (STANDING / SITTING / PANIC) because Bukkit exposes them. Vanilla has no pose setter at
 * all: sitting is derived from the sign of the last pose-change tick, which is what gets written here. PANIC is dropped
 * rather than faked, since it is a transient AI state on an entity whose AI a Citizens NPC has switched off.
 */
@TraitName("cameltrait")
public class CamelTrait extends Trait {
    @Persist
    private boolean sitting;

    public CamelTrait() {
        super("cameltrait");
    }

    public boolean isSitting() {
        return sitting;
    }

    @Override
    public void run() {
        if (!(npc.getCosmeticEntity() instanceof Camel camel) || camel.isCamelSitting() == sitting)
            return;
        // a negative last-pose-change tick is what vanilla reads as "sitting"
        long now = camel.level().getGameTime();
        camel.getEntityData().set(Camel.LAST_POSE_CHANGE_TICK, sitting ? -now : now);
    }

    public void setSitting(boolean sitting) {
        this.sitting = sitting;
    }
}
"""

files["CatTrait"] = HDR + """import java.util.Locale;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.CatVariant;
import net.minecraft.world.item.DyeColor;

/**
 * Cat NPC appearance: breed, collar colour, and the sitting and lying poses.
 * <p>
 * The breed is a registry object in vanilla rather than Bukkit's {@code Cat.Type} enum, so it is stored by name and
 * resolved through the registry — which also means breeds added by other mods work with no code change. Bukkit's enum
 * constant names are the registry ids uppercased, so existing saves read back unchanged.
 */
@TraitName("cattrait")
public class CatTrait extends Trait {
    @Persist
    private DyeColor collarColor = null;
    @Persist
    private boolean lying = false;
    @Persist
    private boolean sitting = false;
    private Holder<CatVariant> type;

    public CatTrait() {
        super("cattrait");
    }

    public DyeColor getCollarColor() {
        return collarColor;
    }

    public boolean isLyingDown() {
        return lying;
    }

    public boolean isSitting() {
        return sitting;
    }

    public Holder<CatVariant> getType() {
        return type == null ? defaultVariant() : type;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        type = parse(key.getString("type"));
    }

    @Override
    public void save(DataKey key) {
        key.setString("type", getType().unwrapKey().map(k -> k.location().getPath().toUpperCase(Locale.ROOT)).orElse(""));
    }

    public static Holder<CatVariant> parse(String raw) {
        if (raw == null || raw.isEmpty())
            return defaultVariant();
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        if (id == null || !BuiltInRegistries.CAT_VARIANT.containsKey(id))
            return defaultVariant();
        return BuiltInRegistries.CAT_VARIANT.getHolder(id).map(h -> (Holder<CatVariant>) h).orElseGet(
                CatTrait::defaultVariant);
    }

    private static Holder<CatVariant> defaultVariant() {
        return BuiltInRegistries.CAT_VARIANT.getHolderOrThrow(CatVariant.BLACK);
    }

    @Override
    public void run() {
        if (!(npc.getCosmeticEntity() instanceof Cat cat))
            return;
        cat.setInSittingPose(sitting);
        cat.setLying(lying);
        cat.setVariant(getType());
        if (collarColor != null) {
            cat.setCollarColor(collarColor);
        }
    }

    public void setCollarColor(DyeColor color) {
        collarColor = color;
    }

    public void setLyingDown(boolean lying) {
        this.lying = lying;
    }

    public void setSitting(boolean sitting) {
        this.sitting = sitting;
    }

    public void setType(Holder<CatVariant> type) {
        this.type = type == null ? defaultVariant() : type;
    }
}
"""

files["FrogTrait"] = HDR + """import java.util.Locale;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.FrogVariant;
import net.minecraft.world.entity.animal.frog.Frog;

/**
 * A frog NPC variant (temperate / warm / cold).
 * <p>
 * A registry object in vanilla rather than Bukkit's enum, so stored by name and resolved through the registry; variants
 * added by other mods therefore work unchanged. The three vanilla names match Bukkit's constants.
 */
@TraitName("frogtrait")
public class FrogTrait extends Trait {
    private Holder<FrogVariant> variant;

    public FrogTrait() {
        super("frogtrait");
    }

    public Holder<FrogVariant> getVariant() {
        return variant == null ? defaultVariant() : variant;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        variant = parse(key.getString("variant", key.getString("")));
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("");
        key.setString("variant",
                getVariant().unwrapKey().map(k -> k.location().getPath().toUpperCase(Locale.ROOT)).orElse(""));
    }

    @SuppressWarnings("unchecked")
    public static Holder<FrogVariant> parse(String raw) {
        if (raw == null || raw.isEmpty())
            return defaultVariant();
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        if (id == null || !BuiltInRegistries.FROG_VARIANT.containsKey(id))
            return defaultVariant();
        return BuiltInRegistries.FROG_VARIANT.getHolder(id).map(h -> (Holder<FrogVariant>) h)
                .orElseGet(FrogTrait::defaultVariant);
    }

    private static Holder<FrogVariant> defaultVariant() {
        return BuiltInRegistries.FROG_VARIANT.getHolderOrThrow(FrogVariant.TEMPERATE);
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Frog frog) {
            frog.setVariant(getVariant());
        }
    }

    public void setVariant(Holder<FrogVariant> variant) {
        this.variant = variant == null ? defaultVariant() : variant;
    }
}
"""

for name, body in files.items():
    io.open(d + name + ".java", "w", encoding="utf-8", newline="\n").write(body)
print("wrote", len(files), "files")
