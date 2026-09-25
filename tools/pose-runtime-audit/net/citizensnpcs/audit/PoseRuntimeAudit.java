package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.Poses;
import net.citizensnpcs.trait.RotationTrait;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec2;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class PoseRuntimeAudit {
    private static boolean done;
    private static int passed;
    private static NPC npc;
    private static int deadline;
    private static final List<String> messages = new ArrayList<>();

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        var server = event.getServer();
        try {
            if (deadline > 0) {
                if (server.getTickCount() < deadline) return;
                check(angles(75, -25), "default_pose_applies_during_real_world_ticks");
                done = true;
                LoggerFactory.getLogger("citizens").info("[POSEAUDIT] COMPLETE {} checks", passed);
                return;
            }
            check(Files.isRegularFile(Path.of("pose-audit-fixture.txt")), "isolated_fixture");
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "PoseAudit");
            npc.getOrAddTrait(LookClose.class).setEnabled(false);
            check(npc.spawn(new Location(server.overworld(), 0.5, -60, 0.5)), "npc_spawned");
            npc.getEntity().setYRot(42); npc.getEntity().setXRot(11);
            var source = server.createCommandSourceStack().withRotation(new Vec2(-15, 135));
            CitizensAPI.getDefaultNPCSelector().select(source, npc);
            check(server.getCommands().getDispatcher().execute("npc pose --save original", source) == 1, "save_command_executes");
            var pose = npc.getTrait(Poses.class).getPose("original");
            check(pose != null && pose.getYaw() == 42 && pose.getPitch() == 11, "save_captures_npc_not_sender_angles");
            commands(server);
            persistence();
            lifecycle(server);
            var poses = npc.getTrait(Poses.class);
            poses.addPose("realtick", new Location(server.overworld(), 0, 0, 0, 75, -25), true);
            npc.getEntity().setYRot(0); npc.getEntity().setXRot(0);
            deadline = server.getTickCount() + 20;
        } catch (Throwable failure) {
            done = true;
            LoggerFactory.getLogger("citizens").error("[POSEAUDIT] FAILED", failure);
        } finally {
            if (done) try { if (npc != null) npc.destroy(); }
            finally { server.halt(false); }
        }
    }

    private static CommandSourceStack source(MinecraftServer server) {
        return server.createCommandSourceStack().withRotation(new Vec2(-15, 135)).withSource(new CommandSource() {
            @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        });
    }

    private static int command(MinecraftServer server, String value) throws Exception {
        var source = source(server); CitizensAPI.getDefaultNPCSelector().select(source, npc);
        try { return server.getCommands().getDispatcher().execute("npc pose " + value, source); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { return 0; }
    }

    private static boolean pose(String name, float yaw, float pitch) {
        var pose = npc.getTrait(Poses.class).getPose(name);
        return pose != null && pose.getYaw() == yaw && pose.getPitch() == pitch;
    }

    private static boolean angles(float yaw, float pitch) {
        return Math.abs(Mth.wrapDegrees(npc.getEntity().getYRot() - yaw)) < 0.01
                && Math.abs(npc.getEntity().getXRot() - pitch) < 0.01;
    }

    private static void rotate() { for (int i = 0; i < 20; i++) npc.getOrAddTrait(RotationTrait.class).run(); }

    private static long rotationRevision() throws ReflectiveOperationException {
        // Observe the package-private ownership counter without widening the production API for an audit.
        var method = RotationTrait.RotationSession.class.getDeclaredMethod("revision");
        method.setAccessible(true);
        return (long) method.invoke(npc.getTrait(RotationTrait.class).getPhysicalSession());
    }

    private static MemoryDataKey save() {
        var key = new MemoryDataKey(); var poses = npc.getTrait(Poses.class);
        PersistenceLoader.save(poses, key); poses.save(key); return key;
    }

    private static void commands(MinecraftServer server) throws Exception {
        check(command(server, "--save explicit --yaw -90 --pitch -20 -d") == 1 && pose("explicit", -90, -20)
                && npc.getTrait(Poses.class).getDefaultPose().equals("explicit"), "save_overrides_and_default_flag");
        check(angles(42, 11), "save_does_not_change_current_rotation");
        check(command(server, "--save yawonly --yaw 60") == 1 && pose("yawonly", 60, 11), "yaw_only_preserves_npc_pitch");
        check(command(server, "--save pitchonly --pitch 5") == 1 && pose("pitchonly", 42, 5), "pitch_only_preserves_npc_yaw");
        check(command(server, "--mirror sender -d") == 1 && pose("sender", 135, -15)
                && npc.getTrait(Poses.class).getDefaultPose().equals("sender"), "mirror_uses_sender_with_default_flag");
        check(command(server, "--mirror located --location 0,-60,0,minecraft:overworld,80,25") == 1
                && pose("located", 80, 25), "mirror_retains_explicit_source_location");
        check(command(server, "--mirror entity --entitylocation " + npc.getEntity().getUUID()) == 1
                && pose("entity", 42, 11), "mirror_retains_entity_location");
        check(command(server, "--assume EXPLICIT") == 1, "assume_case_insensitive"); rotate();
        check(angles(-90, -20), "assume_uses_native_rotation_session");
        check(command(server, "-a") == 1, "assume_sender_flag_executes"); rotate();
        check(angles(135, -15), "assume_sender_flag_uses_sender_rotation");
        check(command(server, "--save separate --yaw 30 --pitch 20 -a") == 1 && pose("separate", 30, 20),
                "save_and_assume_sender_keep_distinct_directions"); rotate();
        check(angles(135, -15), "trailing_assume_sender_runs_after_save");
        check(command(server, "--default Original") == 1 && npc.getTrait(Poses.class).getDefaultPose().equals("original"),
                "named_default_is_case_insensitive");
        messages.clear(); check(command(server, "") == 1, "listing_executes");
        check(messages.stream().anyMatch(text -> text.contains("0  original  11.0 / 42.0")), "listing_includes_stable_id_pitch_and_yaw");
        Map<String, Object> before = save().getValuesDeep();
        for (String invalid : List.of("--save broken --yaw NaN", "--save broken --pitch Infinity", "--save \"bad;name\"",
                "--save \"\"", "--save \"   \"", "--save original", "--assume missing", "--default missing",
                "--remove missing", "--save x --mirror y", "--save x --assume original", "--mirror x --yaw 3",
                "--yaw 3", "-d", "2 --save x", "--save x --unknown true", "--save x -a --location invalid-dimension")) {
            command(server, invalid);
            check(save().getValuesDeep().equals(before), "atomic_rejection_" + invalid);
        }
        check(command(server, "--remove SEPARATE") == 1 && !npc.getTrait(Poses.class).hasPose("separate"), "remove_is_case_insensitive");
        npc.removeTrait(Poses.class);
        for (String invalid : List.of("--save broken --pitch NaN", "--mirror \"bad;name\"", "--assume missing", "--default missing",
                "--mirror invalid --entitylocation not-a-uuid", "0", "2", "not-a-page")) {
            command(server, invalid);
            check(!npc.hasTrait(Poses.class), "invalid_command_does_not_attach_trait_" + invalid);
        }
        check(command(server, "") == 1 && !npc.hasTrait(Poses.class), "empty_listing_does_not_attach_trait");
        npc.getOrAddTrait(Poses.class);
    }

    private static void persistence() throws Exception {
        var input = new MemoryDataKey(); input.setString("defaultPose", "INVALID");
        input.setString("list.0", "Original;11.0;42.0");
        List<String> invalid = List.of("invalid;NaN;20", "overflow;0;Infinity", "wrongfields;1", "extra;1;2;3", "broken;no;0", ";1;2");
        for (int i = 0; i < invalid.size(); i++) input.setString("list." + (i + 1), invalid.get(i));
        var poses = new Poses(); npc.addTrait(poses); PersistenceLoader.load(poses, input); poses.load(input);
        check(pose("original", 42, 11), "legacy_pose_loads_original_angle_order");
        check(poses.getPoses().size() == 1 && !poses.hasPose("invalid"), "invalid_legacy_angles_remain_inactive");
        long revision = rotationRevision(); poses.run();
        check(rotationRevision() == revision, "invalid_default_never_submits_rotation");
        check(save().getRelative("list").getValuesDeep().values().containsAll(invalid), "invalid_legacy_records_survive_save");
        var roundtrip = save(); poses.load(roundtrip);
        check(poses.getPoses().size() == 1 && save().getRelative("list").getValuesDeep().values().containsAll(invalid), "inactive_records_survive_reload");
        poses.addPose("INVALID", new Location(null, 0, 0, 0, 20, 10));
        check(pose("invalid", 20, 10) && !save().getRelative("list").getValuesDeep().values().contains(invalid.get(0)), "explicit_repair_replaces_invalid_record");
        check(poses.removePose("WRONGFIELDS") && !save().getRelative("list").getValuesDeep().values().contains(invalid.get(2)), "explicit_removal_can_remove_inactive_record");
        var before = save().getValuesDeep();
        try { poses.addPose("bad;name", new Location(null, 0, 0, 0)); throw new AssertionError("Invalid name accepted"); }
        catch (IllegalArgumentException expected) { check(before.equals(save().getValuesDeep()), "api_invalid_name_is_atomic"); }
        try { poses.addPose("badangle", new Location(null, 0, 0, 0, Float.NaN, 0)); throw new AssertionError("Invalid angle accepted"); }
        catch (IllegalArgumentException expected) { check(before.equals(save().getValuesDeep()), "api_invalid_angle_is_atomic"); }
        try { poses.assumePose(new Location(null, 0, 0, 0, 0, Float.POSITIVE_INFINITY)); throw new AssertionError("Invalid assumption accepted"); }
        catch (IllegalArgumentException expected) { check(rotationRevision() == revision,
                "api_invalid_assumption_never_submits_rotation"); }
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            poses.addPose("IRIS", new Location(null, 0, 0, 0, 35, 15), true);
            check(pose("iris", 35, 15) && poses.getDefaultPose().equals("iris"), "names_are_independent_of_host_locale");
            var copy = poses.getPoses(); copy.clear();
            check(poses.hasPose("IRIS"), "pose_snapshot_is_detached");
            check(new ArrayList<>(poses.getPoses().keySet()).equals(List.of("original", "invalid", "iris")), "snapshot_preserves_insertion_order");
        } finally { Locale.setDefault(old); }
    }

    private static void lifecycle(MinecraftServer server) throws Exception {
        var poses = npc.getTrait(Poses.class);
        npc.despawn(); Location stored = npc.getStoredLocation().clone();
        check(command(server, "--save offline --yaw -70") == 1 && pose("offline", -70, stored.getPitch()), "save_works_while_unspawned");
        check(npc.getStoredLocation().equals(stored) && !npc.isSpawned(), "save_overrides_do_not_mutate_stored_spawn_location");
        check(command(server, "--assume offline") == 1 && npc.isSpawned(), "assume_respawns_at_stored_location"); rotate();
        check(angles(-70, stored.getPitch()), "respawned_entity_assumes_saved_angles");
        poses.setDefaultPose("original");
        npc.getNavigator().setTarget(new Location(server.overworld(), 8, -60, 0));
        check(npc.getNavigator().isNavigating(), "native_navigation_started");
        long revision = rotationRevision(); poses.run();
        check(rotationRevision() == revision, "default_pose_yields_to_navigation");
        npc.getNavigator().cancelNavigation(); poses.run(); rotate();
        check(angles(42, 11), "default_pose_returns_when_idle");
        NPC original = npc;
        try {
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.COW, "NoStoredPosition");
            command(server, "--save missing"); check(!npc.hasTrait(Poses.class), "missing_npc_location_rejects_save_without_attachment");
            var unplaced = npc.getOrAddTrait(Poses.class); unplaced.addPose("only", new Location(null, 0, 0, 0, 1, 2));
            unplaced.assumePose("only"); check(!npc.isSpawned(), "unplaced_npc_is_not_spawned_at_an_invented_position");
        } finally { npc.destroy(); npc = original; }
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[POSEAUDIT] PASS {}", label);
    }
}
