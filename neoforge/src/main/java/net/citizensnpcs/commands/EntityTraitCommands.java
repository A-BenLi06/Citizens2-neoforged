package net.citizensnpcs.commands;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.trait.HorseModifiers;
import net.citizensnpcs.trait.VillagerProfession;
import net.citizensnpcs.trait.versioned.*;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.CatVariant;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.FrogVariant;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.TropicalFish;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.monster.SpellcasterIllager;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.alchemy.Potion;

/** Native configuration commands for existing version-specific entity traits. */
public final class EntityTraitCommands {
    private static final String ENTITY_ACTION = "citizens.commands.npc.entity.action";
    @Command(
            aliases = { "npc" },
            usage = "allay (-d(ancing))",
            desc = "",
            strictArguments = true,
            modifiers = { "allay" },
            min = 1,
            max = 1,
            flags = "d",
            permission = "citizens.npc.allay")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "allay")
    public static void allay(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        requireInput(args, "d");
        AllayTrait trait = npc.getOrAddTrait(AllayTrait.class);
        String output = "";
        if (args.hasFlag('d')) {
            trait.setDancing(!trait.isDancing());
            output += ' ' + (trait.isDancing() ? Messaging.tr(Messages.ALLAY_DANCING_SET, npc.getName())
                    : Messaging.tr(Messages.ALLAY_DANCING_UNSET, npc.getName()));
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "armadillo --state [state]",
            desc = "",
            strictArguments = true,
            modifiers = { "armadillo" },
            min = 1,
            max = 1,
            flags = "",
            permission = "citizens.npc.armadillo")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "armadillo")
    public static void armadillo(CommandContext args, CommandSourceStack sender, NPC npc, @Flag(value = "state", validator = ArmadilloStateValue.class, completionsProvider = ArmadilloStates.class) Armadillo.ArmadilloState state)
            throws CommandException {
        requireInput(args, "", "state");
        ArmadilloTrait trait = npc.getOrAddTrait(ArmadilloTrait.class);
        String output = "";
        if (state != null) {
            trait.setState(state);
            output += Messaging.tr(Messages.ARMADILLO_STATE_SET, state);
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "axolotl (-d) (--variant variant)",
            desc = "",
            strictArguments = true,
            modifiers = { "axolotl" },
            min = 1,
            max = 1,
            flags = "d",
            permission = "citizens.npc.axolotl")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "axolotl")
    public static void axolotl(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag("variant") Axolotl.Variant variant) throws CommandException {
        requireInput(args, "d", "variant");
        AxolotlTrait trait = npc.getOrAddTrait(AxolotlTrait.class);
        String output = "";
        if (args.hasValueFlag("variant")) {
            if (variant == null)
                throw new CommandException(Messages.INVALID_AXOLOTL_VARIANT,
                        Util.listValuesPretty(Axolotl.Variant.values()));
            trait.setVariant(variant);
            output += ' ' + Messaging.tr(Messages.AXOLOTL_VARIANT_SET, args.getFlag("variant"));
        }
        if (args.hasFlag('d')) {
            trait.setPlayingDead(!trait.isPlayingDead());
            output += ' ' + (trait.isPlayingDead() ? Messaging.tr(Messages.AXOLOTL_PLAYING_DEAD, npc.getName())
                    : Messaging.tr(Messages.AXOLOTL_NOT_PLAYING_DEAD, npc.getName()));
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "bee (-s/-n) --anger anger",
            desc = "",
            strictArguments = true,
            modifiers = { "bee" },
            min = 1,
            max = 1,
            flags = "sn",
            permission = "citizens.npc.bee")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "bee")
    public static void bee(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("anger") Integer anger)
            throws CommandException {
        requireInput(args, "sn", "anger");
        if (anger != null && anger < 0) throw new CommandException(Messages.INVALID_BEE_ANGER);
        BeeTrait trait = npc.getOrAddTrait(BeeTrait.class);
        String output = "";
        if (anger != null) {
            if (anger < 0)
                throw new CommandException(Messages.INVALID_BEE_ANGER);
            trait.setAnger(anger);
            output += ' ' + Messaging.tr(Messages.BEE_ANGER_SET, args.getFlag("anger"));
        }
        if (args.hasFlag('s')) {
            trait.setStung(!trait.hasStung());
            output += ' ' + (trait.hasStung() ? Messaging.tr(Messages.BEE_STUNG, npc.getName())
                    : Messaging.tr(Messages.BEE_NOT_STUNG, npc.getName()));
        }
        if (args.hasFlag('n')) {
            trait.setNectar(!trait.hasNectar());
            output += ' ' + (trait.hasNectar() ? Messaging.tr(Messages.BEE_HAS_NECTAR, npc.getName())
                    : Messaging.tr(Messages.BEE_NO_NECTAR, npc.getName()));
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "camel (--pose pose)",
            desc = "",
            strictArguments = true,
            modifiers = { "camel" },
            min = 1,
            max = 1,
            permission = "citizens.npc.camel")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "camel")
    public static void camel(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("pose") CamelTrait.CamelPose pose)
            throws CommandException {
        CamelTrait trait = npc.getOrAddTrait(CamelTrait.class);
        String output = "";

        if (pose != null) {
            trait.setPose(pose);
            output += Messaging.tr(Messages.CAMEL_POSE_SET, pose);
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "cat (-s/-n/-l) --type type --ccolor collar color",
            desc = "",
            strictArguments = true,
            modifiers = { "cat" },
            min = 1,
            max = 1,
            flags = "snl",
            permission = "citizens.npc.cat")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "cat")
    public static void cat(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("ccolor") DyeColor ccolor,
            @Flag(value = "type", validator = CatTypeValue.class, completionsProvider = CatTypes.class) Holder<CatVariant> type) throws CommandException {
        requireInput(args, "snl", "ccolor", "type");
        CatTrait trait = npc.getOrAddTrait(CatTrait.class);
        String output = "";
        if (args.hasValueFlag("type")) {
            if (type == null)
                throw new CommandUsageException(Messages.INVALID_CAT_TYPE, registryNames(BuiltInRegistries.CAT_VARIANT));
            trait.setType(type);
            output += ' ' + Messaging.tr(Messages.CAT_TYPE_SET, args.getFlag("type"));
        }
        if (args.hasValueFlag("ccolor")) {
            if (ccolor == null)
                throw new CommandUsageException(Messages.INVALID_CAT_COLLAR_COLOR,
                        Util.listValuesPretty(DyeColor.values()));
            trait.setCollarColor(ccolor);
            output += ' ' + Messaging.tr(Messages.CAT_COLLAR_COLOR_SET, args.getFlag("ccolor"));
        }
        if (args.hasFlag('s')) {
            trait.setSitting(true);
            output += ' ' + Messaging.tr(Messages.CAT_STARTED_SITTING, npc.getName());
        } else if (args.hasFlag('n')) {
            trait.setSitting(false);
            output += ' ' + Messaging.tr(Messages.CAT_STOPPED_SITTING, npc.getName());
        }
        if (args.hasFlag('l')) {
            trait.setLyingDown(!trait.isLyingDown());
            output += ' ' + Messaging.tr(trait.isLyingDown() ? Messages.CAT_STARTED_LYING : Messages.CAT_STOPPED_LYING,
                    npc.getName());
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "fox --type type --sleeping [true|false] --sitting [true|false] --crouching [true|false] --interested [true|false] --pouncing [true|false] --faceplanted [true|false]",
            desc = "",
            strictArguments = true,
            modifiers = { "fox" },
            min = 1,
            max = 1,
            permission = "citizens.npc.fox")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "fox")
    public static void fox(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("sleeping") Boolean sleeping,
            @Flag("sitting") Boolean sitting, @Flag("crouching") Boolean crouching,
            @Flag(value = "type", completions = { "RED", "SNOW" }) String rawtype, @Flag("pouncing") Boolean pouncing,
            @Flag("interested") Boolean interested, @Flag("faceplanted") Boolean faceplanted) throws CommandException {
        requireInput(args, "", "sleeping", "sitting", "crouching", "type", "pouncing", "interested", "faceplanted");
        FoxTrait trait = npc.getOrAddTrait(FoxTrait.class);
        String output = "";
        if (rawtype != null) {
            Fox.Type type = Util.matchEnum(Fox.Type.values(), args.getFlag("type"));
            if (type == null)
                throw new CommandUsageException(
                        Messaging.tr(Messages.INVALID_FOX_TYPE, Util.listValuesPretty(Fox.Type.values())), null);
            trait.setType(type);
            output += ' ' + Messaging.tr(Messages.FOX_TYPE_SET, args.getFlag("type"), npc.getName());
        }
        if (sleeping != null) {
            trait.setSleeping(sleeping);
            output += ' '
                    + Messaging.tr(sleeping ? Messages.FOX_SLEEPING_SET : Messages.FOX_SLEEPING_UNSET, npc.getName());
        }
        if (sitting != null) {
            trait.setSitting(sitting);
            output += ' '
                    + Messaging.tr(sitting ? Messages.FOX_SITTING_SET : Messages.FOX_SITTING_UNSET, npc.getName());
        }
        if (crouching != null) {
            trait.setCrouching(crouching);
            output += ' ' + Messaging.tr(crouching ? Messages.FOX_CROUCHING_SET : Messages.FOX_CROUCHING_UNSET,
                    npc.getName());
        }
        if (interested != null) {
            trait.setInterested(interested);
            output += ' ' + Messaging.tr(interested ? Messages.FOX_INTERESTED_SET : Messages.FOX_INTERESTED_UNSET,
                    npc.getName());
        }
        if (pouncing != null) {
            trait.setPouncing(pouncing);
            output += ' '
                    + Messaging.tr(pouncing ? Messages.FOX_POUNCING_SET : Messages.FOX_POUNCING_UNSET, npc.getName());
        }
        if (faceplanted != null) {
            trait.setFaceplanted(faceplanted);
            output += ' ' + Messaging.tr(faceplanted ? Messages.FOX_FACEPLANTED_SET : Messages.FOX_FACEPLANTED_UNSET,
                    npc.getName());
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "frog (--variant variant)",
            desc = "",
            strictArguments = true,
            modifiers = { "frog" },
            min = 1,
            max = 1,
            permission = "citizens.npc.frog")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "frog")
    public static void frog(CommandContext args, CommandSourceStack sender, NPC npc, @Flag(value = "variant", validator = FrogVariantValue.class, completionsProvider = FrogVariants.class) Holder<FrogVariant> variant)
            throws CommandException {
        FrogTrait trait = npc.getOrAddTrait(FrogTrait.class);
        String output = "";
        if (args.hasValueFlag("variant")) {
            if (variant == null)
                throw new CommandException(Messages.INVALID_FROG_VARIANT,
                        registryNames(BuiltInRegistries.FROG_VARIANT));
            trait.setVariant(variant);
            output += Messaging.tr(Messages.FROG_VARIANT_SET, variant);
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "goat -l(eft) -r(ight) -n(either) -b(oth) horn",
            desc = "",
            strictArguments = true,
            modifiers = { "goat" },
            flags = "lrnb",
            min = 1,
            max = 1,
            permission = "citizens.npc.goat")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "goat")
    public static void goat(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        GoatTrait trait = npc.getOrAddTrait(GoatTrait.class);
        boolean left = trait.isLeftHorn(), right = trait.isRightHorn();
        if (args.hasFlag('l')) {
            left = !left;
        }
        if (args.hasFlag('r')) {
            right = !right;
        }
        if (args.hasFlag('b')) {
            left = right = true;
        }
        if (args.hasFlag('n')) {
            left = right = false;
        }
        trait.setLeftHorn(left);
        trait.setRightHorn(right);
        String output = Messaging.tr(Messages.GOAT_HORNS_SET, npc.getName(), left, right);
        if (!output.isEmpty()) {
            Messaging.send(sender, output);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "llama (--color color) (--strength strength)",
            desc = "",
            strictArguments = true,
            modifiers = { "llama" },
            min = 1,
            max = 1,
            flags = "cb",
            permission = "citizens.npc.llama")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "llama", "trader_llama" })
    public static void llama(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag({ "color", "colour" }) Llama.Variant color, @Flag("strength") Integer strength) throws CommandException {
        LlamaTrait trait = npc.getOrAddTrait(LlamaTrait.class);
        String output = "";
        if (args.hasAnyValueFlag("color", "colour")) {
            if (color == null) {
                String valid = Util.listValuesPretty(Llama.Variant.values());
                throw new CommandException(Messages.INVALID_LLAMA_COLOR, valid);
            }
            trait.setColor(color);
            output += Messaging.tr(Messages.LLAMA_COLOR_SET, Util.prettyEnum(color));
        }
        if (strength != null) {
            trait.setStrength(Math.max(1, Math.min(5, strength)));
            output += Messaging.tr(Messages.LLAMA_STRENGTH_SET, trait.getStrength());
        }
        if (args.hasFlag('c')) {
            npc.getOrAddTrait(HorseModifiers.class).setCarryingChest(true);
            output += Messaging.tr(Messages.HORSE_CHEST_SET) + " ";
        } else if (args.hasFlag('b')) {
            npc.getOrAddTrait(HorseModifiers.class).setCarryingChest(false);
            output += Messaging.tr(Messages.HORSE_CHEST_UNSET) + " ";
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "mushroomcow (--variant [variant])",
            desc = "",
            strictArguments = true,
            modifiers = { "mushroomcow", "mooshroom" },
            min = 1,
            max = 1,
            permission = "citizens.npc.mushroomcow")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "mooshroom")
    public static void mushroomcow(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag("variant") MushroomCow.MushroomType variant) throws CommandException {
        requireInput(args, "", "variant");
        MushroomCowTrait trait = npc.getOrAddTrait(MushroomCowTrait.class);
        boolean hasArg = false;
        if (args.hasValueFlag("variant")) {
            if (variant == null) {
                Messaging.sendErrorTr(sender, Messages.INVALID_MUSHROOM_COW_VARIANT,
                        Util.listValuesPretty(MushroomCow.MushroomType.values()));
                return;
            }
            trait.setVariant(variant);
            Messaging.sendTr(sender, Messages.MUSHROOM_COW_VARIANT_SET, npc.getName(), variant);
            hasArg = true;
        }
        if (!hasArg)
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "panda --gene (main gene) --hiddengene (hidden gene) -e(ating) -s(itting) -n (sneezing) -r(olling)",
            desc = "",
            strictArguments = true,
            modifiers = { "panda" },
            flags = "srne",
            min = 1,
            max = 1,
            permission = "citizens.npc.panda")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "panda")
    public static void panda(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("gene") Panda.Gene gene,
            @Flag("hiddengene") Panda.Gene hiddengene) throws CommandException {
        requireInput(args, "esrn", "gene", "hiddengene");
        PandaTrait trait = npc.getOrAddTrait(PandaTrait.class);
        String output = "";
        if (args.hasValueFlag("gene")) {
            if (gene == null)
                throw new CommandUsageException(Messages.INVALID_PANDA_GENE,
                        Util.listValuesPretty(Panda.Gene.values()));
            trait.setMainGene(gene);
            output += ' ' + Messaging.tr(Messages.PANDA_MAIN_GENE_SET, args.getFlag("gene"));
        }
        if (args.hasValueFlag("hiddengene")) {
            if (hiddengene == null)
                throw new CommandUsageException(Messages.INVALID_PANDA_GENE,
                        Util.listValuesPretty(Panda.Gene.values()));
            trait.setHiddenGene(hiddengene);
            output += ' ' + Messaging.tr(Messages.PANDA_HIDDEN_GENE_SET, hiddengene);
        }
        if (args.hasFlag('e')) {
            boolean isEating = !trait.isEating();
            trait.setEating(isEating);
            output += ' '
                    + Messaging.tr(isEating ? Messages.PANDA_EATING : Messages.PANDA_STOPPED_EATING, npc.getName());
        }
        if (args.hasFlag('s')) {
            boolean isSitting = !trait.isSitting();
            trait.setSitting(isSitting);
            output += ' '
                    + Messaging.tr(isSitting ? Messages.PANDA_SITTING : Messages.PANDA_STOPPED_SITTING, npc.getName());
        }
        if (args.hasFlag('r')) {
            boolean isRolling = !trait.isRolling();
            trait.setRolling(isRolling);
            output += ' '
                    + Messaging.tr(isRolling ? Messages.PANDA_ROLLING : Messages.PANDA_STOPPED_ROLLING, npc.getName());
        }
        if (args.hasFlag('n')) {
            boolean isSneezing = !trait.isSneezing();
            trait.setSneezing(isSneezing);
            output += ' ' + Messaging.tr(isSneezing ? Messages.PANDA_SNEEZING : Messages.PANDA_STOPPED_SNEEZING,
                    npc.getName());
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "parrot (--variant variant)",
            desc = "",
            strictArguments = true,
            modifiers = { "parrot" },
            min = 1,
            max = 1,
            permission = "citizens.npc.parrot")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "parrot")
    public static void parrot(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("variant") Parrot.Variant variant)
            throws CommandException {
        ParrotTrait trait = npc.getOrAddTrait(ParrotTrait.class);
        String output = "";
        if (args.hasValueFlag("variant")) {
            if (variant == null)
                throw new CommandException(Messages.INVALID_PARROT_VARIANT, Util.listValuesPretty(Parrot.Variant.values()));
            trait.setVariant(variant);
            output += Messaging.tr(Messages.PARROT_VARIANT_SET, Util.prettyEnum(variant));
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "polarbear (-r)",
            desc = "",
            strictArguments = true,
            modifiers = { "polarbear" },
            min = 1,
            max = 1,
            flags = "r",
            permission = "citizens.npc.polarbear")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "polar_bear" })
    public static void polarbear(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        requireInput(args, "r");
        PolarBearTrait trait = npc.getOrAddTrait(PolarBearTrait.class);
        String output = "";
        if (args.hasFlag('r')) {
            trait.setRearing(!trait.isRearing());
            output += Messaging.tr(
                    trait.isRearing() ? Messages.POLAR_BEAR_REARING : Messages.POLAR_BEAR_STOPPED_REARING,
                    npc.getName());
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output);
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "pufferfish (--state state)",
            desc = "",
            strictArguments = true,
            modifiers = { "pufferfish" },
            min = 1,
            max = 1,
            permission = "citizens.npc.pufferfish")
    @Requirements(selected = true, ownership = true, types = "pufferfish")
    public static void pufferfish(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("state") Integer state)
            throws CommandException {
        PufferFishTrait trait = npc.getOrAddTrait(PufferFishTrait.class);
        String output = "";
        if (state != null) {
            state = Math.min(Math.max(state, 0), 2);
            trait.setPuffState(state);
            output += Messaging.tr(Messages.PUFFERFISH_STATE_SET, state);
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "sniffer (--state [state])",
            desc = "",
            strictArguments = true,
            modifiers = { "sniffer" },
            min = 1,
            max = 1,
            permission = "citizens.npc.sniffer")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "sniffer")
    public static void sniffer(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("state") Sniffer.State state)
            throws CommandException {
        requireInput(args, "", "state");
        SnifferTrait trait = npc.getOrAddTrait(SnifferTrait.class);
        String output = "";
        if (state != null) {
            trait.setState(state);
            output += ' ' + Messaging.tr(Messages.SNIFFER_STATE_SET, npc.getName(), state);
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "snowman (-d[erp]) (-f[orm snow])",
            desc = "",
            strictArguments = true,
            modifiers = { "snowman", "snowgolem" },
            min = 1,
            max = 1,
            flags = "df",
            permission = "citizens.npc.snowman")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "snow_golem")
    public static void snowman(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        requireInput(args, "df");
        SnowmanTrait trait = npc.getOrAddTrait(SnowmanTrait.class);
        boolean hasArg = false;
        if (args.hasFlag('d')) {
            boolean isDerp = !trait.isDerp();
            trait.setDerp(isDerp);
            Messaging.sendTr(sender, isDerp ? Messages.SNOWMAN_DERP_SET : Messages.SNOWMAN_DERP_STOPPED, npc.getName());
            hasArg = true;
        }
        if (args.hasFlag('f')) {
            trait.setFormSnow(!trait.shouldFormSnow());
            Messaging.sendTr(sender,
                    trait.shouldFormSnow() ? Messages.SNOWMAN_FORM_SNOW_SET : Messages.SNOWMAN_FORM_SNOW_STOPPED,
                    npc.getName());
            hasArg = true;
        }
        if (!hasArg)
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "tropicalfish (--body color) (--pattern pattern) (--patterncolor color)",
            desc = "",
            strictArguments = true,
            modifiers = { "tropicalfish" },
            min = 1,
            max = 1,
            permission = "citizens.npc.tropicalfish")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "tropical_fish")
    public static void tropicalfish(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("body") DyeColor body,
            @Flag("patterncolor") DyeColor patterncolor, @Flag("pattern") TropicalFish.Pattern pattern) throws CommandException {
        requireInput(args, "", "body", "patterncolor", "pattern");
        TropicalFishTrait trait = npc.getOrAddTrait(TropicalFishTrait.class);
        String output = "";
        if (args.hasValueFlag("body")) {
            if (body == null)
                throw new CommandException(Messages.INVALID_TROPICALFISH_COLOR,
                        Util.listValuesPretty(DyeColor.values()));
            trait.setBodyColor(body);
            output += Messaging.tr(Messages.TROPICALFISH_BODY_COLOR_SET, Util.prettyEnum(body));
        }
        if (args.hasValueFlag("patterncolor")) {
            if (patterncolor == null)
                throw new CommandException(Messages.INVALID_TROPICALFISH_COLOR,
                        Util.listValuesPretty(DyeColor.values()));
            trait.setPatternColor(patterncolor);
            output += Messaging.tr(Messages.TROPICALFISH_PATTERN_COLOR_SET, Util.prettyEnum(patterncolor));
        }
        if (args.hasValueFlag("pattern")) {
            if (pattern == null)
                throw new CommandException(Messages.INVALID_TROPICALFISH_PATTERN,
                        Util.listValuesPretty(TropicalFish.Pattern.values()));
            trait.setPattern(pattern);
            output += Messaging.tr(Messages.TROPICALFISH_PATTERN_SET, Util.prettyEnum(pattern));
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output);
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "villager (--level level) (--type type) (--profession profession) -s(hake head)",
            desc = "",
            strictArguments = true,
            modifiers = { "villager" },
            min = 1,
            max = 1,
            flags = "s",
            permission = "citizens.npc.villager")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "villager")
    public static void villager(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag(value = "profession", validator = ProfessionValue.class, completionsProvider = Professions.class) net.minecraft.world.entity.npc.VillagerProfession profession, @Flag(value = "type", validator = VillagerTypeValue.class, completionsProvider = VillagerTypes.class) VillagerType type, @Flag("level") Integer level)
            throws CommandException {
        if (args.hasFlag('s') && !(npc.getCosmeticEntity() instanceof Villager))
            throw new CommandException(CommandMessages.MUST_BE_SPAWNED);
        requireInput(args, "s", "profession", "type", "level");
        if (level != null && level < 0) throw new CommandUsageException();
        VillagerTrait trait = npc.getOrAddTrait(VillagerTrait.class);
        String output = "";
        if (level != null) {
            if (level < 0)
                throw new CommandUsageException();
            trait.setLevel(level);
            output += " " + Messaging.tr(Messages.VILLAGER_LEVEL_SET, level);
        }
        if (args.hasValueFlag("type")) {
            if (type == null)
                throw new CommandException(Messages.INVALID_VILLAGER_TYPE,
                        registryNames(BuiltInRegistries.VILLAGER_TYPE));
            trait.setType(type);
            output += " " + Messaging.tr(Messages.VILLAGER_TYPE_SET, args.getFlag("type"));
        }
        if (args.hasValueFlag("profession")) {
            if (profession == null)
                throw new CommandException(Messages.INVALID_PROFESSION, args.getFlag("profession"),
                        registryNames(BuiltInRegistries.VILLAGER_PROFESSION));
            npc.getOrAddTrait(VillagerProfession.class).setProfession(profession);
            output += " " + Messaging.tr(Messages.PROFESSION_SET, npc.getName(), args.getFlag("profession"));
        }
        if (args.hasFlag('s')) {
            if (!(npc.getCosmeticEntity() instanceof Villager villager)) throw new CommandException(CommandMessages.MUST_BE_SPAWNED);
            villager.setUnhappyCounter(40);
            output += " " + Messaging.tr(ENTITY_ACTION, npc.getName(), "shake");
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "areaeffectcloud --color [color] --duration [duration] --radius [radius] --radius_per_tick [radius] --particle [particle]",
            desc = "",
            strictArguments = true,
            modifiers = { "areaeffectcloud" },
            min = 1,
            max = 1,
            permission = "citizens.npc.areaeffectcloud")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "area_effect_cloud" })
    public static void areaeffectcloud(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag("duration") Integer duration, @Flag("radius") Float radius,
            @Flag("radius_per_tick") Float radiusPerTick, @Flag(value = "color", validator = PackedColorValue.class) Integer color,
            @Flag(value = "potiontype", validator = PotionValue.class, completionsProvider = Potions.class) Holder<Potion> type, @Flag(value = "particle", validator = ParticleValue.class, completionsProvider = Particles.class) ParticleOptions particle) throws CommandException {
        if (duration != null && duration < 0) throw new CommandException(CommandMessages.INVALID_VALUE, "--duration", duration);
        if (radius != null && radius < 0) throw new CommandException(CommandMessages.INVALID_VALUE, "--radius", radius);
        AreaEffectCloudTrait trait = npc.getOrAddTrait(AreaEffectCloudTrait.class);
        String output = "";
        if (radius != null) {
            trait.setRadius(radius);
        }
        if (radiusPerTick != null) {
            trait.setRadiusPerTick(radiusPerTick);
        }
        if (duration != null) {
            trait.setDuration(duration);
        }
        if (color != null) {
            trait.setColor(color);
        }
        if (type != null) {
            trait.setPotionType(type);
        }
        if (particle != null) {
            trait.setParticle(particle);
        }
        trait.onSpawn();
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "boat --type [type]",
            desc = "",
            strictArguments = true,
            modifiers = { "boat" },
            min = 1,
            max = 1,
            permission = "citizens.npc.boat")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "boat", "chest_boat" })
    public static void boat(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag("type") Boat.Type type)
            throws CommandException {
        if (type == null)
            throw new CommandUsageException();
        npc.getOrAddTrait(BoatTrait.class).setType(type);
        Messaging.sendTr(sender, Messages.BOAT_TYPE_SET, type);
    }

    @Command(
            aliases = { "npc" },
            usage = "enderdragon --phase [phase] --destroywalls [true|false]",
            desc = "",
            strictArguments = true,
            modifiers = { "enderdragon" },
            min = 1,
            max = 1,
            permission = "citizens.npc.enderdragon")
    @Requirements(ownership = true, selected = true, cosmeticTypes = "ender_dragon")
    public static void enderdragon(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag("phase") EnderDragonTrait.DragonPhase phase, @Flag("destroywalls") Boolean destroyWalls)
            throws CommandException {
        EnderDragonTrait trait = npc.getOrAddTrait(EnderDragonTrait.class);
        if (phase != null) {
            trait.setPhase(phase);
        }
        if (destroyWalls != null) {
            trait.setDestroyWalls(destroyWalls);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "phantom (--size size)",
            desc = "",
            strictArguments = true,
            modifiers = { "phantom" },
            min = 1,
            max = 1,
            permission = "citizens.npc.phantom")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "phantom")
    public static void phantom(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("size") Integer size)
            throws CommandException {
        requireInput(args, "", "size");
        if (size != null && size <= 0) throw new CommandUsageException();
        PhantomTrait trait = npc.getOrAddTrait(PhantomTrait.class);
        String output = "";
        if (size != null) {
            if (size <= 0)
                throw new CommandUsageException();
            trait.setSize(size);
            output += Messaging.tr(Messages.PHANTOM_STATE_SET, size);
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output);
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "piglin (--dancing [true|false])",
            desc = "",
            strictArguments = true,
            modifiers = { "piglin" },
            min = 1,
            max = 1,
            permission = "citizens.npc.piglin")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "piglin" })
    public static void piglin(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("dancing") Boolean dancing)
            throws CommandException {
        requireInput(args, "", "dancing");
        PiglinTrait trait = npc.getOrAddTrait(PiglinTrait.class);
        boolean hasArg = false;
        if (dancing != null) {
            trait.setDancing(dancing);
            Messaging.sendTr(sender, dancing ? Messages.PIGLIN_DANCING_SET : Messages.PIGLIN_DANCING_UNSET,
                    npc.getName());
            hasArg = true;
        }
        if (!hasArg)
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "shulker (--peek [peek] --color [color])",
            desc = "",
            strictArguments = true,
            modifiers = { "shulker" },
            min = 1,
            max = 1,
            permission = "citizens.npc.shulker")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "shulker" })
    public static void shulker(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("peek") Integer peek,
            @Flag("color") DyeColor color) throws CommandException {
        requireInput(args, "", "peek", "color");
        if (peek != null && (peek < 0 || peek > 100)) throw new CommandException(CommandMessages.INVALID_VALUE, "--peek", peek);
        ShulkerTrait trait = npc.getOrAddTrait(ShulkerTrait.class);
        boolean hasArg = false;
        if (peek != null) {
            trait.setPeek(peek);
            Messaging.sendTr(sender, Messages.SHULKER_PEEK_SET, npc.getName(), peek);
            hasArg = true;
        }
        if (args.hasValueFlag("color")) {
            if (color == null) {
                Messaging.sendErrorTr(sender, Messages.INVALID_SHULKER_COLOR, Util.listValuesPretty(DyeColor.values()));
                return;
            }
            trait.setColor(color);
            Messaging.sendTr(sender, Messages.SHULKER_COLOR_SET, npc.getName(), Util.prettyEnum(color));
            hasArg = true;
        }
        if (!hasArg)
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "spellcaster (--spell spell)",
            desc = "",
            strictArguments = true,
            modifiers = { "spellcaster" },
            min = 1,
            max = 1,
            flags = "d",
            permission = "citizens.npc.spellcaster")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "evoker", "illusioner" })
    public static void spellcaster(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("spell") SpellcasterIllager.IllagerSpell spell)
            throws CommandException {
        requireInput(args, "", "spell");
        SpellcasterTrait trait = npc.getOrAddTrait(SpellcasterTrait.class);
        String output = "";
        if (spell != null) {
            trait.setSpell(spell);
            output += Messaging.tr(Messages.SPELL_SET, spell);
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "vex (--charging [charging])",
            desc = "",
            strictArguments = true,
            modifiers = { "vex" },
            min = 1,
            max = 1,
            permission = "citizens.npc.vex")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "vex" })
    public static void vex(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("charging") Boolean charging)
            throws CommandException {
        requireInput(args, "", "charging");
        VexTrait trait = npc.getOrAddTrait(VexTrait.class);
        boolean hasArg = false;
        if (charging != null) {
            trait.setCharging(charging);
            Messaging.sendTr(sender, Messages.VEX_CHARGING_SET, npc.getName(), charging);
            hasArg = true;
        }
        if (!hasArg)
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "warden dig|emerge|roar|anger [entity uuid/player name] [anger]",
            desc = "",
            strictArguments = true,
            modifiers = { "warden" },
            min = 1,
            max = 4,
            permission = "citizens.npc.warden")
    @Requirements(selected = true, ownership = true, cosmeticTypes = "warden")
    public static void warden(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completions = { "anger", "dig", "emerge", "roar" }) String command, @Arg(2) String player,
            @Arg(3) Integer anger) throws CommandException {
        if (command == null) throw new CommandUsageException();
        String output = "";
        if (command.equalsIgnoreCase("anger")) {
            if (anger == null || player == null || anger < 0)
                throw new CommandUsageException();
            Entity entity = null;
            try {
                UUID uuid = UUID.fromString(player);
                entity = EntityUtil.getEntity(uuid);
            } catch (IllegalArgumentException iae) {
                entity = sender.getServer().getPlayerList().getPlayerByName(player);
            }
            if (entity != null) {
                npc.getOrAddTrait(WardenTrait.class).addAnger(entity, anger);
                output = Messaging.tr(Messages.WARDEN_ANGER_ADDED, entity, anger);
            }
        } else if (command.equalsIgnoreCase("dig")) {
            setWardenPose(npc, Pose.DIGGING);
            output = Messaging.tr(Messages.WARDEN_POSE_SET, npc.getName(), "dig");
        } else if (command.equalsIgnoreCase("emerge")) {
            setWardenPose(npc, Pose.EMERGING);
            output = Messaging.tr(Messages.WARDEN_POSE_SET, npc.getName(), "emerge");
        } else if (command.equalsIgnoreCase("roar")) {
            setWardenPose(npc, Pose.ROARING);
            output = Messaging.tr(Messages.WARDEN_POSE_SET, npc.getName(), "roar");
        }
        if (!output.isEmpty()) {
            Messaging.send(sender, output.trim());
        } else
            throw new CommandUsageException();
    }

    private static void requireInput(CommandContext args, String flags, String... values) throws CommandUsageException {
        if (flags.chars().anyMatch(flag -> args.hasFlag((char) flag)) || Arrays.stream(values).anyMatch(args::hasValueFlag)) return;
        throw new CommandUsageException();
    }

    private static String registryNames(Registry<?> registry) {
        return Util.listValuesPretty(registry.keySet().toArray());
    }

    private static ResourceLocation registryId(Registry<?> registry, String input) throws CommandException {
        ResourceLocation id = ResourceLocation.tryParse(input.trim().toLowerCase(Locale.ROOT));
        if (id == null || !registry.containsKey(id))
            throw new CommandException(CommandMessages.INVALID_VALUE, registry.key().location(), input);
        return id;
    }

    private abstract static class RegistryNames implements CompletionsProvider {
        private final Registry<?> registry;
        RegistryNames(Registry<?> registry) { this.registry = registry; }
        public Collection<String> getCompletions(CommandContext args, CommandSourceStack sender, NPC npc) {
            return registry.keySet().stream().map(id -> id.getNamespace().equals("minecraft") ? id.getPath() : id.toString()).toList();
        }
    }
    public static class CatTypes extends RegistryNames { public CatTypes() { super(BuiltInRegistries.CAT_VARIANT); } }
    public static class FrogVariants extends RegistryNames { public FrogVariants() { super(BuiltInRegistries.FROG_VARIANT); } }
    public static class VillagerTypes extends RegistryNames { public VillagerTypes() { super(BuiltInRegistries.VILLAGER_TYPE); } }
    public static class Professions extends RegistryNames { public Professions() { super(BuiltInRegistries.VILLAGER_PROFESSION); } }
    public static class Potions extends RegistryNames { public Potions() { super(BuiltInRegistries.POTION); } }
    public static class Particles extends RegistryNames { public Particles() { super(BuiltInRegistries.PARTICLE_TYPE); } }

    public static class CatTypeValue implements FlagValidator<Holder<CatVariant>> {
        public Holder<CatVariant> validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) throws CommandException {
            return BuiltInRegistries.CAT_VARIANT.getHolder(registryId(BuiltInRegistries.CAT_VARIANT, input)).orElseThrow();
        }
    }
    public static class FrogVariantValue implements FlagValidator<Holder<FrogVariant>> {
        public Holder<FrogVariant> validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) throws CommandException {
            return BuiltInRegistries.FROG_VARIANT.getHolder(registryId(BuiltInRegistries.FROG_VARIANT, input)).orElseThrow();
        }
    }
    public static class VillagerTypeValue implements FlagValidator<VillagerType> {
        public VillagerType validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) throws CommandException {
            return BuiltInRegistries.VILLAGER_TYPE.get(registryId(BuiltInRegistries.VILLAGER_TYPE, input));
        }
    }
    public static class ProfessionValue implements FlagValidator<net.minecraft.world.entity.npc.VillagerProfession> {
        public net.minecraft.world.entity.npc.VillagerProfession validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) throws CommandException {
            var value = VillagerProfession.parse(input);
            if (value == null) throw new CommandException(Messages.INVALID_PROFESSION, input, registryNames(BuiltInRegistries.VILLAGER_PROFESSION));
            return value;
        }
    }
    public static class PotionValue implements FlagValidator<Holder<Potion>> {
        public Holder<Potion> validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) throws CommandException {
            return BuiltInRegistries.POTION.getHolder(registryId(BuiltInRegistries.POTION, input)).orElseThrow();
        }
    }
    public static class ParticleValue implements FlagValidator<ParticleOptions> {
        public ParticleOptions validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) throws CommandException {
            Object value = BuiltInRegistries.PARTICLE_TYPE.get(registryId(BuiltInRegistries.PARTICLE_TYPE, input));
            if (!(value instanceof ParticleOptions particle))
                throw new CommandException(CommandMessages.INVALID_VALUE, "particle without parameters", input);
            return particle;
        }
    }
    public static class ArmadilloStateValue implements FlagValidator<Armadillo.ArmadilloState> {
        public Armadillo.ArmadilloState validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) throws CommandException {
            var state = ArmadilloTrait.parseStrict(input);
            if (state == null) throw new CommandException(CommandMessages.INVALID_VALUE, "state", input);
            return state;
        }
    }
    public static class ArmadilloStates implements CompletionsProvider {
        public Collection<String> getCompletions(CommandContext args, CommandSourceStack sender, NPC npc) { return ArmadilloTrait.stateNames(); }
    }
    public static class PackedColorValue implements FlagValidator<Integer> {
        public Integer validate(CommandContext args, CommandSourceStack sender, NPC npc, String input) throws CommandException {
            try {
                if (!input.contains(",")) {
                    int rgb = Integer.decode(input);
                    if (rgb < 0 || rgb > 0xFFFFFF) throw new IllegalArgumentException("RGB out of range");
                    return 0xFF000000 | rgb;
                }
                int[] channels = Arrays.stream(input.split(",", -1)).map(String::trim).mapToInt(Integer::parseInt).toArray();
                if (channels.length != 3 && channels.length != 4) throw new IllegalArgumentException("Expected RGB or RGBA");
                for (int channel : channels) if (channel < 0 || channel > 255) throw new IllegalArgumentException("Channel out of range");
                int alpha = channels.length == 4 ? channels[3] : 255;
                return alpha << 24 | channels[0] << 16 | channels[1] << 8 | channels[2];
            } catch (IllegalArgumentException failure) {
                throw new CommandException(CommandMessages.INVALID_VALUE, "color", input);
            }
        }
    }

    private static void setWardenPose(NPC npc, Pose pose) throws CommandException {
        if (!(npc.getCosmeticEntity() instanceof Warden warden)) throw new CommandException(CommandMessages.MUST_BE_SPAWNED);
        if (warden.getPose() == pose) return;
        warden.setPose(pose);
        int duration;
        if (pose == Pose.DIGGING) {
            warden.playSound(net.minecraft.sounds.SoundEvents.WARDEN_DIG, 5, 1);
            return;
        } else if (pose == Pose.EMERGING) {
            warden.playSound(net.minecraft.sounds.SoundEvents.WARDEN_EMERGE, 5, 1);
            duration = 134;
        } else {
            warden.playSound(net.minecraft.sounds.SoundEvents.WARDEN_ROAR, 3, 1);
            duration = 84;
        }
        net.citizensnpcs.api.CitizensAPI.getScheduler().runEntityTaskLater(warden, () -> {
            if (npc.getCosmeticEntity() == warden && !warden.isRemoved() && warden.getPose() == pose) warden.setPose(Pose.STANDING);
        }, duration);
    }

}
