package net.citizensnpcs.trait;

import java.util.Locale;
import java.util.Map;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.trait.versioned.CatTrait;

/**
 * Migrates a pre-1.14 ocelot NPC onto {@link CatTrait}.
 * <p>
 * There is nothing left for this trait to apply: 1.14 split cats out into their own entity, so a 1.21.1 ocelot is a plain
 * animal with neither a breed nor a sitting pose. Upstream reaches the same conclusion at runtime — its
 * {@code SUPPORTS_CAT_TYPE} flag flips off on any modern server and it migrates — so this port is only that path, with
 * the version probing removed.
 * <p>
 * The trait is kept rather than deleted so that a save written by an old Citizens still carries its breed and posture
 * over instead of silently losing them. It hands both to {@code CatTrait} on load and does nothing else.
 */
@TraitName("ocelotmodifiers")
public class OcelotModifiers extends Trait {
    @Persist("sitting")
    private boolean sitting;
    private String type = "WILD_OCELOT";

    public OcelotModifiers() {
        super("ocelotmodifiers");
    }

    public boolean isSitting() {
        return sitting;
    }

    public String getType() {
        return type;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        type = key.getString("type", "WILD_OCELOT");
    }

    @Override
    public void save(DataKey key) {
        key.setString("type", type);
    }

    @Override
    public void onAttach() {
        migrateToCat();
    }

    @Override
    public void onSpawn() {
        migrateToCat();
    }

    private void migrateToCat() {
        CatTrait cat = npc.getOrAddTrait(CatTrait.class);
        cat.setSitting(sitting);
        cat.setType(CatTrait.parse(catVariantNameOf(type)));
    }

    public void setSitting(boolean sit) {
        sitting = sit;
        migrateToCat();
    }

    public void setType(String type) {
        this.type = type;
        migrateToCat();
    }

    /**
     * @return the cat breed the old ocelot type becomes, following upstream's own mapping
     */
    public static String catVariantNameOf(String ocelotType) {
        return OCELOT_TO_CAT.getOrDefault(ocelotType == null ? "" : ocelotType.toUpperCase(Locale.ROOT), "CALICO");
    }

    /** Upstream's mapping from the four pre-1.14 ocelot types to cat breeds. */
    private static final Map<String, String> OCELOT_TO_CAT = Map.of("WILD_OCELOT", "CALICO", "BLACK_CAT", "BLACK",
            "RED_CAT", "RED", "SIAMESE_CAT", "SIAMESE");
}
