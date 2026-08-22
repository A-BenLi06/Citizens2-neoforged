package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.TextParser;
import net.minecraft.world.entity.Display;

/**
 * The text-display-only properties: the text itself, its alignment, line width, background and the shadow and
 * see-through flags.
 * <p>
 * Vanilla packs alignment, shadow and see-through into one flag byte, and derives "no background" from a flag rather than
 * from the colour, so the byte is rebuilt from whichever fields were configured and the rest of it is left as it was.
 * Alignment names match Bukkit's. The background keeps upstream's on-disk shape, a single ARGB int.
 * <p>
 * The text goes through {@link TextParser}, so the same markup that works elsewhere in Citizens works here.
 */
@TraitName("textdisplaytrait")
public class TextDisplayTrait extends Trait {
    @Persist
    private Display.TextDisplay.Align alignment;
    @Persist("bgcolor")
    private Integer backgroundColor;
    @Persist
    private Integer lineWidth;
    @Persist
    private Boolean seeThrough;
    @Persist
    private Boolean shadowed;
    @Persist
    private String text;

    public TextDisplayTrait() {
        super("textdisplaytrait");
    }

    public Display.TextDisplay.Align getAlignment() {
        return alignment;
    }

    public Integer getBackgroundColor() {
        return backgroundColor;
    }

    public Integer getLineWidth() {
        return lineWidth;
    }

    public String getText() {
        return text;
    }

    public Boolean isSeeThrough() {
        return seeThrough;
    }

    public Boolean isShadowed() {
        return shadowed;
    }

    @Override
    public void onSpawn() {
        apply();
    }

    private void apply() {
        if (!(npc.getCosmeticEntity() instanceof Display.TextDisplay display))
            return;
        if (text != null) {
            display.setText(TextParser.parse(text));
        }
        if (lineWidth != null) {
            display.setLineWidth(lineWidth);
        }
        if (backgroundColor != null) {
            display.setBackgroundColor(backgroundColor);
        }
        byte flags = display.getFlags();
        if (shadowed != null) {
            flags = set(flags, Display.TextDisplay.FLAG_SHADOW, shadowed);
        }
        if (seeThrough != null) {
            flags = set(flags, Display.TextDisplay.FLAG_SEE_THROUGH, seeThrough);
        }
        if (backgroundColor != null) {
            // an explicit colour means the default background is no longer wanted
            flags = set(flags, Display.TextDisplay.FLAG_USE_DEFAULT_BACKGROUND, false);
        }
        if (alignment != null) {
            flags = set(flags, Display.TextDisplay.FLAG_ALIGN_LEFT, alignment == Display.TextDisplay.Align.LEFT);
            flags = set(flags, Display.TextDisplay.FLAG_ALIGN_RIGHT, alignment == Display.TextDisplay.Align.RIGHT);
        }
        display.setFlags(flags);
    }

    private static byte set(byte flags, byte bit, boolean on) {
        return (byte) (on ? flags | bit : flags & ~bit);
    }

    public void setAlignment(Display.TextDisplay.Align alignment) {
        this.alignment = alignment;
    }

    public void setBackgroundColor(Integer backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public void setLineWidth(Integer lineWidth) {
        this.lineWidth = lineWidth;
    }

    public void setSeeThrough(Boolean seeThrough) {
        this.seeThrough = seeThrough;
    }

    public void setShadowed(Boolean shadowed) {
        this.shadowed = shadowed;
    }

    public void setText(String text) {
        this.text = text;
    }
}
