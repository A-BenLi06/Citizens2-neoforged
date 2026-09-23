package net.citizensnpcs.commands;

import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.api.npc.templates.Template;
import net.citizensnpcs.commands.gui.NPCConfigurator;
import java.io.File;
import java.io.IOException;
import net.citizensnpcs.trait.BehaviorTrait;
import net.citizensnpcs.trait.DisguiseTrait;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.npc.ai.tree.NPCExpressionScope;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.api.npc.BlockBreaker;
import net.citizensnpcs.api.npc.BlockBreaker.BlockBreakerConfiguration;
import net.citizensnpcs.api.ai.tree.StatusMapper;
import net.citizensnpcs.editor.Editor;
import net.citizensnpcs.trait.waypoint.Waypoints;
import net.citizensnpcs.trait.waypoint.WanderWaypointProvider;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.block.state.BlockState;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.trait.HologramTrait.HologramRenderer;
import net.citizensnpcs.trait.RotationTrait;
import net.citizensnpcs.trait.RotationTrait.RotationParams;
import net.citizensnpcs.trait.versioned.TextDisplayTrait;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.primitives.Ints;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.citizensnpcs.api.gui.InventoryMenu;
import net.citizensnpcs.trait.CommandTrait;
import net.citizensnpcs.trait.CommandTrait.CommandTraitError;
import net.citizensnpcs.trait.CommandTrait.ExecutionMode;
import net.citizensnpcs.trait.CommandTrait.ItemRequirementGUI;
import net.citizensnpcs.trait.CommandTrait.NPCCommandBuilder;
import net.minecraft.server.MinecraftServer;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.trait.Controllable;
import net.citizensnpcs.trait.Controllable.BuiltInControls;
import net.minecraft.core.registries.BuiltInRegistries;
import net.citizensnpcs.trait.MirrorTrait;
import net.citizensnpcs.trait.ShopTrait;
import net.citizensnpcs.trait.shop.NPCShop;
import net.citizensnpcs.trait.shop.NPCShopItem;
import net.citizensnpcs.trait.shop.StoredShops;
import net.minecraft.world.entity.LivingEntity;
import net.citizensnpcs.trait.PausePathfindingTrait;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.npc.skin.SkinPacketTracker;
import net.minecraft.world.entity.Mob;
import net.citizensnpcs.trait.BatTrait;
import net.citizensnpcs.trait.EnderCrystalTrait;
import net.citizensnpcs.trait.EndermanTrait;
import net.citizensnpcs.trait.OcelotModifiers;
import net.citizensnpcs.trait.PaintingTrait;
import net.citizensnpcs.trait.RabbitType;
import net.citizensnpcs.trait.WitherTrait;
import net.citizensnpcs.trait.WolfModifiers;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.citizensnpcs.trait.ArmorStandTrait;
import net.citizensnpcs.trait.EntityPoseTrait;
import net.citizensnpcs.trait.SitTrait;
import net.citizensnpcs.trait.SkinLayers;
import net.citizensnpcs.util.PlayerAnimation;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.citizensnpcs.commands.history.CommandHistory;
import net.citizensnpcs.commands.history.CreateNPCHistoryItem;
import net.citizensnpcs.commands.history.RemoveNPCHistoryItem;
import net.citizensnpcs.trait.AttributeTrait;
import net.citizensnpcs.trait.GameModeTrait;
import net.citizensnpcs.trait.ScaledMaxHealthTrait;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.level.GameType;
import net.citizensnpcs.trait.HorseModifiers;
import net.citizensnpcs.api.util.ItemStorage;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.horse.Markings;
import net.minecraft.world.entity.animal.horse.Variant;
import net.minecraft.world.item.ItemStack;
import net.citizensnpcs.trait.DropsTrait;
import net.citizensnpcs.trait.ForcefieldTrait;
import net.citizensnpcs.trait.BoundingBoxTrait;
import net.citizensnpcs.api.util.EntityDim;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot;
import net.minecraft.world.phys.Vec3;
import net.citizensnpcs.trait.ItemFrameTrait;
import net.citizensnpcs.trait.ItemFrameTrait.FrameRotation;
import net.minecraft.core.Direction;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.Arg;
import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.command.Flag;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.command.exception.CommandUsageException;
import net.citizensnpcs.api.command.exception.NoPermissionsException;
import net.citizensnpcs.api.event.DespawnReason;
import java.time.Duration;

import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.ai.TeleportStuckAction;
import net.citizensnpcs.api.ai.speech.SpeechContext;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Paginator;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.npc.EntityControllers;
import net.citizensnpcs.trait.Age;
import net.citizensnpcs.trait.ChunkTicketTrait;
import net.citizensnpcs.trait.FollowTrait;
import net.citizensnpcs.trait.Gravity;
import net.citizensnpcs.trait.HomeTrait;
import net.citizensnpcs.trait.HomeTrait.ReturnStrategy;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.MountTrait;
import net.citizensnpcs.trait.Poses;
import net.citizensnpcs.trait.Powered;
import net.citizensnpcs.trait.Saddle;
import net.citizensnpcs.trait.ScoreboardTrait;
import net.citizensnpcs.trait.SheepTrait;
import net.citizensnpcs.trait.SkinTrait;
import net.citizensnpcs.trait.SlimeSize;
import net.citizensnpcs.trait.SneakTrait;
import net.citizensnpcs.trait.TargetableTrait;
import net.citizensnpcs.trait.VillagerProfession;
import net.citizensnpcs.api.util.Durations;
import net.citizensnpcs.util.Anchor;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.Util;
import net.citizensnpcs.util.StringHelper;
import net.citizensnpcs.api.util.TeleportCause;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.DyeColor;

/**
 * {@code /npc} — the main command surface.
 * <p>
 * The methods keep upstream's annotations, sub-command names, flags and message keys, so an existing language file and a
 * user's muscle memory both still work. What has changed is only the platform: the sender is a
 * {@link CommandSourceStack}, entity types are registry objects, and the two Bukkit-only create flags ({@code --item}
 * needs an item parser, {@code -p} needs packet NPCs) are absent because their subsystems are.
 * <p>
 * This is a working subset rather than all of upstream's roughly ninety sub-commands: the ones here cover the NPC
 * lifecycle and the traits that have landed. Anything else is still reachable with {@code /trait add}, which attaches any
 * registered trait by name, so no trait is unusable for want of a command.
 */
@Requirements(selected = true, ownership = true)
public class NPCCommands {
    /**
     * Undo history, shared by every command that creates or destroys an NPC. Built lazily for the same reason the
     * temporary registry is looked up late: the API implementation does not exist while commands are registered.
     */
    private static CommandHistory history;

    private static CommandHistory history() {
        if (history == null) {
            history = new CommandHistory(CitizensAPI.getDefaultNPCSelector());
        }
        return history;
    }

    /**
     * Command classes are registered while the mod is constructed, which is before the API implementation exists, so
     * the temporary registry is looked up when it is actually needed rather than held.
     */
    private static NPCRegistry temporaryRegistry() {
        return CitizensAPI.getTemporaryNPCRegistry();
    }

    @Command(
            aliases = { "npc" },
            usage = "age [age] (-l(ock) -u(nlock))",
            desc = "",
            flags = "lu",
            modifiers = { "age" },
            min = 1,
            max = 2,
            permission = "citizens.npc.age")
    @Requirements(selected = true, ownership = true, livingEntity = true)
    public void age(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        Age trait = npc.getOrAddTrait(Age.class);
        if (args.hasFlag('l') || args.hasFlag('u')) {
            trait.setLocked(args.hasFlag('l'));
            Messaging.sendTr(sender, args.hasFlag('l') ? Messages.AGE_LOCKED : Messages.AGE_UNLOCKED);
            if (args.argsLength() == 1)
                return;
        }
        if (args.argsLength() <= 1) {
            trait.setAge(0);
            Messaging.sendTr(sender, Messages.AGE_SET_NORMAL, npc.getName());
            return;
        }
        String raw = args.getString(1);
        if (raw.equalsIgnoreCase("baby")) {
            trait.setAge(-24000);
            Messaging.sendTr(sender, Messages.AGE_SET_BABY, npc.getName());
            return;
        }
        if (raw.equalsIgnoreCase("adult")) {
            trait.setAge(0);
            Messaging.sendTr(sender, Messages.AGE_SET_ADULT, npc.getName());
            return;
        }
        int age;
        try {
            age = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw new CommandException(Messages.INVALID_AGE);
        }
        trait.setAge(age);
        Messaging.sendTr(sender, Messages.AGE_SET_NORMAL, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "anchor (--save [name]|--assume [name]|--remove [name]) (-a)",
            desc = "",
            flags = "a",
            modifiers = { "anchor" },
            min = 1,
            max = 3,
            permission = "citizens.npc.anchor")
    public void anchor(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("save") String save,
            @Flag("assume") String assume, @Flag("remove") String remove) throws CommandException {
        net.citizensnpcs.trait.Anchors trait = npc.getOrAddTrait(net.citizensnpcs.trait.Anchors.class);
        if (save != null) {
            if (trait.getAnchor(save) != null)
                throw new CommandException(Messages.ANCHOR_ALREADY_EXISTS, save);
            Location at = args.getSenderLocation();
            if (at == null)
                throw new CommandException(CommandMessages.MUST_BE_INGAME);
            trait.addAnchor(save, at);
            Messaging.sendTr(sender, Messages.ANCHOR_ADDED);
            return;
        }
        if (remove != null) {
            Anchor anchor = trait.getAnchor(remove);
            if (anchor == null)
                throw new CommandException(Messages.ANCHOR_MISSING, remove);
            trait.removeAnchor(anchor);
            Messaging.sendTr(sender, Messages.ANCHOR_REMOVED);
            return;
        }
        if (assume != null) {
            Anchor anchor = trait.getAnchor(assume);
            if (anchor == null || anchor.getLocation() == null)
                throw new CommandException(Messages.ANCHOR_MISSING, assume);
            npc.teleport(anchor.getLocation(), TeleportCause.COMMAND);
            return;
        }
        Paginator paginator = new Paginator().header("Anchors").console(sender.getPlayer() == null);
        for (Anchor anchor : trait.getAnchors()) {
            paginator.addLine("<e>- " + anchor.getName());
        }
        if (!paginator.sendPage(sender, args.getInteger(1, 1)))
            throw new CommandException(CommandMessages.COMMAND_PAGE_MISSING, args.getInteger(1, 1));
    }

    @Command(
            aliases = { "npc" },
            usage = "chunkload",
            desc = "",
            modifiers = { "chunkload", "cload" },
            min = 1,
            max = 1,
            permission = "citizens.npc.chunkload")
    public void chunkload(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        boolean keep = !npc.data().get(NPC.Metadata.KEEP_CHUNK_LOADED, Setting.KEEP_CHUNKS_LOADED.asBoolean());
        npc.data().setPersistent(NPC.Metadata.KEEP_CHUNK_LOADED, keep);
        if (keep) {
            npc.getOrAddTrait(ChunkTicketTrait.class);
        }
        Messaging.sendTr(sender, keep ? Messages.CHUNKLOAD_SET : Messages.CHUNKLOAD_UNSET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "collidable",
            desc = "",
            modifiers = { "collidable", "pushable" },
            min = 1,
            max = 1,
            permission = "citizens.npc.collidable")
    public void collidable(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        boolean collidable = !npc.data().get(NPC.Metadata.COLLIDABLE, !npc.isProtected());
        npc.data().setPersistent(NPC.Metadata.COLLIDABLE, collidable);
        npc.getOrAddTrait(ScoreboardTrait.class);
        Messaging.sendTr(sender, collidable ? Messages.COLLIDABLE_SET : Messages.COLLIDABLE_UNSET, npc.getName());
    }


    @Command(
            aliases = { "npc" },
            usage = "command (add [command] | execute [player UUID] [hand] | remove [id|all] | permissions [permissions] (duration) | sequential | cycle | random | forgetplayer (uuid) | clearerror [type] (name|uuid) | errormessage [type] [msg] | rememberlastused [true|false] | cost [cost] | expcost [cost] | itemcost (id) | hideerrors) (-s(hift)) (-l[eft]/-r[ight]) (-p[layer] -o[p] -n[pc]), --cooldown --gcooldown [seconds] --delay [ticks] --permissions [perms] --n [max # of uses] --gn [max # of global uses]",
            desc = "",
            modifiers = { "command", "cmd" },
            min = 1,
            flags = "sproln",
            permission = "citizens.npc.command")
    public void command(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag(value = { "permissions", "permission" }) String permissions,
            @Flag(value = "cost", defValue = "-1") Double cost,
            @Flag(value = "expcost", defValue = "-1") Integer experienceCost,
            @Flag(value = "cooldown", defValue = "0") Duration cooldown,
            @Flag(value = "gcooldown", defValue = "0") Duration gcooldown, @Flag(value = "n", defValue = "-1") int n,
            @Flag(value = "gn", defValue = "-1") int gn, @Flag(value = "delay", defValue = "0") Duration delay,
            @Arg(
                    value = 1,
                    completions = { "add", "execute", "remove", "permissions", "rememberlastused", "sequential",
                            "cycle", "random", "forgetplayer", "hideerrors", "errormessage", "clearerror", "expcost",
                            "itemcost", "cost" }) String action)
            throws CommandException {
        CommandTrait commands = npc.getOrAddTrait(CommandTrait.class);
        if (args.argsLength() == 1) {
            commands.describe(sender);
        } else if (action.equalsIgnoreCase("add")) {
            if (args.argsLength() == 2)
                throw new CommandUsageException();
            if (args.hasFlag('o') && !PermissionUtil.hasPermission(sender, "citizens.admin"))
                throw new NoPermissionsException();

            String command = args.getJoinedStrings(2);
            CommandTrait.Hand hand = args.hasFlag('l') && args.hasFlag('r') ? CommandTrait.Hand.BOTH
                    : args.hasFlag('l') ? CommandTrait.Hand.LEFT : CommandTrait.Hand.RIGHT;
            if (args.hasFlag('s') && hand != CommandTrait.Hand.BOTH) {
                hand = hand == CommandTrait.Hand.LEFT ? CommandTrait.Hand.SHIFT_LEFT : CommandTrait.Hand.SHIFT_RIGHT;
            }
            List<String> perms = new ArrayList<>();
            if (permissions != null) {
                perms.addAll(Arrays.asList(permissions.split(",")));
            }
            if (command.startsWith("npc select"))
                throw new CommandException("npc select is not supported inside NPC commands. Use --id <id> instead");

            try {
                int id = commands.addCommand(new NPCCommandBuilder(command, hand).addPerms(perms)
                        .player(args.hasFlag('p') || args.hasFlag('o')).op(args.hasFlag('o')).cooldown(cooldown)
                        .cost(cost).experienceCost(experienceCost).globalCooldown(gcooldown).n(n).globalN(gn)
                        .delay(delay).npc(args.hasFlag('n')));
                Messaging.sendTr(sender, Messages.COMMAND_ADDED, command, id);
            } catch (NumberFormatException ex) {
                throw new CommandException(CommandMessages.INVALID_NUMBER);
            }
        } else if (action.equalsIgnoreCase("execute")) {
            if (args.argsLength() < 4)
                throw new CommandUsageException();
            ServerPlayer player = matchPlayer(sender, args.getString(2));
            if (player == null)
                throw new CommandException(Messages.NPC_COMMAND_PLAYER_NOT_VALID, args.getString(2));

            CommandTrait.Hand hand = Util.matchEnum(CommandTrait.Hand.values(), args.getString(3));
            if (hand == null)
                throw new CommandException(Messages.NPC_COMMAND_INVALID_HAND,
                        Util.listValuesPretty(CommandTrait.Hand.values()));

            commands.dispatch(player, hand);
        } else if (action.equalsIgnoreCase("forgetplayer")) {
            if (args.argsLength() < 3) {
                commands.clearPlayerHistory(null);
                Messaging.sendTr(sender, Messages.NPC_COMMAND_ALL_PLAYERS_FORGOTTEN, npc.getName());
                return;
            }
            UUID who = matchPlayerUUID(sender, args.getString(2));
            if (who == null)
                throw new CommandException(Messages.NPC_COMMAND_INVALID_PLAYER, args.getString(2));
            commands.clearPlayerHistory(who);
            Messaging.sendTr(sender, Messages.NPC_COMMAND_PLAYER_FORGOTTEN, who);
        } else if (action.equalsIgnoreCase("clearerror")) {
            if (args.argsLength() < 3)
                throw new CommandException(Messages.NPC_COMMAND_INVALID_ERROR_MESSAGE,
                        Util.listValuesPretty(CommandTraitError.values()));
            CommandTraitError which = Util.matchEnum(CommandTraitError.values(), args.getString(2));
            if (which == null)
                throw new CommandException(Messages.NPC_COMMAND_INVALID_ERROR_MESSAGE,
                        Util.listValuesPretty(CommandTraitError.values()));
            if (args.argsLength() < 4) {
                commands.clearHistory(which, null);
                Messaging.sendTr(sender, Messages.NPC_COMMAND_ALL_ERRORS_CLEARED, npc.getName(),
                        Util.prettyEnum(which));
                return;
            }
            UUID who = matchPlayerUUID(sender, args.getString(3));
            if (who == null)
                throw new CommandException(Messages.NPC_COMMAND_INVALID_PLAYER, args.getString(3));
            commands.clearHistory(which, who);
            Messaging.sendTr(sender, Messages.NPC_COMMAND_ERRORS_CLEARED, Util.prettyEnum(which), who);
        } else if (action.equalsIgnoreCase("sequential")) {
            commands.setExecutionMode(commands.getExecutionMode() == ExecutionMode.SEQUENTIAL ? ExecutionMode.LINEAR
                    : ExecutionMode.SEQUENTIAL);
            Messaging.sendTr(sender,
                    commands.getExecutionMode() == ExecutionMode.SEQUENTIAL ? Messages.COMMANDS_SEQUENTIAL_SET
                            : Messages.COMMANDS_SEQUENTIAL_UNSET);
        } else if (action.equalsIgnoreCase("cycle")) {
            commands.setExecutionMode(
                    commands.getExecutionMode() == ExecutionMode.CYCLE ? ExecutionMode.LINEAR : ExecutionMode.CYCLE);
            Messaging.sendTr(sender, commands.getExecutionMode() == ExecutionMode.CYCLE ? Messages.COMMANDS_CYCLE_SET
                    : Messages.COMMANDS_CYCLE_UNSET);
        } else if (action.equalsIgnoreCase("random")) {
            commands.setExecutionMode(
                    commands.getExecutionMode() == ExecutionMode.RANDOM ? ExecutionMode.LINEAR : ExecutionMode.RANDOM);
            Messaging.sendTr(sender, commands.getExecutionMode() == ExecutionMode.RANDOM ? Messages.COMMANDS_RANDOM_SET
                    : Messages.COMMANDS_RANDOM_UNSET);
        } else if (action.equalsIgnoreCase("rememberlastused")) {
            if (args.argsLength() == 2) {
                commands.setRememberLastUsed(!commands.rememberLastUsed());
            } else {
                commands.setRememberLastUsed(Boolean.parseBoolean(args.getString(2)));
            }
            Messaging.sendTr(sender, commands.rememberLastUsed() ? Messages.COMMANDS_REMEMBER_LAST_USED_SET
                    : Messages.COMMANDS_REMEMBER_LAST_USED_UNSET);
        } else if (action.equalsIgnoreCase("remove")) {
            if (args.argsLength() == 2)
                throw new CommandUsageException();
            if (args.getString(2).equalsIgnoreCase("all")) {
                commands.clear();
                Messaging.sendTr(sender, Messages.COMMANDS_CLEARED, npc.getName());
            } else {
                int id = args.getInteger(2, -1);
                if (!commands.hasCommandId(id))
                    throw new CommandException(Messages.COMMAND_UNKNOWN_COMMAND_ID, id);
                commands.removeCommandById(id);
                Messaging.sendTr(sender, Messages.COMMAND_REMOVED, id);
            }
        } else if (action.equalsIgnoreCase("permissions") || action.equalsIgnoreCase("perms")) {
            if (!PermissionUtil.hasPermission(sender, "citizens.admin"))
                throw new NoPermissionsException();
            if (args.argsLength() == 2)
                throw new CommandUsageException();
            List<String> temporaryPermissions = Arrays.asList(args.getString(2).split(","));
            int duration = -1;
            if (args.argsLength() == 4) {
                duration = Durations.toTicks(Durations.parse(args.getString(3), TimeUnit.SECONDS));
            }
            commands.setTemporaryPermissions(temporaryPermissions, duration);
            Messaging.sendTr(sender, Messages.COMMAND_TEMPORARY_PERMISSIONS_SET,
                    Joiner.on(' ').join(temporaryPermissions), duration);
        } else if (action.equalsIgnoreCase("cost")) {
            if (args.argsLength() == 2)
                throw new CommandException(Messages.COMMAND_MISSING_COST);
            commands.setCost(args.getDouble(2));
            Messaging.sendTr(sender, Messages.COMMAND_COST_SET, args.getDouble(2));
        } else if (action.equalsIgnoreCase("expcost")) {
            if (args.argsLength() == 2)
                throw new CommandException(Messages.COMMAND_MISSING_COST);
            commands.setExperienceCost(args.getInteger(2));
            Messaging.sendTr(sender, Messages.COMMAND_EXPERIENCE_COST_SET, args.getInteger(2));
        } else if (action.equalsIgnoreCase("hideerrors")) {
            commands.setHideErrorMessages(!commands.isHideErrorMessages());
            Messaging.sendTr(sender, commands.isHideErrorMessages() ? Messages.COMMAND_HIDE_ERROR_MESSAGES_SET
                    : Messages.COMMAND_HIDE_ERROR_MESSAGES_UNSET);
        } else if (action.equalsIgnoreCase("itemcost")) {
            if (!(sender.getEntity() instanceof ServerPlayer player))
                throw new CommandException(CommandMessages.MUST_BE_INGAME);
            if (args.argsLength() == 2) {
                InventoryMenu.createSelfRegistered(new ItemRequirementGUI(commands)).present(player);
            } else {
                int id = args.getInteger(2, -1);
                if (!commands.hasCommandId(id))
                    throw new CommandException(Messages.COMMAND_UNKNOWN_COMMAND_ID, id);
                InventoryMenu.createSelfRegistered(new ItemRequirementGUI(commands, id)).present(player);
            }
        } else if (action.equalsIgnoreCase("errormessage")) {
            if (args.argsLength() < 4)
                throw new CommandUsageException();
            CommandTraitError which = Util.matchEnum(CommandTraitError.values(), args.getString(2));
            if (which == null)
                throw new CommandException(Messages.NPC_COMMAND_INVALID_ERROR_MESSAGE,
                        Util.listValuesPretty(CommandTraitError.values()));
            commands.setCustomErrorMessage(which, args.getJoinedStrings(3));
            Messaging.sendTr(sender, Messages.NPC_COMMAND_ERROR_MESSAGE_SET, Util.prettyEnum(which),
                    args.getJoinedStrings(3));
        } else
            throw new CommandUsageException();
    }


    @Command(
            aliases = { "npc" },
            usage = "shop (edit|show|delete|copyfrom) (name) (new_name)",
            desc = "",
            modifiers = { "shop" },
            min = 1,
            max = 4,
            permission = "citizens.npc.shop")
    @Requirements(selected = false, ownership = true)
    public void shop(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completions = { "edit", "show", "delete", "copyfrom" }) String action)
            throws CommandException {
        StoredShops shops = Citizens.getInstance().getShops();
        ServerPlayer player = sender.getEntity() instanceof ServerPlayer sp ? sp : null;
        if (args.argsLength() == 1) {
            if (player == null)
                throw new CommandException(CommandMessages.MUST_BE_INGAME);
            if (npc != null) {
                npc.getOrAddTrait(ShopTrait.class).getDefaultShop().display(player);
            }
            return;
        }
        NPCShop shop = npc != null ? npc.getOrAddTrait(ShopTrait.class).getDefaultShop() : null;
        if (args.argsLength() >= 3) {
            shop = shops.getShop(args.getString(2));
            if (shop == null && action.equalsIgnoreCase("edit")) {
                if (player == null)
                    throw new CommandException(CommandMessages.MUST_BE_INGAME);
                shop = shops.addNamedShop(args.getString(2));
                if (!shop.canEdit(npc, sender)) {
                    shops.deleteShop(shop);
                    throw new NoPermissionsException();
                }
            }
        }
        if (shop == null)
            throw new CommandException(Messages.SHOP_NOT_FOUND, args.argsLength() >= 3 ? args.getString(2) : "");

        if (action.equalsIgnoreCase("delete")) {
            if (!shop.canEdit(npc, sender))
                throw new NoPermissionsException();
            shops.deleteShop(shop);
            Messaging.sendTr(sender, Messages.SHOP_DELETED, shop.getName());
        } else if (action.equalsIgnoreCase("edit")) {
            if (player == null)
                throw new CommandException(CommandMessages.MUST_BE_INGAME);
            if (!shop.canEdit(npc, sender))
                throw new NoPermissionsException();
            shop.displayEditor(npc == null ? null : npc.getOrAddTrait(ShopTrait.class), player);
        } else if (action.equalsIgnoreCase("copyfrom")) {
            if (npc == null)
                throw new CommandException(CommandMessages.MUST_HAVE_SELECTED);
            if (!shop.canEdit(npc, sender)
                    || !npc.getOrAddTrait(ShopTrait.class).getDefaultShop().canEdit(npc, sender))
                throw new NoPermissionsException();
            String newName = args.argsLength() == 4 ? args.getString(3) : UUID.randomUUID().toString();
            DataKey key = new MemoryDataKey().getRelative(newName);
            PersistenceLoader.save(shop, key);
            NPCShop copy = PersistenceLoader.load(NPCShop.class, key);
            npc.getOrAddTrait(ShopTrait.class).setDefaultShop(copy);
            Messaging.send(sender, "Copied shop " + shop.getName() + " to " + npc.getName());
        } else if (action.equalsIgnoreCase("show")) {
            if (args.argsLength() == 4) {
                player = matchPlayer(sender, args.getString(3));
                if (player == null)
                    throw new CommandException(Messages.SHOP_PLAYER_NOT_FOUND, args.getString(3));
            }
            if (player == null)
                throw new CommandException(CommandMessages.MUST_BE_INGAME);
            shop.display(player);
        } else
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "shopitem [shop name/id|all] [reset_purchases|reset_player_purchases] [item index|all] (--page [page]) (--player [uuid])",
            desc = "",
            modifiers = { "shopitem" },
            min = 4,
            max = 5,
            permission = "citizens.npc.shopitem")
    @Requirements(selected = false, ownership = true)
    public void shopitem(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String shopName,
            @Arg(value = 2, completions = { "reset_purchases", "reset_player_purchases" }) String operation,
            @Arg(3) String index, @Flag("page") Integer page, @Flag("player") String playerFlag)
            throws CommandException {
        StoredShops shops = Citizens.getInstance().getShops();
        if (!"all".equals(shopName) && shops.getShop(shopName) == null)
            throw new CommandException(Messages.SHOP_NOT_FOUND, shopName);
        int pageIndex = page == null || page < 1 ? 0 : page - 1;
        UUID playerUUID = null;
        if ("reset_player_purchases".equals(operation)) {
            if (playerFlag == null)
                throw new CommandUsageException();
            playerUUID = matchPlayerUUID(sender, playerFlag);
            if (playerUUID == null)
                throw new CommandException(Messages.SHOP_PLAYER_NOT_FOUND, playerFlag);
        }
        List<NPCShop> targets = new ArrayList<>();
        if (shopName.equals("all")) {
            targets.addAll(shops.globalShops.values());
            targets.addAll(shops.npcShops.values());
        } else {
            targets.add(shops.getShop(shopName));
        }
        for (NPCShop shop : targets) {
            if (!shop.canEdit(npc, sender))
                throw new NoPermissionsException();

            List<NPCShopItem> items = new ArrayList<>();
            if (index.equals("all")) {
                shop.getPages().forEach(p -> items.addAll(p.getItems()));
            } else {
                // upstream reads the page before checking the bounds, and then checks them the wrong way round, so this
                // branch fails for every valid page and throws an index error for the rest
                if (pageIndex >= shop.getPages().size())
                    throw new CommandException(Messages.SHOP_PAGE_NOT_FOUND, pageIndex + 1, shop.getPages().size());
                NPCShopItem item;
                try {
                    // upstream adds one to the index, so it resets the item in the slot after the one named
                    item = shop.getPages().get(pageIndex).getItem(Integer.parseInt(index.trim()));
                } catch (NumberFormatException ex) {
                    throw new CommandException(CommandMessages.INVALID_NUMBER);
                }
                if (item == null)
                    throw new CommandException(Messages.SHOP_ITEM_NOT_FOUND, index);
                items.add(item);
            }
            if ("reset_purchases".equals(operation)) {
                items.forEach(NPCShopItem::resetPurchaseHistory);
            } else if ("reset_player_purchases".equals(operation)) {
                UUID who = playerUUID;
                items.forEach(item -> item.resetPurchaseHistory(who));
            } else
                throw new CommandUsageException();
        }
        Messaging.send(sender, "Reset purchase history in shop " + shopName);
    }

    @Command(
            aliases = { "npc" },
            usage = "showshop (name) (--player [player])",
            desc = "",
            modifiers = { "showshop" },
            min = 1,
            max = 2,
            permission = "citizens.npc.showshop")
    @Requirements(selected = false, ownership = true)
    public void showshop(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String shopName,
            @Flag("player") String flagPlayer) throws CommandException {
        ServerPlayer player = null;
        if (flagPlayer != null) {
            if (!PermissionUtil.hasPermission(sender, "citizens.npc.showshop.to-others"))
                throw new NoPermissionsException();
            player = matchPlayer(sender, flagPlayer);
        } else if (sender.getEntity() instanceof ServerPlayer sp) {
            player = sp;
        }
        if (player == null)
            throw new CommandException(Messages.SHOP_PLAYER_NOT_FOUND, flagPlayer == null ? "" : flagPlayer);
        if (shopName == null && npc == null)
            throw new CommandException(Messages.SHOP_NOT_FOUND, "");

        NPCShop shop = shopName == null ? npc.getOrAddTrait(ShopTrait.class).getDefaultShop()
                : Citizens.getInstance().getShops().getShop(shopName);
        if (shop == null)
            throw new CommandException(Messages.SHOP_NOT_FOUND, shopName);
        shop.display(player);
    }


    @Command(
            aliases = { "npc" },
            usage = "ai (true|false)",
            desc = "",
            modifiers = { "ai" },
            min = 1,
            max = 2,
            permission = "citizens.npc.ai")
    public void ai(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) Boolean explicit) {
        boolean useAI = explicit == null ? !npc.useMinecraftAI() : explicit;
        npc.setUseMinecraftAI(useAI);
        Messaging.sendTr(sender, useAI ? Messages.USING_MINECRAFT_AI : Messages.NOT_USING_MINECRAFT_AI);
    }

    @Command(
            aliases = { "npc" },
            usage = "hurt [damage]",
            desc = "",
            modifiers = { "hurt" },
            min = 2,
            max = 2,
            permission = "citizens.npc.hurt")
    public void hurt(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        if (!(npc.getEntity() instanceof LivingEntity living)) {
            Messaging.sendErrorTr(sender, Messages.NPC_NOT_DAMAGEABLE, npc.getOrAddTrait(MobType.class).getType());
            return;
        }
        if (npc.isProtected()) {
            Messaging.sendErrorTr(sender, Messages.NPC_PROTECTED);
            return;
        }
        living.hurt(living.damageSources().generic(), args.getInteger(1));
    }

    @Command(
            aliases = { "npc" },
            usage = "jump",
            desc = "",
            modifiers = { "jump" },
            min = 1,
            max = 1,
            permission = "citizens.npc.jump")
    public void jump(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        if (!(npc.getEntity() instanceof LivingEntity living))
            throw new CommandException(Messages.NPC_NOT_DAMAGEABLE, npc.getOrAddTrait(MobType.class).getType());
        living.setJumping(true);
        living.jumpFromGround();
    }

    @Command(
            aliases = { "npc" },
            usage = "respawn [delay]",
            desc = "",
            modifiers = { "respawn" },
            min = 1,
            max = 2,
            permission = "citizens.npc.respawn")
    public void respawn(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) Duration delay) {
        if (delay == null) {
            Messaging.sendTr(sender, Messages.RESPAWN_DELAY_DESCRIBE, npc.data().get(NPC.Metadata.RESPAWN_DELAY, -1));
            return;
        }
        npc.data().setPersistent(NPC.Metadata.RESPAWN_DELAY, Durations.toTicks(delay));
        Messaging.sendTr(sender, Messages.RESPAWN_DELAY_SET, Durations.toTicks(delay));
    }

    @Command(
            aliases = { "npc" },
            usage = "speed [speed]",
            desc = "",
            modifiers = { "speed" },
            min = 2,
            max = 2,
            permission = "citizens.npc.speed")
    public void speed(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        float newSpeed = (float) Math.abs(args.getDouble(1));
        npc.getNavigator().getDefaultParameters().speedModifier(newSpeed);
        Messaging.sendTr(sender, Messages.SPEED_MODIFIER_SET, newSpeed);
    }

    @Command(
            aliases = { "npc" },
            usage = "velocity [x] [y] [z]",
            desc = "",
            modifiers = { "velocity", "vel" },
            min = 4,
            max = 4,
            permission = "citizens.npc.velocity")
    public void velocity(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) double x, @Arg(2) double y,
            @Arg(3) double z) throws CommandException {
        if (!npc.isSpawned())
            throw new CommandException(Messages.MOUNT_NPC_MUST_BE_SPAWNED, npc.getName());
        npc.getEntity().setDeltaMovement(x, y, z);
        // without this the client keeps drawing the NPC where its own physics put it
        npc.getEntity().hasImpulse = true;
    }

    @Command(
            aliases = { "npc" },
            usage = "vulnerable (-t(emporary))",
            desc = "",
            modifiers = { "vulnerable" },
            min = 1,
            max = 1,
            flags = "t",
            permission = "citizens.npc.vulnerable")
    public void vulnerable(CommandContext args, CommandSourceStack sender, NPC npc) {
        boolean vulnerable = !npc.isProtected();
        if (args.hasFlag('t')) {
            npc.data().set(NPC.Metadata.DEFAULT_PROTECTED, vulnerable);
        } else {
            npc.data().setPersistent(NPC.Metadata.DEFAULT_PROTECTED, vulnerable);
        }
        // The metadata is only pushed to the entity by the per-tick update, so without this the entity keeps vanilla's
        // invulnerable flag for the rest of the tick - and anything that damages the NPC in that same tick, /npc hurt
        // included, is refused by vanilla while this command has already reported success.
        if (npc.isSpawned() && npc.getEntity() instanceof LivingEntity living) {
            living.setInvulnerable(npc.isProtected());
        }
        Messaging.sendTr(sender, vulnerable ? Messages.VULNERABLE_STOPPED : Messages.VULNERABLE_SET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "tpto [player name|npc id] [player name|npc id]",
            desc = "",
            modifiers = { "tpto" },
            min = 2,
            max = 3,
            permission = "citizens.npc.tpto",
            parsePlaceholders = true)
    @Requirements
    public void tpto(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        Entity from = npc == null ? null : npc.getEntity();
        Entity to = null;
        boolean firstWasPlayer = false;
        Entity first = resolveEntity(sender, args.getString(1));
        if (first == null)
            throw new CommandException(args.argsLength() == 2 ? Messages.TPTO_ENTITY_NOT_FOUND
                    : Messages.FROM_ENTITY_NOT_FOUND);
        firstWasPlayer = first instanceof ServerPlayer;
        if (args.argsLength() == 2) {
            to = first;
        } else {
            from = first;
            to = resolveEntity(sender, args.getString(2));
        }
        if (from == null)
            throw new CommandException(Messages.FROM_ENTITY_NOT_FOUND);
        if (to == null)
            throw new CommandException(Messages.TPTO_ENTITY_NOT_FOUND);
        NPC fromNPC = CitizensAPI.getNPCRegistry().getNPC(from);
        if (fromNPC != null) {
            fromNPC.teleport(Location.of(to), TeleportCause.COMMAND);
        } else {
            from.teleportTo((net.minecraft.server.level.ServerLevel) to.level(), to.getX(), to.getY(), to.getZ(),
                    java.util.Set.of(), to.getYRot(), to.getXRot());
        }
        Messaging.sendTr(sender, Messages.TPTO_SUCCESS);
    }

    /** An NPC id or a player name, which is what the teleport commands accept on both sides. */
    private static Entity resolveEntity(CommandSourceStack sender, String raw) {
        try {
            NPC byId = CitizensAPI.getNPCRegistry().getById(Integer.parseInt(raw.trim()));
            return byId == null ? null : byId.getEntity();
        } catch (NumberFormatException e) {
            return matchPlayer(sender, raw);
        }
    }


    @Command(
            aliases = { "npc" },
            usage = "activationrange [range]",
            desc = "",
            modifiers = { "activationrange" },
            min = 1,
            max = 2,
            permission = "citizens.npc.activationrange")
    public void activationrange(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) Integer range) {
        if (range == null) {
            npc.data().remove(NPC.Metadata.ACTIVATION_RANGE);
        } else {
            npc.data().setPersistent(NPC.Metadata.ACTIVATION_RANGE, range);
        }
        Messaging.sendTr(sender, Messages.ACTIVATION_RANGE_SET, range);
    }

    @Command(
            aliases = { "npc" },
            usage = "aggressive [true|false] (-t(emporary))",
            desc = "",
            flags = "t",
            modifiers = { "aggressive" },
            min = 1,
            max = 2,
            permission = "citizens.npc.aggressive")
    public void aggressive(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) Boolean aggressive) {
        boolean aggro = aggressive != null ? aggressive : !npc.data().get(NPC.Metadata.AGGRESSIVE, false);
        // upstream stores the raw argument in the temporary branch, so "-t" with no value stores null
        if (args.hasFlag('t')) {
            npc.data().set(NPC.Metadata.AGGRESSIVE, aggro);
        } else {
            npc.data().setPersistent(NPC.Metadata.AGGRESSIVE, aggro);
        }
        if (npc.getEntity() instanceof Mob mob) {
            mob.setAggressive(aggro);
        }
        Messaging.sendTr(sender, aggro ? Messages.AGGRESSIVE_SET : Messages.AGGRESSIVE_UNSET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "flyable (true|false)",
            desc = "",
            modifiers = { "flyable" },
            min = 1,
            max = 2,
            permission = "citizens.npc.flyable")
    public void flyable(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) Boolean explicit)
            throws CommandException {
        if (Util.isAlwaysFlyable(npc.getOrAddTrait(MobType.class).getType()))
            throw new CommandException(CommandMessages.REQUIREMENTS_INVALID_MOB_TYPE,
                    npc.getOrAddTrait(MobType.class).getType());
        boolean flyable = explicit != null ? explicit : !npc.isFlyable();
        npc.setFlyable(flyable);
        // read back: a type that always flies ignores the request
        flyable = npc.isFlyable();
        Messaging.sendTr(sender, flyable ? Messages.FLYABLE_SET : Messages.FLYABLE_UNSET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "knockback (--explicit true|false)",
            desc = "",
            modifiers = { "knockback" },
            min = 1,
            max = 1,
            permission = "citizens.npc.knockback")
    public void knockback(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("explicit") Boolean explicit) {
        boolean kb = explicit != null ? explicit : !npc.data().get(NPC.Metadata.KNOCKBACK, true);
        npc.data().set(NPC.Metadata.KNOCKBACK, kb);
        Messaging.sendTr(sender, kb ? Messages.KNOCKBACK_SET : Messages.KNOCKBACK_UNSET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "leashable (-t(emporary))",
            desc = "",
            modifiers = { "leashable" },
            min = 1,
            max = 1,
            flags = "t",
            permission = "citizens.npc.leashable")
    @Requirements(selected = true, ownership = true, excludedTypes = { "minecraft:player" })
    public void leashable(CommandContext args, CommandSourceStack sender, NPC npc) {
        boolean leashable = !npc.data().get(NPC.Metadata.LEASH_PROTECTED, true);
        if (args.hasFlag('t')) {
            npc.data().set(NPC.Metadata.LEASH_PROTECTED, leashable);
        } else {
            npc.data().setPersistent(NPC.Metadata.LEASH_PROTECTED, leashable);
        }
        Messaging.sendTr(sender, leashable ? Messages.LEASHABLE_STOPPED : Messages.LEASHABLE_SET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "passive (--set [true|false])",
            desc = "",
            modifiers = { "passive" },
            min = 1,
            max = 1,
            permission = "citizens.npc.passive")
    public void passive(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("set") Boolean set) {
        boolean damageOthers = set != null ? set : !npc.data().get(NPC.Metadata.DAMAGE_OTHERS, true);
        npc.data().setPersistent(NPC.Metadata.DAMAGE_OTHERS, damageOthers);
        Messaging.sendTr(sender, damageOthers ? Messages.PASSIVE_UNSET : Messages.PASSIVE_SET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "pausepathfinding --onrightclick [true|false] --when-player-within [range] --pauseduration [duration] --lockoutduration [duration]",
            desc = "",
            modifiers = { "pausepathfinding" },
            min = 1,
            max = 1,
            permission = "citizens.npc.pausepathfinding")
    public void pausepathfinding(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag("onrightclick") Boolean rightclick, @Flag("when-player-within") Double playerRange,
            @Flag("pauseduration") Duration pauseDuration, @Flag("lockoutduration") Duration lockoutDuration)
            throws CommandException {
        PausePathfindingTrait trait = npc.getOrAddTrait(PausePathfindingTrait.class);
        if (playerRange != null) {
            if (playerRange <= 0 && playerRange != -1)
                throw new CommandException(CommandMessages.INVALID_NUMBER);
            trait.setPlayerRangeInBlocks(playerRange);
            Messaging.sendTr(sender, Messages.PAUSEPATHFINDING_RANGE_SET, npc.getName(), playerRange);
        }
        if (rightclick != null) {
            trait.setPauseOnRightClick(rightclick);
            Messaging.sendTr(sender,
                    rightclick ? Messages.PAUSEPATHFINDING_RIGHTCLICK_SET : Messages.PAUSEPATHFINDING_RIGHTCLICK_UNSET,
                    npc.getName());
        }
        if (lockoutDuration != null) {
            trait.setLockoutDuration(Durations.toTicks(lockoutDuration));
            Messaging.sendTr(sender, Messages.PAUSEPATHFINDING_LOCKOUT_DURATION_SET, npc.getName(), lockoutDuration);
        }
        if (pauseDuration != null) {
            trait.setPauseDuration(Durations.toTicks(pauseDuration));
            Messaging.sendTr(sender, Messages.PAUSEPATHFINDING_TICKS_SET, npc.getName(), pauseDuration);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "pickupitems (--set [true|false])",
            desc = "",
            strictArguments = true,
            modifiers = { "pickupitems" },
            min = 1,
            max = 1,
            permission = "citizens.npc.pickupitems")
    public void pickupitems(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("set") Boolean set) {
        boolean pickup = set == null ? !npc.data().get(NPC.Metadata.PICKUP_ITEMS, false) : set;
        npc.data().setPersistent(NPC.Metadata.PICKUP_ITEMS, pickup);
        if (npc.getEntity() instanceof net.minecraft.world.entity.Mob mob) mob.setCanPickUpLoot(pickup);
        if (pickup && npc.getEntity() instanceof net.minecraft.world.entity.LivingEntity) npc.getOrAddTrait(Equipment.class);
        Messaging.sendTr(sender, pickup ? Messages.PICKUP_ITEMS_SET : Messages.PICKUP_ITEMS_UNSET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "playerlist (-a(dd),r(emove))",
            desc = "",
            modifiers = { "playerlist" },
            min = 1,
            max = 1,
            flags = "ar",
            permission = "citizens.npc.playerlist")
    @Requirements(selected = true, ownership = true, types = { "minecraft:player" })
    public void playerlist(CommandContext args, CommandSourceStack sender, NPC npc) {
        boolean remove = !npc.shouldRemoveFromPlayerList();
        if (args.hasFlag('a')) {
            remove = false;
        } else if (args.hasFlag('r')) {
            remove = true;
        }
        npc.data().setPersistent(NPC.Metadata.REMOVE_FROM_PLAYERLIST, remove);
        if (npc.isSpawned() && npc.getEntity() instanceof EntityHumanNPC human) {
            human.updatePlayerListMembership();
        }
        Messaging.sendTr(sender, remove ? Messages.REMOVED_FROM_PLAYERLIST : Messages.ADDED_TO_PLAYERLIST,
                npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "swim (--set [true|false])",
            desc = "",
            strictArguments = true,
            modifiers = { "swim" },
            min = 1,
            max = 1,
            permission = "citizens.npc.swim")
    public void swim(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("set") Boolean set) {
        boolean swim = set != null ? set : !net.citizensnpcs.npc.ai.NPCSwimming.isEnabled(npc, sender.getLevel());
        npc.data().setPersistent(NPC.Metadata.SWIM, swim);
        Messaging.sendTr(sender, swim ? Messages.SWIM_SET : Messages.SWIM_UNSET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "trackingrange [range]",
            desc = "",
            modifiers = { "trackingrange" },
            min = 1,
            max = 2,
            permission = "citizens.npc.trackingrange")
    public void trackingrange(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) Integer range) {
        if (range == null) {
            npc.data().remove(NPC.Metadata.TRACKING_RANGE);
        } else {
            npc.data().setPersistent(NPC.Metadata.TRACKING_RANGE, range);
        }
        Messaging.sendTr(sender, Messages.TRACKING_RANGE_SET, range);
    }

    @Command(
            aliases = { "npc" },
            usage = "useitem (-o(ffhand))",
            desc = "",
            modifiers = { "useitem" },
            min = 1,
            max = 1,
            flags = "o",
            permission = "citizens.npc.useitem")
    public void useitem(CommandContext args, CommandSourceStack sender, NPC npc) {
        NPC.Metadata key = args.hasFlag('o') ? NPC.Metadata.USING_OFFHAND_ITEM : NPC.Metadata.USING_HELD_ITEM;
        boolean using = !npc.data().get(key, false);
        npc.data().setPersistent(key, using);
        Messaging.sendTr(sender,
                args.hasFlag('o') ? Messages.TOGGLED_USING_OFFHAND_ITEM : Messages.TOGGLED_USING_HELD_ITEM,
                Boolean.toString(using));
    }


    @Command(
            aliases = { "npc" },
            usage = "bat --awake [awake]",
            desc = "",
            modifiers = { "bat" },
            min = 1,
            max = 1,
            permission = "citizens.npc.bat")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "minecraft:bat" })
    public void bat(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("awake") Boolean awake)
            throws CommandException {
        if (awake == null)
            throw new CommandUsageException();
        npc.getOrAddTrait(BatTrait.class).setAwake(awake);
        Messaging.sendTr(sender, awake ? Messages.BAT_AWAKE_SET : Messages.BAT_AWAKE_UNSET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "endercrystal -b(ottom)",
            desc = "",
            modifiers = { "endercrystal" },
            min = 1,
            max = 1,
            flags = "b",
            permission = "citizens.npc.endercrystal")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "minecraft:end_crystal" })
    public void endercrystal(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        if (!args.hasFlag('b'))
            throw new CommandUsageException();
        EnderCrystalTrait trait = npc.getOrAddTrait(EnderCrystalTrait.class);
        boolean showing = !trait.isShowBase();
        trait.setShowBase(showing);
        Messaging.sendTr(sender,
                showing ? Messages.ENDERCRYSTAL_SHOWING_BOTTOM : Messages.ENDERCRYSTAL_NOT_SHOWING_BOTTOM,
                npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "enderman -a(ngry)",
            desc = "",
            flags = "a",
            modifiers = { "enderman" },
            min = 1,
            max = 1,
            permission = "citizens.npc.enderman")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "minecraft:enderman" })
    public void enderman(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        if (!args.hasFlag('a'))
            throw new CommandUsageException();
        boolean angry = npc.getOrAddTrait(EndermanTrait.class).toggleAngry();
        Messaging.sendTr(sender, angry ? Messages.ENDERMAN_ANGRY_SET : Messages.ENDERMAN_ANGRY_UNSET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "minecart (--offset [offset])",
            desc = "",
            modifiers = { "minecart" },
            min = 1,
            max = 1,
            permission = "citizens.npc.minecart")
    public void minecart(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("offset") Integer offset)
            throws CommandException {
        if (!BuiltInRegistries.ENTITY_TYPE.getKey(npc.getOrAddTrait(MobType.class).getType()).getPath()
                .contains("minecart"))
            throw new CommandUsageException();
        if (offset != null) {
            npc.data().setPersistent(NPC.Metadata.MINECART_OFFSET, offset);
        }
        Messaging.sendTr(sender, Messages.MINECART_SET, npc.getName(), npc.data().get(NPC.Metadata.MINECART_OFFSET, 0));
    }

    @Command(
            aliases = { "npc" },
            usage = "ocelot (--type type) (-s(itting), -n(ot sitting))",
            desc = "",
            modifiers = { "ocelot" },
            min = 1,
            max = 1,
            requiresFlags = true,
            flags = "sn",
            permission = "citizens.npc.ocelot")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "minecraft:ocelot", "minecraft:cat" })
    public void ocelot(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("type") String type) {
        OcelotModifiers trait = npc.getOrAddTrait(OcelotModifiers.class);
        if (args.hasFlag('s')) {
            trait.setSitting(true);
        } else if (args.hasFlag('n')) {
            trait.setSitting(false);
        }
        if (type != null) {
            trait.setType(type);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "painting (--art art)",
            desc = "",
            modifiers = { "painting" },
            min = 1,
            max = 1,
            permission = "citizens.npc.painting")
    @Requirements(selected = true, ownership = true, types = { "minecraft:painting" })
    public void painting(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("art") String art)
            throws CommandException {
        if (art == null)
            throw new CommandUsageException();
        Holder<PaintingVariant> parsed = PaintingTrait.parse(art);
        if (parsed == null)
            throw new CommandException(CommandMessages.INVALID_NUMBER);
        npc.getOrAddTrait(PaintingTrait.class).setArt(parsed);
        Messaging.sendTr(sender, Messages.PAINTING_ART_SET, npc.getName(), art);
    }

    @Command(
            aliases = { "npc" },
            usage = "rabbittype [type]",
            desc = "",
            modifiers = { "rabbittype", "rbtype" },
            min = 2,
            max = 2,
            permission = "citizens.npc.rabbittype")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "minecraft:rabbit" })
    public void rabbitType(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String type)
            throws CommandException {
        Rabbit.Variant parsed = RabbitType.parse(type);
        if (parsed == null)
            throw new CommandException(Messages.INVALID_RABBIT_TYPE, Util.listValuesPretty(Rabbit.Variant.values()));
        npc.getOrAddTrait(RabbitType.class).setType(parsed);
        Messaging.sendTr(sender, Messages.RABBIT_TYPE_SET, npc.getName(), parsed.name());
    }

    @Command(
            aliases = { "npc" },
            usage = "slimesize [size]",
            desc = "",
            modifiers = { "slimesize" },
            min = 1,
            max = 2,
            permission = "citizens.npc.slimesize")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "minecraft:slime", "minecraft:magma_cube" })
    public void slimeSize(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        SlimeSize trait = npc.getOrAddTrait(SlimeSize.class);
        if (args.argsLength() <= 1) {
            trait.describe(sender);
            return;
        }
        int size = Math.max(-2, args.getInteger(1));
        trait.setSize(size);
        Messaging.sendTr(sender, Messages.SIZE_SET, npc.getName(), size);
    }

    @Command(
            aliases = { "npc" },
            usage = "wither (--invulnerable [true|false]) (--invulnerable-ticks [ticks]) (--arrow-shield [true|false])",
            desc = "",
            modifiers = { "wither" },
            min = 1,
            max = 1,
            requiresFlags = true,
            permission = "citizens.npc.wither")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "minecraft:wither" })
    public void wither(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("invulnerable") Boolean invulnerable,
            @Flag("arrow-shield") Boolean arrows, @Flag("invulnerable-ticks") Integer invulnerableTicks) {
        WitherTrait trait = npc.getOrAddTrait(WitherTrait.class);
        if (invulnerable != null) {
            trait.setInvulnerable(invulnerable);
        }
        if (invulnerableTicks != null) {
            trait.setInvulnerableTicks(invulnerableTicks);
        }
        if (arrows != null) {
            trait.setBlocksArrows(arrows);
        }
        Messaging.sendTr(sender, Messages.WITHER_TRAIT_UPDATED, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "wolf (-s(itting) a(ngry) t(amed) i(nterested)) --collar [name|hex] --variant [variant]",
            desc = "",
            modifiers = { "wolf" },
            min = 1,
            max = 1,
            requiresFlags = true,
            flags = "sati",
            permission = "citizens.npc.wolf")
    @Requirements(selected = true, ownership = true, cosmeticTypes = { "minecraft:wolf" })
    public void wolf(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("collar") String collar,
            @Flag("variant") String variant) throws CommandException {
        WolfModifiers trait = npc.getOrAddTrait(WolfModifiers.class);
        if (args.hasFlag('a')) {
            trait.setAngry(!trait.isAngry());
        }
        if (args.hasFlag('s')) {
            trait.setSitting(!trait.isSitting());
        }
        if (args.hasFlag('t')) {
            trait.setTamed(!trait.isTamed());
        }
        if (args.hasFlag('i')) {
            trait.setInterested(!trait.isInterested());
        }
        if (variant != null) {
            if (WolfModifiers.parseVariant(variant) == null)
                throw new CommandException(Messages.INVALID_WOLF_VARIANT, variant);
            trait.setVariant(variant);
        }
        if (collar != null) {
            DyeColor color = Util.matchEnum(DyeColor.values(), collar.replace(' ', '_'));
            if (color == null)
                throw new CommandException(Messages.COLLAR_COLOUR_NOT_RECOGNISED, collar);
            trait.setCollarColor(color);
            Messaging.sendTr(sender, Messages.COLLAR_COLOUR_SET, npc.getName(), color.getName());
        }
    }


    @Command(
            aliases = { "npc" },
            usage = "armorstand --visible [visible] --small [small] --marker [marker] --gravity [gravity] --arms [arms] --baseplate [baseplate]",
            desc = "",
            modifiers = { "armorstand" },
            min = 1,
            max = 1,
            permission = "citizens.npc.armorstand")
    @Requirements(selected = true, ownership = true, types = { "minecraft:armor_stand" })
    public void armorstand(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("visible") Boolean visible,
            @Flag("small") Boolean small, @Flag("gravity") Boolean gravity, @Flag("arms") Boolean arms,
            @Flag("marker") Boolean marker, @Flag("baseplate") Boolean baseplate) {
        ArmorStandTrait trait = npc.getOrAddTrait(ArmorStandTrait.class);
        if (visible != null) {
            trait.setVisible(visible);
        }
        if (small != null) {
            trait.setSmall(small);
        }
        if (gravity != null) {
            trait.setGravity(gravity);
        }
        if (marker != null) {
            trait.setMarker(marker);
        }
        if (arms != null) {
            trait.setHasArms(arms);
        }
        if (baseplate != null) {
            trait.setHasBaseplate(baseplate);
        }
        Messaging.sendTr(sender, Messages.ARMORSTAND_UPDATED, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "entitypose [pose]",
            desc = "",
            modifiers = { "entitypose" },
            min = 2,
            max = 2,
            permission = "citizens.npc.entitypose")
    public void entitypose(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String pose)
            throws CommandException {
        Pose parsed = Util.matchEnum(Pose.values(), pose);
        if (parsed == null)
            throw new CommandException(Messages.ENTITYPOSE_INVALID, Util.listValuesPretty(Pose.values()));
        npc.getOrAddTrait(EntityPoseTrait.class).setPose(parsed);
        Messaging.sendTr(sender, Messages.ENTITYPOSE_SET, parsed);
    }

    @Command(
            aliases = { "npc" },
            usage = "sitting (--explicit [true|false]) (--at [at])",
            desc = "",
            modifiers = { "sitting" },
            min = 1,
            max = 2,
            permission = "citizens.npc.sitting")
    public void sitting(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("explicit") Boolean explicit,
            @Flag("at") Location at) {
        SitTrait trait = npc.getOrAddTrait(SitTrait.class);
        boolean toSit = explicit != null ? explicit : !trait.isSitting();
        if (!toSit) {
            trait.setSitting(null);
            Messaging.sendTr(sender, Messages.SITTING_UNSET, npc.getName());
            return;
        }
        Location target = at == null ? npc.getStoredLocation() : at;
        trait.setSitting(target);
        Messaging.sendTr(sender, Messages.SITTING_SET, npc.getName(), Util.prettyPrintLocation(target));
    }

    @Command(
            aliases = { "npc" },
            usage = "panimate [animation]",
            desc = "",
            modifiers = { "panimate" },
            min = 2,
            max = 2,
            permission = "citizens.npc.panimate")
    @Requirements(selected = true, ownership = true, types = { "minecraft:player" })
    public void playeranimate(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String animation)
            throws CommandException {
        PlayerAnimation parsed = Util.matchEnum(PlayerAnimation.values(), animation);
        if (parsed == null)
            throw new CommandException(Messages.UNKNOWN_PLAYER_ANIMATION,
                    Util.listValuesPretty(PlayerAnimation.values()));
        if (!(npc.getEntity() instanceof ServerPlayer player))
            throw new CommandException(CommandMessages.REQUIREMENTS_INVALID_MOB_TYPE,
                    npc.getOrAddTrait(MobType.class).getType());
        parsed.play(player, 64);
    }

    @Command(
            aliases = { "npc" },
            usage = "skinlayers (--cape [true|false]) (--hat [true|false]) (--jacket [true|false]) (--sleeves [true|false]) (--pants [true|false])",
            desc = "",
            modifiers = { "skinlayers" },
            min = 1,
            max = 5,
            permission = "citizens.npc.skinlayers")
    @Requirements(selected = true, ownership = true, types = { "minecraft:player" })
    public void skinLayers(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("cape") Boolean cape,
            @Flag("hat") Boolean hat, @Flag("jacket") Boolean jacket, @Flag("sleeves") Boolean sleeves,
            @Flag("pants") Boolean pants) {
        SkinLayers trait = npc.getOrAddTrait(SkinLayers.class);
        if (cape != null) {
            trait.setVisible(PlayerModelPart.CAPE, cape);
        }
        if (hat != null) {
            trait.setVisible(PlayerModelPart.HAT, hat);
        }
        if (jacket != null) {
            trait.setVisible(PlayerModelPart.JACKET, jacket);
        }
        if (sleeves != null) {
            trait.setVisible(PlayerModelPart.LEFT_SLEEVE, sleeves);
            trait.setVisible(PlayerModelPart.RIGHT_SLEEVE, sleeves);
        }
        if (pants != null) {
            trait.setVisible(PlayerModelPart.LEFT_PANTS_LEG, pants);
            trait.setVisible(PlayerModelPart.RIGHT_PANTS_LEG, pants);
        }
        Messaging.sendTr(sender, Messages.SKIN_LAYERS_UPDATED, npc.getName());
    }


    @Command(
            aliases = { "npc" },
            usage = "attribute [attribute] [value]",
            desc = "",
            modifiers = { "attribute" },
            min = 2,
            max = 3,
            permission = "citizens.npc.attribute")
    public void attribute(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String attribute,
            @Arg(2) Double value) {
        Holder<Attribute> attr = AttributeTrait.parse(attribute);
        if (attr == null) {
            Messaging.sendErrorTr(sender, Messages.ATTRIBUTE_NOT_FOUND, attribute);
            return;
        }
        AttributeTrait trait = npc.getOrAddTrait(AttributeTrait.class);
        if (value == null) {
            trait.resetToDefaultValue(attr);
            Messaging.sendTr(sender, Messages.ATTRIBUTE_RESET, attribute);
            return;
        }
        trait.setAttributeValue(attr, value);
        Messaging.sendTr(sender, Messages.ATTRIBUTE_SET, attribute, value);
    }

    @Command(
            aliases = { "npc" },
            usage = "gamemode [gamemode]",
            desc = "",
            modifiers = { "gamemode" },
            min = 1,
            max = 2,
            permission = "citizens.npc.gamemode")
    @Requirements(selected = true, ownership = true, types = { "minecraft:player" })
    public void gamemode(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String mode) {
        GameModeTrait trait = npc.getOrAddTrait(GameModeTrait.class);
        if (args.argsLength() == 1) {
            Messaging.sendTr(sender, Messages.GAMEMODE_DESCRIBE, npc.getName(), trait.getGameMode());
            return;
        }
        GameType parsed = Util.matchEnum(GameType.values(), mode);
        if (parsed == null) {
            Messaging.sendErrorTr(sender, Messages.GAMEMODE_INVALID, mode);
            return;
        }
        trait.setGameMode(parsed);
        Messaging.sendTr(sender, Messages.GAMEMODE_SET, parsed.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "scaledmaxhealth [health]",
            desc = "",
            modifiers = { "scaledmaxhealth" },
            min = 1,
            max = 2,
            permission = "citizens.npc.scaledmaxhealth")
    public void scaledhealth(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) Double scaled) {
        npc.getOrAddTrait(ScaledMaxHealthTrait.class).setMaxHealth(scaled);
        if (npc.isSpawned()) {
            // the attribute is written on spawn, so apply it now rather than only after the next respawn
            npc.getOrAddTrait(ScaledMaxHealthTrait.class).onSpawn();
        }
        Messaging.sendTr(sender, Messages.SCALED_MAX_HEALTH_SET, scaled);
    }

    @Command(
            aliases = { "npc" },
            usage = "spectate (-r(eset))",
            desc = "",
            flags = "r",
            modifiers = { "spectate" },
            min = 1,
            max = 1,
            permission = "citizens.npc.spectate")
    public void spectate(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        if (!(sender.getEntity() instanceof ServerPlayer player))
            throw new CommandException(CommandMessages.MUST_BE_INGAME);
        boolean reset = args.hasFlag('r');
        if (!reset && !npc.isSpawned())
            throw new CommandException(Messages.MOUNT_NPC_MUST_BE_SPAWNED, npc.getName());
        player.connection.send(new ClientboundSetCameraPacket(reset ? player : npc.getEntity()));
        Messaging.sendTr(sender, reset ? Messages.SPECTATE_RESET : Messages.SPECTATE_SET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "undo (all)",
            desc = "",
            modifiers = { "undo" },
            min = 1,
            max = 2,
            permission = "citizens.npc.undo")
    @Requirements
    public void undo(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completions = "all") String action) throws CommandException {
        if ("all".equalsIgnoreCase(action)) {
            while (history().undo(sender)) {
            }
            Messaging.sendTr(sender, Messages.UNDO_SUCCESSFUL);
            return;
        }
        Messaging.sendTr(sender, history().undo(sender) ? Messages.UNDO_SUCCESSFUL : Messages.UNDO_UNSUCCESSFUL);
    }


    @Command(
            aliases = { "npc" },
            usage = "horse|donkey|mule (--color color) (--style style) (-c(hest) -b(no chest) -t(ame toggle))",
            desc = "",
            modifiers = { "horse", "donkey", "mule" },
            min = 1,
            max = 1,
            flags = "cbt",
            permission = "citizens.npc.horse")
    public void horse(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag({ "color", "colour" }) String colour, @Flag("style") String style) throws CommandException {
        EntityType<?> type = npc.getCosmeticEntityType();
        if (!Util.isHorse(type))
            throw new CommandException(CommandMessages.REQUIREMENTS_INVALID_MOB_TYPE, type);
        HorseModifiers horse = npc.getOrAddTrait(HorseModifiers.class);
        StringBuilder output = new StringBuilder();
        if (args.hasFlag('c')) {
            horse.setCarryingChest(true);
            output.append(Messaging.tr(Messages.HORSE_CHEST_SET)).append(' ');
        } else if (args.hasFlag('b')) {
            horse.setCarryingChest(false);
            output.append(Messaging.tr(Messages.HORSE_CHEST_UNSET)).append(' ');
        }
        if (args.hasFlag('t')) {
            horse.setTamed(!horse.isTamed());
            output.append(Messaging.tr(horse.isTamed() ? Messages.HORSE_TAMED_SET : Messages.HORSE_TAMED_UNSET,
                    npc.getName())).append(' ');
        }
        if (colour != null) {
            // only a horse has a coat colour; a donkey, mule, llama or camel has none
            if (type != EntityType.HORSE)
                throw new CommandException(CommandMessages.REQUIREMENTS_INVALID_MOB_TYPE, type);
            Variant parsed = Util.matchEnum(Variant.values(), colour);
            if (parsed == null)
                throw new CommandException(Messages.INVALID_HORSE_COLOR, Util.listValuesPretty(Variant.values()));
            horse.setColor(parsed);
            output.append(Messaging.tr(Messages.HORSE_COLOR_SET, parsed.name())).append(' ');
        }
        if (style != null) {
            if (type != EntityType.HORSE)
                throw new CommandException(CommandMessages.REQUIREMENTS_INVALID_MOB_TYPE, type);
            Markings parsed = Util.matchEnum(Markings.values(), style);
            if (parsed == null)
                throw new CommandException(Messages.INVALID_HORSE_STYLE, Util.listValuesPretty(Markings.values()));
            horse.setStyle(parsed);
            output.append(Messaging.tr(Messages.HORSE_STYLE_SET, parsed.name())).append(' ');
        }
        if (output.length() == 0)
            throw new CommandUsageException();
        Messaging.send(sender, output.toString().trim());
    }

    @Command(
            aliases = { "npc" },
            usage = "item [item] (-h(and))",
            desc = "",
            modifiers = { "item" },
            min = 1,
            max = 2,
            flags = "h",
            permission = "citizens.npc.item")
    public void item(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String item)
            throws CommandException {
        ItemStack stack;
        if (args.hasFlag('h')) {
            if (!(sender.getEntity() instanceof ServerPlayer player))
                throw new CommandException(CommandMessages.MUST_BE_INGAME);
            stack = player.getMainHandItem().copy();
        } else {
            if (item == null)
                throw new CommandException(Messages.UNKNOWN_MATERIAL);
            stack = ItemStorage.parseItemStack(item, 1);
        }
        if (stack.isEmpty())
            throw new CommandException(Messages.UNKNOWN_MATERIAL);
        ItemStack provided = stack;
        npc.setItemProvider(() -> provided.copy());
        if (npc.isSpawned()) {
            // the item is read when the entity is created, so it only changes on a respawn
            npc.despawn(DespawnReason.PENDING_RESPAWN);
            npc.spawn(npc.getStoredLocation(), SpawnReason.RESPAWN);
        }
        Messaging.sendTr(sender, Messages.ITEM_SET, npc.getName(), stack.getHoverName().getString());
    }

    @Command(
            aliases = { "npc" },
            usage = "playsound [sound] (volume) (pitch) (--to [player]) (--at [x,y,z])",
            desc = "",
            modifiers = { "playsound" },
            min = 2,
            max = 4,
            permission = "citizens.npc.playsound")
    public void playsound(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String sound,
            @Arg(value = 2, defValue = "1") Float volume, @Arg(value = 3, defValue = "1") Float pitch,
            @Flag("to") String to, @Flag("at") Location at) throws CommandException {
        ResourceLocation id = ResourceLocation.tryParse(sound.contains(":") ? sound : "minecraft:" + sound);
        if (id == null)
            throw new CommandException(Messages.INVALID_SOUND);
        Location loc = at == null ? npc.getStoredLocation() : at;
        if (loc == null)
            throw new CommandException(Messages.MOUNT_NPC_MUST_BE_SPAWNED, npc.getName());
        ClientboundSoundPacket packet = new ClientboundSoundPacket(
                Holder.direct(SoundEvent.createVariableRangeEvent(id)), SoundSource.MASTER, loc.getX(), loc.getY(),
                loc.getZ(), volume, pitch, loc.getWorld().getRandom().nextLong());
        if (to != null) {
            ServerPlayer player = matchPlayer(sender, to);
            if (player == null)
                throw new CommandException(Messages.SHOP_PLAYER_NOT_FOUND, to);
            player.connection.send(packet);
            return;
        }
        for (ServerPlayer player : loc.getWorld().players()) {
            player.connection.send(packet);
        }
    }


    @Command(
            aliases = { "npc" },
            usage = "drops",
            desc = "",
            modifiers = { "drops" },
            min = 1,
            max = 1,
            permission = "citizens.npc.drops")
    public void drops(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        if (!(sender.getEntity() instanceof ServerPlayer player))
            throw new CommandException(CommandMessages.MUST_BE_INGAME);
        npc.getOrAddTrait(DropsTrait.class).displayEditor(player);
    }

    @Command(
            aliases = { "npc" },
            usage = "forcefield --width [width] --height [height] --strength [strength] --vertical-strength [strength]",
            desc = "",
            modifiers = { "forcefield" },
            min = 1,
            max = 1,
            permission = "citizens.npc.forcefield")
    public void forcefield(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("width") Double width,
            @Flag("height") Double height, @Flag("strength") Double strength,
            @Flag("vertical-strength") Double verticalStrength) throws CommandException {
        ForcefieldTrait trait = npc.getOrAddTrait(ForcefieldTrait.class);
        StringBuilder output = new StringBuilder();
        if (width != null) {
            trait.setWidth(width);
            output.append(Messaging.tr(Messages.FORCEFIELD_WIDTH_SET, width));
        }
        if (height != null) {
            trait.setHeight(height);
            output.append(Messaging.tr(Messages.FORCEFIELD_HEIGHT_SET, height));
        }
        if (strength != null) {
            trait.setStrength(strength);
            output.append(Messaging.tr(Messages.FORCEFIELD_STRENGTH_SET, strength));
        }
        if (verticalStrength != null) {
            trait.setVerticalStrength(verticalStrength);
            output.append(Messaging.tr(Messages.FORCEFIELD_VERTICAL_STRENGTH_SET, verticalStrength));
        }
        if (output.length() == 0)
            throw new CommandUsageException();
        Messaging.send(sender, output.toString().trim());
    }

    @Command(
            aliases = { "npc" },
            usage = "hitbox --scale [scale] --width [width] --height [height] --offset [x,y,z]",
            desc = "",
            modifiers = { "hitbox" },
            min = 1,
            max = 1,
            permission = "citizens.npc.hitbox")
    public void hitbox(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("scale") Float scale,
            @Flag("width") Float width, @Flag("height") Float height, @Flag("offset") String offset)
            throws CommandException {
        BoundingBoxTrait trait = npc.getOrAddTrait(BoundingBoxTrait.class);
        if (scale != null) {
            trait.setScale(scale);
        }
        if (width != null) {
            trait.setWidth(width);
        }
        if (height != null) {
            trait.setHeight(height);
        }
        if (offset != null) {
            String[] parts = offset.split(",");
            if (parts.length != 3)
                throw new CommandException(CommandMessages.INVALID_NUMBER);
            try {
                trait.setOffset(new Vec3(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2])));
            } catch (NumberFormatException e) {
                throw new CommandException(CommandMessages.INVALID_NUMBER);
            }
        }
        EntityDim dim = trait.getAdjustedDimensions();
        Messaging.sendTr(sender, Messages.BOUNDING_BOX_SET, "width " + dim.width + " height " + dim.height);
    }

    @Command(
            aliases = { "npc" },
            usage = "inventory (player name/uuid)",
            desc = "",
            modifiers = { "inventory" },
            min = 1,
            max = 2,
            permission = "citizens.npc.inventory")
    public void inventory(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String who)
            throws CommandException {
        ServerPlayer viewer = who == null ? null : matchPlayer(sender, who);
        if (viewer == null && sender.getEntity() instanceof ServerPlayer self) {
            viewer = self;
        }
        if (viewer == null)
            throw new CommandException(CommandMessages.MUST_BE_INGAME);
        npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.Inventory.class).openInventory(viewer);
    }

    @Command(
            aliases = { "npc" },
            usage = "setequipment (-c(osmetic)) [slot] [item|hand]",
            desc = "",
            flags = "c",
            modifiers = { "setequipment" },
            min = 2,
            max = 3,
            permission = "citizens.npc.setequipment")
    public void setequipment(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String slotName,
            @Arg(2) String itemName) throws CommandException {
        EquipmentSlot slot = Util.matchEnum(EquipmentSlot.values(), slotName);
        if (slot == null)
            throw new CommandException(Messages.INVALID_EQUIPMENT_SLOT, Util.listValuesPretty(EquipmentSlot.values()));
        ItemStack item = ItemStack.EMPTY;
        if (itemName != null) {
            if (itemName.equalsIgnoreCase("hand")) {
                if (!(sender.getEntity() instanceof ServerPlayer player))
                    throw new CommandException(CommandMessages.MUST_BE_INGAME);
                item = player.getMainHandItem().copy();
            } else {
                item = ItemStorage.parseItemStack(itemName, 1);
            }
        }
        if (args.hasFlag('c')) {
            npc.getOrAddTrait(Equipment.class).setCosmetic(slot, item);
            Messaging.sendTr(sender, Messages.COSMETIC_EQUIPMENT_SET, slot, item.getHoverName().getString());
            return;
        }
        npc.getOrAddTrait(Equipment.class).set(slot, item);
        Messaging.sendTr(sender, Messages.EQUIPMENT_SET, slot, item.getHoverName().getString());
    }


    @Command(
            aliases = { "npc" },
            usage = "itemframe --visible [true|false] --fixed [true|false] --rotation [rotation] --item [item] --face [face]",
            desc = "",
            modifiers = { "itemframe" },
            min = 1,
            max = 1,
            permission = "citizens.npc.itemframe")
    @Requirements(ownership = true, selected = true, cosmeticTypes = { "minecraft:item_frame",
            "minecraft:glow_item_frame" })
    public void itemframe(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("visible") Boolean visible,
            @Flag("fixed") Boolean fixed, @Flag("rotation") String rotation, @Flag("item") String item,
            @Flag("face") String face) throws CommandException {
        ItemFrameTrait ift = npc.getOrAddTrait(ItemFrameTrait.class);
        StringBuilder msg = new StringBuilder();
        if (visible != null) {
            ift.setVisible(visible);
            msg.append(' ').append(Messaging.tr(Messages.ITEMFRAME_VISIBLE_SET, visible));
        }
        if (fixed != null) {
            ift.setFixed(fixed);
            msg.append(' ').append(Messaging.tr(Messages.ITEMFRAME_FIXED_SET, fixed));
        }
        if (item != null) {
            ItemStack stack = ItemStorage.parseItemStack(item, 1);
            if (stack.isEmpty())
                throw new CommandException(Messages.UNKNOWN_MATERIAL);
            ift.setItem(stack);
            msg.append(' ').append(Messaging.tr(Messages.ITEMFRAME_ITEM_SET, stack.getHoverName().getString()));
        }
        if (face != null) {
            Direction parsed = Util.matchEnum(Direction.values(), face);
            if (parsed == null)
                throw new CommandException(Messages.INVALID_BLOCKFACE, Util.listValuesPretty(Direction.values()));
            ift.setFacing(parsed);
            msg.append(' ').append(Messaging.tr(Messages.ITEMFRAME_BLOCKFACE_SET, parsed));
        }
        if (rotation != null) {
            FrameRotation parsed = Util.matchEnum(FrameRotation.values(), rotation);
            if (parsed == null)
                throw new CommandException(Messages.INVALID_ITEMFRAME_ROTATION,
                        Util.listValuesPretty(FrameRotation.values()));
            ift.setRotation(parsed);
            msg.append(' ').append(Messaging.tr(Messages.ITEMFRAME_ROTATION_SET, parsed));
        }
        if (msg.length() == 0)
            throw new CommandUsageException();
        Messaging.send(sender, msg.toString().trim());
    }

    @Command(
            aliases = { "npc" },
            usage = "metadata set|get|remove [key] (value) (-t(emporary))",
            desc = "",
            modifiers = { "metadata" },
            flags = "t",
            min = 3,
            max = 4,
            permission = "citizens.npc.metadata")
    public void metadata(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completions = { "set", "get", "remove" }) String command, @Arg(2) String key)
            throws CommandException {
        NPC.Metadata enumKey = Util.matchEnum(NPC.Metadata.values(), key);
        if (command.equalsIgnoreCase("set")) {
            if (args.argsLength() != 4)
                throw new CommandUsageException();
            Object value = parseMetadata(args.getString(3));
            if (args.hasFlag('t')) {
                if (enumKey != null) {
                    npc.data().set(enumKey, value);
                } else {
                    npc.data().set(key, value);
                }
            } else if (enumKey != null) {
                npc.data().setPersistent(enumKey, value);
            } else {
                npc.data().setPersistent(key, value);
            }
            Messaging.sendTr(sender, Messages.METADATA_SET, enumKey != null ? enumKey : key, args.getString(3));
            return;
        }
        if (command.equalsIgnoreCase("get")) {
            if (args.argsLength() != 3)
                throw new CommandUsageException();
            Object data = enumKey != null ? npc.data().get(enumKey) : npc.data().get(key);
            Messaging.send(sender, data == null ? "null" : data.toString());
            return;
        }
        if (command.equalsIgnoreCase("remove")) {
            if (args.argsLength() != 3)
                throw new CommandUsageException();
            if (enumKey != null) {
                npc.data().remove(enumKey);
            } else {
                npc.data().remove(key);
            }
            Messaging.sendTr(sender, Messages.METADATA_REMOVED, enumKey != null ? enumKey : key);
            return;
        }
        throw new CommandUsageException();
    }

    /** Metadata values are typed by what they look like, so "5" is a number and "true" a boolean, as upstream does. */
    private static Object parseMetadata(String raw) {
        if (raw.equals("true") || raw.equals("false"))
            return Boolean.parseBoolean(raw);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
        }
        return raw;
    }

    /** Online players only: dispatching commands needs a real player to dispatch on. */
    private static ServerPlayer matchPlayer(CommandSourceStack sender, String raw) {
        MinecraftServer server = sender.getServer();
        if (server == null)
            return null;
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return server.getPlayerList().getPlayerByName(raw);
        }
    }

    /**
     * Resolves a name or UUID to a player's UUID, for the commands that only need an identity - forgetting a player's
     * cooldowns works whether or not they are online.
     * <p>
     * Upstream uses Bukkit's {@code OfflinePlayer} and its {@code hasPlayedBefore} check. NeoForge's equivalent is the
     * server's own profile cache, which is what populates the whitelist and ban list, so a name that has ever joined
     * still resolves.
     */
    private static UUID matchPlayerUUID(CommandSourceStack sender, String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            MinecraftServer server = sender.getServer();
            if (server == null)
                return null;
            ServerPlayer online = server.getPlayerList().getPlayerByName(raw);
            if (online != null)
                return online.getUUID();
            return server.getProfileCache() == null ? null
                    : server.getProfileCache().get(raw).map(profile -> profile.getId()).orElse(null);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "controllable|control (-m(ount),-o(wner required)) (--controls [controls]) (--enabled [true|false])",
            desc = "",
            modifiers = { "controllable", "control" },
            min = 1,
            max = 1,
            flags = "mo",
            permission = "citizens.npc.controllable")
    public void controllable(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag("controls") BuiltInControls controls, @Flag("enabled") Boolean enabled) throws CommandException {
        if (npc.isSpawned() && !PermissionUtil.hasPermission(sender, "citizens.npc.controllable." + BuiltInRegistries
                .ENTITY_TYPE.getKey(npc.getEntity().getType()).getPath().toLowerCase(Locale.ROOT)))
            throw new NoPermissionsException();
        if (!npc.hasTrait(Controllable.class) && enabled == null) {
            npc.getOrAddTrait(Controllable.class).setEnabled(false);
        }
        Controllable trait = npc.getOrAddTrait(Controllable.class);
        if (controls != null) {
            trait.setControls(controls);
            Messaging.sendTr(sender, Messages.CONTROLLABLE_CONTROLS_SET, controls);
            return;
        }
        if (enabled != null) {
            trait.setEnabled(enabled);
        } else {
            enabled = trait.toggle();
        }
        trait.setOwnerRequired(args.hasFlag('o'));
        Messaging.sendTr(sender, enabled ? Messages.CONTROLLABLE_SET : Messages.CONTROLLABLE_REMOVED, npc.getName());
        if (trait.isEnabled() && args.hasFlag('m') && sender.getEntity() instanceof ServerPlayer player) {
            trait.mount(player);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "copy (--name newname)",
            desc = "",
            modifiers = { "copy" },
            min = 1,
            max = 1,
            permission = "citizens.npc.copy")
    public void copy(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("name") String name)
            throws CommandException {
        // a copy counts against the limit just as a create does; upstream checks it through its Clone events
        checkCreationLimit(sender);
        NPC copy = npc.clone();
        if (name != null) {
            copy.setName(name);
        }
        if (copy.isSpawned() && args.getSenderLocation() != null) {
            copy.teleport(args.getSenderLocation(), TeleportCause.COMMAND);
        }
        CitizensAPI.getDefaultNPCSelector().select(sender, copy);
        Messaging.sendTr(sender, Messages.NPC_COPIED, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "create [name] (--type type) (--at x,y,z,world) (--trait trait1,trait2) (--registry name) (-t(emporary) -b(aby) -s(ilent))",
            desc = "",
            flags = "tbs",
            modifiers = { "create" },
            min = 2,
            permission = "citizens.npc.create")
    @Requirements
    public void create(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("at") String at,
            @Flag(value = "type", defValue = "player") String rawType, @Flag("trait") String traits,
            @Flag(value = "nameplate", completions = { "true", "false", "hover" }) String nameplate,
            @Flag(value = "template", completionsProvider = TemplateCommands.TemplateCompletions.class) String templateName,
            @Flag("registry") String registryName) throws CommandException {
        EntityType<?> type = MobType.match(rawType);
        if (type == null)
            throw new CommandException(Messages.NPC_CREATE_INVALID_MOBTYPE, rawType);
        if (!EntityControllers.controllerExistsForType(type))
            throw new CommandException(Messages.NPC_CREATE_MISSING_MOBTYPE, rawType);
        checkCreationLimit(sender);

        String name = args.getJoinedStrings(1).trim();
        int nameLength = EntityUtil.getMaxNameLength(type);
        if (Placeholders.replace(Messaging.stripColor(name), sender, npc).length() > nameLength) {
            Messaging.sendErrorTr(sender, Messages.NPC_NAME_TOO_LONG, nameLength);
            name = name.substring(0, nameLength);
        }
        if (name.isEmpty())
            throw new CommandException();

        String typePath = EntityType.getKey(type).getPath();
        if (!PermissionUtil.hasPermission(sender, "citizens.npc.create.*")
                && !PermissionUtil.hasPermission(sender, "citizens.npc.createall")
                && !PermissionUtil.hasPermission(sender, "citizens.npc.create." + typePath))
            throw new NoPermissionsException();
        if ((at != null || registryName != null || traits != null)
                && !PermissionUtil.hasPermission(sender, "citizens.npc.admin"))
            throw new NoPermissionsException();

        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registryName != null) {
            registry = CitizensAPI.getNamedNPCRegistry(registryName);
            if (registry == null) {
                registry = CitizensAPI.createNamedNPCRegistry(registryName, new MemoryNPCDataStore());
                Messaging.send(sender, "An in-memory registry has been created named [[" + registryName + "]].");
            }
        }
        if (args.hasFlag('t')) {
            registry = temporaryRegistry();
        }
        NPC created = registry.createNPC(type, name);
        created.getOrAddTrait(MobType.class).setType(type);
        StringBuilder msg = new StringBuilder("Created [[" + created.getName() + "]] (ID [[" + created.getId() + "]])");

        if (args.hasFlag('b')) {
            msg.append(" as a baby");
            created.getOrAddTrait(Age.class).setAge(-24000);
        }
        if (args.hasFlag('s')) {
            created.data().setPersistent(NPC.Metadata.SILENT, true);
        }
        if (nameplate != null) {
            created.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE,
                    nameplate.equalsIgnoreCase("hover") ? "hover" : Boolean.parseBoolean(nameplate));
        }
        if (!Setting.SERVER_OWNS_NPCS.asBoolean()) {
            created.getOrAddTrait(Owner.class).setOwner(sender);
        }
        if (traits != null) {
            List<String> added = new ArrayList<>();
            for (String traitName : Splitter.on(',').trimResults().split(traits)) {
                Trait trait = CitizensAPI.getTraitFactory().getTrait(traitName);
                if (trait == null) {
                    continue;
                }
                created.addTrait(trait);
                added.add(StringHelper.wrap(traitName));
            }
            if (!added.isEmpty()) {
                msg.append(" with traits ").append(Joiner.on(", ").join(added));
            }
        }
        if (templateName != null) {
            // comma-separated, and each entry is either namespace:name or a bare name that must be unambiguous. A name
            // that resolves to nothing is skipped rather than failing the create, as upstream does - the NPC is already
            // made by this point, so aborting would leave it half-configured.
            List<String> applied = new ArrayList<>();
            for (String part : Splitter.on(',').trimResults().omitEmptyStrings().split(templateName)) {
                Template template = null;
                if (part.contains(":")) {
                    ResourceLocation key = ResourceLocation.tryParse(part.toLowerCase(Locale.ROOT));
                    template = key == null ? null : CitizensAPI.getTemplateRegistry().getTemplateByKey(key);
                } else {
                    Collection<Template> matches = CitizensAPI.getTemplateRegistry().getTemplates(part);
                    if (matches.size() == 1) {
                        template = matches.iterator().next();
                    }
                }
                if (template == null) {
                    Messaging.sendErrorTr(sender, Messages.TEMPLATE_MISSING);
                    continue;
                }
                template.apply(created);
                applied.add(StringHelper.wrap(part));
            }
            if (!applied.isEmpty()) {
                msg.append(" with templates ").append(Joiner.on(", ").join(applied));
            }
        }
        Location spawnAt = at != null ? args.parseLocation(at) : args.getSenderLocation();
        if (spawnAt == null) {
            msg.append(" unspawned - run this in-world or pass --at to place it");
        } else {
            created.spawn(spawnAt, SpawnReason.COMMAND);
        }
        CitizensAPI.getDefaultNPCSelector().select(sender, created);
        Messaging.send(sender, msg.append('.').toString());
    }

    @Command(
            aliases = { "npc" },
            usage = "deselect",
            desc = "",
            modifiers = { "deselect", "desel" },
            min = 1,
            max = 1,
            permission = "citizens.npc.select")
    @Requirements
    public void deselect(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        CitizensAPI.getDefaultNPCSelector().deselect(sender);
        Messaging.sendTr(sender, Messages.DESELECTED_NPC);
    }

    @Command(
            aliases = { "npc" },
            usage = "despawn (id)",
            desc = "",
            modifiers = { "despawn" },
            min = 1,
            max = 2,
            permission = "citizens.npc.despawn")
    @Requirements
    public void despawn(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        NPCCommandSelector.Callback callback = target -> {
            if (target == null)
                throw new CommandException(Messages.NO_NPC_WITH_ID_FOUND, args.getString(1));
            // cleared first, so the NPC stays away across a restart rather than coming back
            target.getOrAddTrait(Spawned.class).setSpawned(false);
            target.despawn(DespawnReason.PLUGIN);
            Messaging.sendTr(sender, Messages.NPC_DESPAWNED, target.getName());
        };
        if (npc == null || args.argsLength() == 2) {
            if (args.argsLength() < 2)
                throw new CommandException(CommandMessages.MUST_HAVE_SELECTED);

            // the argument may be an id, a uuid or a name; a name matching several NPCs asks which one
            NPCCommandSelector.startWithCallback(callback, CitizensAPI.getNPCRegistry(), sender, args,
                    args.getString(1));
            return;
        }
        callback.run(npc);
    }

    @Command(
            aliases = { "npc" },
            usage = "follow (player name) (-c(ancel) -p(rotect)) (--margin margin)",
            desc = "",
            flags = "cp",
            modifiers = { "follow" },
            min = 1,
            max = 2,
            permission = "citizens.npc.follow")
    public void follow(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("margin") Double margin)
            throws CommandException {
        FollowTrait trait = npc.getOrAddTrait(FollowTrait.class);
        if (margin != null) {
            trait.setFollowingMargin(margin);
            Messaging.sendTr(sender, Messages.FOLLOW_MARGIN_SET, margin);
            return;
        }
        if (args.hasFlag('c') || args.argsLength() == 1 && trait.isEnabled()) {
            trait.follow(null);
            Messaging.sendTr(sender, Messages.FOLLOW_UNSET, npc.getName(), "");
            return;
        }
        ServerPlayer target = args.argsLength() > 1 ? playerByName(sender, args.getString(1)) : sender.getPlayer();
        if (target == null) {
            // upstream falls back to following another NPC when the name is not a player's, which is how an NPC is made
            // to follow an NPC at all. Only reachable with an explicit name, and gated the same way upstream gates it.
            if (args.argsLength() < 2)
                throw new CommandException(CommandMessages.PLAYER_NOT_FOUND_FOR_SPAWN);
            if (!PermissionUtil.hasPermission(sender, "citizens.npc.follow.others"))
                throw new CommandException(CommandMessages.NO_PERMISSION);

            NPCCommandSelector.Callback callback = following -> {
                if (following == null)
                    throw new CommandException(CommandMessages.PLAYER_NOT_FOUND_FOR_SPAWN);
                if (sender.getPlayer() != null && !following.getOrAddTrait(Owner.class).isOwnedBy(sender))
                    throw new CommandException(CommandMessages.MUST_BE_OWNER);
                if (!following.isSpawned())
                    throw new CommandException(Messages.MOUNT_NPC_MUST_BE_SPAWNED, following.getName());

                trait.setProtecting(args.hasFlag('p'));
                trait.follow(following.getEntity());
                Messaging.sendTr(sender, Messages.FOLLOW_SET, npc.getName(), following.getName());
            };
            NPCCommandSelector.startWithCallback(callback, CitizensAPI.getNPCRegistry(), sender, args,
                    args.getString(1));
            return;
        }
        trait.setProtecting(args.hasFlag('p'));
        trait.follow(target);
        Messaging.sendTr(sender, Messages.FOLLOW_SET, npc.getName(), target.getGameProfile().getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "glowing (--color color)",
            desc = "",
            modifiers = { "glowing" },
            min = 1,
            max = 1,
            permission = "citizens.npc.glowing")
    public void glowing(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("color") String color)
            throws CommandException {
        if (color != null) {
            ChatFormatting formatting = ChatFormatting.getByName(color.toUpperCase(Locale.ROOT));
            if (formatting == null || formatting.isFormat())
                throw new CommandException(Messages.GLOWING_COLOR_CANNOT_BE_FORMAT);
            npc.getOrAddTrait(ScoreboardTrait.class).setColor(formatting);
            Messaging.sendTr(sender, Messages.GLOWING_COLOR_SET, npc.getName(), color);
            return;
        }
        boolean glowing = !npc.data().get(NPC.Metadata.GLOWING, false);
        npc.data().setPersistent(NPC.Metadata.GLOWING, glowing);
        Messaging.sendTr(sender, glowing ? Messages.GLOWING_SET : Messages.GLOWING_UNSET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "gravity",
            desc = "",
            modifiers = { "gravity" },
            min = 1,
            max = 1,
            permission = "citizens.npc.gravity")
    public void gravity(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        boolean enabled = npc.getOrAddTrait(Gravity.class).toggle();
        Messaging.sendTr(sender, enabled ? Messages.GRAVITY_DISABLED : Messages.GRAVITY_ENABLED, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "home (--location x,y,z,world) (--distance blocks) (--delay ticks) (-p(athfind) -t(eleport) -c(lear))",
            desc = "",
            flags = "ptc",
            modifiers = { "home" },
            min = 1,
            max = 1,
            permission = "citizens.npc.home")
    public void home(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("location") String location,
            @Flag("distance") Double distance, @Flag("delay") Integer delay) throws CommandException {
        HomeTrait trait = npc.getOrAddTrait(HomeTrait.class);
        if (args.hasFlag('c')) {
            trait.setHomeLocation(null);
            Messaging.send(sender, "Home location cleared.");
            return;
        }
        boolean changed = false;
        if (args.hasFlag('p') || args.hasFlag('t')) {
            trait.setReturnStrategy(args.hasFlag('p') ? ReturnStrategy.PATHFIND : ReturnStrategy.TELEPORT);
            Messaging.sendTr(sender,
                    args.hasFlag('p') ? Messages.HOME_TRAIT_PATHFIND_SET : Messages.HOME_TRAIT_TELEPORT_SET);
            changed = true;
        }
        if (distance != null) {
            trait.setDistanceBlocks(distance);
            Messaging.sendTr(sender, Messages.HOME_TRAIT_DISTANCE_SET, distance);
            changed = true;
        }
        if (delay != null) {
            trait.setDelayTicks(delay);
            Messaging.sendTr(sender, Messages.HOME_TRAIT_DELAY_SET, delay);
            changed = true;
        }
        if (location != null || !changed) {
            Location at = location != null ? args.parseLocation(location) : args.getSenderLocation();
            if (at == null)
                throw new CommandException(CommandMessages.MUST_BE_INGAME);
            trait.setHomeLocation(at);
            Messaging.sendTr(sender, Messages.HOME_TRAIT_LOCATION_SET, at.getBlockX(), at.getBlockY(), at.getBlockZ());
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "id",
            desc = "",
            modifiers = { "id" },
            min = 1,
            max = 1,
            permission = "citizens.npc.id")
    public void id(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        Messaging.send(sender, "[[" + npc.getId() + "]] (uuid [[" + npc.getUniqueId() + "]])");
    }

    @Command(
            aliases = { "npc" },
            usage = "list (page) (--type type) (--owner owner)",
            desc = "",
            modifiers = { "list" },
            min = 1,
            max = 2,
            permission = "citizens.npc.list")
    @Requirements
    public void list(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("type") String rawType,
            @Flag("owner") String owner) throws CommandException {
        EntityType<?> filterType = rawType == null ? null : MobType.match(rawType);
        Paginator paginator = new Paginator().header("NPCs").console(sender.getPlayer() == null);
        for (NPC each : CitizensAPI.getNPCRegistry().sorted()) {
            EntityType<?> type = each.getOrAddTrait(MobType.class).getType();
            if (filterType != null && type != filterType) {
                continue;
            }
            if (owner != null && !each.getOrAddTrait(Owner.class).isOwnedBy(owner)) {
                continue;
            }
            paginator.addLine("<e>" + each.getId() + "<gray> - " + each.getName() + " <gray>("
                    + EntityType.getKey(type).getPath() + ", " + (each.isSpawned() ? "spawned" : "despawned") + ")");
        }
        int page = args.getInteger(1, 1);
        if (!paginator.sendPage(sender, page))
            throw new CommandException(CommandMessages.COMMAND_PAGE_MISSING, page);
    }

    @Command(
            aliases = { "npc" },
            usage = "lookclose (--range range) (-r(andom) -h(eadonly) -p(erplayer) -d(isable while navigating))",
            desc = "",
            flags = "rhpd",
            modifiers = { "lookclose", "look" },
            min = 1,
            max = 1,
            permission = "citizens.npc.lookclose")
    public void lookclose(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("range") Double range)
            throws CommandException {
        LookClose trait = npc.getOrAddTrait(LookClose.class);
        if (range != null) {
            trait.setRange(range);
            Messaging.sendTr(sender, Messages.LOOKCLOSE_RANGE_SET, range);
            return;
        }
        if (args.hasFlag('r')) {
            trait.setRandomLook(!trait.isRandomLook());
            Messaging.sendTr(sender, Messages.LOOKCLOSE_RANDOM_SET, npc.getName(), trait.isRandomLook());
            return;
        }
        if (args.hasFlag('h')) {
            trait.setHeadOnly(!trait.isHeadOnly());
            Messaging.send(sender, "Head-only looking is now " + trait.isHeadOnly() + ".");
            return;
        }
        if (args.hasFlag('p')) {
            trait.setPerPlayer(!trait.isPerPlayer());
            Messaging.sendTr(sender,
                    trait.isPerPlayer() ? Messages.LOOKCLOSE_PERPLAYER_SET : Messages.LOOKCLOSE_PERPLAYER_UNSET,
                    npc.getName());
            return;
        }
        if (args.hasFlag('d')) {
            trait.setDisableWhileNavigating(!trait.disableWhileNavigating());
            Messaging.sendTr(sender,
                    trait.disableWhileNavigating() ? Messages.LOOKCLOSE_DISABLE_WHEN_NAVIGATING
                            : Messages.LOOKCLOSE_ENABLE_WHEN_NAVIGATING,
                    npc.getName());
            return;
        }
        boolean enabled = trait.toggle();
        Messaging.sendTr(sender, enabled ? Messages.LOOKCLOSE_SET : Messages.LOOKCLOSE_STOPPED, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            modifiers = { "mirror" },
            usage = "mirror --name [true|false] --equipment [true|false]",
            desc = "",
            min = 1,
            max = 1,
            permission = "citizens.npc.mirror")
    public void mirror(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("name") Boolean name,
            @Flag("equipment") Boolean equipment) throws CommandException {
        // upstream refuses this command outright unless the PacketEvents library is installed, because Bukkit cannot
        // rewrite a packet per recipient. Nothing extra is needed here, so there is nothing to refuse.
        MirrorTrait trait = npc.getOrAddTrait(MirrorTrait.class);
        if (equipment != null) {
            trait.setMirrorEquipment(equipment);
        }
        if (name != null) {
            trait.setEnabled(true);
            trait.setMirrorName(name);
            Messaging.sendTr(sender, name ? Messages.MIRROR_NAME_SET : Messages.MIRROR_NAME_UNSET, npc.getName());
            return;
        }
        boolean enabled = !trait.isEnabled();
        trait.setEnabled(enabled);
        Messaging.sendTr(sender, enabled ? Messages.MIRROR_SET : Messages.MIRROR_UNSET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "mount (--onnpc id)",
            desc = "",
            modifiers = { "mount" },
            min = 1,
            max = 1,
            permission = "citizens.npc.mount")
    public void mount(CommandContext args, ServerPlayer player, NPC npc, @Flag("onnpc") Integer onNPC)
            throws CommandException {
        CommandSourceStack sender = player.createCommandSourceStack();
        if (onNPC != null) {
            NPC mount = CitizensAPI.getNPCRegistry().getById(onNPC);
            if (mount == null || !mount.isSpawned())
                throw new CommandException(Messages.MOUNT_NPC_MUST_BE_SPAWNED, onNPC);
            if (mount == npc)
                throw new CommandException(Messages.MOUNT_TRIED_TO_MOUNT_NPC_ON_ITSELF);
            npc.getOrAddTrait(MountTrait.class).setMountedOn(mount.getUniqueId());
            return;
        }
        if (!npc.isSpawned())
            throw new CommandException(Messages.MOUNT_NPC_MUST_BE_SPAWNED, npc.getName());
        if (!player.startRiding(npc.getEntity(), true)) {
            Messaging.sendTr(sender, Messages.FAILED_TO_MOUNT_NPC, npc.getName());
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "name",
            desc = "",
            modifiers = { "name", "hidename" },
            min = 1,
            max = 2,
            permission = "citizens.npc.name")
    public void name(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        Object current = npc.data().get(NPC.Metadata.NAMEPLATE_VISIBLE, true);
        Object next;
        if (args.argsLength() > 1) {
            String raw = args.getString(1);
            next = raw.equalsIgnoreCase("hover") ? "hover" : Boolean.parseBoolean(raw);
        } else {
            next = !Boolean.parseBoolean(current.toString());
        }
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, next);
        npc.getOrAddTrait(ScoreboardTrait.class);
        Messaging.sendTr(sender, Messages.NAMEPLATE_VISIBILITY_SET, next);
    }

    @Command(
            aliases = { "npc" },
            usage = "owner [name]",
            desc = "",
            modifiers = { "owner" },
            min = 1,
            max = 2,
            permission = "citizens.npc.owner")
    public void owner(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        Owner trait = npc.getOrAddTrait(Owner.class);
        if (args.argsLength() == 1) {
            Messaging.sendTr(sender, Messages.NPC_OWNER, npc.getName(), trait.getOwner());
            return;
        }
        String name = args.getString(1);
        if (trait.isOwnedBy(name))
            throw new CommandException(Messages.ALREADY_OWNER, name, npc.getName());
        if (name.equalsIgnoreCase("server")) {
            trait.setOwner((java.util.UUID) null);
            Messaging.sendTr(sender, Messages.OWNER_SET_SERVER, npc.getName());
            return;
        }
        ServerPlayer target = playerByName(sender, name);
        if (target != null) {
            trait.setOwner(target.getGameProfile().getName(), target.getUUID());
        } else {
            trait.setOwner(name);
        }
        Messaging.sendTr(sender, Messages.OWNER_SET, npc.getName(), name);
    }

    @Command(
            aliases = { "npc" },
            usage = "moveto x:y:z:world | x y z world",
            desc = "",
            modifiers = "moveto",
            min = 1,
            valueFlags = { "x", "y", "z", "yaw", "pitch", "world" },
            permission = "citizens.npc.moveto")
    public void moveto(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        if (!npc.isSpawned()) {
            npc.spawn(npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.CurrentLocation.class).getLocation(), SpawnReason.COMMAND);
            if (!npc.isSpawned())
                throw new CommandException(Messages.MOVETO_FORMAT);
        }
        Location current = npc.getStoredLocation();
        Location to;
        if (args.argsLength() > 1) {
            String[] parts = args.getJoinedStrings(1, ':').split(":");
            if (parts.length != 4 && parts.length != 3)
                throw new CommandException(Messages.MOVETO_FORMAT);
            ServerLevel level = parts.length == 4 ? Util.getLevel(sender.getServer(), parts[3]) : current.getWorld();
            if (level == null)
                throw new CommandException(Messages.WORLD_NOT_FOUND);
            to = new Location(level, Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), current.getYaw(), current.getPitch());
        } else {
            to = current.clone();
            if (args.hasValueFlag("x")) {
                to.setX(args.getFlagDouble("x"));
            }
            if (args.hasValueFlag("y")) {
                to.setY(args.getFlagDouble("y"));
            }
            if (args.hasValueFlag("z")) {
                to.setZ(args.getFlagDouble("z"));
            }
            if (args.hasValueFlag("yaw")) {
                to.setYaw((float) args.getFlagDouble("yaw"));
            }
            if (args.hasValueFlag("pitch")) {
                to.setPitch((float) args.getFlagDouble("pitch"));
            }
            if (args.hasValueFlag("world")) {
                ServerLevel level = Util.getLevel(sender.getServer(), args.getFlag("world"));
                if (level == null)
                    throw new CommandException(Messages.WORLD_NOT_FOUND);
                to.setWorld(level);
            }
        }
        npc.teleport(to, TeleportCause.COMMAND);
        npc.faceLocation(to);
        Messaging.sendTr(sender, Messages.MOVETO_TELEPORTED, npc.getName(), Util.prettyPrintLocation(to));
    }

    @Command(
            aliases = { "npc" },
            usage = "pathopt --avoid-teleporting [true|false] --avoid-water [true|false] --attack-delay-duration [duration] --destination-teleport-margin [margin] --open-doors [true|false] --path-range [range] --stationary-ticks [ticks] --attack-range [range] --distance-margin [margin] --path-distance-margin [margin] --pathfinder-type [CITIZENS|CITIZENS_ASYNC|MINECRAFT] --falling-distance [distance]",
            desc = "",
            modifiers = { "pathopt", "po", "patho" },
            min = 1,
            max = 1,
            permission = "citizens.npc.pathfindingoptions")
    @Requirements(selected = true, ownership = true)
    public void pathfindingOptions(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag("path-range") Float range, @Flag("avoid-water") Boolean avoidwater,
            @Flag("avoid-teleporting") Boolean avoidteleporting, @Flag("open-doors") Boolean opendoors,
            @Flag("stationary-ticks") Integer stationaryTicks,
            @Flag("destination-teleport-margin") Double destinationTeleportMargin,
            @Flag("distance-margin") Double distanceMargin, @Flag("attack-delay-duration") Duration duration,
            @Flag("path-distance-margin") Double pathDistanceMargin, @Flag("attack-range") Double attackRange,
            @Flag("falling-distance") Integer fallingDistance,
            @Flag("pathfinder-type") PathfinderType pathfinderType) throws CommandException {
        NavigatorParameters params = npc.getNavigator().getDefaultParameters();
        String output = "";

        if (avoidteleporting != null) {
            npc.data().setPersistent(NPC.Metadata.DISABLE_DEFAULT_STUCK_ACTION, avoidteleporting);
            params.stuckAction(avoidteleporting ? null : TeleportStuckAction.INSTANCE);
            output += Messaging.tr(avoidteleporting ? Messages.WAYPOINT_TELEPORTING_DISABLED
                    : Messages.WAYPOINT_TELEPORTING_ENABLED, npc.getName());
        }
        if (avoidwater != null) {
            params.avoidWater(avoidwater);
            output += Messaging.tr(avoidwater ? Messages.PATHFINDING_OPTIONS_AVOID_WATER_SET
                    : Messages.PATHFINDING_OPTIONS_AVOID_WATER_UNSET, npc.getName());
        }
        if (duration != null) {
            params.attackDelayTicks(Durations.toTicks(duration));
            output += Messaging.tr(Messages.PATHFINDING_OPTIONS_ATTACK_DELAY_TICKS_SET, npc.getName(),
                    Durations.toTicks(duration));
        }
        if (opendoors != null) {
            npc.data().setPersistent(NPC.Metadata.PATHFINDER_OPEN_DOORS, opendoors);
            output += Messaging.tr(opendoors ? Messages.PATHFINDING_OPTIONS_OPEN_DOORS_SET
                    : Messages.PATHFINDING_OPTIONS_OPEN_DOORS_UNSET, npc.getName());
        }
        if (stationaryTicks != null) {
            if (stationaryTicks < 0)
                throw new CommandUsageException();
            params.stationaryTicks(stationaryTicks);
            output += " "
                    + Messaging.tr(Messages.PATHFINDING_OPTIONS_STATIONARY_TICKS_SET, npc.getName(), stationaryTicks);
        }
        if (destinationTeleportMargin != null) {
            if (destinationTeleportMargin < 0)
                throw new CommandUsageException();
            params.destinationTeleportMargin(destinationTeleportMargin);
            output += " " + Messaging.tr(Messages.PATHFINDING_OPTIONS_DESTINATION_TELEPORT_MARGIN_SET, npc.getName(),
                    destinationTeleportMargin);
        }
        if (distanceMargin != null) {
            if (distanceMargin < 0)
                throw new CommandUsageException();
            params.distanceMargin(distanceMargin);
            output += " "
                    + Messaging.tr(Messages.PATHFINDING_OPTIONS_DISTANCE_MARGIN_SET, npc.getName(), distanceMargin);
        }
        if (range != null) {
            if (range < 1)
                throw new CommandUsageException();
            params.range(range);
            output += " " + Messaging.tr(Messages.PATHFINDING_RANGE_SET, range);
        }
        if (pathDistanceMargin != null) {
            if (pathDistanceMargin < 0)
                throw new CommandUsageException();
            params.pathDistanceMargin(pathDistanceMargin);
            output += " " + Messaging.tr(Messages.PATHFINDING_OPTIONS_PATH_DISTANCE_MARGIN_SET, npc.getName(),
                    pathDistanceMargin);
        }
        if (attackRange != null) {
            if (attackRange < 0)
                throw new CommandUsageException();
            params.attackRange(attackRange);
            output += " " + Messaging.tr(Messages.PATHFINDING_OPTIONS_ATTACK_RANGE_SET, npc.getName(), attackRange);
        }
        if (pathfinderType != null) {
            params.pathfinderType(pathfinderType);
            output += " " + Messaging.tr(Messages.PATHFINDING_OPTIONS_PATHFINDER_TYPE, npc.getName(), pathfinderType);
        }
        if (fallingDistance != null) {
            params.fallDistance(fallingDistance);
            output += " "
                    + Messaging.tr(Messages.PATHFINDING_OPTIONS_FALLING_DISTANCE_SET, npc.getName(), fallingDistance);
        }
        if (output.isEmpty())
            throw new CommandUsageException();
        Messaging.send(sender, output.trim());
    }

    @Command(
            aliases = { "npc" },
            usage = "pathto me | here | cursor | [x] [y] [z] (--margin [distance margin]) (-s[traight line])",
            desc = "",
            modifiers = { "pathto" },
            min = 2,
            max = 4,
            flags = "s",
            permission = "citizens.npc.pathto")
    @Requirements(selected = true, ownership = true)
    public void pathto(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completions = { "me", "here", "cursor" }) String option, @Flag("margin") Double margin)
            throws CommandException {
        Location loc = npc.getStoredLocation();
        if (args.argsLength() == 2) {
            if (option.equalsIgnoreCase("me") || option.equalsIgnoreCase("here")) {
                loc = args.getSenderLocation();
            } else if (option.equalsIgnoreCase("cursor")) {
                loc = args.getSenderTargetBlockLocation();
            } else
                throw new CommandUsageException();
            if (loc == null)
                throw new CommandException(CommandMessages.MUST_BE_INGAME);
        } else {
            loc = new Location(loc.getWorld(), args.getDouble(1), args.getDouble(2), args.getDouble(3), loc.getYaw(),
                    loc.getPitch());
        }
        if (args.hasFlag('s')) {
            npc.getNavigator().setStraightLineTarget(loc);
        } else {
            npc.getNavigator().setTarget(loc);
        }
        // the margin applies to the navigation that was just started, so it goes on the local parameters
        if (margin != null) {
            npc.getNavigator().getLocalParameters().distanceMargin(margin);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "pose (--save name|--assume name|--remove name) (--default name)",
            desc = "",
            modifiers = { "pose" },
            min = 1,
            max = 2,
            permission = "citizens.npc.pose")
    public void pose(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("save") String save,
            @Flag("assume") String assume, @Flag("remove") String remove, @Flag("default") String defaultPose)
            throws CommandException {
        Poses trait = npc.getOrAddTrait(Poses.class);
        if (save != null) {
            Location at = args.getSenderLocation();
            if (at == null)
                throw new CommandException(CommandMessages.MUST_BE_INGAME);
            if (!trait.addPose(save, at))
                throw new CommandException(Messages.POSE_ALREADY_EXISTS, save);
            Messaging.sendTr(sender, Messages.POSE_ADDED);
            return;
        }
        if (remove != null) {
            if (!trait.removePose(remove))
                throw new CommandException(Messages.POSE_MISSING, remove);
            Messaging.sendTr(sender, Messages.POSE_REMOVED);
            return;
        }
        if (assume != null) {
            if (!trait.hasPose(assume))
                throw new CommandException(Messages.POSE_MISSING, assume);
            trait.assumePose(assume);
            return;
        }
        if (defaultPose != null) {
            if (!trait.hasPose(defaultPose))
                throw new CommandException(Messages.POSE_MISSING, defaultPose);
            trait.setDefaultPose(defaultPose);
            Messaging.sendTr(sender, Messages.DEFAULT_POSE_SET, defaultPose);
            return;
        }
        Paginator paginator = new Paginator().header("Poses").console(sender.getPlayer() == null);
        trait.getPoses().values().forEach(pose -> paginator.addLine("<e>- " + pose.getName()));
        if (!paginator.sendPage(sender, args.getInteger(1, 1)))
            throw new CommandException(CommandMessages.COMMAND_PAGE_MISSING, args.getInteger(1, 1));
    }

    @Command(
            aliases = { "npc" },
            usage = "powered",
            desc = "",
            modifiers = { "powered" },
            min = 1,
            max = 1,
            permission = "citizens.npc.powered")
    @Requirements(selected = true, ownership = true, types = { "creeper" })
    public void powered(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        Powered trait = npc.getOrAddTrait(Powered.class);
        trait.setPowered(!trait.isPowered());
        Messaging.sendTr(sender, trait.isPowered() ? Messages.POWERED_SET : Messages.POWERED_STOPPED, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "profession [profession]",
            desc = "",
            modifiers = { "profession", "prof" },
            min = 2,
            max = 2,
            permission = "citizens.npc.profession")
    @Requirements(selected = true, ownership = true, types = { "villager", "zombie_villager" })
    public void profession(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        String raw = args.getString(1);
        net.minecraft.world.entity.npc.VillagerProfession parsed = VillagerProfession.parse(raw);
        if (parsed == null)
            throw new CommandException(Messages.INVALID_PROFESSION, raw, npc.getName());
        npc.getOrAddTrait(VillagerProfession.class).setProfession(parsed);
        Messaging.sendTr(sender, Messages.PROFESSION_SET, npc.getName(), raw);
    }

    @Command(
            aliases = { "npc" },
            usage = "saddle",
            desc = "",
            modifiers = { "saddle" },
            min = 1,
            max = 1,
            permission = "citizens.npc.saddle")
    @Requirements(selected = true, ownership = true, types = { "pig", "strider" })
    public void saddle(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        boolean saddled = npc.getOrAddTrait(Saddle.class).toggle();
        Messaging.send(sender, "[[" + npc.getName() + "]] is " + (saddled ? "now" : "no longer") + " saddled.");
    }

    @Command(
            aliases = { "npc" },
            usage = "sheep (--color color) (--sheared true|false)",
            desc = "",
            modifiers = { "sheep" },
            min = 1,
            max = 1,
            permission = "citizens.npc.sheep")
    @Requirements(selected = true, ownership = true, types = { "sheep" })
    public void sheep(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("color") String color,
            @Flag("sheared") Boolean sheared) throws CommandException {
        SheepTrait trait = npc.getOrAddTrait(SheepTrait.class);
        if (sheared != null) {
            trait.setSheared(sheared);
            Messaging.send(sender, "[[" + npc.getName() + "]] is " + (sheared ? "now" : "no longer") + " sheared.");
        }
        if (color != null) {
            DyeColor parsed = DyeColor.byName(color.toLowerCase(Locale.ROOT), null);
            if (parsed == null)
                throw new CommandException(Messages.INVALID_SHEEP_COLOR, color);
            trait.setColor(parsed);
            Messaging.sendTr(sender, Messages.SHEEP_COLOR_SET, npc.getName(), color);
        }
        if (color == null && sheared == null)
            throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "protected",
            desc = "",
            modifiers = { "protected", "protect" },
            min = 1,
            max = 1,
            permission = "citizens.npc.protected")
    public void protect(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        boolean protect = !npc.isProtected();
        npc.setProtected(protect);
        Messaging.sendTr(sender, Messages.NPC_PROTECTED, npc.getName(), protect);
    }

    @Command(
            aliases = { "npc" },
            usage = "remove (all|id|uuid|name|--owner owner|--eid entity-uuid|--world world)",
            desc = "",
            modifiers = { "remove", "rem" },
            min = 1,
            max = 2)
    @Requirements
    public void remove(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("owner") String owner,
            @Flag("eid") UUID eid, @Flag("world") String world,
            @Arg(value = 1, completions = "all") String action) throws CommandException {
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (owner != null) {
            boolean serverOwned = owner.equalsIgnoreCase("server");
            UUID ownerId = null;
            if (!serverOwned) {
                try { ownerId = UUID.fromString(owner); }
                catch (IllegalArgumentException notUuid) {
                    var online = sender.getServer().getPlayerList().getPlayerByName(owner);
                    if (online != null) ownerId = online.getUUID();
                    else if (sender.getServer().getProfileCache() != null)
                        ownerId = sender.getServer().getProfileCache().get(owner).map(profile -> profile.getId()).orElse(null);
                }
            }
            var candidates = new ArrayList<NPC>();
            registry.forEach(candidates::add);
            for (NPC candidate : candidates) {
                Owner ownership = candidate.getOrAddTrait(Owner.class);
                boolean matches = serverOwned ? ownership.getOwnerId() == null
                        : ownerId != null && ownership.isOwnedBy(ownerId);
                if (matches && ownership.isOwnedBy(sender)) removeWithHistory(sender, candidate);
            }
            Messaging.sendTr(sender, Messages.NPCS_REMOVED);
            return;
        }
        if (world != null) {
            var level = net.citizensnpcs.api.persistence.LocationPersister.resolveStrict(world);
            if (level == null) throw new CommandException(Messages.WORLD_NOT_FOUND, world);
            var candidates = new ArrayList<NPC>();
            registry.forEach(candidates::add);
            for (NPC candidate : candidates) {
                Location location = candidate.getStoredLocation();
                if (location != null && location.getWorld() == level
                        && candidate.getOrAddTrait(Owner.class).isOwnedBy(sender)) removeWithHistory(sender, candidate);
            }
            Messaging.sendTr(sender, Messages.NPCS_REMOVED);
            return;
        }
        if (eid != null) {
            NPC found = null;
            for (var level : sender.getServer().getAllLevels()) {
                Entity entity = level.getEntity(eid);
                if (entity != null) { found = registry.getNPC(entity); break; }
            }
            if (found == null || !found.getOrAddTrait(Owner.class).isOwnedBy(sender))
                throw new CommandException(Messages.NPC_NOT_FOUND);
            String name = found.getName();
            int id = found.getId();
            removeWithHistory(sender, found);
            Messaging.sendTr(sender, Messages.NPC_REMOVED, name, id);
            return;
        }
        if ("all".equalsIgnoreCase(action)) {
            if (!PermissionUtil.hasPermission(sender, "citizens.admin.remove.all")
                    && !PermissionUtil.hasPermission(sender, "citizens.admin")
                    && !PermissionUtil.hasPermission(sender, "citizens.npc.remove.all"))
                throw new NoPermissionsException();
            var snapshots = new ArrayList<RemoveNPCHistoryItem>();
            registry.forEach(candidate -> snapshots.add(new RemoveNPCHistoryItem(candidate)));
            snapshots.forEach(snapshot -> history().add(sender, snapshot));
            NPC selected = CitizensAPI.getDefaultNPCSelector().getSelected(sender);
            if (selected != null && selected.getOwningRegistry() == registry)
                CitizensAPI.getDefaultNPCSelector().deselect(sender);
            registry.deregisterAll();
            Messaging.sendTr(sender, Messages.REMOVED_ALL_NPCS);
            return;
        }
        NPCCommandSelector.Callback remove = target -> {
            if (target == null) throw new CommandException(Messages.NPC_NOT_FOUND);
            if (!target.getOrAddTrait(Owner.class).isOwnedBy(sender))
                throw new CommandException(CommandMessages.MUST_BE_OWNER);
            if (!PermissionUtil.hasPermission(sender, "citizens.npc.remove")
                    && !PermissionUtil.hasPermission(sender, "citizens.admin")) throw new NoPermissionsException();
            String name = target.getName();
            int id = target.getId();
            removeWithHistory(sender, target);
            Messaging.sendTr(sender, Messages.NPC_REMOVED, name, id);
        };
        if (action != null) NPCCommandSelector.startWithCallback(remove, registry, sender, args, action);
        else {
            if (npc == null) throw new CommandException(CommandMessages.MUST_HAVE_SELECTED);
            remove.run(npc);
        }
    }

    private void removeWithHistory(CommandSourceStack sender, NPC npc) {
        var snapshot = new RemoveNPCHistoryItem(npc);
        history().add(sender, snapshot);
        if (CitizensAPI.getDefaultNPCSelector().getSelected(sender) == npc)
            CitizensAPI.getDefaultNPCSelector().deselect(sender);
        npc.destroy(sender);
    }

    @Command(
            aliases = { "npc" },
            usage = "rename [name]",
            desc = "",
            modifiers = { "rename" },
            min = 2,
            permission = "citizens.npc.rename")
    public void rename(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        String oldName = npc.getName();
        String newName = args.getJoinedStrings(1);
        int nameLength = EntityUtil.getMaxNameLength(npc.getOrAddTrait(MobType.class).getType());
        if (newName.length() > nameLength) {
            Messaging.sendErrorTr(sender, Messages.NPC_NAME_TOO_LONG, nameLength);
            newName = newName.substring(0, nameLength);
        }
        npc.setName(newName);
        Messaging.sendTr(sender, Messages.NPC_RENAMED, oldName, newName);
    }

    @Command(
            aliases = { "npc" },
            usage = "select (id|--name name)",
            desc = "",
            modifiers = { "select", "sel" },
            min = 1,
            max = 2,
            permission = "citizens.npc.select")
    @Requirements
    public void select(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("name") String name)
            throws CommandException {
        NPC target = null;
        if (name != null) {
            for (NPC each : CitizensAPI.getNPCRegistry()) {
                if (each.getName().equalsIgnoreCase(name)) {
                    target = each;
                    break;
                }
            }
        } else if (args.argsLength() > 1) {
            target = CitizensAPI.getNPCRegistry().getById(args.getInteger(1));
        } else {
            target = nearestNPC(sender);
        }
        if (target == null)
            throw new CommandException(Messages.NPC_NOT_FOUND);
        if (target.equals(npc))
            throw new CommandException(Messages.NPC_ALREADY_SELECTED);
        CitizensAPI.getDefaultNPCSelector().select(sender, target);
        Messaging.send(sender, "Selected [[" + target.getName() + "]] (ID [[" + target.getId() + "]]).");
    }

    @Command(
            aliases = { "npc" },
            usage = "size [size]",
            desc = "",
            modifiers = { "size" },
            min = 1,
            max = 2,
            permission = "citizens.npc.size")
    public void size(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        SlimeSize trait = npc.getOrAddTrait(SlimeSize.class);
        if (args.argsLength() == 1) {
            Messaging.sendTr(sender, Messages.SIZE_DESCRIPTION, npc.getName(), trait.getSize());
            return;
        }
        int size = args.getInteger(1);
        trait.setSize(size);
        Messaging.sendTr(sender, Messages.SIZE_SET, npc.getName(), size);
    }

    @Command(
            aliases = { "npc" },
            usage = "skin (name) (-c(lear) -l(atest))",
            desc = "",
            flags = "cl",
            modifiers = { "skin" },
            min = 1,
            max = 2,
            permission = "citizens.npc.skin")
    public void skin(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        SkinTrait trait = npc.getOrAddTrait(SkinTrait.class);
        if (args.hasFlag('c')) {
            trait.clearTexture();
            Messaging.sendTr(sender, Messages.SKIN_CLEARED);
            return;
        }
        if (args.hasFlag('l')) {
            trait.setShouldUpdateSkins(true);
            Messaging.sendTr(sender, Messages.SKIN_LATEST_SET, npc.getName());
            return;
        }
        if (args.argsLength() < 2)
            throw new CommandUsageException();
        String skinName = args.getString(1);
        trait.setSkinName(skinName);
        Messaging.sendTr(sender, Messages.SKIN_SET, npc.getName(), skinName);
    }

    @Command(
            aliases = { "npc" },
            usage = "sneak",
            desc = "",
            modifiers = { "sneak" },
            min = 1,
            max = 1,
            permission = "citizens.npc.sneak")
    public void sneak(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        SneakTrait trait = npc.getOrAddTrait(SneakTrait.class);
        trait.setSneaking(!trait.isSneaking());
        Messaging.send(sender, "[[" + npc.getName() + "]] is " + (trait.isSneaking() ? "now" : "no longer")
                + " sneaking.");
    }

    @Command(
            aliases = { "npc" },
            usage = "spawn (id)",
            desc = "",
            modifiers = { "spawn" },
            min = 1,
            max = 2,
            permission = "citizens.npc.spawn")
    @Requirements
    public void spawn(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        NPC target = args.argsLength() > 1 ? CitizensAPI.getNPCRegistry().getById(args.getInteger(1)) : npc;
        if (target == null)
            throw new CommandException(CommandMessages.MUST_HAVE_SELECTED);
        if (target.isSpawned())
            throw new CommandException(Messages.NPC_ALREADY_SPAWNED, target.getName());
        Location at = target.getStoredLocation() != null ? target.getStoredLocation() : args.getSenderLocation();
        if (at == null)
            throw new CommandException(CommandMessages.MUST_BE_INGAME);
        target.getOrAddTrait(Spawned.class).setSpawned(true);
        target.spawn(at, SpawnReason.COMMAND);
        Messaging.sendTr(sender, Messages.NPC_SPAWNED, target.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "speak [message] --bubble [duration] --target [npcid|player name] --range [blocks]",
            desc = "",
            modifiers = { "speak" },
            min = 2,
            permission = "citizens.npc.speak")
    public void speak(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("bubble") Duration bubbleDuration,
            @Flag("type") String type, @Flag("target") String target, @Flag("range") Float range)
            throws CommandException {
        String message = args.getJoinedStrings(1);
        SpeechContext context = new SpeechContext(message);
        ServerPlayer playerRecipient = null;
        if (target != null) {
            Integer targetId = Ints.tryParse(target);
            if (targetId != null) {
                NPC targetNPC = CitizensAPI.getNPCRegistry().getById(targetId);
                if (targetNPC != null && targetNPC.isSpawned()) {
                    context.addRecipient(targetNPC.getEntity());
                }
            } else {
                playerRecipient = sender.getServer().getPlayerList().getPlayerByName(target);
                if (playerRecipient != null) {
                    context.addRecipient(playerRecipient);
                }
            }
        }
        if (bubbleDuration != null) {
            HologramTrait trait = npc.getOrAddTrait(HologramTrait.class);
            trait.addTemporaryLine(Placeholders.replace(message,
                    playerRecipient == null ? null : playerRecipient.createCommandSourceStack(), npc),
                    Durations.toTicks(bubbleDuration));
            return;
        }
        if (!npc.isSpawned())
            return;
        Entity entity = npc.getEntity();
        if (range != null) {
            entity.level().getEntities(entity, entity.getBoundingBox().inflate(range)).stream()
                    .filter(e -> !CitizensAPI.getNPCRegistry().isNPC(e)).forEach(context::addRecipient);
        }
        context.setTalker(entity);
        npc.speak(context);
    }

    @Command(
            aliases = { "npc" },
            usage = "target [name|UUID] (-a[ggressive]) (-c[ancel])",
            desc = "",
            modifiers = { "target" },
            flags = "ac",
            min = 1,
            max = 2,
            permission = "citizens.npc.target")
    @Requirements(selected = true, ownership = true)
    public void target(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) ServerPlayer player)
            throws CommandException {
        if (args.hasFlag('c')) {
            npc.getNavigator().cancelNavigation();
            return;
        }
        Entity toTarget = player != null ? player
                : sender.getEntity() instanceof ServerPlayer self ? self : null;
        if (toTarget == null)
            throw new CommandUsageException();
        npc.getNavigator().setTarget(toTarget, args.hasFlag('a'));
    }

    @Command(
            aliases = { "npc" },
            usage = "targetable",
            desc = "",
            modifiers = { "targetable" },
            min = 1,
            max = 1,
            permission = "citizens.npc.targetable")
    public void targetable(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        TargetableTrait trait = npc.getOrAddTrait(TargetableTrait.class);
        boolean targetable = !trait.isTargetable();
        trait.setTargetable(targetable);
        Messaging.sendTr(sender, targetable ? Messages.TARGETABLE_SET : Messages.TARGETABLE_UNSET, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "tp",
            desc = "",
            modifiers = { "tp", "teleport" },
            min = 1,
            max = 1,
            permission = "citizens.npc.tp")
    public void tp(CommandContext args, ServerPlayer player, NPC npc) throws CommandException {
        CommandSourceStack sender = player.createCommandSourceStack();
        Location at = npc.getStoredLocation();
        if (at == null || at.getWorld() == null)
            throw new CommandException(Messages.NPC_NOT_FOUND);
        EntityUtil.teleport(player, at);
        Messaging.sendTr(sender, Messages.NPC_TELEPORTED, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "tphere (-c(urrent location))",
            desc = "",
            flags = "c",
            modifiers = { "tphere", "tph", "move" },
            min = 1,
            max = 1,
            permission = "citizens.npc.tphere")
    public void tphere(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        Location at = args.getSenderLocation();
        if (at == null)
            throw new CommandException(CommandMessages.MUST_BE_INGAME);
        if (!npc.isSpawned()) {
            npc.spawn(at, SpawnReason.COMMAND);
        } else {
            npc.teleport(at, TeleportCause.COMMAND);
        }
        Messaging.sendTr(sender, Messages.NPC_TELEPORTED, npc.getName());
    }

    @Command(
            aliases = { "npc" },
            usage = "type [type]",
            desc = "",
            modifiers = { "type" },
            min = 2,
            max = 2,
            permission = "citizens.npc.type")
    public void type(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completionsProvider = EntityTypeCompletions.class) String rawType) throws CommandException {
        EntityType<?> type = MobType.match(rawType);
        if (type == null)
            throw new CommandException(Messages.NPC_CREATE_INVALID_MOBTYPE, rawType);
        if (!EntityControllers.controllerExistsForType(type))
            throw new CommandException(Messages.NPC_CREATE_MISSING_MOBTYPE, rawType);
        npc.setEntityType(type);
        Messaging.sendTr(sender, Messages.ENTITY_TYPE_SET, npc.getName(), EntityType.getKey(type).getPath());
    }

    /** Offers every entity type that can actually be made into an NPC. */
    public static class EntityTypeCompletions extends Arg.RegistryCompletions<EntityType<?>> {
        public EntityTypeCompletions() {
            super(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "sound (--death [death sound|d]) (--ambient [ambient sound|d]) (--hurt [hurt sound|d]) (-n(one)/-s(ilent)) (-d(efault))",
            desc = "",
            modifiers = { "sound" },
            flags = "dns",
            min = 1,
            max = 1,
            permission = "citizens.npc.sound")
    @Requirements(selected = true, ownership = true, livingEntity = true)
    public void sound(CommandContext args, CommandSourceStack sender, NPC npc,
            @Flag(value = "death", completionsProvider = SoundCompletions.class) String death,
            @Flag(value = "ambient", completionsProvider = SoundCompletions.class) String ambient,
            @Flag(value = "hurt", completionsProvider = SoundCompletions.class) String hurt) throws CommandException {
        String ambientSound = npc.data().get(NPC.Metadata.AMBIENT_SOUND);
        String deathSound = npc.data().get(NPC.Metadata.DEATH_SOUND);
        String hurtSound = npc.data().get(NPC.Metadata.HURT_SOUND);
        if (args.getValueFlags().size() == 0 && args.getFlags().size() == 0) {
            Messaging.sendTr(sender, Messages.SOUND_INFO, npc.getName(), ambientSound, hurtSound, deathSound);
            return;
        }
        if (args.hasFlag('n')) {
            ambientSound = deathSound = hurtSound = "";
            npc.data().setPersistent(NPC.Metadata.SILENT, true);
        }
        if (args.hasFlag('s')) {
            npc.data().setPersistent(NPC.Metadata.SILENT, !npc.data().get(NPC.Metadata.SILENT, false));
            if (npc.data().get(NPC.Metadata.SILENT, false)) {
                ambientSound = deathSound = hurtSound = "";
            } else {
                ambientSound = deathSound = hurtSound = null;
            }
        }
        if (args.hasFlag('d')) {
            ambientSound = deathSound = hurtSound = null;
            npc.data().setPersistent(NPC.Metadata.SILENT, false);
        } else {
            if (death != null) {
                deathSound = parseSoundName(death);
            }
            if (ambient != null) {
                ambientSound = parseSoundName(ambient);
            }
            if (hurt != null) {
                hurtSound = parseSoundName(hurt);
            }
        }
        if (deathSound == null) {
            npc.data().remove(NPC.Metadata.DEATH_SOUND);
        } else {
            npc.data().setPersistent(NPC.Metadata.DEATH_SOUND, deathSound);
        }
        if (hurtSound == null) {
            npc.data().remove(NPC.Metadata.HURT_SOUND);
        } else {
            npc.data().setPersistent(NPC.Metadata.HURT_SOUND, hurtSound);
        }
        if (ambientSound == null) {
            npc.data().remove(NPC.Metadata.AMBIENT_SOUND);
        } else {
            npc.data().setPersistent(NPC.Metadata.AMBIENT_SOUND, ambientSound);
        }
        if (ambientSound != null && ambientSound.isEmpty()) {
            ambientSound = "none";
        }
        if (hurtSound != null && hurtSound.isEmpty()) {
            hurtSound = "none";
        }
        if (deathSound != null && deathSound.isEmpty()) {
            deathSound = "none";
        }
        if (!Strings.isNullOrEmpty(ambientSound) && !ambientSound.equals("none")
                || !Strings.isNullOrEmpty(deathSound) && !deathSound.equals("none")
                || !Strings.isNullOrEmpty(hurtSound) && !hurtSound.equals("none")) {
            npc.data().setPersistent(NPC.Metadata.SILENT, false);
        }
        Messaging.sendTr(sender, Messages.SOUND_SET, npc.getName(), ambientSound, hurtSound, deathSound);
    }

    /**
     * Reads one {@code --death}/{@code --ambient}/{@code --hurt} value.
     * <p>
     * Two deliberate differences from upstream, which stores whatever string it is handed: {@code none} is accepted as
     * "silence this one slot" (upstream can only silence all three at once, with {@code -n}), and a name that no sound is
     * registered under is rejected here rather than persisted. Upstream's version leaves a typo stored, where it reads
     * back as the vanilla sound and looks like the command did nothing.
     *
     * @return the canonical registry id to persist, "" for silence, or null to fall back to the vanilla sound
     */
    private static String parseSoundName(String raw) throws CommandException {
        if (raw.equals("d") || raw.equalsIgnoreCase("default"))
            return null;
        if (raw.equalsIgnoreCase("none"))
            return "";
        ResourceLocation id = ResourceLocation.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
        if (id == null || !BuiltInRegistries.SOUND_EVENT.containsKey(id))
            throw new CommandException(Messages.INVALID_SOUND);
        return id.toString();
    }

    public static class SoundCompletions extends Arg.RegistryCompletions<SoundEvent> {
        public SoundCompletions() {
            super(BuiltInRegistries.SOUND_EVENT);
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "rotate (--towards [x,y,z]) (--toentity [name|uuid|me]) (--body [yaw]) (--head [yaw]) (--pitch [pitch]) (-s(mooth))",
            desc = "",
            flags = "s",
            modifiers = { "rotate" },
            min = 1,
            max = 1,
            permission = "citizens.npc.rotate")
    @Requirements(selected = true, ownership = true, livingEntity = false)
    public void rotate(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("body") Float yaw,
            @Flag("head") Float head, @Flag("pitch") Float pitch, @Flag("towards") Location towards,
            @Flag("toentity") String entity) throws CommandException {
        if (!npc.isSpawned())
            throw new CommandException(Messages.MOUNT_NPC_MUST_BE_SPAWNED, npc.getName());

        if (args.hasFlag('s')) {
            if (pitch == null) {
                pitch = npc.getStoredLocation().getPitch();
            }
            if (yaw == null) {
                yaw = head != null ? head : npc.getEntity().getYHeadRot();
            }
            npc.getOrAddTrait(RotationTrait.class).getPhysicalSession().rotateToHave(yaw, pitch);
            return;
        }
        if (entity != null) {
            if (entity.equals("me")) {
                towards = args.getSenderLocation();
            } else {
                ServerPlayer player = matchPlayer(sender, entity);
                if (player == null)
                    throw new CommandException(Messages.SHOP_PLAYER_NOT_FOUND, entity);
                towards = Location.of(player);
            }
        }
        if (towards != null) {
            npc.getOrAddTrait(RotationTrait.class).getPhysicalSession().rotateToFace(towards);
            return;
        }
        if (yaw == null && head == null && pitch == null)
            throw new CommandUsageException();

        applyRotation(npc.getEntity(), yaw, head, pitch);
    }

    /**
     * Writes a rotation straight onto the entity, which is what {@code /npc rotate} does without {@code -s}.
     * <p>
     * Upstream reaches for its NMS bridge here; the equivalent is to mirror what {@code RotationTrait} does when it
     * applies a finished rotation, including overwriting the previous-tick body yaw. Skipping that makes the client
     * interpolate the body round from where it used to be, so a half-turn reads as a lurch.
     * <p>
     * A player NPC additionally gets the rotation pushed as packets and an arm swing, as upstream does: the client keeps
     * a player's body yaw from its own interpolation and will not re-read it from a tracker update alone, so without the
     * nudge the NPC's legs stay pointing the old way until it next moves.
     */
    private static void applyRotation(Entity entity, Float bodyYaw, Float headYaw, Float pitch) {
        if (bodyYaw != null) {
            float clamped = Util.clamp(bodyYaw);
            if (entity instanceof LivingEntity living) {
                living.yBodyRotO = clamped;
                living.setYBodyRot(clamped);
            }
            entity.setYRot(clamped);
        }
        if (headYaw != null && entity instanceof LivingEntity living) {
            living.setYHeadRot(Util.clamp(headYaw));
        }
        if (pitch != null) {
            entity.setXRot(pitch);
        }
        if (!(entity instanceof ServerPlayer player))
            return;
        ClientboundMoveEntityPacket.Rot rot = new ClientboundMoveEntityPacket.Rot(entity.getId(),
                degreesToPacket(entity.getYRot()), degreesToPacket(entity.getXRot()), entity.onGround());
        ClientboundRotateHeadPacket headPacket = new ClientboundRotateHeadPacket(entity,
                degreesToPacket(entity.getYHeadRot()));
        for (ServerPlayer viewer : EntityUtil.getNearbyVisiblePlayers(entity, 64)) {
            viewer.connection.send(rot);
            viewer.connection.send(headPacket);
        }
        PlayerAnimation.ARM_SWING.play(player);
    }

    private static byte degreesToPacket(float degrees) {
        return (byte) Math.floor(degrees * 256.0F / 360.0F);
    }

    @Command(
            aliases = { "npc" },
            usage = "rotationsettings [linear|immediate] (--link_body) (--head_only) (--lock_pitch) (--max_pitch_per_tick) (--max_yaw_per_tick) (--pitch_range) (--yaw_range)",
            desc = "",
            modifiers = { "rotationsettings" },
            min = 2,
            max = 2,
            permission = "citizens.npc.rotationsettings")
    public void rotationsettings(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completions = { "linear", "immediate" }) String type, @Flag("link_body") Boolean linkBody,
            @Flag("lock_pitch") Boolean lockPitch, @Flag("head_only") Boolean headOnly,
            @Flag("max_pitch_per_tick") Float maxPitchPerTick, @Flag("max_yaw_per_tick") Float maxYawPerTick,
            @Flag(value = "pitch_range", validator = Arg.FloatArrayFlagValidator.class) float[] pitchRange,
            @Flag(value = "yaw_range", validator = Arg.FloatArrayFlagValidator.class) float[] yawRange)
            throws CommandException {
        if (!"linear".equalsIgnoreCase(type) && !"immediate".equalsIgnoreCase(type))
            throw new CommandUsageException();

        RotationParams params = npc.getOrAddTrait(RotationTrait.class).getGlobalParameters();
        params.immediate("immediate".equalsIgnoreCase(type));
        if (linkBody != null) {
            params.linkedBody(linkBody);
        }
        if (headOnly != null) {
            params.headOnly(headOnly);
        }
        if (lockPitch != null) {
            params.lockPitch(lockPitch);
        }
        if (maxPitchPerTick != null) {
            params.maxPitchPerTick(maxPitchPerTick);
        }
        if (maxYawPerTick != null) {
            params.maxYawPerTick(maxYawPerTick);
        }
        if (pitchRange != null) {
            params.pitchRange(pitchRange);
        }
        if (yawRange != null) {
            params.yawRange(yawRange);
        }
        Messaging.sendTr(sender, Messages.ROTATIONSETTINGS_DESCRIBE, params.describe());
    }

    @Command(
            aliases = { "npc" },
            usage = "hologram add [text] | insert [line#] [text] | set [line#] [text] | remove [line#] | clear | lineheight [height] | viewrange [range] | bgcolor [line#|name|template] [color] | edit_npc [line#|name|template] | margintop [line#] [margin] | marginbottom [line#] [margin] (--duration [duration])",
            desc = "",
            modifiers = { "hologram" },
            min = 1,
            max = -1,
            permission = "citizens.npc.hologram")
    public void hologram(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(
                    value = 1,
                    completions = { "add", "insert", "set", "edit_npc", "remove", "clear", "lineheight", "viewrange",
                            "bgcolor", "margintop", "marginbottom" }) String action,
            @Arg(value = 2, completionsProvider = HologramLineCompletions.class) String secondCompletion,
            @Flag("duration") Duration duration) throws CommandException {
        if (npc.hasTrait(ClickRedirectTrait.class)) {
            npc = npc.getOrAddTrait(ClickRedirectTrait.class).getRedirectToNPC();
            CitizensAPI.getDefaultNPCSelector().select(sender, npc);
        }
        HologramTrait trait = npc.getOrAddTrait(HologramTrait.class);
        if (args.argsLength() == 1) {
            String output = Messaging.tr(Messages.HOLOGRAM_DESCRIBE_HEADER, npc.getName());
            List<String> lines = trait.getLines();
            for (int i = 0; i < lines.size(); i++) {
                output += "<br>    [[" + i + "]] - " + lines.get(i);
            }
            Messaging.send(sender, output);
            return;
        }
        if (action.equalsIgnoreCase("set")) {
            int idx = hologramLine(args, trait, false);
            if (args.argsLength() == 3)
                throw new CommandException(Messages.HOLOGRAM_TEXT_MISSING);

            trait.setLine(idx, args.getJoinedStrings(3));
            Messaging.sendTr(sender, Messages.HOLOGRAM_LINE_SET, idx, args.getJoinedStrings(3));
        } else if (action.equalsIgnoreCase("bgcolor")) {
            if (args.argsLength() <= 3)
                throw new CommandException(Messages.HOLOGRAM_INVALID_LINE);

            HologramRenderer hr = hologramRenderer(args, trait);
            if (hr == null || hr.getTemplateNPC() == null)
                throw new CommandException(Messages.HOLOGRAM_INVALID_LINE);
            if (hr.getTemplateNPC().getOrAddTrait(MobType.class).getType() != EntityType.TEXT_DISPLAY)
                throw new CommandException(Messages.HOLOGRAM_INVALID_LINE);

            hr.getTemplateNPC().getOrAddTrait(TextDisplayTrait.class)
                    .setBackgroundColor(parseColor(args.getString(3)));
            Messaging.sendTr(sender, Messages.HOLOGRAM_BACKGROUND_COLOR_SET, args.getString(3));
        } else if (action.equalsIgnoreCase("edit_npc")) {
            if (args.argsLength() == 2)
                throw new CommandException(Messages.HOLOGRAM_INVALID_LINE);

            HologramRenderer hr = hologramRenderer(args, trait);
            if (hr == null || hr.getTemplateNPC() == null)
                throw new CommandException(Messages.HOLOGRAM_INVALID_LINE);

            CitizensAPI.getDefaultNPCSelector().select(sender, hr.getTemplateNPC());
            Messaging.sendTr(sender, Messages.HOLOGRAM_RENDERER_SELECTED, hr.getPerPlayerText(npc, null));
        } else if (action.equalsIgnoreCase("viewrange")) {
            if (args.argsLength() == 2)
                throw new CommandUsageException();

            trait.setViewRange(args.getInteger(2));
            Messaging.sendTr(sender, Messages.HOLOGRAM_VIEW_RANGE_SET, npc.getName(), args.getInteger(2));
        } else if (action.equalsIgnoreCase("add")) {
            if (args.argsLength() == 2)
                throw new CommandException(Messages.HOLOGRAM_TEXT_MISSING);

            if (duration != null) {
                trait.addTemporaryLine(args.getJoinedStrings(2), Durations.toTicks(duration));
            } else {
                trait.addLine(args.getJoinedStrings(2));
            }
            Messaging.sendTr(sender, Messages.HOLOGRAM_LINE_ADD, args.getJoinedStrings(2));
        } else if (action.equalsIgnoreCase("insert")) {
            // inserting is the one action allowed to name the slot one past the end, which appends
            int idx = hologramLine(args, trait, true);
            if (args.argsLength() == 3)
                throw new CommandException(Messages.HOLOGRAM_TEXT_MISSING);

            trait.insertLine(idx, args.getJoinedStrings(3));
            Messaging.sendTr(sender, Messages.HOLOGRAM_LINE_ADD, args.getJoinedStrings(3));
        } else if (action.equalsIgnoreCase("remove")) {
            int idx = hologramLine(args, trait, false);
            trait.removeLine(idx);
            Messaging.sendTr(sender, Messages.HOLOGRAM_LINE_REMOVED, idx);
        } else if (action.equalsIgnoreCase("clear")) {
            trait.clear();
            Messaging.sendTr(sender, Messages.HOLOGRAM_CLEARED);
        } else if (action.equalsIgnoreCase("lineheight")) {
            if (args.argsLength() == 2)
                throw new CommandUsageException();

            trait.setLineHeight(args.getDouble(2));
            Messaging.sendTr(sender, Messages.HOLOGRAM_LINE_HEIGHT_SET, args.getDouble(2));
        } else if (action.equalsIgnoreCase("margintop") || action.equalsIgnoreCase("marginbottom")) {
            String which = action.equalsIgnoreCase("margintop") ? "top" : "bottom";
            int idx = hologramLine(args, trait, false);
            if (args.argsLength() == 3)
                throw new CommandException(Messages.HOLOGRAM_MARGIN_MISSING);

            trait.setMargin(idx, which, args.getDouble(3));
            Messaging.sendTr(sender, Messages.HOLOGRAM_MARGIN_SET, idx, which, args.getDouble(3));
        } else {
            throw new CommandUsageException();
        }
    }

    /**
     * Reads the line number argument the hologram subcommands share, accepting {@code top} and {@code bottom} by name.
     * Upstream repeats this five times inline; the bounds check differs only for {@code insert}.
     *
     * @param allowAppend
     *            true to also accept the slot one past the last line
     */
    private static int hologramLine(CommandContext args, HologramTrait trait, boolean allowAppend)
            throws CommandException {
        if (args.argsLength() == 2)
            throw new CommandException(Messages.HOLOGRAM_INVALID_LINE);

        int size = trait.getLines().size();
        String raw = args.getString(2);
        int idx = raw.equals("bottom") ? 0 : raw.equals("top") ? size - 1 : Math.max(0, args.getInteger(2));
        if (allowAppend ? idx > size : idx >= size)
            throw new CommandException(Messages.HOLOGRAM_INVALID_LINE);

        return idx;
    }

    /**
     * @return the renderer the second argument names — {@code name} for the nameplate, {@code template} for the template
     *         renderer, otherwise the renderer of that line
     */
    private static HologramRenderer hologramRenderer(CommandContext args, HologramTrait trait) throws CommandException {
        String raw = args.getString(2);
        if (raw.equals("name"))
            return trait.getNameRenderer();
        if (raw.equals("template"))
            return trait.getTemplateRenderer();
        int idx = hologramLine(args, trait, false);
        List<HologramRenderer> renderers = new ArrayList<>(trait.getHologramRenderers());
        return idx < renderers.size() ? renderers.get(idx) : null;
    }

    /** Offers the line numbers a hologram currently has, for the subcommands that take one. */
    public static class HologramLineCompletions implements Arg.CompletionsProvider {
        @Override
        public Collection<String> getCompletions(CommandContext args, CommandSourceStack sender, NPC npc) {
            if (args.length() <= 1 || npc == null
                    || !LINE_ARGS.contains(args.getString(1).toLowerCase(Locale.ROOT)))
                return Collections.emptyList();

            HologramTrait trait = npc.getOrAddTrait(HologramTrait.class);
            return IntStream.range(0, trait.getLines().size()).mapToObj(Integer::toString)
                    .collect(Collectors.toList());
        }

        private static final Set<String> LINE_ARGS = ImmutableSet.of("set", "remove", "margintop", "marginbottom",
                "insert", "bgcolor", "edit_npc");
    }

    /**
     * Parses a colour written as {@code 0xRRGGBB}, {@code r,g,b} or {@code r,g,b,a}.
     * <p>
     * Stands in for upstream's {@code SpigotUtil.parseColor}, which returns a Bukkit {@code Color}; the packed ARGB int
     * is what the display entity actually stores, so it is returned directly. An alpha of 0 would make the background
     * invisible rather than opaque, so the three-component form fills in 255 as Bukkit's {@code fromRGB} does.
     */
    private static int parseColor(String raw) throws CommandException {
        try {
            if (!raw.contains(","))
                return 0xFF000000 | Integer.decode(raw) & 0xFFFFFF;

            List<Integer> parts = Splitter.on(',').splitToStream(raw).map(String::trim).map(Integer::parseInt)
                    .collect(Collectors.toList());
            if (parts.size() == 3)
                return 0xFF000000 | parts.get(0) << 16 | parts.get(1) << 8 | parts.get(2);
            if (parts.size() == 4)
                return parts.get(3) << 24 | parts.get(0) << 16 | parts.get(1) << 8 | parts.get(2);
        } catch (NumberFormatException ex) {
            throw new CommandException(Messages.HOLOGRAM_INVALID_COLOR, raw);
        }
        throw new CommandException(Messages.HOLOGRAM_INVALID_COLOR, raw);
    }

    @Command(
            aliases = { "npc" },
            usage = "playerfilter -a(llowlist) -e(mpty) -d(enylist) --add [uuid] --remove [uuid] --addpermission [permission] --removepermission [permission] --addgroup [group] --removegroup [group] -c(lear) --applywithin [blocks range]",
            desc = "",
            modifiers = { "playerfilter" },
            min = 1,
            max = 1,
            flags = "adce",
            permission = "citizens.npc.playerfilter")
    public void playerfilter(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("add") UUID add,
            @Flag("remove") UUID remove, @Flag("removegroup") String removegroup, @Flag("addgroup") String addgroup,
            @Flag("addpermission") String addpermission, @Flag("removepermission") String removepermission,
            @Flag("applywithin") Double applyRange) {
        PlayerFilter trait = npc.getOrAddTrait(PlayerFilter.class);
        if (add != null) {
            trait.addPlayer(add);
            Messaging.sendTr(sender, Messages.PLAYERFILTER_PLAYER_ADDED, add, npc.getName());
        }
        if (remove != null) {
            trait.removePlayer(remove);
            Messaging.sendTr(sender, Messages.PLAYERFILTER_PLAYER_REMOVED, remove, npc.getName());
        }
        if (addgroup != null) {
            trait.addGroup(addgroup);
            Messaging.sendTr(sender, Messages.PLAYERFILTER_GROUP_ADDED, addgroup, npc.getName());
        }
        if (removegroup != null) {
            trait.removeGroup(removegroup);
            Messaging.sendTr(sender, Messages.PLAYERFILTER_GROUP_REMOVED, removegroup, npc.getName());
        }
        if (addpermission != null) {
            trait.addPermission(addpermission);
            Messaging.sendTr(sender, Messages.PLAYERFILTER_PERMISSION_ADDED, addpermission, npc.getName());
        }
        if (removepermission != null) {
            trait.removePermission(removepermission);
            Messaging.sendTr(sender, Messages.PLAYERFILTER_PERMISSION_REMOVED, removepermission, npc.getName());
        }
        if (applyRange != null) {
            trait.setApplyRange(applyRange);
            Messaging.sendTr(sender, Messages.PLAYERFILTER_APPLYRANGE_SET, npc.getName(), applyRange);
        }
        if (args.hasFlag('e')) {
            trait.setPlayers(Collections.emptySet());
            Messaging.sendTr(sender, Messages.PLAYERFILTER_EMPTY_SET, npc.getName());
        }
        if (args.hasFlag('a')) {
            trait.setAllowlist();
            Messaging.sendTr(sender, Messages.PLAYERFILTER_ALLOWLIST_SET, npc.getName());
        }
        if (args.hasFlag('d')) {
            trait.setDenylist();
            Messaging.sendTr(sender, Messages.PLAYERFILTER_DENYLIST_SET, npc.getName());
        }
        if (args.hasFlag('c')) {
            trait.clear();
            Messaging.sendTr(sender, Messages.PLAYERFILTER_CLEARED, npc.getName());
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "debug -p(aths) -n(avigation) -i(tem in hand)",
            desc = "",
            modifiers = { "debug" },
            min = 1,
            max = 1,
            flags = "pni",
            permission = "citizens.npc.debug")
    @Requirements(ownership = true, selected = true)
    public void debug(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        NavigatorParameters params = npc.getNavigator().getDefaultParameters();
        if (args.hasFlag('p')) {
            params.debug(!params.debug());
            Messaging.send(sender, "Path debugging set to " + params.debug());
        } else if (args.hasFlag('n')) {
            String output = "Pathfinder type [[" + params.pathfinderType();
            output += "]] distance margin [[" + params.distanceMargin() + "]] (path margin [["
                    + params.pathDistanceMargin() + "]])<br>";
            output += "Teleport if below " + params.destinationTeleportMargin() + " blocks<br>";
            output += "Range [[" + params.range() + "]] speed [[" + params.speed() + "]]<br>";
            output += "Stuck action [[" + params.stuckAction() + "]]<br>";
            Messaging.send(sender, output);
        } else if (args.hasFlag('i')) {
            ServerPlayer player = sender.getPlayer();
            if (player == null)
                throw new CommandException(CommandMessages.MUST_BE_INGAME);

            // upstream prints the dump its NMS bridge builds; the component map is that same information, unbridged
            Messaging.send(sender, player.getMainHandItem().getComponents().toString());
        } else {
            throw new CommandUsageException();
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "wander (add x y z (world)) | (xyrange [xrange] [yrange]) | (pathfind [true|false]) | (delay [ticks])",
            desc = "",
            modifiers = { "wander" },
            min = 1,
            max = 6,
            permission = "citizens.npc.wander")
    public void wander(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completions = { "add", "xyrange", "pathfind", "delay" }) String command)
            throws CommandException {
        Waypoints trait = npc.getOrAddTrait(Waypoints.class);
        if (args.argsLength() == 1) {
            ServerPlayer player = sender.getPlayer();
            if (player != null && Editor.hasEditor(player)) {
                Editor.leave(player);
            }
            trait.setWaypointProvider(trait.getCurrentProviderName().equals("wander") ? "linear" : "wander");
            Messaging.sendTr(sender, Messages.WAYPOINT_PROVIDER_SET, trait.getCurrentProviderName());
            return;
        }
        if (!(trait.getCurrentProvider() instanceof WanderWaypointProvider)) {
            trait.setWaypointProvider("wander");
        }
        WanderWaypointProvider provider = (WanderWaypointProvider) trait.getCurrentProvider();
        if (command.equals("add")) {
            if (args.argsLength() < 5)
                throw new CommandUsageException();

            ServerLevel level = args.argsLength() > 5 ? Util.getLevel(sender.getServer(), args.getString(5))
                    : npc.getStoredLocation() == null ? null : npc.getStoredLocation().getWorld();
            if (level == null)
                throw new CommandException(Messages.WORLD_NOT_FOUND);

            Location loc = new Location(level, args.getInteger(2), args.getInteger(3), args.getInteger(4));
            provider.addRegionCentre(loc);
            Messaging.sendTr(sender, Messages.WAYPOINT_ADDED, Util.prettyPrintLocation(loc));
        } else if (command.equals("xyrange")) {
            if (args.argsLength() != 4)
                throw new CommandUsageException();

            provider.setXYRange(args.getInteger(2), args.getInteger(3));
            Messaging.sendTr(sender, Messages.WANDER_XY_RANGE_SET, provider.getXRange(), provider.getYRange());
        } else if (command.equals("pathfind")) {
            if (args.argsLength() != 3)
                throw new CommandUsageException();

            provider.setPathfind(Boolean.parseBoolean(args.getString(2)));
            Messaging.sendTr(sender, Messages.WANDER_PATHFIND_SET, provider.isPathfind());
        } else if (command.equals("delay")) {
            if (args.argsLength() != 3)
                throw new CommandUsageException();

            provider.setDelay(Durations.toTicks(Durations.parse(args.getString(2), TimeUnit.SECONDS)));
            Messaging.sendTr(sender, Messages.WANDER_DELAY_SET, provider.getDelay());
        } else {
            throw new CommandUsageException();
        }
    }

    @Command(
            aliases = { "npc" },
            usage = "breakblock (--location [x,y,z]) (--radius [radius])",
            desc = "",
            modifiers = { "breakblock" },
            min = 1,
            max = 1,
            permission = "citizens.npc.breakblock")
    @Requirements(selected = true, ownership = true, livingEntity = true)
    public void breakblock(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("radius") Double radius,
            @Flag("location") Location location) throws CommandException {
        // upstream advertises --location in its usage line and then never reads it, always breaking the block the sender
        // is looking at; the flag is honoured here so the command works from console and from a datapack
        Location target = location != null ? location : args.getSenderTargetBlockLocation();
        BlockBreakerConfiguration cfg = new BlockBreakerConfiguration();
        if (radius != null) {
            cfg.radius(radius);
        } else if (Setting.DEFAULT_BLOCK_BREAKER_RADIUS.asDouble() > 0) {
            cfg.radius(Setting.DEFAULT_BLOCK_BREAKER_RADIUS.asDouble());
        }
        if (npc.hasTrait(Inventory.class)) {
            // upstream diverts the drops whenever the entity is a Bukkit InventoryHolder. The equivalent question here
            // is whether the NPC has an inventory at all, which is what the trait means, and it covers every entity type
            // rather than only the handful vanilla gives a container to.
            cfg.blockBreaker((broken, item) -> {
                Container container = npc.getOrAddTrait(Inventory.class).getInventoryView();
                ServerLevel level = (ServerLevel) broken.level();
                BlockState state = level.getBlockState(broken.pos());
                List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(state, level, broken.pos(),
                        level.getBlockEntity(broken.pos()), npc.getEntity(), item == null ? ItemStack.EMPTY : item);
                level.destroyBlock(broken.pos(), false);
                for (ItemStack drop : drops) {
                    ItemStack left = container == null ? drop : addToContainer(container, drop);
                    if (!left.isEmpty()) {
                        net.minecraft.world.level.block.Block.popResource(level, broken.pos(), left);
                    }
                }
            });
        }
        BlockBreaker breaker = npc.getBlockBreaker(target.getBlockPos(), cfg);
        npc.getDefaultBehaviorController().addBehavior(StatusMapper.singleUse(breaker));
    }

    /**
     * Merges a stack into a container the way a hopper would, filling partial stacks of the same item first.
     *
     * @return whatever would not fit, which is empty when all of it went in
     */
    private static ItemStack addToContainer(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
                if (!container.canPlaceItem(slot, remaining)) continue;
                ItemStack existing = container.getItem(slot);
                if (pass == 0) {
                    if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining)) {
                        continue;
                    }
                    int room = container.getMaxStackSize(existing) - existing.getCount();
                    int moved = Math.min(room, remaining.getCount());
                    if (moved <= 0) {
                        continue;
                    }
                    existing.grow(moved);
                    container.setItem(slot, existing);
                    remaining.shrink(moved);
                } else if (existing.isEmpty()) {
                    int moved = Math.min(container.getMaxStackSize(remaining), remaining.getCount());
                    container.setItem(slot, remaining.split(moved));
                }
            }
        }
        container.setChanged();
        return remaining;
    }

    @Command(
            aliases = { "npc" },
            usage = "fish [cast_out|reel_in] (location)",
            desc = "",
            modifiers = { "fish" },
            min = 2,
            max = 3,
            permission = "citizens.npc.fish")
    @Requirements(selected = true, ownership = true, types = { "minecraft:player" })
    public void fish(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completions = { "cast_out", "reel_in" }) String command, @Arg(2) Location to)
            throws CommandException {
        if (!npc.isSpawned())
            throw new CommandException(Messages.MOUNT_NPC_MUST_BE_SPAWNED, npc.getName());

        ServerPlayer player = (ServerPlayer) npc.getEntity();
        if (command.equalsIgnoreCase("cast_out")) {
            if (!player.getMainHandItem().is(Items.FISHING_ROD))
                throw new CommandException(Messages.FISH_MUST_HOLD_ROD, npc.getName());
            if (to == null)
                throw new CommandUsageException();

            PlayerAnimation.ARM_SWING.play(player);
            // the constructor puts the hook at the player's eyes, points it where the player is looking, and assigns
            // player.fishing, which is what makes the client draw the line; only the velocity is ours to replace
            FishingHook hook = new FishingHook(player, player.level(), 0, 0);
            Vec3 towards = new Vec3(to.getX(), to.getY(), to.getZ()).subtract(hook.position());
            hook.setDeltaMovement(towards.lengthSqr() == 0 ? Vec3.ZERO : towards.normalize());
            player.level().addFreshEntity(hook);
            npc.data().set("fish_uuid", hook.getUUID());
            return;
        }
        if (command.equalsIgnoreCase("reel_in")) {
            if (!npc.data().has("fish_uuid"))
                return;

            UUID uuid = npc.data().get("fish_uuid");
            Entity hook = ((ServerLevel) player.level()).getEntity(uuid);
            if (hook != null) {
                PlayerAnimation.ARM_SWING.play(player);
                // discard runs FishingHook's removal, which clears player.fishing and so takes the line with it
                hook.discard();
            }
            npc.data().remove("fish_uuid");
            return;
        }
        throw new CommandUsageException();
    }

    @Command(
            aliases = { "npc" },
            usage = "behavior [file.yml]",
            desc = "",
            modifiers = { "behavior" },
            min = 1,
            max = 2,
            permission = "citizens.npc.behavior")
    public void behavior(CommandContext args, CommandSourceStack sender, NPC npc, @Arg(1) String file)
            throws CommandException {
        if (file == null)
            throw new CommandException(Messages.INVALID_BEHAVIOR_FILE);

        File src = new File(CitizensAPI.getDataFolder(), "behaviors");
        File target = new File(src, file);
        // the parent check keeps "../config.yml" from reaching outside the behaviors folder; canonical paths are compared
        // rather than the raw ones upstream compares, so a symlink or a "./" in the name cannot slip past it either
        try {
            if (!target.exists() || !target.getCanonicalFile().getParentFile().equals(src.getCanonicalFile()))
                throw new CommandException(Messages.INVALID_BEHAVIOR_FILE);
        } catch (IOException ex) {
            throw new CommandException(Messages.INVALID_BEHAVIOR_FILE);
        }
        if (!npc.getOrAddTrait(BehaviorTrait.class).applyBehaviorsFromFile(target))
            throw new CommandException(Messages.INVALID_BEHAVIOR_FILE);

        Messaging.sendTr(sender, Messages.BEHAVIOR_TREE_APPLIED, npc.getName(), file);
    }

    @Command(
            aliases = { "npc" },
            usage = "eval [expression]",
            desc = "",
            modifiers = { "eval" },
            min = 2,
            max = -1,
            permission = "citizens.npc.eval")
    @Requirements
    public void eval(CommandContext args, CommandSourceStack sender, NPC npc) {
        String expression = CitizensAPI.getExpressionRegistry()
                .applyDefaultExpressionMarkup(args.getJoinedStrings(1));
        ExpressionScope scope = npc == null ? new ExpressionScope() : NPCExpressionScope.createFor(npc);
        Messaging.send(sender, CitizensAPI.getExpressionRegistry().parseValue(expression).evaluate(scope));
    }

    @Command(
            aliases = { "npc" },
            usage = "disguise --type [type]",
            desc = "",
            modifiers = { "disguise" },
            min = 1,
            max = 1,
            permission = "citizens.npc.disguise")
    public void disguise(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("type") String type)
            throws CommandException {
        DisguiseTrait trait = npc.getOrAddTrait(DisguiseTrait.class);
        // upstream refuses unless its PacketEvents listener is up, because its disguise is a packet rewrite driven by
        // LibsDisguises. DisguiseTrait here spawns a real cosmetic entity of the disguise type instead, so there is
        // nothing to be enabled and no plugin to require.
        if (type == null) {
            EntityType<?> current = trait.getDisguiseType();
            Messaging.sendTr(sender, Messages.DISGUISE_SET,
                    current == null ? "none" : EntityType.getKey(current).toString());
            return;
        }
        EntityType<?> parsed = DisguiseTrait.parse(type);
        if (parsed == null)
            throw new CommandException(Messages.INVALID_ENTITY_TYPE, type);

        trait.disguiseAsType(parsed);
        Messaging.sendTr(sender, Messages.DISGUISE_SET, EntityType.getKey(parsed).toString());
    }

    @Command(
            aliases = { "npc" },
            usage = "configgui",
            desc = "",
            modifiers = { "configgui" },
            min = 1,
            max = 1,
            permission = "citizens.npc.configgui")
    public void configgui(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        ServerPlayer player = sender.getPlayer();
        if (player == null)
            throw new CommandException(CommandMessages.MUST_BE_INGAME);

        InventoryMenu.createSelfRegistered(new NPCConfigurator(npc)).present(player);
    }

    @Command(
            aliases = { "npc" },
            usage = "packet --enabled [true|false]",
            desc = "",
            modifiers = { "packet" },
            min = 1,
            max = 1,
            permission = "citizens.npc.packet")
    public void packet(CommandContext args, CommandSourceStack sender, NPC npc, @Flag("enabled") Boolean explicit)
            throws CommandException {
        if (explicit == null) {
            explicit = !npc.hasTrait(PacketNPC.class);
        }
        if (explicit) {
            npc.getOrAddTrait(PacketNPC.class);
            // the trait only takes effect through the controller it wraps, so the controller is rebuilt now rather than
            // leaving the NPC as a normal world entity until something else happens to respawn it
            npc.setEntityType(npc.getOrAddTrait(MobType.class).getType());
            Messaging.sendTr(sender, Messages.NPC_PACKET_ENABLED, npc.getName());
        } else {
            npc.removeTrait(PacketNPC.class);
            Messaging.sendTr(sender, Messages.NPC_PACKET_DISABLED, npc.getName());
        }
    }

    /**
     * Refuses a creation that would take the sender past their NPC limit.
     * <p>
     * Upstream does this by firing {@code PlayerCreateNPCEvent}/{@code CommandSenderCreateNPCEvent} <em>after</em> the NPC
     * exists and having {@code EventListen} cancel it, which means the NPC is built and then destroyed again. Neither event
     * class is ported, and there is no reason to build something only to throw it away, so the check runs before creation
     * instead. The permission scheme is upstream's: {@code citizens.admin.avoid-limits} exempts entirely, and the highest
     * {@code citizens.npc.limit.<n>} the sender holds overrides the configured default. A negative limit means unlimited.
     * <p>
     * Without this, {@code npc.limits.default-limit} did nothing at all — the setting and its message both existed, but
     * nothing ever read them.
     */
    private static void checkCreationLimit(CommandSourceStack sender) throws CommandException {
        if (PermissionUtil.hasPermission(sender, "citizens.admin.avoid-limits"))
            return;

        int limit = Setting.DEFAULT_NPC_LIMIT.asInt();
        for (int i = Setting.MAX_NPC_LIMIT_CHECKS.asInt(); i >= 0; i--) {
            if (PermissionUtil.hasPermission(sender, "citizens.npc.limit." + i)) {
                limit = i;
                break;
            }
        }
        if (limit < 0)
            return;

        int owned = 0;
        for (NPC each : CitizensAPI.getNPCRegistry()) {
            if (each.hasTrait(Owner.class) && each.getTraitNullable(Owner.class).isOwnedBy(sender)) {
                owned++;
            }
        }
        if (owned + 1 > limit)
            throw new CommandException(Messages.OVER_NPC_LIMIT, limit);
    }

    private static ServerPlayer playerByName(CommandSourceStack sender, String name) {
        return sender.getServer() == null ? null : sender.getServer().getPlayerList().getPlayerByName(name);
    }

    /**
     * @return the NPC nearest the sender within a short radius, so {@code /npc select} works by walking up to one
     */
    private static NPC nearestNPC(CommandSourceStack sender) {
        Entity from = sender.getEntity();
        if (from == null)
            return null;
        NPC closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (NPC each : CitizensAPI.getNPCRegistry()) {
            if (!each.isSpawned() || each.getEntity().level() != from.level()) {
                continue;
            }
            double distance = each.getEntity().distanceToSqr(from);
            if (distance < closestDistance && distance <= SELECT_RADIUS * SELECT_RADIUS) {
                closest = each;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private static final double SELECT_RADIUS = 10;
}
