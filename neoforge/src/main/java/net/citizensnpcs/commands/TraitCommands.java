package net.citizensnpcs.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.event.NPCTraitCommandAttachEvent;
import net.citizensnpcs.api.event.NPCTraitCommandDetachEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.StringHelper;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.neoforge.common.NeoForge;

/**
 * {@code /trait} — attaches, removes and toggles traits on the selected NPC by name.
 * <p>
 * This is the generic door onto every registered trait, including ones with no command of their own, so it is worth
 * having even where a dedicated command exists.
 */
@Requirements(selected = true, ownership = true)
public class TraitCommands {
    private final Citizens plugin;

    public TraitCommands(Citizens plugin) {
        this.plugin = plugin;
    }

    @Command(
            aliases = { "trait" },
            usage = "add [trait name]...",
            desc = "",
            modifiers = { "add", "a" },
            min = 2,
            permission = "citizens.npc.trait")
    public void add(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        List<String> added = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String traitName : Splitter.on(',').split(args.getJoinedStrings(1))) {
            Class<? extends Trait> clazz = resolve(traitName, sender, failed);
            if (clazz == null) {
                continue;
            }
            if (npc.hasTrait(clazz)) {
                failed.add(String.format("%s: Already added", traitName));
                continue;
            }
            addTrait(npc, clazz, sender);
            added.add(StringHelper.wrap(traitName));
        }
        if (!added.isEmpty()) {
            Messaging.sendTr(sender, Messages.TRAITS_ADDED, Joiner.on(", ").join(added));
        }
        if (!failed.isEmpty()) {
            Messaging.sendTr(sender, Messages.TRAITS_FAILED_TO_ADD, Joiner.on(", ").join(failed));
        }
    }

    @Command(
            aliases = { "trait" },
            usage = "clearsaves [trait name]",
            desc = "",
            modifiers = { "clearsaves" },
            min = 2,
            permission = "citizens.npc.trait.clearsaves")
    public void clearsaves(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        if (plugin.getDefaultNPCDataStore() == null)
            throw new CommandException("The NPC save file is not loaded.");
        List<String> names = Splitter.on(',').splitToStream(args.getJoinedStrings(1))
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toList());
        plugin.getDefaultNPCDataStore().clearTraitData(names);
        Messaging.sendTr(sender, Messages.TRAIT_DATA_CLEARED, Joiner.on(", ").join(names));
    }

    @Command(
            aliases = { "trait" },
            usage = "remove [trait names]...",
            desc = "",
            modifiers = { "remove", "rem", "r" },
            min = 2,
            permission = "citizens.npc.trait")
    public void remove(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        List<String> removed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String traitName : Splitter.on(',').split(args.getJoinedStrings(1))) {
            Class<? extends Trait> clazz = resolve(traitName, sender, failed);
            if (clazz == null) {
                continue;
            }
            if (!npc.hasTrait(clazz)) {
                failed.add(String.format("%s: Trait not attached", traitName));
                continue;
            }
            removeTrait(npc, clazz, sender);
            removed.add(StringHelper.wrap(traitName));
        }
        if (!removed.isEmpty()) {
            Messaging.sendTr(sender, Messages.TRAITS_REMOVED, Joiner.on(", ").join(removed));
        }
        if (!failed.isEmpty()) {
            Messaging.sendTr(sender, Messages.FAILED_TO_REMOVE, Joiner.on(", ").join(failed));
        }
    }

    /** {@code /trait <name>} with no sub-command toggles: attached traits come off, unattached ones go on. */
    @Command(
            aliases = { "trait" },
            usage = "[trait name], [trait name]...",
            desc = "",
            modifiers = { "*" },
            min = 1,
            permission = "citizens.npc.trait")
    public void toggle(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String traitName : Splitter.on(',').split(args.getJoinedStrings(0))) {
            Class<? extends Trait> clazz = resolve(traitName, sender, failed);
            if (clazz == null) {
                continue;
            }
            if (npc.hasTrait(clazz)) {
                removeTrait(npc, clazz, sender);
                removed.add(StringHelper.wrap(traitName));
                continue;
            }
            addTrait(npc, clazz, sender);
            added.add(StringHelper.wrap(traitName));
        }
        if (!added.isEmpty()) {
            Messaging.sendTr(sender, Messages.TRAITS_ADDED, Joiner.on(", ").join(added));
        }
        if (!removed.isEmpty()) {
            Messaging.sendTr(sender, Messages.TRAITS_REMOVED, Joiner.on(", ").join(removed));
        }
        if (!failed.isEmpty()) {
            Messaging.send(sender, "Failed to toggle traits", Joiner.on(", ").join(failed));
        }
    }

    /**
     * @return the trait class, or null after recording why it could not be used
     */
    private Class<? extends Trait> resolve(String traitName, CommandSourceStack sender, List<String> failed) {
        if (!PermissionUtil.hasPermission(sender, "citizens.npc.trait." + traitName)
                && !PermissionUtil.hasPermission(sender, "citizens.npc.trait.*")) {
            failed.add(String.format("%s: No permission", traitName));
            return null;
        }
        Class<? extends Trait> clazz = CitizensAPI.getTraitFactory().getTraitClass(traitName);
        if (clazz == null) {
            failed.add(String.format("%s: Trait not found", traitName));
        }
        return clazz;
    }

    private void addTrait(NPC npc, Class<? extends Trait> clazz, CommandSourceStack sender) {
        npc.addTrait(clazz);
        NeoForge.EVENT_BUS.post(new NPCTraitCommandAttachEvent(npc, clazz, sender));
    }

    private void removeTrait(NPC npc, Class<? extends Trait> clazz, CommandSourceStack sender) {
        NeoForge.EVENT_BUS.post(new NPCTraitCommandDetachEvent(npc, clazz, sender));
        npc.removeTrait(clazz);
    }
}
