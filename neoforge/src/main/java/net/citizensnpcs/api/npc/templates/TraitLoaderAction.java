package net.citizensnpcs.api.npc.templates;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.templates.TemplateRegistry.TemplateErrorReporter;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitTemplateParser;
import net.citizensnpcs.api.trait.TraitTemplateParser.ShortTemplateParser;
import net.citizensnpcs.api.trait.TraitTemplateParser.TemplateParser;
import net.citizensnpcs.api.trait.TraitTemplateParser.TraitParserContext;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Applies a template's {@code traits:} block.
 * <p>
 * Each list entry is either a map with a {@code name} plus the trait's own keys, parsed by that trait's
 * {@link TemplateParser}, or a one-line short form ({@code "behavior mytree.yml"}) parsed by its
 * {@link ShortTemplateParser}. Unknown trait names and missing names are reported through the
 * {@link TemplateErrorReporter} rather than thrown, so one bad entry names itself instead of failing the whole file.
 */
public class TraitLoaderAction implements Consumer<NPC> {
    private final List<Function<NPC, Trait>> actions = new ArrayList<>();

    public TraitLoaderAction(TemplateErrorReporter errors, TemplateWorkspace workspace, DataKey traits) {
        this(errors, workspace, traits, name -> CitizensAPI.getTraitFactory().getTemplateParser(name));
    }

    TraitLoaderAction(TemplateErrorReporter errors, TemplateWorkspace workspace, DataKey traits,
            Function<String, TraitTemplateParser> parsers) {
        for (DataKey key : traits.getIntegerSubKeys()) {
            if (key.hasSubKeys()) {
                if (!key.keyExists("name")) {
                    errors.addError(key.getPath() + ": Missing trait name");
                    continue;
                }
                String traitName = key.getString("name");
                TraitTemplateParser parser = parsers.apply(traitName);
                if (parser == null) {
                    errors.addError(key.getPath() + ": Unknown trait " + traitName);
                    continue;
                }
                TemplateParser tp = parser.getTemplateParser();
                if (tp != null) {
                    DataKey definition = key.copyWithRoot();
                    actions.add(npc -> tp.apply(new TraitParserContext(npc, workspace), definition.copyWithRoot()));
                } else errors.addError(key.getPath() + ": Trait " + traitName + " does not support structured templates");
            } else {
                String[] parts = key.getString("").trim().split(" ");
                TraitTemplateParser parser = parsers.apply(parts[0]);
                if (parser == null) {
                    errors.addError(key.getPath() + ": Unknown trait " + parts[0]);
                    continue;
                }
                ShortTemplateParser stp = parser.getShortTemplateParser();
                if (stp != null) {
                    // upstream builds this context from Bukkit's console sender; the server's own command source is the
                    // equivalent, and it may legitimately be absent while the server is still coming up
                    commandContext(parts); // Validate syntax before accepting the action.
                    actions.add(npc -> stp.apply(new TraitParserContext(npc, workspace), commandContext(parts)));
                } else errors.addError(key.getPath() + ": Trait " + parts[0] + " does not support short templates");
            }
        }
    }

    private static CommandContext commandContext(String[] parts) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? new CommandContext(parts.clone())
                : new CommandContext(server.createCommandSourceStack(), parts.clone());
    }

    @Override
    public void accept(NPC npc) {
        for (Function<NPC, Trait> action : actions) {
            Trait trait = action.apply(npc);
            if (trait != null) {
                npc.addTrait(trait);
            }
        }
    }
}
