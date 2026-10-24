package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Runs against the pre-fix public API too; the original fails before saving its missing fields. */
@EventBusSubscriber(modid = "citizens")
public final class NavigationSettingsRuntimeAudit {
    private static MinecraftServer server;
    private static String phase;
    private static int started, stage;
    private static boolean forced, done;
    private static final List<String> checks = new ArrayList<>();
    private static final Map<String, UUID> ids = new LinkedHashMap<>();
    private static final List<String> PREFIXES = List.of("ordinary", "packet");

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("navigation-settings-fixture.txt")), "isolated_fixture");
                phase = Files.readString(Path.of("phase.txt")).strip();
                check(phase.equals("seed") || phase.equals("recover"), "known_phase");
                check(!Files.exists(Path.of(phase + ".started")), "attempted_phase_cannot_replay");
                Files.writeString(Path.of(phase + ".started"), phase);
                if (phase.equals("recover")) {
                    var expected = JsonParser.parseString(Files.readString(Path.of("expected.json"))).getAsJsonObject();
                    for (var entry : expected.getAsJsonArray("npcs")) {
                        var snapshot = entry.getAsJsonObject().getAsJsonObject("snapshot");
                        String prefix = snapshot.get("name").getAsString().replace("Settings", "");
                        ids.put(prefix, UUID.fromString(snapshot.get("uuid").getAsString()));
                    }
                    check(ids.keySet().equals(new java.util.LinkedHashSet<>(PREFIXES)), "seed_expected_identities");
                }
                started = server.getTickCount(); server.overworld().setChunkForced(0, 0, true); forced = true;
            }
            if (server.getTickCount() - started > 500) throw new AssertionError("fixture_timeout_stage_" + stage);
            if (!server.overworld().isPositionEntityTicking(new BlockPos(4, -60, 4))) return;
            if (stage == 0) {
                if (phase.equals("seed")) {
                    check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "fresh_default_registry");
                    for (String prefix : PREFIXES) seed(prefix);
                    Citizens.getInstance().storeNPCs(); Citizens.getInstance().reload(); stage = 1; return;
                }
                if (!ready()) return;
                for (String prefix : PREFIXES) {
                    NPC npc = target(prefix);
                    check(npc.getUniqueId().equals(ids.get(prefix)) && npc.getRawName().equals(prefix + "Settings"),
                            prefix + "_restart_identity");
                    verify(npc, 8.75, -2, 0.375, prefix + "_shutdown_edits_restored");
                }
                walk(); stage = 2; return;
            }
            if (!ready()) return;
            if (stage == 1) {
                for (String prefix : PREFIXES) verify(target(prefix), 7.25, 63, 0.125, prefix + "_reload");
                walk(); stage = 2; return;
            }
            if (stage == 2) {
                double x = phase.equals("seed") ? 10 : 4;
                if (PREFIXES.stream().anyMatch(prefix -> target(prefix).getStoredLocation().distance(at(prefix, x)) > 1)) return;
                for (String prefix : PREFIXES) {
                    NPC npc = target(prefix);
                    check(npc.getStoredLocation().distance(at(prefix, x)) <= 1, prefix + "_restored_settings_drive_real_walk");
                    check(Double.isFinite(npc.getEntity().getX()) && Double.isFinite(npc.getEntity().getY())
                            && Double.isFinite(npc.getEntity().getZ()), prefix + "_finite_native_position");
                    npc.getNavigator().cancelNavigation();
                    if (phase.equals("seed")) {
                        command(npc, "pathopt --attack-range 8.75 --attack-delay-duration -2t --path-distance-margin 0.375");
                        verify(npc, 8.75, -2, 0.375, prefix + "_unsaved_shutdown_edits");
                    } else {
                        command(npc, "pathopt --attack-range " + Setting.NPC_ATTACK_DISTANCE.asDouble()
                                + " --attack-delay-duration " + new NavigatorParameters().attackDelayTicks()
                                + "t --path-distance-margin " + Setting.DEFAULT_PATH_DISTANCE_MARGIN.asDouble());
                        check(noOverrides(npc), prefix + "_default_reset_removes_snapshot_overrides");
                    }
                }
                if (phase.equals("seed")) {
                    Files.writeString(Path.of("expected.json"), json(result())); complete(); return;
                }
                Citizens.getInstance().storeNPCs(); Citizens.getInstance().reload(); stage = 3; return;
            }
            for (String prefix : PREFIXES) {
                verify(target(prefix), Setting.NPC_ATTACK_DISTANCE.asDouble(), new NavigatorParameters().attackDelayTicks(),
                        Setting.DEFAULT_PATH_DISTANCE_MARGIN.asDouble(), prefix + "_default_reset_reload");
                check(noOverrides(target(prefix)), prefix + "_default_reset_reload_has_no_stale_overrides");
            }
            complete();
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[NAVSETTINGSAUDIT] FAILED", failure); finish();
        }
    }

    private static void seed(String prefix) throws Exception {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PIG, prefix + "Settings");
        ids.put(prefix, npc.getUniqueId());
        if (prefix.equals("packet")) npc.getOrAddTrait(PacketNPC.class);
        check(npc.spawn(at(prefix, 4)), prefix + "_spawn");
        command(npc, "speed 1.25");
        command(npc, "pathopt --path-range 41 --avoid-water true --avoid-teleporting true --open-doors true "
                + "--stationary-ticks 80 --destination-teleport-margin 0 --distance-margin 0.3 "
                + "--path-distance-margin 0.125 --attack-range 7.25 --attack-delay-duration 63t "
                + "--falling-distance 4 --pathfinder-type CITIZENS");
        var snapshot = new MemoryDataKey(); npc.saveSnapshot(snapshot);
        var nav = snapshot.getRelative("navigator");
        check(nav.keyExists("attackrange") && nav.keyExists("attackdelayticks"), prefix + "_snapshot_contains_attack_settings");
        check(nav.getDouble("attackrange") == 7.25 && nav.getInt("attackdelayticks") == 63
                && nav.getDouble("pathdistancemargin") == 0.125, prefix + "_snapshot_exact_values");
        NPC copy = npc.copy();
        verify(copy, 7.25, 63, 0.125, prefix + "_clone");
        copy.destroy();
        npc.getNavigator().getDefaultParameters().attackRange(99).attackDelayTicks(1).pathDistanceMargin(2);
        npc.load(snapshot);
        verify(npc, 7.25, 63, 0.125, prefix + "_snapshot_restore");
        var defaults = snapshot.copy();
        for (String key : List.of("attackrange", "attackdelayticks", "pathdistancemargin"))
            defaults.getRelative("navigator").removeKey(key);
        npc.load(defaults);
        verify(npc, Setting.NPC_ATTACK_DISTANCE.asDouble(), new NavigatorParameters().attackDelayTicks(),
                Setting.DEFAULT_PATH_DISTANCE_MARGIN.asDouble(), prefix + "_default_snapshot_clears_old_overrides");
        check(noOverrides(npc), prefix + "_default_snapshot_does_not_resurrect_overrides");
        npc.load(snapshot);
        verify(npc, 7.25, 63, 0.125, prefix + "_explicit_snapshot_restored_again");
    }

    private static void verify(NPC npc, double range, int delay, double margin, String label) {
        var params = npc.getNavigator().getDefaultParameters();
        check(params.attackRange() == range, label + "_attack_range");
        check(params.attackDelayTicks() == delay, label + "_attack_delay");
        check(params.pathDistanceMargin() == margin, label + "_path_distance_margin");
        check(params.range() == 41 && params.speedModifier() == 1.25 && params.stationaryTicks() == 80
                && params.distanceMargin() == 0.3 && params.destinationTeleportMargin() == 0 && params.fallDistance() == 4
                && params.avoidWater() && params.stuckAction() == null && params.pathfinderType() == PathfinderType.CITIZENS,
                label + "_existing_settings_retained");
        check(npc.data().get(NPC.Metadata.DISABLE_DEFAULT_STUCK_ACTION, false)
                && npc.data().get(NPC.Metadata.PATHFINDER_OPEN_DOORS, false), label + "_metadata_retained");
    }

    private static void walk() {
        for (String prefix : PREFIXES) {
            NPC npc = target(prefix); npc.getNavigator().setTarget(at(prefix, phase.equals("seed") ? 10 : 4));
            check(npc.getNavigator().getLocalParameters().attackRange() == (phase.equals("seed") ? 7.25 : 8.75)
                    && npc.getNavigator().getLocalParameters().attackDelayTicks() == (phase.equals("seed") ? 63 : -2)
                    && npc.getNavigator().getLocalParameters().pathDistanceMargin() == (phase.equals("seed") ? 0.125 : 0.375),
                    prefix + "_restored_defaults_copied_to_new_request");
        }
    }

    private static boolean noOverrides(NPC npc) {
        var snapshot = new MemoryDataKey(); npc.saveSnapshot(snapshot);
        var nav = snapshot.getRelative("navigator");
        return !nav.keyExists("attackrange") && !nav.keyExists("attackdelayticks") && !nav.keyExists("pathdistancemargin");
    }
    private static NPC target(String prefix) { return CitizensAPI.getNPCRegistry().getByUniqueId(ids.get(prefix)); }
    private static boolean ready() { return PREFIXES.stream().allMatch(prefix -> target(prefix) != null && target(prefix).isSpawned()); }
    private static Location at(String prefix, double x) { return new Location(server.overworld(), x, -60, prefix.equals("packet") ? 7 : 4); }
    private static void command(NPC npc, String input) throws Exception {
        check(server.getCommands().getDispatcher().execute("npc " + input + " --id " + npc.getId(),
                server.createCommandSourceStack()) == 1, npc.getRawName() + "_command_" + input);
    }
    private static Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>(); result.put("phase", phase);
        result.put("checks", checks.size()); result.put("passed", checks);
        result.put("npcs", PREFIXES.stream().map(prefix -> {
            var state = new LinkedHashMap<String, Object>(); NPC npc = target(prefix);
            var snapshot = new MemoryDataKey(); npc.saveSnapshot(snapshot); state.put("snapshot", snapshot.getValuesDeep());
            var p = npc.getNavigator().getDefaultParameters();
            state.put("attackRange", p.attackRange()); state.put("attackDelay", p.attackDelayTicks());
            state.put("pathDistanceMargin", p.pathDistanceMargin()); return state;
        }).toList());
        return result;
    }
    private static String json(Object value) { return new GsonBuilder().setPrettyPrinting().create().toJson(value); }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks.add(name); LoggerFactory.getLogger("citizens").info("[NAVSETTINGSAUDIT] PASS {}", name);
    }
    private static void complete() throws Exception {
        Files.writeString(Path.of(phase + ".complete.json"), json(result()));
        LoggerFactory.getLogger("citizens").info("[NAVSETTINGSAUDIT] COMPLETE {} {} checks", phase, checks.size()); finish();
    }
    private static void finish() { done = true; if (forced) server.overworld().setChunkForced(0, 0, false); server.halt(false); }
}
