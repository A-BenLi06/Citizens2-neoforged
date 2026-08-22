package net.citizensnpcs.trait.versioned;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;

/**
 * The display-entity properties shared by text, item and block displays: size, transform, brightness, shadow and view
 * range.
 * <p>
 * Every field is boxed so that "not configured" stays distinct from a configured zero, which is what lets the trait leave
 * vanilla's own default in place. Applied on spawn, as upstream does — none of these change on their own.
 * <p>
 * The hologram renderers keep their own copies of a few of these rather than delegating here; both write the same keys,
 * so the on-disk shape is the same either way.
 */
@TraitName("displaytrait")
public class DisplayTrait extends Trait {
    @Persist
    private Display.BillboardConstraints billboard;
    @Persist
    private Integer blockLight;
    @Persist
    private Float height;
    @Persist
    private Integer interpolationDelay;
    @Persist
    private Integer interpolationDuration;
    @Persist
    private Quaternionf leftRotation;
    @Persist
    private Vector3f offset;
    @Persist
    private Quaternionf rightRotation;
    @Persist
    private Vector3f scale;
    @Persist
    private Float shadowRadius;
    @Persist
    private Float shadowStrength;
    @Persist
    private Integer skyLight;
    @Persist
    private Float viewRange;
    @Persist
    private Float width;

    public DisplayTrait() {
        super("displaytrait");
    }

    public Display.BillboardConstraints getBillboard() {
        return billboard;
    }

    public Float getHeight() {
        return height;
    }

    public Vector3f getOffset() {
        return offset;
    }

    public Vector3f getScale() {
        return scale;
    }

    public Float getWidth() {
        return width;
    }

    @Override
    public void onSpawn() {
        apply();
    }

    private void apply() {
        if (!(npc.getCosmeticEntity() instanceof Display display))
            return;
        if (billboard != null) {
            display.setBillboardConstraints(billboard);
        }
        if (blockLight != null && skyLight != null) {
            display.setBrightnessOverride(new Brightness(blockLight, skyLight));
        }
        if (interpolationDelay != null) {
            display.setTransformationInterpolationDelay(interpolationDelay);
        }
        if (interpolationDuration != null) {
            display.setTransformationInterpolationDuration(interpolationDuration);
        }
        if (height != null) {
            display.setHeight(height);
        }
        if (width != null) {
            display.setWidth(width);
        }
        if (offset != null || scale != null || leftRotation != null || rightRotation != null) {
            // vanilla offers no getter for the current transform, so the whole thing is rebuilt from the fields that
            // were configured, with vanilla's own defaults for the rest
            display.setTransformation(new Transformation(offset == null ? new Vector3f() : new Vector3f(offset),
                    leftRotation == null ? null : new Quaternionf(leftRotation),
                    scale == null ? new Vector3f(1, 1, 1) : new Vector3f(scale),
                    rightRotation == null ? null : new Quaternionf(rightRotation)));
        }
        if (viewRange != null) {
            display.setViewRange(viewRange);
        }
        if (shadowRadius != null) {
            display.setShadowRadius(shadowRadius);
        }
        if (shadowStrength != null) {
            display.setShadowStrength(shadowStrength);
        }
    }

    public void setBillboard(Display.BillboardConstraints billboard) {
        this.billboard = billboard;
    }

    public void setBrightness(Brightness brightness) {
        blockLight = brightness == null ? null : brightness.block();
        skyLight = brightness == null ? null : brightness.sky();
    }

    public void setHeight(Float height) {
        this.height = height;
    }

    public void setInterpolationDelay(Integer interpolationDelay) {
        this.interpolationDelay = interpolationDelay;
    }

    public void setInterpolationDuration(Integer interpolationDuration) {
        this.interpolationDuration = interpolationDuration;
    }

    public void setLeftRotation(Quaternionf leftRotation) {
        this.leftRotation = leftRotation;
    }

    public void setOffset(Vector3f offset) {
        this.offset = offset;
    }

    public void setRightRotation(Quaternionf rightRotation) {
        this.rightRotation = rightRotation;
    }

    public void setScale(Vector3f scale) {
        this.scale = scale;
    }

    public void setShadowRadius(Float shadowRadius) {
        this.shadowRadius = shadowRadius;
    }

    public void setShadowStrength(Float shadowStrength) {
        this.shadowStrength = shadowStrength;
    }

    public void setViewRange(Float viewRange) {
        this.viewRange = viewRange;
    }

    public void setWidth(Float width) {
        this.width = width;
    }
}
