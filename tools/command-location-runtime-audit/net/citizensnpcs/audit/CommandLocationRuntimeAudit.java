package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class CommandLocationRuntimeAudit {
    private static boolean done;
    private static int passed;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        done = true;
        var server = event.getServer();
        try {
            check(Files.isRegularFile(Path.of("command-location-audit-fixture.txt")), "isolated_fixture");
            parsing(server);
            commands(server);
            LoggerFactory.getLogger("citizens").info("[COMMANDLOCATIONAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[COMMANDLOCATIONAUDIT] FAILED", failure);
        } finally {
            for (NPC npc : snapshot()) npc.destroy();
            server.halt(false);
        }
    }

    private static void parsing(MinecraftServer server) throws Exception {
        var overworld = server.overworld();
        var nether = server.getLevel(Level.NETHER);
        var end = server.getLevel(Level.END);
        at(CommandContext.parseLocation(overworld, "1.5,64,-2.25,minecraft:the_nether,90,-30"), nether, 1.5, 64, -2.25, 90, -30,
                "namespace_and_rotation_preserved");
        at(CommandContext.parseLocation(nether, "1,64,2"), nether, 1, 64, 2, 0, 0, "omitted_world_uses_actual_source_level");
        at(CommandContext.parseLocation(null, "1,64,2,minecraft:the_end"), end, 1, 64, 2, 0, 0, "explicit_world_without_source");
        at(CommandContext.parseLocation(overworld, "l@1,64,2,45,-15,w@minecraft:the_nether"), nether, 1, 64, 2, 45, -15,
                "denizen_world_last_and_rotation_order");
        at(CommandContext.parseLocation(nether, "l@1,64,2,45,-15"), nether, 1, 64, 2, 45, -15, "denizen_rotation_without_world");
        at(CommandContext.parseLocation(overworld, "1:64:2:the_nether:45:-15"), nether, 1, 64, 2, 45, -15, "legacy_colon_coordinates");
        String folder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
        at(CommandContext.parseLocation(nether, "1,64,2," + folder), overworld, 1, 64, 2, 0, 0, "actual_world_folder_alias");
        at(CommandContext.parseLocation(overworld, "1,64,2," + folder + "/DIM1"), end, 1, 64, 2, 0, 0, "actual_dimension_folder_alias");
        for (String input : List.of("1,64,2,absent:overworld", "1,64,2,unknown_world", "1,,2", "1,64,2,",
                "1,64,2,minecraft:overworld,NaN", "NaN,64,2", "1,1e309,2", "1,64,2,minecraft:overworld,0,Infinity")) {
            try { CommandContext.parseLocation(overworld, input); throw new AssertionError("Accepted invalid location: " + input); }
            catch (CommandException expected) { check(true, "invalid_location_is_command_failure"); }
        }
        try { CommandContext.parseLocation(null, "1,64,2"); throw new AssertionError("Missing level accepted"); }
        catch (CommandException expected) { check(true, "missing_source_world_is_rejected"); }
        var source = server.createCommandSourceStack().withLevel(nether).withPosition(new Vec3(4, 65, -6)).withRotation(new Vec2(-10, 80));
        var context = new CommandContext(source, new String[] { "npc", "create", "Test" });
        for (String alias : List.of("here", "me")) at(context.parseLocation(alias), nether, 4, 65, -6, 80, -10, "source_alias_uses_real_context");
    }

    private static void commands(MinecraftServer server) throws Exception {
        var source = server.createCommandSourceStack().withLevel(server.overworld()).withPosition(new Vec3(0, -60, 0));
        run(server, source, "npc create LocationAudit --type COW --at 1.5,-60,-2.25,minecraft:overworld,90,-30");
        NPC selected = CitizensAPI.getDefaultNPCSelector().getSelected(source);
        check(selected != null && selected.isSpawned(), "create_spawns_and_selects_native_npc");
        at(Location.of(selected.getEntity()), server.overworld(), 1.5, -60, -2.25, 90, -30, "created_entity_matches_location");
        List<NPC> before = snapshot();
        for (String input : List.of("0,nope,0", "0,-60,0,absent:overworld", "0,-60,0,unknown_world", "NaN,-60,0", "0,,0")) {
            fails(server, source, "npc create InvalidLocation --type COW --at " + input);
            check(snapshot().equals(before), "invalid_create_does_not_register_npc");
            check(CitizensAPI.getDefaultNPCSelector().getSelected(source) == selected, "invalid_create_preserves_selection");
        }
        String named = "location-audit-" + UUID.randomUUID();
        fails(server, source, "npc create InvalidNamed --type COW --registry " + named + " --at 0,bad,0");
        check(CitizensAPI.getNamedNPCRegistry(named) == null, "invalid_create_does_not_create_named_registry");
        fails(server, source, "npc create InvalidInherited --type COW --location 0,bad,0");
        check(snapshot().equals(before), "invalid_inherited_location_does_not_register_npc");
        fails(server, source, "npc create InvalidEntityLocation --type COW --entitylocation " + UUID.randomUUID());
        check(snapshot().equals(before), "missing_entity_location_does_not_register_npc");
        run(server, source, "npc home --location 2,-60,3,minecraft:overworld");
        at(selected.getTrait(net.citizensnpcs.trait.HomeTrait.class).getHomeLocation(), server.overworld(), 2, -60, 3, 0, 0,
                "shared_location_binding_accepts_native_namespace");
        run(server, source, "npc create NetherAudit --type COW --at l@1,65,2,45,-15,w@minecraft:the_nether");
        NPC nether = CitizensAPI.getDefaultNPCSelector().getSelected(source);
        check(nether != null && nether != selected && nether.isSpawned(), "denizen_create_spawns_new_npc");
        at(Location.of(nether.getEntity()), server.getLevel(Level.NETHER), 1, 65, 2, 45, -15, "denizen_create_keeps_dimension_and_rotation");
    }

    private static List<NPC> snapshot() {
        List<NPC> result = new ArrayList<>();
        CitizensAPI.getNPCRegistry().forEach(result::add);
        return result;
    }
    private static void run(MinecraftServer server, CommandSourceStack source, String command) throws CommandSyntaxException {
        check(server.getCommands().getDispatcher().execute(command, source) == 1, "native_dispatch_success");
    }
    private static void fails(MinecraftServer server, CommandSourceStack source, String command) throws CommandSyntaxException {
        try { check(server.getCommands().getDispatcher().execute(command, source) == 0, "native_dispatch_reports_failure"); }
        catch (CommandSyntaxException expected) { check(true, "native_dispatch_reports_failure"); }
    }
    private static void at(Location actual, ServerLevel world, double x, double y, double z, float yaw, float pitch, String label) {
        check(actual != null && actual.getWorld() == world && actual.getX() == x && actual.getY() == y && actual.getZ() == z
                && actual.getYaw() == yaw && actual.getPitch() == pitch, label);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[COMMANDLOCATIONAUDIT] PASS {}", label);
    }
}
