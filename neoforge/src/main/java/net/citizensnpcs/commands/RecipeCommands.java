package net.citizensnpcs.commands;

import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.Requirements;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.CraftingRecipes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;

@Requirements
public final class RecipeCommands {
    @Command(aliases = { "citizens" }, modifiers = { "recipe" }, usage = "recipe <recipe ID>",
            desc = "citizens.commands.recipe.description", min = 2, max = 2, strictArguments = true)
    public void query(CommandContext args, CommandSourceStack source, NPC npc) throws CommandException {
        try {
            var recipe = CraftingRecipes.find(source.getServer(), ResourceLocation.parse(args.getString(1)))
                    .orElseThrow(() -> new IllegalArgumentException("Recipe is unavailable"));
            present(source, recipe);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(RecipeCommands.class).warn("Recipe query failed for {}", source.getTextName(), failure);
            throw new CommandException("citizens.commands.recipe.failed");
        }
    }

    public static void present(CommandSourceStack source, CraftingRecipes.Snapshot recipe) {
        if (!recipe.supported()) {
            Messaging.sendTr(source, "citizens.commands.recipe.unsupported", recipe.id(), recipe.type(), recipe.serializer());
            return;
        }
        var output = recipe.output();
        Messaging.sendTr(source, "citizens.commands.recipe.status", recipe.id(), output.getCount(),
                output.getHoverName().getString(), recipe.workstation(), recipe.inputs().size());
    }
}
