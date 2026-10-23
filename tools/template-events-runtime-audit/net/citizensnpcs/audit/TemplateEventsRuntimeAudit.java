package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.templates.Template;
import net.citizensnpcs.api.npc.templates.TemplateRegistry;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Uses only pre-existing public APIs so the same probe also runs against the original implementation. */
@EventBusSubscriber(modid = "citizens")
public final class TemplateEventsRuntimeAudit {
    private static final List<String> EVENTS = List.of("spawn", "damage", "death", "left_click", "right_click");
    private static MinecraftServer server;
    private static String phase;
    private static int started, stage, passed;
    private static boolean forced, done;
    private static final Properties expected = new Properties();

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("template-events-fixture.txt")), "isolated_fixture");
                phase = Files.readString(Path.of("phase.txt")).strip();
                check(!Files.exists(Path.of(phase + ".complete")), "completed_phase_cannot_replay");
                check(phase.equals("seed") || phase.equals("recover"), "known_phase");
                started = server.getTickCount();
                server.overworld().setChunkForced(0, 0, true);
                forced = true;
            }
            if (server.getTickCount() - started > 400) throw new AssertionError("fixture_timeout");
            if (!server.overworld().isPositionEntityTicking(new BlockPos(4, -58, 4))) return;
            if (phase.equals("recover")) {
                if (!ready()) return;
                recover(); complete(); return;
            }
            if (stage == 0) {
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "fresh_default_registry");
                command("scoreboard objectives add template_events dummy");
                seed(false); seed(true);
                Citizens.getInstance().storeNPCs();
                Citizens.getInstance().reload();
                stage = 1;
                return;
            }
            if (!ready()) return;
            for (String prefix : List.of("ordinary", "packet")) {
                NPC npc = target(prefix);
                check(score(prefix + "_spawn") == 3, prefix + "_reload_and_clone_spawn_once_each");
                check(score(prefix + "_apply") == 1, prefix + "_reload_does_not_repeat_one_time_commands");
                fire(npc);
                for (String name : EVENTS.subList(1, EVENTS.size()))
                    check(score(prefix + "_" + name) == 2, prefix + "_reload_event_" + name + "_once");
                check(npc.getRawName().equals(prefix + "_runtime"), prefix + "_reload_preserves_runtime_name");
            }
            // Leave this reapplication unsaved: normal shutdown must retain the latest applied definition.
            definition("ordinary", 10).apply(target("ordinary"));
            check(score("ordinary_spawn") == 13, "reapplication_replaces_spawn_commands");
            check(score("ordinary_apply") == 2, "explicit_reapplication_runs_one_time_command_once");
            target("ordinary").setName("ordinary_latest_runtime");
            saveExpected(); complete();
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[TEMPLATEEVENTAUDIT] FAILED", failure);
            finish();
        }
    }

    private static void seed(boolean packet) throws Exception {
        String prefix = packet ? "packet" : "ordinary";
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PIG, prefix);
        if (packet) npc.getOrAddTrait(PacketNPC.class);
        expected.setProperty(prefix + ".uuid", npc.getUniqueId().toString());
        check(npc.spawn(at(packet ? 6 : 4)), prefix + "_initial_spawn");
        definition(prefix, 1).apply(npc);
        check(score(prefix + "_spawn") == 1 && score(prefix + "_apply") == 1, prefix + "_template_apply_commands");
        var snapshot = new MemoryDataKey(); npc.saveSnapshot(snapshot);
        check(snapshot.keyExists("template-events") && snapshot.getRelative("template-events").getValuesDeep()
                .toString().contains(prefix + "_right_click"), prefix + "_snapshot_contains_applied_event_commands");
        NPC copy = npc.copy();
        check(copy.spawn(at(packet ? 10 : 8)), prefix + "_clone_spawn");
        check(score(prefix + "_spawn") == 2 && score(prefix + "_apply") == 1, prefix + "_clone_restores_only_events");
        copy.destroy();
        npc.setName(prefix + "_runtime");
        var bad = new MemoryDataKey(); npc.saveSnapshot(bad);
        bad.setString("name", "must not publish"); bad.setInt("template-events.version", 999);
        try { npc.load(bad); throw new AssertionError("malformed_snapshot_accepted"); }
        catch (IllegalArgumentException correct) { passed++; }
        check(npc.getRawName().equals(prefix + "_runtime"), prefix + "_invalid_snapshot_preserves_name");
        fire(npc);
        for (String name : EVENTS.subList(1, EVENTS.size()))
            check(score(prefix + "_" + name) == 1, prefix + "_event_" + name + "_once");
    }

    private static void recover() throws Exception {
        try (var reader = Files.newBufferedReader(Path.of("expected.properties"))) { expected.load(reader); }
        for (String prefix : List.of("ordinary", "packet")) {
            NPC npc = target(prefix);
            int amount = prefix.equals("ordinary") ? 10 : 1;
            check(npc.getUniqueId().equals(UUID.fromString(expected.getProperty(prefix + ".uuid"))), prefix + "_restart_uuid");
            check(npc.getRawName().equals(prefix.equals("ordinary") ? "ordinary_latest_runtime" : "packet_runtime"),
                    prefix + "_restart_does_not_reapply_yaml_replacement");
            check(score(prefix + "_spawn") == Integer.parseInt(expected.getProperty(prefix + ".spawn")) + amount,
                    prefix + "_restart_spawn_command_once");
            check(score(prefix + "_apply") == Integer.parseInt(expected.getProperty(prefix + ".apply")),
                    prefix + "_restart_does_not_replay_one_time_command");
            fire(npc);
            for (String name : EVENTS.subList(1, EVENTS.size()))
                check(score(prefix + "_" + name) == Integer.parseInt(expected.getProperty(prefix + "." + name)) + amount,
                        prefix + "_restart_event_" + name + "_once");
            // Applying a template with no hooks must survive another NPC snapshot restore.
            emptyDefinition(prefix).apply(npc);
            var snapshot = new MemoryDataKey(); npc.saveSnapshot(snapshot); npc.load(snapshot);
            int previous = score(prefix + "_damage");
            NeoForge.EVENT_BUS.post(new NPCDamageEvent(npc, server.overworld().damageSources().generic(), 1));
            check(score(prefix + "_damage") == previous, prefix + "_removed_events_stay_removed_after_snapshot_load");
        }
    }

    private static Template definition(String prefix, int amount) {
        var root = new MemoryDataKey();
        var commands = new LinkedHashMap<String, Object>();
        for (String event : EVENTS) commands.put("on_" + event,
                List.of("scoreboard players add " + prefix + "_" + event + " template_events " + amount));
        commands.put("on_template_apply", List.of("scoreboard players add " + prefix + "_apply template_events 1"));
        root.setRaw("commands", commands);
        root.setRaw("yaml_replace", Map.of("override", true, "replacements", Map.of("name", prefix + "_definition")));
        return Template.load(new TemplateRegistry.TemplateErrorReporter(), null, ResourceLocation.parse("audit:" + prefix), root);
    }

    private static Template emptyDefinition(String prefix) {
        var root = new MemoryDataKey(); root.setRaw("", Map.of());
        return Template.load(new TemplateRegistry.TemplateErrorReporter(), null, ResourceLocation.parse("audit:" + prefix), root);
    }

    private static void fire(NPC npc) {
        // No physical input is simulated. These are native Citizens events with a synthetic actor.
        npc.removeTrait(net.citizensnpcs.trait.text.Text.class);
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "TemplateActor"),
                ClientInformation.createDefault()) {
            @Override public void sendSystemMessage(Component message) { }
        };
        NeoForge.EVENT_BUS.post(new NPCDamageEvent(npc, server.overworld().damageSources().generic(), 1));
        NeoForge.EVENT_BUS.post(new NPCDeathEvent(npc, server.overworld().damageSources().generic(), new ArrayList<>()));
        NeoForge.EVENT_BUS.post(new NPCLeftClickEvent(npc, player));
        NeoForge.EVENT_BUS.post(new NPCRightClickEvent(npc, player));
    }

    private static boolean ready() {
        for (String prefix : List.of("ordinary", "packet")) {
            NPC npc = target(prefix);
            if (npc == null || !npc.isSpawned()) return false;
        }
        return true;
    }

    private static NPC target(String prefix) {
        if (phase.equals("recover") && expected.isEmpty()) {
            try (var reader = Files.newBufferedReader(Path.of("expected.properties"))) { expected.load(reader); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        return CitizensAPI.getNPCRegistry().getByUniqueId(UUID.fromString(expected.getProperty(prefix + ".uuid")));
    }

    private static Location at(int x) { return new Location(server.overworld(), x, -60, 4); }
    private static int command(String command) throws Exception {
        return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack().withPermission(4));
    }
    private static int score(String holder) throws Exception { return command("scoreboard players get " + holder + " template_events"); }
    private static void saveExpected() throws Exception {
        for (String prefix : List.of("ordinary", "packet")) {
            for (String event : EVENTS) expected.setProperty(prefix + "." + event, Integer.toString(score(prefix + "_" + event)));
            expected.setProperty(prefix + ".apply", Integer.toString(score(prefix + "_apply")));
        }
        try (var writer = Files.newBufferedWriter(Path.of("expected.properties"))) { expected.store(writer, "Seed result before normal shutdown"); }
        var snapshots = new YamlStorage(Path.of("expected-npcs.yml").toFile());
        for (String prefix : List.of("ordinary", "packet")) target(prefix).saveSnapshot(snapshots.getKey(prefix));
        snapshots.save();
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("citizens").info("[TEMPLATEEVENTAUDIT] PASS {}", name);
    }
    private static void complete() throws Exception {
        Files.writeString(Path.of(phase + ".complete"), Integer.toString(passed));
        LoggerFactory.getLogger("citizens").info("[TEMPLATEEVENTAUDIT] COMPLETE {} {} checks", phase, passed);
        finish();
    }
    private static void finish() {
        done = true;
        if (forced) server.overworld().setChunkForced(0, 0, false);
        server.halt(false);
    }
}
