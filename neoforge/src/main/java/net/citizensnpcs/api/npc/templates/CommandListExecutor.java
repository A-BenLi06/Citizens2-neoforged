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
 * vanilla {@code /say}, and a Citizens command with no {@code --id} or {@code --uuid} gets an ID so it acts on this NPC rather than on
 * whatever the console last selected. The shared {@code NPCCommandFormat} applies the same rules to scheduled commands.
 * <p>
 * Dispatch raises the source to permission level 4 for the one command instead of opping anyone, which is what a command
 * source makes possible here and what upstream needs an op-list write for.
 */
public class CommandListExecutor implements Consumer<NPC> {
    private final List<String> commands;

    public CommandListExecutor(List<String> commands) {
        this.commands = List.copyOf(commands);
    }

    @Override
    public void accept(NPC npc) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;

        for (String command : commands) {
            var prepared = net.citizensnpcs.api.util.NPCCommandFormat.prepareInvocation(command);
            String cmd = prepared.command();
            CommandSourceStack source = server.createCommandSourceStack().withPermission(4);
            String interpolated = Placeholders.replace(cmd, source, npc);
            try {
                net.citizensnpcs.api.command.NPCCommandTarget.dispatch(prepared.implicitTarget() ? npc : null,
                        interpolated, () -> server.getCommands().performPrefixedCommand(source, interpolated));
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        }
    }
}
