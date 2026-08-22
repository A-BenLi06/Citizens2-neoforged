package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.PlayerModelPart;

/**
 * Which outer skin layers a player NPC shows — cape, jacket, sleeves, trouser legs and hat.
 * <p>
 * Vanilla carries these as a bit mask inside the player's client settings, which a real client sends on joining. An NPC
 * has no client, so {@link net.citizensnpcs.npc.entity.EntityHumanNPC} starts every layer on and this trait narrows it.
 * The mask is rewritten by handing the player a fresh {@link ClientInformation} built from its current one, so nothing
 * else in those settings is disturbed and no access widening is needed.
 */
@TraitName("skinlayers")
public class SkinLayers extends Trait {
    @Persist("cape")
    private boolean cape = true;
    @Persist("hat")
    private boolean hat = true;
    @Persist("jacket")
    private boolean jacket = true;
    @Persist("left-pants")
    private boolean leftPants = true;
    @Persist("left-sleeve")
    private boolean leftSleeve = true;
    @Persist("right-pants")
    private boolean rightPants = true;
    @Persist("right-sleeve")
    private boolean rightSleeve = true;

    public SkinLayers() {
        super("skinlayers");
    }

    public SkinLayers hide() {
        cape = hat = jacket = leftSleeve = rightSleeve = leftPants = rightPants = false;
        setFlags();
        return this;
    }

    public SkinLayers hideCape() {
        cape = false;
        setFlags();
        return this;
    }

    public SkinLayers hideHat() {
        hat = false;
        setFlags();
        return this;
    }

    public SkinLayers hideJacket() {
        jacket = false;
        setFlags();
        return this;
    }

    public SkinLayers hidePants() {
        leftPants = rightPants = false;
        setFlags();
        return this;
    }

    public SkinLayers hideSleeves() {
        leftSleeve = rightSleeve = false;
        setFlags();
        return this;
    }

    public boolean isVisible(PlayerModelPart part) {
        return switch (part) {
            case CAPE -> cape;
            case HAT -> hat;
            case JACKET -> jacket;
            case LEFT_PANTS_LEG -> leftPants;
            case LEFT_SLEEVE -> leftSleeve;
            case RIGHT_PANTS_LEG -> rightPants;
            case RIGHT_SLEEVE -> rightSleeve;
        };
    }

    @Override
    public void onSpawn() {
        setFlags();
    }

    public SkinLayers show() {
        cape = hat = jacket = leftSleeve = rightSleeve = leftPants = rightPants = true;
        setFlags();
        return this;
    }

    public void setVisible(PlayerModelPart part, boolean visible) {
        switch (part) {
            case CAPE -> cape = visible;
            case HAT -> hat = visible;
            case JACKET -> jacket = visible;
            case LEFT_PANTS_LEG -> leftPants = visible;
            case LEFT_SLEEVE -> leftSleeve = visible;
            case RIGHT_PANTS_LEG -> rightPants = visible;
            case RIGHT_SLEEVE -> rightSleeve = visible;
        }
        setFlags();
    }

    private void setFlags() {
        if (!(npc.getEntity() instanceof ServerPlayer player))
            return;
        int mask = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            if (isVisible(part)) {
                mask |= part.getMask();
            }
        }
        ClientInformation current = player.clientInformation();
        player.updateOptions(new ClientInformation(current.language(), current.viewDistance(),
                current.chatVisibility(), current.chatColors(), mask, current.mainHand(),
                current.textFilteringEnabled(), current.allowsListing()));
    }

    /** Every layer on — the mask a player NPC starts with, since no client will send one. */
    public static final int ALL_LAYERS_MASK = maskOfAll();

    private static int maskOfAll() {
        int mask = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            mask |= part.getMask();
        }
        return mask;
    }
}
