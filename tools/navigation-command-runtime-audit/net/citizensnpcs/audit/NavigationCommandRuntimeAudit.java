package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.ai.TeleportStuckAction;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class NavigationCommandRuntimeAudit {
    private static MinecraftServer server;
    private static CommandSourceStack source;
    private static int started, passed, stage;
    private static int nextTick, virtualTicks, riderTicks, sampledTick;
    private static net.minecraft.world.entity.Entity rider;
    private static boolean forced, done;
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<String> messages = new ArrayList<>(), checks = new ArrayList<>();
    private static final Map<NPC, Location> goals = new java.util.IdentityHashMap<>();

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("navigation-command-fixture.txt")), "isolated_fixture");
                check(!Files.exists(Path.of("complete.json")), "accepted_fixture_cannot_replay");
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_default_registry");
                source = server.createCommandSourceStack().withSource(new CommandSource() {
                    public void sendSystemMessage(Component message) { messages.add(message.getString()); }
                    public boolean acceptsSuccess() { return true; }
                    public boolean acceptsFailure() { return true; }
                    public boolean shouldInformAdmins() { return false; }
                });
                server.overworld().setChunkForced(0, 0, true); forced = true; started = server.getTickCount();
            }
            if (server.getTickCount() - started > 400) throw new AssertionError("fixture_timeout");
            if (!server.overworld().isPositionEntityTicking(new BlockPos(4, -60, 4))) return;
            if (stage == 0) {
                exercise(false); exercise(true);
                npcs.get(1).getNavigator().setPaused(true);
                virtualTicks = npcs.get(1).getEntity().tickCount;
                nextTick = server.getTickCount() + 5; stage = 1; return;
            }
            if (stage == 1) {
                if (server.getTickCount() < nextTick) return;
                check(npcs.get(1).getEntity().tickCount == virtualTicks, "paused_packet_navigation_does_not_simulate");
                npcs.get(1).getNavigator().setPaused(false); stage = 2;
            }
            if (stage == 4) {
                if (server.getTickCount() < nextTick) return;
                check(npcs.get(1).getEntity().tickCount == virtualTicks, "idle_packet_root_returns_to_decorative_state");
                writeResult(); return;
            }
            if (npcs.stream().anyMatch(npc -> npc.getStoredLocation().distance(goals.get(npc)) > 1)) return;
            for (NPC npc : npcs) {
                String label = npc.hasTrait(PacketNPC.class) ? "packet" : "ordinary";
                check(npc.isSpawned() && npc.getStoredLocation().distance(goals.get(npc)) <= 1,
                        label + (stage == 2 ? "_citizens" : "_minecraft") + "_valid_commands_drive_real_navigation_after_rejections");
                check(finite(npc.getEntity().position()) && finite(npc.getEntity().getDeltaMovement()),
                        label + (stage == 2 ? "_citizens" : "_minecraft") + "_native_position_and_motion_remain_finite");
            }
            if (stage == 2) {
                check(npcs.get(1).getEntity().tickCount > virtualTicks, "active_packet_navigation_receives_native_ticks");
                for (NPC npc : npcs) {
                    npc.getNavigator().cancelNavigation();
                    npc.getNavigator().getDefaultParameters().pathfinderType(PathfinderType.MINECRAFT);
                    Location goal = new Location(server.overworld(), 4, -60, npc == npcs.get(0) ? 4 : 7);
                    goals.put(npc, goal); npc.getNavigator().setTarget(goal);
                }
                rider = EntityType.COW.create(server.overworld()); rider.moveTo(npcs.get(1).getEntity().position());
                check(server.overworld().addFreshEntity(rider) && rider.startRiding(npcs.get(1).getEntity(), true),
                        "native_passenger_on_navigating_packet_root");
                virtualTicks = npcs.get(1).getEntity().tickCount; riderTicks = rider.tickCount;
                sampledTick = server.getTickCount(); stage = 3; return;
            }
            check(rider.tickCount - riderTicks == server.getTickCount() - sampledTick,
                    "world_passenger_of_navigating_packet_root_ticks_exactly_once");
            check(npcs.get(1).getEntity().tickCount - virtualTicks <= server.getTickCount() - sampledTick,
                    "packet_root_never_receives_duplicate_native_ticks");
            rider.discard();
            for (NPC npc : npcs) npc.getNavigator().cancelNavigation();
            virtualTicks = npcs.get(1).getEntity().tickCount;
            nextTick = server.getTickCount() + 5; stage = 4;
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[NAVCOMMANDAUDIT] FAILED", failure);
            finish();
        }
    }

    private static void writeResult() throws Exception {
            var result = new LinkedHashMap<String, Object>();
            result.put("checks", passed); result.put("passed", checks);
            result.put("npcs", npcs.stream().map(NavigationCommandRuntimeAudit::state).toList());
            Files.writeString(Path.of("complete.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
            LoggerFactory.getLogger("citizens").info("[NAVCOMMANDAUDIT] COMPLETE {} checks", passed);
            finish();
    }

    private static void exercise(boolean packet) throws Exception {
        String label = packet ? "packet" : "ordinary";
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PIG, label + "Navigation"); npcs.add(npc);
        if (packet) npc.getOrAddTrait(PacketNPC.class);
        check(npc.spawn(new Location(server.overworld(), 4, -60, packet ? 7 : 4)), label + "_spawn");
        var params = npc.getNavigator().getDefaultParameters();
        params.range(32).stationaryTicks(15).distanceMargin(2).pathDistanceMargin(1).attackRange(2)
                .stuckAction(TeleportStuckAction.INSTANCE);
        String earlier = "pathopt --avoid-water true --avoid-teleporting true --open-doors true ";
        var before = state(npc);
        check(command(source, npc, earlier + "--path-range 64 --stationary-ticks 40 --distance-margin 4 "
                + "--attack-delay-duration 1s --path-distance-margin 3 --attack-range -1") == 0,
                label + "_late_invalid_option_is_rejected");
        check(before.equals(state(npc)), label + "_rejected_path_edit_preserves_all_state");

        for (String invalid : List.of("--stationary-ticks -1", "--path-range 0", "--distance-margin -1",
                "--destination-teleport-margin -1", "--path-distance-margin -1", "--attack-range -1",
                "--attack-delay-duration 9223372036854775807s", "--attack-delay-duration not-a-duration",
                "--falling-distance wrong", "--pathfinder-type wrong", "--unknown-option true")) {
            rejectedUnchanged(source, npc, earlier + invalid, label + "_invalid_path_" + invalid.replace(' ', '_'));
        }
        rejectedUnchanged(source, npc, "pathopt --avoid-water yes --path-range 64", label + "_invalid_boolean");
        for (String flag : List.of("path-range", "distance-margin", "destination-teleport-margin", "path-distance-margin", "attack-range")) {
            for (String invalid : List.of("NaN", "Infinity", "-Infinity", "1e309"))
                rejectedUnchanged(source, npc, earlier + "--" + flag + " " + invalid, label + "_finite_" + flag + "_" + invalid);
        }
        rejectedUnchanged(source, npc, earlier + "--path-range 1e39", label + "_path_range_float_overflow");
        for (String invalid : List.of("NaN", "Infinity", "-Infinity", "1e39", "-1e39"))
            rejectedUnchanged(source, npc, "speed \"" + invalid + "\"", label + "_speed_" + invalid);
        for (String accepted : List.of("0", "-1.75", Float.toString(Float.MAX_VALUE))) {
            check(command(source, npc, "speed " + accepted) == 1, label + "_speed_accepts_" + accepted);
            check(params.speedModifier() == Math.abs(Float.parseFloat(accepted)), label + "_speed_value_" + accepted);
        }
        check(command(source, npc, "speed 1.5") == 1, label + "_speed_recovers_after_rejection");

        npc.getEntity().setDeltaMovement(0.05, 0, -0.03); npc.getEntity().hasImpulse = false;
        for (String invalid : List.of("NaN 0 0", "0 Infinity 0", "0 0 -Infinity", "1e309 0 0"))
            rejectedUnchanged(source, npc, "velocity " + invalid, label + "_velocity_" + invalid.replace(' ', '_'));
        check(command(source, npc, "velocity 0.08 0 -0.04") == 1, label + "_valid_velocity_command");
        check(npc.getEntity().getDeltaMovement().equals(new Vec3(0.08, 0, -0.04)) && npc.getEntity().hasImpulse,
                label + "_valid_velocity_updates_motion_and_impulse");
        npc.getEntity().setDeltaMovement(Vec3.ZERO); npc.getEntity().hasImpulse = false;

        check(command(source, npc, "pathopt --path-range 64 --avoid-water false --avoid-teleporting true "
                + "--open-doors false --stationary-ticks 80 --destination-teleport-margin 0 --distance-margin 0.3 "
                + "--path-distance-margin 0.25 --attack-range 3 --attack-delay-duration 3t "
                + "--falling-distance -1 --pathfinder-type CITIZENS") == 1, label + "_complete_valid_path_edit");
        check(params.range() == 64 && !params.avoidWater() && params.stuckAction() == null && params.stationaryTicks() == 80
                && params.destinationTeleportMargin() == 0 && params.distanceMargin() == 0.3 && params.pathDistanceMargin() == 0.25
                && params.attackRange() == 3 && params.attackDelayTicks() == 3 && params.fallDistance() == -1
                && params.pathfinderType() == PathfinderType.CITIZENS
                && npc.data().get(NPC.Metadata.DISABLE_DEFAULT_STUCK_ACTION, false)
                && !npc.data().get(NPC.Metadata.PATHFINDER_OPEN_DOORS, true), label + "_all_valid_values_and_metadata_applied");
        check(command(source, npc, "pathopt --attack-delay-duration -2t") == 1 && params.attackDelayTicks() == -2,
                label + "_existing_signed_duration_compatibility");
        check(command(source, npc, "pathopt --attack-delay-duration 3t") == 1, label + "_restore_positive_attack_delay");
        permission(npc, label);

        Location destination = new Location(server.overworld(), 10, -60, packet ? 7 : 4); goals.put(npc, destination);
        npc.getNavigator().setTarget(destination);
        var strategy = npc.getNavigator().getPathStrategy();
        check(strategy != null && npc.getNavigator().getLocalParameters().speedModifier() == 1.5F
                && npc.getNavigator().getLocalParameters().range() == 64, label + "_new_navigation_uses_validated_defaults");
        rejectedUnchanged(source, npc, earlier + "--attack-range -1", label + "_active_request_invalid_edit");
        check(npc.getNavigator().getPathStrategy() == strategy, label + "_rejection_preserves_active_navigation_owner");
    }

    private static void permission(NPC npc, String label) throws Exception {
        var previous = PermissionUtil.getPermissionResolver();
        try {
            var actor = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "NavigationActor"),
                    ClientInformation.createDefault());
            PermissionUtil.setPermissionResolver((player, permission) -> false);
            rejectedUnchanged(source.withEntity(actor).withPermission(0), npc,
                    "pathopt --path-range 100", label + "_permission_rejection");
        } finally { PermissionUtil.setPermissionResolver(previous); }
    }

    private static void rejectedUnchanged(CommandSourceStack sender, NPC npc, String input, String label) throws Exception {
        var before = state(npc);
        check(command(sender, npc, input) == 0, label + "_rejected");
        check(before.equals(state(npc)), label + "_state_preserved");
    }

    private static int command(CommandSourceStack sender, NPC npc, String input) throws Exception {
        messages.clear();
        try {
            return server.getCommands().getDispatcher().execute("npc " + input + " --id " + npc.getId(), sender);
        } catch (CommandSyntaxException rejected) { return 0; }
    }

    private static Map<String, Object> state(NPC npc) {
        var root = new MemoryDataKey(); npc.saveSnapshot(root);
        var params = npc.getNavigator().getDefaultParameters(); var motion = npc.getEntity().getDeltaMovement();
        var result = new LinkedHashMap<String, Object>();
        result.put("snapshot", root.getValuesDeep());
        result.put("range", params.range()); result.put("speed", params.speedModifier());
        result.put("stationary", params.stationaryTicks()); result.put("water", params.avoidWater());
        result.put("teleport", params.stuckAction() == TeleportStuckAction.INSTANCE);
        result.put("distance", params.distanceMargin()); result.put("pathDistance", params.pathDistanceMargin());
        result.put("destinationTeleport", params.destinationTeleportMargin()); result.put("attackRange", params.attackRange());
        result.put("attackDelay", params.attackDelayTicks()); result.put("falling", params.fallDistance());
        result.put("pathfinder", params.pathfinderType().name());
        result.put("motion", List.of(motion.x, motion.y, motion.z)); result.put("impulse", npc.getEntity().hasImpulse);
        return result;
    }
    private static boolean finite(Vec3 vector) { return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z); }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++; checks.add(name); LoggerFactory.getLogger("citizens").info("[NAVCOMMANDAUDIT] PASS {}", name);
    }
    private static void finish() {
        done = true;
        if (forced) server.overworld().setChunkForced(0, 0, false);
        server.halt(false);
    }
}
