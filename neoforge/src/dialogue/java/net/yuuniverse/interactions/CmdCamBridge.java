package net.yuuniverse.interactions;

import java.util.Collection;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.world.level.Level;

/** Checks CmdCam's server-owned scene and recipients before its normal start command sends the path. */
final class CmdCamBridge {
    private CmdCamBridge() {
    }

    static void validateStart(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        try {
            Object scene = Class.forName("team.creative.cmdcam.server.CMDCamServer")
                    .getMethod("get", Level.class, String.class).invoke(null, context.getSource().getLevel(), name);
            if (scene == null) throw new IllegalArgumentException("Unknown CmdCam scene: " + name);
            Object points = scene.getClass().getField("points").get(scene);
            if (!(points instanceof Collection<?> entries) || entries.isEmpty())
                throw new IllegalArgumentException("CmdCam scene has no camera points: " + name);
            if (EntityArgument.getPlayers(context, "players").isEmpty())
                throw new IllegalArgumentException("CmdCam has no online recipients");
        } catch (ReflectiveOperationException | CommandSyntaxException failure) {
            throw new IllegalStateException("Could not validate CmdCam scene " + name,
                    failure.getCause() == null ? failure : failure.getCause());
        }
    }
}
