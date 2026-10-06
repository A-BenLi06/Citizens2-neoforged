package net.citizensnpcs.api.util;

import net.citizensnpcs.api.command.CommandContext;

/** Shared shorthand and target selection before placeholder expansion. */
public final class NPCCommandFormat {
    private NPCCommandFormat() { }

    public static String prepare(String command) {
        return prepareInvocation(command).command();
    }

    public record Prepared(String command, boolean implicitTarget) { }

    public static Prepared prepareInvocation(String command) {
        String result = command.strip();
        boolean implicitTarget = false;
        if (result.startsWith("/")) result = result.substring(1);
        String verb = result.split("\\s+", 2)[0];
        if (verb.equals("say")) {
            String message = result.substring(verb.length()).strip();
            result = "npc speak " + CommandContext.quoteArgument(message) + " --target <p>";
            verb = "npc";
        }
        if (verb.equals("npc") || verb.equals("waypoints") || verb.equals("wp")) {
            CommandContext context = new CommandContext(result.split(" "));
            // Selection accepts a positional target or --name in addition to the shared identity flags.
            // Adding an ID would turn those valid commands into conflicting-selector errors.
            boolean explicitSelection = verb.equals("npc")
                    && (context.getString(0, "").equalsIgnoreCase("select")
                            || context.getString(0, "").equalsIgnoreCase("sel"))
                    && (context.argsLength() > 1 || context.hasValueFlag("name"));
            if (!context.hasValueFlag("id") && !context.hasValueFlag("uuid") && !explicitSelection) {
                result += " --id <id>";
                implicitTarget = !context.hasValueFlag("registry");
            }
        }
        return new Prepared(result, implicitTarget);
    }
}
