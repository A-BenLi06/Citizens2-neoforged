package net.citizensnpcs.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.citizensnpcs.api.command.Arg;
import net.citizensnpcs.api.command.Arg.CompletionsProvider;
import net.citizensnpcs.api.command.Arg.FlagValidator;
import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.command.Flag;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.command.exception.CommandUsageException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.trait.versioned.BossBarTrait;
import net.citizensnpcs.trait.versioned.DisplayTrait;
import net.citizensnpcs.trait.versioned.InteractionTrait;
import net.citizensnpcs.trait.versioned.ItemDisplayTrait;
import net.citizensnpcs.trait.versioned.PotionEffectsTrait;
import net.citizensnpcs.trait.versioned.TextDisplayTrait;
import net.citizensnpcs.util.Messages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Brightness;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;

/** Native display, interaction, boss-bar and potion-effect configuration. */
public final class PresentationTraitCommands {
    @Command(aliases = "npc", modifiers = "bossbar", min = 1, max = 1, desc = "", strictArguments = true,
            usage = "bossbar --style [style] --color [color] --title [title] --visible [true|false] --viewpermission [permission] --flags [flags] --track [health|placeholder] --range [range]",
            permission = "citizens.npc.bossbar")
    @Requirements(selected = true, ownership = true)
    public static void bossbar(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag(value = "style", validator = BarStyleValue.class, completionsProvider = BarStyles.class) BossEvent.BossBarOverlay style,
            @Flag("color") BossEvent.BossBarColor color, @Flag("title") String title,
            @Flag("visible") Boolean visible, @Flag("viewpermission") String viewPermission,
            @Flag(value = "flags", validator = BarFlagsValue.class) List<BossBarTrait.BarFlag> flags,
            @Flag("track") String track,
            @Flag(value = "range", permission = "citizens.npc.bossbar.range") Integer range) {
        BossBarTrait trait = npc.getOrAddTrait(BossBarTrait.class);
        if (style != null) trait.setStyle(style);
        if (color != null) trait.setColor(color);
        if (title != null) trait.setTitle(title);
        if (visible != null) trait.setVisible(visible);
        if (viewPermission != null) trait.setViewPermission(viewPermission.isEmpty() ? null : viewPermission);
        if (flags != null) trait.setFlags(flags);
        if (track != null) trait.setTrackVariable(track);
        if (range != null) trait.setRange(range);
        trait.run();
        changed(sender, npc, "bossbar");
    }

    @Command(aliases = "npc", modifiers = "display", min = 1, max = 1, desc = "", strictArguments = true,
            usage = "display --billboard [billboard] --brightness [block,sky] --interpolation_delay [ticks] --interpolation_duration [ticks] --height [height] --width [width] --scale [x,y,z] --view_range [range] --left_rotation [x,y,z,w] --right_rotation [x,y,z,w] --offset [x,y,z] --shadow_radius [radius] --shadow_strength [strength]",
            permission = "citizens.npc.display")
    @Requirements(selected = true, ownership = true, cosmeticTypes = {"item_display", "text_display", "block_display"})
    public static void display(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag("billboard") Display.BillboardConstraints billboard,
            @Flag(value = {"left_rotation", "leftrotation"}, validator = RotationValue.class) Quaternionf leftRotation,
            @Flag(value = {"right_rotation", "rightrotation"}, validator = RotationValue.class) Quaternionf rightRotation,
            @Flag(value = "scale", validator = VectorValue.class) Vector3f scale,
            @Flag(value = "offset", validator = VectorValue.class) Vector3f offset,
            @Flag({"view_range", "viewrange"}) Float viewRange,
            @Flag(value = "brightness", validator = BrightnessValue.class) Brightness brightness,
            @Flag({"interpolation_delay", "interpolationdelay"}) Integer interpolationDelay,
            @Flag({"interpolation_duration", "interpolationduration"}) Integer interpolationDuration,
            @Flag("height") Float height, @Flag("width") Float width,
            @Flag({"shadow_radius", "shadowradius"}) Float shadowRadius,
            @Flag({"shadow_strength", "shadowstrength"}) Float shadowStrength) throws CommandException {
        requireOptions(args);
        nonnegative("height", height); nonnegative("width", width); nonnegative("view_range", viewRange);
        nonnegative("shadow_radius", shadowRadius); nonnegative("shadow_strength", shadowStrength);
        nonnegative("interpolation_duration", interpolationDuration);
        DisplayTrait trait = npc.getOrAddTrait(DisplayTrait.class);
        if (billboard != null) trait.setBillboard(billboard);
        if (brightness != null) trait.setBrightness(brightness);
        if (leftRotation != null) trait.setLeftRotation(leftRotation);
        if (rightRotation != null) trait.setRightRotation(rightRotation);
        if (scale != null) trait.setScale(scale);
        if (offset != null) trait.setOffset(offset);
        if (viewRange != null) trait.setViewRange(viewRange);
        if (interpolationDelay != null) trait.setInterpolationDelay(interpolationDelay);
        if (interpolationDuration != null) trait.setInterpolationDuration(interpolationDuration);
        if (height != null) trait.setHeight(height);
        if (width != null) trait.setWidth(width);
        if (shadowRadius != null) trait.setShadowRadius(shadowRadius);
        if (shadowStrength != null) trait.setShadowStrength(shadowStrength);
        trait.onSpawn();
        changed(sender, npc, "display");
    }

    @Command(aliases = "npc", modifiers = "itemdisplay", min = 1, max = 1, desc = "", strictArguments = true,
            usage = "itemdisplay --transform [transform]", permission = "citizens.npc.itemdisplay")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "item_display")
    public static void itemdisplay(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag(value = "transform", validator = ItemTransformValue.class, completionsProvider = ItemTransforms.class) ItemDisplayContext transform)
            throws CommandException {
        requireOptions(args);
        npc.getOrAddTrait(ItemDisplayTrait.class).setTransform(transform);
        changed(sender, npc, "itemdisplay");
    }

    @Command(aliases = "npc", modifiers = "textdisplay", min = 1, max = 1, desc = "", strictArguments = true,
            usage = "textdisplay --shadowed [true|false] --seethrough [true|false] --line_width [width] --text [text] --bgcolor [r,g,b,a] --alignment [left|center|right]",
            permission = "citizens.npc.textdisplay")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "text_display")
    public static void textdisplay(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag("shadowed") Boolean shadowed, @Flag("seethrough") Boolean seeThrough,
            @Flag("line_width") Integer lineWidth, @Flag("text") String text,
            @Flag(value = "bgcolor", validator = EntityTraitCommands.PackedColorValue.class) Integer backgroundColor,
            @Flag("alignment") Display.TextDisplay.Align alignment) throws CommandException {
        requireOptions(args);
        nonnegative("line_width", lineWidth);
        TextDisplayTrait trait = npc.getOrAddTrait(TextDisplayTrait.class);
        if (shadowed != null) trait.setShadowed(shadowed);
        if (seeThrough != null) trait.setSeeThrough(seeThrough);
        if (lineWidth != null) trait.setLineWidth(lineWidth);
        if (text != null) trait.setText(text);
        if (backgroundColor != null) trait.setBackgroundColor(backgroundColor);
        if (alignment != null) trait.setAlignment(alignment);
        trait.onSpawn();
        changed(sender, npc, "textdisplay");
    }

    @Command(aliases = "npc", modifiers = "interaction", min = 1, max = 1, desc = "", strictArguments = true,
            usage = "interaction --height [height] --responsive [true|false] --width [width]",
            permission = "citizens.npc.interaction")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "interaction")
    public static void interaction(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag("height") Float height, @Flag("width") Float width, @Flag("responsive") Boolean responsive)
            throws CommandException {
        requireOptions(args);
        nonnegative("height", height); nonnegative("width", width);
        InteractionTrait trait = npc.getOrAddTrait(InteractionTrait.class);
        if (height != null) trait.setInteractionHeight(height);
        if (width != null) trait.setInteractionWidth(width);
        if (responsive != null) trait.setResponsive(responsive);
        changed(sender, npc, "interaction");
    }

    @Command(aliases = "npc", modifiers = "potioneffect", min = 2, max = 2, flags = "it", desc = "", strictArguments = true,
            usage = "potioneffect [add|remove|list] (--name [name] or -t for temporary) --type [type] --duration [ticks] --amplifier [amplifier] --icon [true|false] --ambient [true|false] --particles [true|false]",
            permission = "citizens.npc.potioneffect")
    @Requirements(selected = true, ownership = true, livingEntity = true)
    public static void potioneffect(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completions = {"add", "remove", "list"}) String operation,
            @Flag("name") String name, @Flag(value = "duration", defValue = "-1") Integer duration,
            @Flag(value = "amplifier", defValue = "1") Integer amplifier,
            @Flag(value = "type", validator = EffectTypeValue.class, completionsProvider = EffectTypes.class) Holder<MobEffect> type,
            @Flag(value = "icon", defValue = "false") Boolean icon,
            @Flag(value = "ambient", defValue = "false") Boolean ambient,
            @Flag(value = "particles", defValue = "false") Boolean particles) throws CommandException {
        switch (operation.toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (type == null || !args.hasFlag('t') && (name == null || name.isBlank())) throw new CommandUsageException();
                if (args.hasFlag('i')) duration = -1;
                if (duration < -1) throw new CommandException(CommandMessages.INVALID_VALUE, "--duration", duration);
                if (amplifier < 0 || amplifier > MobEffectInstance.MAX_AMPLIFIER)
                    throw new CommandException(CommandMessages.INVALID_VALUE, "--amplifier", amplifier);
                MobEffectInstance effect = new MobEffectInstance(type, duration, amplifier, ambient, particles, icon);
                PotionEffectsTrait trait = npc.getOrAddTrait(PotionEffectsTrait.class);
                if (args.hasFlag('t')) trait.addEffect(effect);
                else trait.addPersistentEffect(name, effect);
                Messaging.sendTr(sender, Messages.POTION_EFFECT_ADDED, effect, npc.getName());
            }
            case "list" -> {
                PotionEffectsTrait trait = npc.getTraitNullable(PotionEffectsTrait.class);
                if (trait == null || trait.getPersistentEffects().isEmpty())
                    Messaging.sendTr(sender, "citizens.commands.npc.potioneffects.list-empty", npc.getName());
                else for (Map.Entry<String, MobEffectInstance> entry : trait.getPersistentEffects().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList())
                    Messaging.sendTr(sender, "citizens.commands.npc.potioneffects.list-entry", entry.getKey(), entry.getValue());
            }
            case "remove" -> {
                if (name == null || name.isBlank()) throw new CommandUsageException();
                PotionEffectsTrait trait = npc.getTraitNullable(PotionEffectsTrait.class);
                if (trait == null || !trait.getPersistentEffects().containsKey(name))
                    throw new CommandException("citizens.commands.npc.potioneffects.not-found", name, npc.getName());
                trait.removePersistentEffect(name);
                Messaging.sendTr(sender, Messages.POTION_EFFECT_REMOVED, name, npc.getName());
            }
            default -> throw new CommandUsageException();
        }
    }

    private static void changed(CommandSourceStack sender, NPC npc, String command) {
        Messaging.sendTr(sender, "citizens.commands.npc.entity.action", npc.getName(), command);
    }

    private static void requireOptions(CommandContext args) throws CommandUsageException {
        if (args.getValueFlags().keySet().stream().noneMatch(name -> !name.equals("id") && !name.equals("uuid")))
            throw new CommandUsageException();
    }

    private static void nonnegative(String name, Number number) throws CommandException {
        if (number != null && number.doubleValue() < 0) throw new CommandException(CommandMessages.INVALID_VALUE, "--" + name, number);
    }

    private static float[] components(String input, int count) {
        String[] parts = input.split(",", -1);
        if (parts.length != count) throw new IllegalArgumentException("Wrong component count");
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = Float.parseFloat(parts[i].trim());
            if (!Float.isFinite(values[i])) throw new IllegalArgumentException("Nonfinite component");
        }
        return values;
    }

    public static class VectorValue implements FlagValidator<Vector3f> {
        public Vector3f validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) {
            float[] values = components(input, 3);
            return new Vector3f(values[0], values[1], values[2]);
        }
    }

    public static class RotationValue implements FlagValidator<Quaternionf> {
        public Quaternionf validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) {
            float[] values = components(input, 4);
            Quaternionf rotation = new Quaternionf(values[0], values[1], values[2], values[3]);
            if (rotation.lengthSquared() == 0 || !Float.isFinite(rotation.lengthSquared()))
                throw new IllegalArgumentException("Invalid quaternion");
            return rotation;
        }
    }

    public static class BrightnessValue implements FlagValidator<Brightness> {
        public Brightness validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) {
            String[] parts = input.split(",", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Expected block and sky light");
            int block = Integer.parseInt(parts[0].trim()), sky = Integer.parseInt(parts[1].trim());
            if (block < 0 || block > 15 || sky < 0 || sky > 15) throw new IllegalArgumentException("Light out of range");
            return new Brightness(block, sky);
        }
    }

    public static class BarStyleValue implements FlagValidator<BossEvent.BossBarOverlay> {
        public BossEvent.BossBarOverlay validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) {
            var style = BossBarTrait.parseStyleStrict(input);
            if (style == null) throw new IllegalArgumentException("Unknown boss-bar style");
            return style;
        }
    }

    public static class BarStyles implements CompletionsProvider {
        public Collection<String> getCompletions(CommandContext args, CommandSourceStack sender, NPC npc) {
            return BossBarTrait.styleNames();
        }
    }

    public static class BarFlagsValue implements FlagValidator<List<BossBarTrait.BarFlag>> {
        public List<BossBarTrait.BarFlag> validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) {
            List<BossBarTrait.BarFlag> flags = new ArrayList<>();
            for (String part : input.split(",")) {
                if (part.isBlank()) continue;
                BossBarTrait.BarFlag flag = BossBarTrait.parseFlag(part.trim());
                if (flag == null) throw new IllegalArgumentException("Unknown boss-bar flag");
                if (!flags.contains(flag)) flags.add(flag);
            }
            return flags;
        }
    }

    public static class ItemTransformValue implements FlagValidator<ItemDisplayContext> {
        public ItemDisplayContext validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) {
            ItemDisplayContext transform = ItemDisplayTrait.parse(input);
            if (transform == null) throw new IllegalArgumentException("Unknown item display transform");
            return transform;
        }
    }

    public static class ItemTransforms implements CompletionsProvider {
        public Collection<String> getCompletions(CommandContext args, CommandSourceStack sender, NPC npc) {
            return Arrays.stream(ItemDisplayContext.values()).map(ItemDisplayContext::getSerializedName).toList();
        }
    }

    public static class EffectTypeValue implements FlagValidator<Holder<MobEffect>> {
        public Holder<MobEffect> validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) {
            ResourceLocation id = ResourceLocation.tryParse(input.toLowerCase(Locale.ROOT));
            if (id == null || !BuiltInRegistries.MOB_EFFECT.containsKey(id)) throw new IllegalArgumentException("Unknown effect type");
            return BuiltInRegistries.MOB_EFFECT.getHolder(id).orElseThrow();
        }
    }

    public static class EffectTypes implements CompletionsProvider {
        public Collection<String> getCompletions(CommandContext args, CommandSourceStack sender, NPC npc) {
            return BuiltInRegistries.MOB_EFFECT.keySet().stream()
                    .map(id -> id.getNamespace().equals("minecraft") ? id.getPath() : id.toString()).toList();
        }
    }
}
