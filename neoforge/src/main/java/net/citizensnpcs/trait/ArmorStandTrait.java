package net.citizensnpcs.trait;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.core.Rotations;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Armour-stand appearance: limb poses and the visible/arms/base-plate/small/marker flags.
 * <p>
 * Also the source of the invisible "point entity" configuration that hologram lines and mounting helpers use — see
 * {@link #setAsPointEntity()}.
 * <p>
 * Poses are {@link Rotations} (vanilla, degrees) rather than Bukkit's {@code EulerAngle} (radians); the unit conversion
 * lives in {@code RotationsPersister} so saves stay compatible.
 */
@TraitName("armorstandtrait")
public class ArmorStandTrait extends Trait {
    @Persist
    private Rotations body;
    @Persist
    private boolean gravity = true;
    @Persist
    private boolean hasarms = true;
    @Persist
    private boolean hasbaseplate = true;
    @Persist
    private Rotations head;
    @Persist
    private Rotations leftArm;
    @Persist
    private Rotations leftLeg;
    @Persist
    private boolean marker;
    @Persist
    private Rotations rightArm;
    @Persist
    private Rotations rightLeg;
    @Persist
    private boolean small;
    @Persist
    private boolean visible = true;

    public ArmorStandTrait() {
        super("armorstandtrait");
    }

    public boolean getGravity() {
        return gravity;
    }

    public boolean getHasArms() {
        return hasarms;
    }

    public boolean getHasBaseplate() {
        return hasbaseplate;
    }

    public boolean isMarker() {
        return marker;
    }

    public boolean isSmall() {
        return small;
    }

    public boolean isVisible() {
        return visible;
    }

    @Override
    public void onPreSpawn() {
        onSpawn();
    }

    @Override
    public void onSpawn() {
        if (!(npc.getEntity() instanceof ArmorStand entity))
            return;
        if (leftArm != null) {
            entity.setLeftArmPose(leftArm);
        }
        if (leftLeg != null) {
            entity.setLeftLegPose(leftLeg);
        }
        if (rightArm != null) {
            entity.setRightArmPose(rightArm);
        }
        if (rightLeg != null) {
            entity.setRightLegPose(rightLeg);
        }
        if (body != null) {
            entity.setBodyPose(body);
        }
        if (head != null) {
            entity.setHeadPose(head);
        }
        applyFlags(entity);
    }

    @Override
    public void run() {
        if (!(npc.getEntity() instanceof ArmorStand entity))
            return;
        // read the poses back so a stand posed by other means is persisted, then re-assert our own flags
        body = entity.getBodyPose();
        leftArm = entity.getLeftArmPose();
        leftLeg = entity.getLeftLegPose();
        rightArm = entity.getRightArmPose();
        rightLeg = entity.getRightLegPose();
        head = entity.getHeadPose();
        applyFlags(entity);
    }

    private void applyFlags(ArmorStand entity) {
        entity.setInvisible(!visible);
        entity.setNoGravity(!gravity);
        entity.setShowArms(hasarms);
        entity.setNoBasePlate(!hasbaseplate);
        entity.setSmall(small);
        entity.setMarker(marker);
    }

    public void setAsHelperEntity(NPC parent) {
        npc.addTrait(new ClickRedirectTrait(parent));
        setAsPointEntity();
    }

    public void setAsHelperEntityWithName(NPC parent) {
        npc.addTrait(new ClickRedirectTrait(parent));
        setAsPointEntityWithName();
    }

    /**
     * Configures the entity as an invisible point entity, e.g. for mounting NPCs on top, nameplates, etc.
     */
    public void setAsPointEntity() {
        setGravity(false);
        setHasArms(false);
        setHasBaseplate(false);
        setSmall(true);
        setMarker(true);
        setVisible(false);
        npc.setProtected(true);
        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
    }

    public void setAsPointEntityWithName() {
        setAsPointEntity();
        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, true);
    }

    public void setGravity(boolean gravity) {
        this.gravity = gravity;
    }

    public void setHasArms(boolean arms) {
        hasarms = arms;
    }

    public void setHasBaseplate(boolean baseplate) {
        hasbaseplate = baseplate;
    }

    public void setMarker(boolean marker) {
        this.marker = marker;
    }

    public void setSmall(boolean small) {
        this.small = small;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public Rotations getBodyPose() {
        return body;
    }

    public Rotations getHeadPose() {
        return head;
    }

    public Rotations getLeftArmPose() {
        return leftArm;
    }

    public Rotations getLeftLegPose() {
        return leftLeg;
    }

    public Rotations getRightArmPose() {
        return rightArm;
    }

    public Rotations getRightLegPose() {
        return rightLeg;
    }

    public void setBodyPose(Rotations pose) {
        body = pose;
    }

    public void setHeadPose(Rotations pose) {
        head = pose;
    }

    public void setLeftArmPose(Rotations pose) {
        leftArm = pose;
    }

    public void setLeftLegPose(Rotations pose) {
        leftLeg = pose;
    }

    public void setRightArmPose(Rotations pose) {
        rightArm = pose;
    }

    public void setRightLegPose(Rotations pose) {
        rightLeg = pose;
    }
}
