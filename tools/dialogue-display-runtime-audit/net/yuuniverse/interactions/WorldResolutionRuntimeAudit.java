package net.yuuniverse.interactions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yuuniverse.interactions.DialogueDisplayRuntimeAudit.AuditPlayer;
import org.slf4j.LoggerFactory;

/** Actual dimension transfers and lifecycle checks, after the other controller fixtures have shut down. */
final class WorldResolutionRuntimeAudit {
    private static final Component NPC = Component.literal("World speaker");
    private static final String PAYMENT = "remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%";
    private final MinecraftServer server;
    private final BiFunction<String, UUID, AuditPlayer> factory;
    private final Path aliasesFile = Path.of("config/interactions/world-aliases.yml");
    private byte[] original;
    private boolean prepared;
    private InteractionsMod controller;
    private Actions actions;
    private AuditPlayer alice, bob, delayedPlayer, removedPlayer;
    private ServerLevel island, otherIsland;
    private ActionExecution delayed, removed;
    private int elapsed, passed;

    WorldResolutionRuntimeAudit(MinecraftServer server, BiFunction<String, UUID, AuditPlayer> factory) {
        this.server = server; this.factory = factory;
    }

    void start() throws Exception {
        original = Files.exists(aliasesFile) ? Files.readAllBytes(aliasesFile) : null; prepared = true;
        controller = new InteractionsMod(); NeoForge.EVENT_BUS.unregister(controller);
        actions = new Actions(new ItemLibrary(), new Economy());
        var actionField = InteractionsMod.class.getDeclaredField("actions"); actionField.setAccessible(true); actionField.set(controller, actions);
        controller.onRegisterCommands(new RegisterCommandsEvent(server.getCommands().getDispatcher(), Commands.CommandSelection.DEDICATED,
                CommandBuildContext.simple(server.registryAccess(), FeatureFlags.DEFAULT_FLAGS)));
        alice = factory.apply("WorldAlice", UUID.randomUUID()); bob = factory.apply("WorldBob", UUID.randomUUID());
        delayedPlayer = factory.apply("WorldDelayed", UUID.randomUUID()); removedPlayer = factory.apply("WorldRemoved", UUID.randomUUID());
        writeAliases("{}\n"); reload();
        island = Worlds.resolve(server, "auditworlds:island"); otherIsland = Worlds.resolve(server, "otherworlds:island");
        check(island != null && otherIsland != null && island != otherIsland, "fixture_loads_same_path_in_distinct_namespaces");
        check(Worlds.resolve(server, "island") == null, "ambiguous_bare_path_is_unresolved");
        String current = server.getWorldData().getLevelName();
        for (String name : List.of("minecraft:overworld", "overworld", current, current.toUpperCase(java.util.Locale.ENGLISH)))
            teleport(name, server.overworld());
        for (String name : List.of("minecraft:the_nether", "the_nether", current + "/DIM-1", current + "_nether"))
            teleport(name, server.getLevel(Level.NETHER));
        for (String name : List.of("minecraft:the_end", "the_end", current + "/DIM1", current + "_the_end"))
            teleport(name, server.getLevel(Level.END));
        teleport("auditworlds:island", island);
        teleport("otherworlds:island", otherIsland);
        teleport(current + "/auditworlds/island", island);
        teleport(current + "/otherworlds/island", otherIsland);
        teleport(current + "_auditworlds_island", island);
        teleport(current + "_otherworlds_island", otherIsland);
        for (String name : List.of("missing", "world", "world_nether", "world_the_end", "unknown:overworld",
                "missing:island", "old/DIM1", "old/DIM-1", "old_nether", "old_the_end", ":overworld", "island", current + " "))
            invalid(name);
        check(Worlds.resolve(server, null) == null && Worlds.resolve(null, current) == null,
                "missing_player_world_context_has_no_destination");

        writeAliases("legacy: minecraft:overworld\nlegacy/DIM1: minecraft:the_end\nisland: otherworlds:island\n"
                + "the_end: minecraft:overworld\n'港口 别名': auditworlds:island\nmissing_target: missing:dimension\n"
                + "' spaced ': auditworlds:island\n");
        reload();
        teleport("LEGACY", server.overworld()); teleport("legacy/DIM1", server.getLevel(Level.END));
        teleport("island", otherIsland); teleport("the_end", server.overworld());
        teleport("minecraft:the_end", server.getLevel(Level.END)); teleport("港口 别名", island);
        teleport(" spaced ", island); invalid("spaced "); invalid(" spaced");
        invalid("missing_target");
        check(Worlds.resolve(server, "missing_target") == null, "missing_explicit_target_does_not_fall_back");

        writeAliases("legacy: minecraft:overworld\nLEGACY: minecraft:the_end\n");
        alice.clear(); reload(alice);
        check(Worlds.resolve(server, "legacy/DIM1") == server.getLevel(Level.END)
                && alice.chat.stream().anyMatch(message -> message.getString().contains("previous mappings")),
                "failed_reload_reports_retained_alias_snapshot");
        check(Files.readString(aliasesFile).contains("LEGACY:"), "invalid_alias_configuration_is_not_rewritten");
        writeAliases("minecraft:overworld: minecraft:the_end\n"); reload();
        check(Worlds.resolve(server, "minecraft:overworld") == server.overworld()
                && Worlds.resolve(server, "legacy/DIM1") == server.getLevel(Level.END), "reserved_native_id_cannot_be_shadowed");

        writeAliases("legacy: otherworlds:island\n"); reload();
        teleport("legacy", otherIsland); invalid("legacy/DIM1");
        check(Worlds.resolve(server, "island") == null, "successful_reload_removes_obsolete_disambiguation");
        reset(alice); alice.getInventory().add(new ItemStack(Items.PAPER));
        var cancelled = actions.executeAll(List.of("wait_ticks: 20", PAYMENT, action("legacy")), alice, NPC);
        writeAliases("legacy: minecraft:overworld\n"); reload();
        check(cancelled.result() == ActionExecution.Result.CANCELLED && alice.getInventory().countItem(Items.PAPER) == 1,
                "controller_reload_cancels_pending_old_alias_batch_before_payment");

        reset(delayedPlayer); reset(removedPlayer); removedPlayer.getInventory().add(new ItemStack(Items.PAPER));
        writeAliases("later: minecraft:overworld\nremoved: minecraft:overworld\n");
        check(Worlds.load(aliasesFile.toFile()), "public_alias_reload_accepts_valid_snapshot");
        delayed = actions.executeAll(List.of("wait_ticks: 2", action("later")), delayedPlayer, NPC);
        removed = actions.executeAll(List.of("wait_ticks: 2", PAYMENT, action("removed")), removedPlayer, NPC);
        writeAliases("later: auditworlds:island\n");
        check(Worlds.load(aliasesFile.toFile()) && delayed.pending() && removed.pending(), "delayed_batches_keep_pending_until_server_ticks");
    }

    boolean tick(ServerTickEvent.Post event) {
        controller.onServerTick(event); elapsed++;
        if (elapsed == 1) check(delayedPlayer.serverLevel() == server.overworld() && delayed.pending(), "wait_does_not_transfer_early");
        if (elapsed < 2) return false;
        check(delayed.result() == ActionExecution.Result.SUCCEEDED && delayedPlayer.serverLevel() == island,
                "resumed_teleport_uses_current_alias_destination");
        check(removed.result() == ActionExecution.Result.FAILED && removedPlayer.serverLevel() == server.overworld()
                && removedPlayer.getInventory().countItem(Items.PAPER) == 1, "removed_alias_rejects_resumed_tail_before_payment");
        LoggerFactory.getLogger("interactions").info("[WORLDAUDIT] COMPLETE {} checks", passed);
        return true;
    }

    void close() throws Exception {
        if (!prepared) return;
        if (controller != null) controller.onServerStopping(new ServerStoppingEvent(server));
        if (original == null) Files.deleteIfExists(aliasesFile);
        else Files.write(aliasesFile, original);
        Worlds.clear();
        if (original != null && !Worlds.load(aliasesFile.toFile())) throw new IllegalStateException("Could not restore fixture world aliases");
        prepared = false;
    }

    private void teleport(String name, ServerLevel expected) {
        reset(alice); bob.clear();
        check(actions.runAll(List.of(action(name)), alice, NPC) && alice.serverLevel() == expected
                && alice.getX() == 1.125 && alice.getY() == -60.25 && alice.getZ() == 1.375
                && alice.getYRot() == 21.5F && alice.getXRot() == -7.25F,
                "actual_teleport_" + name);
        check(bob.serverLevel() == server.overworld(), "teleport_is_scoped_to_target_" + name);
    }

    private void invalid(String name) {
        reset(alice); alice.getInventory().add(new ItemStack(Items.PAPER));
        check(!actions.runAll(List.of(PAYMENT, action(name), "player_command_as_op: give @s minecraft:diamond 1"), alice, NPC)
                && alice.serverLevel() == server.overworld() && alice.getX() == 1 && alice.getY() == -60 && alice.getZ() == 1
                && alice.getInventory().countItem(Items.PAPER) == 1 && alice.getInventory().countItem(Items.DIAMOND) == 0,
                "unknown_world_preserves_payment_and_position_" + name);
    }

    private static String action(String name) { return "teleport: " + name + ";1.125;-60.25;1.375;21.5;-7.25"; }
    private void reset(AuditPlayer player) {
        player.teleportTo(server.overworld(), 1, -60, 1, 0, 0); player.getInventory().clearContent(); player.clear();
    }
    private void writeAliases(String contents) throws Exception {
        Files.createDirectories(aliasesFile.getParent()); Files.writeString(aliasesFile, contents);
    }
    private void reload() throws Exception { reload(null); }
    private void reload(AuditPlayer player) throws Exception {
        var source = player == null ? server.createCommandSourceStack() : player.createCommandSourceStack().withPermission(4);
        check(server.getCommands().getDispatcher().execute("interactions reload", source) == 1, "actual_reload_command_completes");
        if (player != null) player.pump();
    }
    private void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("interactions").info("[WORLDAUDIT] PASS {}", name);
    }
}
