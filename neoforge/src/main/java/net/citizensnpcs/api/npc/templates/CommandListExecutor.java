package net.citizensnpcs.api.npc.templates;

import java.util.List;
import java.util.function.Consumer;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Placeholders;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Runs a list of console commands against one NPC, as a template's {@code commands:} block asks.
 * <p>
 * The two rewrites are upstream's: a line starting with {@code say} is shorthand for making the NPC talk rather than for
 * vanilla {@code /say}, and a Citizens command with no {@code --id} gets one so it acts on this NPC rather than on
 * whatever the console last selected. {@code Util.runCommand} on the mod side applies the same pair; the duplication is
 * upstream's too and is kept so that this package stays inside the API layer.
 * <p>
 * Dispatch raises the source to permission level 4 for the one command instead of opping anyone, which is what a command
 * source makes possible here and what upstream needs an op-list write for.
 */
public class CommandListExecutor implements Consumer<NPC> {
    private final List<String> commands;

    public CommandListExecutor(List<String> commands) {
        this.commands = commands;
    }

    @Override
    public void accept(NPC npc) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;

        for (String command : commands) {
            String cmd = command;
            if (cmd.startsWith("say")) {
                cmd = "npc speak \"" + cmd.replaceFirst("say", "").trim() + "\" --target <p>";
            }
            if ((cmd.startsWith("npc ") || cmd.startsWith("waypoints ") || cmd.startsWith("wp "))
                    && !cmd.contains("--id ")) {
                cmd += " --id <id>";
            }
            CommandSourceStack source = server.createCommandSourceStack().withPermission(4);
            String interpolated = Placeholders.replace(cmd, source, npc);
            try {
                server.getCommands().performPrefixedCommand(source, interpolated);
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        }
    }
}
