package net.citizensnpcs.api.util;

import net.citizensnpcs.api.command.CommandContext;

/** Shared shorthand and target selection before placeholder expansion. */
public final class NPCCommandFormat {
    private NPCCommandFormat() { }

    public static String prepare(String command) {
        String result = command.strip();
        if (result.startsWith("/")) result = result.substring(1);
        String verb = result.split("\\s+", 2)[0];
        if (verb.equals("say")) {
            String message = result.substring(verb.length()).strip();
            result = "npc speak " + CommandContext.quoteArgument(message) + " --target <p>";
            verb = "npc";
        }
        if (verb.equals("npc") || verb.equals("waypoints") || verb.equals("wp")) {
            CommandContext context = new CommandContext(result.split(" "));
            if (!context.hasValueFlag("id") && !context.hasValueFlag("uuid")) result += " --id <id>";
        }
        return result;
    }
}
