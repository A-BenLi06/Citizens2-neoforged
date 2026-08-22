package net.citizensnpcs.commands;

import java.util.Collection;
import java.util.Locale;
import java.util.stream.Collectors;

import com.google.common.base.Joiner;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.command.Arg;
import net.citizensnpcs.api.command.Arg.CompletionsProvider;
import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.templates.Template;
import net.citizensnpcs.api.npc.templates.TemplateRegistry;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;

/**
 * The {@code /template} root: {@code apply}, {@code generate} and {@code list}.
 * <p>
 * Upstream parses the key through {@code SpigotUtil.getKey}, which builds a Bukkit {@code NamespacedKey}; the equivalent
 * here is {@link ResourceLocation}, with the same "no namespace means look it up by bare name" behaviour and the same
 * {@code generated} default namespace for {@code generate}.
 */
@Requirements(selected = true, ownership = true)
public class TemplateCommands {
    private final Citizens plugin;

    public TemplateCommands(Citizens plugin) {
        this.plugin = plugin;
    }

    @Command(
            aliases = { "template", "tpl" },
            usage = "apply (template namespace:)[template name]",
            desc = "",
            modifiers = { "apply" },
            min = 2,
            permission = "citizens.templates.apply")
    public void apply(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completionsProvider = TemplateCompletions.class) String templateKey)
            throws CommandException {
        TemplateRegistry registry = plugin.getTemplateRegistry();
        Template template;
        if (templateKey.contains(":")) {
            ResourceLocation key = ResourceLocation.tryParse(templateKey.toLowerCase(Locale.ROOT));
            template = key == null ? null : registry.getTemplateByKey(key);
        } else {
            Collection<Template> templates = registry.getTemplates(templateKey);
            if (templates.isEmpty())
                throw new CommandException(Messages.TEMPLATE_MISSING);
            // a bare name that exists in more than one namespace is ambiguous; list the qualified options rather than
            // silently picking whichever the map iterated first
            if (templates.size() > 1)
                throw new CommandException(Messages.TEMPLATE_PICKER, templateKey,
                        Joiner.on(", ").join(templates.stream().map(Template::getKey).collect(Collectors.toList())));

            template = templates.iterator().next();
        }
        if (template == null)
            throw new CommandException(Messages.TEMPLATE_MISSING);

        template.apply(npc);
        Messaging.sendTr(sender, Messages.TEMPLATE_APPLIED, template.getKey().getPath(), npc.getName());
    }

    @Command(
            aliases = { "template", "tpl" },
            usage = "generate (template namespace:)[name]",
            desc = "",
            modifiers = { "generate" },
            min = 2,
            max = 2,
            permission = "citizens.templates.generate")
    public void generate(CommandContext args, CommandSourceStack sender, NPC npc,
            @Arg(value = 1, completionsProvider = TemplateCompletions.class) String templateName)
            throws CommandException {
        String raw = templateName.toLowerCase(Locale.ROOT);
        ResourceLocation key = raw.contains(":") ? ResourceLocation.tryParse(raw)
                : ResourceLocation.tryBuild("generated", raw);
        if (key == null)
            throw new CommandException(Messages.TEMPLATE_MISSING);

        TemplateRegistry registry = plugin.getTemplateRegistry();
        if (registry.getTemplateByKey(key) != null)
            throw new CommandException(Messages.TEMPLATE_CONFLICT);

        registry.generateTemplateFromNPC(key, npc);
        Messaging.sendTr(sender, Messages.TEMPLATE_GENERATED, npc.getName());
    }

    @Command(
            aliases = { "template", "tpl" },
            usage = "list",
            desc = "",
            modifiers = { "list" },
            min = 1,
            max = 1,
            permission = "citizens.templates.list")
    @Requirements
    public void list(CommandContext args, CommandSourceStack sender, NPC npc) throws CommandException {
        Messaging.sendTr(sender, Messages.TEMPLATE_LIST_HEADER);
        for (Template template : plugin.getTemplateRegistry().getAllTemplates()) {
            Messaging.send(sender, "[[-]]    " + template.getKey());
        }
    }

    public static class TemplateCompletions implements CompletionsProvider {
        private final Citizens plugin;

        public TemplateCompletions(Citizens plugin) {
            this.plugin = plugin;
        }

        @Override
        public Collection<String> getCompletions(CommandContext args, CommandSourceStack sender, NPC npc) {
            return plugin.getTemplateRegistry().getAllTemplates().stream().map(t -> t.getKey().toString())
                    .collect(Collectors.toList());
        }
    }
}
