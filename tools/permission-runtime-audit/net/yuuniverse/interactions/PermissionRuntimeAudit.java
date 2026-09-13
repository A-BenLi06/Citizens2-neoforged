package net.yuuniverse.interactions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.Command;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.CommandManager;
import net.citizensnpcs.api.command.Flag;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.commands.CommandRegistry;
import net.citizensnpcs.trait.shop.NPCShop;
import net.citizensnpcs.util.ParadigmPermissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Only loaded by permission-runtime-audit.gradle in a separately prepared, empty server. */
@EventBusSubscriber(modid = "interactions")
public final class PermissionRuntimeAudit {
    private static final Logger LOGGER = LoggerFactory.getLogger("citizens");
    private static boolean finished, forced;
    private static int passed, flagCalls;
    private static State state;
    private static int deadline;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void register(RegisterCommandsEvent event) {
        var manager = new CommandManager();
        manager.register(GuardCommand.class);
        new CommandRegistry(manager).register(event.getDispatcher());
    }

    public static class GuardCommand {
        @Command(aliases = "paudit", modifiers = "guard", permission = "permissionaudit.command", desc = "Audit")
        public static void guard(CommandContext args, CommandSourceStack sender, NPC npc,
                @Flag(value = {"key", "k"}, defValue = "default", permission = "permissionaudit.flag") String key) { flagCalls++; }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (finished) return;
        var server = event.getServer();
        var level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; }
        if (!level.areEntitiesLoaded(ChunkPos.asLong(0, 0)) || !level.isPositionEntityTicking(new BlockPos(1, 0, 1))) {
            if (server.getTickCount() > 1200) {
                LOGGER.error("[PERMISSIONAUDIT] FAILED fixture loading timed out");
                finished = true; server.halt(false);
            }
            return;
        }
        try {
            if (state == null) {
                state = new State(server);
                state.run();
                state.close();
                state.drainStorage();
                deadline = server.getTickCount() + 400;
                return;
            }
            if (!state.drained.isDone()) {
                if (server.getTickCount() > deadline) throw new AssertionError("Provider storage queue did not drain");
                return;
            }
            if (state.provider) check(true, "provider_storage_queue_drains_before_shutdown");
            LOGGER.info("[PERMISSIONAUDIT] COMPLETE {} checks provider={}", passed, state.provider);
        } catch (Throwable failure) {
            LOGGER.error("[PERMISSIONAUDIT] FAILED", failure);
        }
        finished = true;
        try { if (state != null) state.close(); }
        catch (Throwable failure) { LOGGER.error("[PERMISSIONAUDIT] FAILED cleanup", failure); }
        server.halt(false);
    }

    private static final class State {
        final MinecraftServer server;
        final ServerLevel level;
        final NPCRegistry registry = CitizensAPI.getNPCRegistry();
        final boolean provider = ModList.get().isLoaded("paradigm");
        final List<AuditPlayer> players = new ArrayList<>();
        final List<PermissionUtil.Attachment> attachments = new ArrayList<>();
        final String parentGroup = "permission_parent_" + UUID.randomUUID().toString().substring(0, 8);
        final String childGroup = "permission_child_" + UUID.randomUUID().toString().substring(0, 8);
        AuditPlayer alice, bob;
        CommandSourceStack source, elevated, console;
        boolean isolated, closed;
        final CompletableFuture<Void> drained = new CompletableFuture<>();

        State(MinecraftServer server) { this.server = server; level = server.overworld(); }

        void run() throws Exception {
            if (!Files.isRegularFile(Path.of("permission-audit-fixture.txt")) || registry.iterator().hasNext())
                throw new AssertionError("Permission audit requires its own empty fixture");
            isolated = true;
            alice = player("PermissionAlice"); bob = player("PermissionBob");
            source = alice.createCommandSourceStack().withPermission(0);
            elevated = source.withPermission(4);
            console = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
            check(!alice.hasPermissions(2) && !bob.hasPermissions(2), "fixtures_are_non_operators");
            Set<String> registered = PermissionAPI.getRegisteredNodes().stream()
                    .map(node -> node.getNodeName()).collect(java.util.stream.Collectors.toSet());
            check(registered.containsAll(Set.of("citizens.npc.rename", "citizens.npc.*", "citizens.npc.create.villager",
                    "citizens.ignore-owner", "citizens.paudit.help", "permissionaudit.command", "permissionaudit.flag")),
                    "declarations_flags_manual_and_registry_nodes_are_gathered");
            check(!registered.contains("citizens.citizens.npc.rename") && !registered.contains("citizens.citizens.npc._"),
                    "permission_names_are_not_prefixed_or_sanitized");
            check(PermissionAPI.getRegisteredNodes().contains(PermissionUtil.register("CITIZENS.NPC.RENAME")),
                    "case_normalization_reuses_registered_node");
            check(!PermissionUtil.hasPermission(source, "citizens.npc.rename"), "undefined_non_operator_denied");
            check(PermissionUtil.hasPermission(elevated, "citizens.npc.rename"), "undefined_source_elevation_uses_default");
            check(PermissionUtil.hasPermission(console, "citizens.npc.rename"), "console_remains_authorized");
            temporary();
            if (provider) withProvider();
            else {
                check(PermissionUtil.getPermissionResolver() == null, "absent_provider_has_no_reflective_dependency");
                check(!PermissionUtil.hasPermission(alice, "interactions.start.unregistered-story"), "dynamic_fallback_denies_non_operator");
                check(PermissionUtil.hasPermission(elevated, "interactions.start.unregistered-story"), "dynamic_fallback_honors_source_elevation");
                server.getPlayerList().op(bob.getGameProfile());
                check(PermissionUtil.hasPermission(bob, "citizens.npc.rename"), "operator_default_without_provider");
                server.getPlayerList().deop(bob.getGameProfile());
                denied(source, "npc help");
                check(true, "native_command_checks_fallback_permissions");
            }
        }

        void temporary() {
            var names = new ArrayList<>(List.of("  CITIZENS.NPC.RENAME  "));
            var first = hold(alice, names);
            names.clear();
            var second = hold(alice, List.of("citizens.npc.rename"));
            first.remove(); first.remove();
            check(PermissionUtil.hasPermission(alice, "citizens.npc.rename"), "overlapping_attachments_keep_remaining_grant");
            second.remove();
            check(!PermissionUtil.hasPermission(alice, "citizens.npc.rename"), "mutable_input_and_repeated_removal_do_not_leak_grants");
            var parent = hold(alice, List.of("citizens.npc.showshop.*"));
            check(PermissionUtil.hasPermission(alice, "citizens.npc.showshop"), "temporary_parent_expands_declared_base_child");
            parent.remove();
            var old = hold(alice, List.of("permissionaudit.rejoined"));
            PermissionUtil.clearTemporary(alice.getUUID());
            var current = hold(alice, List.of("permissionaudit.rejoined"));
            old.remove();
            check(PermissionUtil.hasPermission(alice, "permissionaudit.rejoined"), "old_attachment_cannot_revoke_new_login_grant");
            current.remove();
            PermissionUtil.setTemporaryPermissionGranter((player, permissions) -> { throw new IllegalStateException("Audit failure"); });
            try {
                try { PermissionUtil.grantTemporary(alice, List.of("permissionaudit.failed-grant")); throw new AssertionError("Expected failure"); }
                catch (IllegalStateException expected) { }
            } finally { PermissionUtil.setTemporaryPermissionGranter(null); }
            check(!PermissionUtil.hasPermission(alice, "permissionaudit.failed-grant"), "external_attachment_failure_rolls_back_local_grant");
        }

        void withProvider() throws Exception {
            check("paradigm:internal".equals(String.valueOf(PermissionAPI.getActivePermissionHandler())), "actual_paradigm_handler_is_selected");
            check(PermissionUtil.getPermissionResolver() != null, "dynamic_bridge_installed_after_provider_startup");
            for (String node : List.of("citizens.npc.rename", "citizens.npc.select", "citizens.npc.create", "citizens.npc.create.villager"))
                permit(alice, node);
            check(PermissionUtil.hasPermission(alice, "citizens.npc.rename"), "real_user_grant_works_without_op");
            check(PermissionAPI.getPermission(alice, PermissionUtil.register("citizens.npc.rename")), "direct_neoforge_api_sees_same_grant");
            remove(alice, "citizens.npc.rename");
            check(!PermissionUtil.hasPermission(alice, "citizens.npc.rename"), "real_user_revoke_takes_effect_immediately");
            permit(alice, "citizens.npc.rename");

            for (String[] command : List.of(
                    new String[] {"waypoints", "wp", "citizens.waypoints.provider"},
                    new String[] {"template", "tpl", "citizens.templates.list"})) {
                String permission = "citizens." + command[0] + ".help";
                permit(alice, permission); permit(alice, command[2]);
                ok(source, command[0] + " help"); ok(source, command[1] + " help");
                check(true, "primary_help_permission_covers_alias_" + command[1]);
                remove(alice, permission);
                denied(source, command[1] + " help");
                check(true, "alias_help_rechecks_canonical_grant_" + command[1]);
                remove(alice, command[2]);
            }

            ok(source, "npc create PermissionCreated --type villager");
            NPC owned = CitizensAPI.getDefaultNPCSelector().getSelected(source);
            check(owned != null && owned.getOrAddTrait(Owner.class).isOwnedBy(source), "non_operator_can_create_authorized_entity_type");
            int before = size();
            denied(source, "npc create ForbiddenType --type zombie");
            check(size() == before, "entity_type_permission_prevents_unauthorized_creation");
            ok(source, "npc rename RenamedByGrant");
            check(owned.getName().equals("RenamedByGrant"), "native_command_uses_real_permission_grant");
            CitizensAPI.getDefaultNPCSelector().deselect(source);
            ok(source, "npc rename TargetedById --id " + owned.getId());
            check(owned.getName().equals("TargetedById"), "canonical_select_permission_authorizes_id_target");
            ok(source, "npc rename TargetedByUuid --uuid " + owned.getUniqueId());
            check(owned.getName().equals("TargetedByUuid"), "canonical_select_permission_authorizes_uuid_target");

            NPC foreign = registry.createNPC(EntityType.VILLAGER, "ForeignOwner");
            foreign.getOrAddTrait(Owner.class).setOwner(bob.getUUID());
            denied(source, "npc rename Intrusion --id " + foreign.getId());
            check(foreign.getName().equals("ForeignOwner"), "command_grant_does_not_bypass_ownership");
            permit(alice, "citizens.ignore-owner");
            ok(source, "npc rename OwnershipBypass --id " + foreign.getId());
            check(foreign.getName().equals("OwnershipBypass"), "manual_ownership_permission_is_registered");
            remove(alice, "citizens.ignore-owner");

            permit(alice, "permissionaudit.command");
            ok(source, "paudit guard");
            check(flagCalls == 1, "omitted_restricted_flag_preserves_command_default");
            denied(source, "paudit guard --key value");
            denied(source, "paudit guard --k value");
            check(flagCalls == 1, "flag_permission_denies_body_and_alias_before_execution");
            permit(alice, "permissionaudit.flag");
            ok(source, "paudit guard --key value");
            check(flagCalls == 2, "flag_permission_grant_reaches_body");

            ok(console, "paradigm group add " + parentGroup);
            ok(console, "paradigm group add " + childGroup);
            ok(console, "paradigm group parent add " + childGroup + " " + parentGroup);
            // Paradigm 2.4.2b maps its STRING command argument to Brigadier word(), which rejects '*' and Unicode
            // even when quoted. Seed those fixture rules through its public mutation API, without changing its jar
            // or pretending the CLI accepts them. Ordinary rules still exercise the real administrative commands.
            providerMutation("addPermissionToGroup", new Class<?>[] {String.class, String.class, boolean.class},
                    parentGroup, "citizens.npc.showshop.*", false);
            providerMutation("addPermissionToGroup", new Class<?>[] {String.class, String.class, boolean.class},
                    parentGroup, "permissionaudit.inherited.*", false);
            ok(console, "paradigm group user add " + alice.getUUID() + " " + childGroup);
            check(Boolean.TRUE.equals(PermissionUtil.inGroup(Set.of(parentGroup), alice)), "group_metadata_includes_inherited_parent");
            check(PermissionUtil.hasPermission(alice, "permissionaudit.inherited.feature"), "dynamic_permission_inherits_group_wildcard");
            check(PermissionUtil.hasPermission(alice, "citizens.npc.showshop"), "declared_wildcard_parent_grants_base_command");
            forbid(alice, "citizens.npc.showshop");
            check(!PermissionUtil.hasPermission(alice, "citizens.npc.showshop"), "exact_denial_wins_over_inherited_parent");
            remove(alice, "citizens.npc.showshop");

            remove(alice, "citizens.npc.rename");
            forbid(alice, "citizens.npc.rename");
            forbid(alice, "citizens.admin");
            check(!PermissionUtil.hasPermission(elevated, "citizens.npc.rename"), "source_elevation_cannot_override_explicit_denial");
            denied(elevated, "npc rename DeniedByBackend --id " + owned.getId());
            check(owned.getName().equals("TargetedByUuid"), "command_failure_preserves_npc_after_explicit_denials");
            server.getPlayerList().op(alice.getGameProfile());
            check(!PermissionUtil.hasPermission(alice, "citizens.npc.rename"), "operator_cannot_override_explicit_denial");
            check(!PermissionUtil.hasPermission(elevated, "citizens.npc.rename"), "operator_source_cannot_override_explicit_denial");
            server.getPlayerList().deop(alice.getGameProfile());
            remove(alice, "citizens.npc.rename"); remove(alice, "citizens.admin");

            String dynamic = "permissionaudit.自定义.unlock";
            apiUserRule(alice, dynamic, true);
            check(PermissionUtil.hasPermission(alice, dynamic), "unregistered_unicode_permission_resolves_exactly");
            NPCShop shop = new NPCShop("PermissionAudit"); shop.setPermission(dynamic);
            check(shop.canView(alice) && !shop.canView(bob), "shop_uses_dynamic_provider_permission");
            providerMutation("removePermissionFromPlayer", new Class<?>[] {UUID.class, String.class}, alice.getUUID(), dynamic);
            apiUserRule(alice, dynamic, false);
            check(!shop.canView(alice) && !PermissionUtil.hasPermission(elevated, dynamic), "dynamic_denial_overrides_shop_and_source_defaults");
            var late = PermissionUtil.register("permissionaudit.late");
            permit(alice, late.getNodeName());
            check(!PermissionAPI.getRegisteredNodes().contains(late) && PermissionUtil.hasPermission(alice, late.getNodeName()),
                    "late_declaration_uses_provider_without_unregistered_api_exception");

            contexts();
            dialogue();
            var resolver = PermissionUtil.getPermissionResolver();
            ParadigmPermissions.uninstall();
            check(PermissionUtil.getPermissionResolver() == null, "shutdown_releases_owned_permission_resolver");
            ParadigmPermissions.install();
            check(PermissionUtil.getPermissionResolver() != null && PermissionUtil.getPermissionResolver() != resolver
                    && PermissionUtil.hasPermission(alice, late.getNodeName()), "bridge_reconnects_to_live_provider");

            Path persisted = Path.of("permission-audit-persistence.txt");
            AuditPlayer witness = player("PermissionPersist", UUID.nameUUIDFromBytes("citizens-permission-persistence".getBytes(StandardCharsets.UTF_8)));
            if (Files.exists(persisted)) {
                check(PermissionUtil.hasPermission(witness, "permissionaudit.persisted"), "real_provider_grant_survives_server_restart");
            } else {
                permit(witness, "permissionaudit.persisted");
                Files.writeString(persisted, "PermissionPersist has a persistent provider grant. Re-run to verify after restart.\n");
            }
            ok(console, "paradigm group user remove " + alice.getUUID() + " " + childGroup);
            ok(console, "paradigm group remove " + childGroup);
            ok(console, "paradigm group remove " + parentGroup);
            // Clear all transient test rules through the provider's public command. Keep just the restart witness.
            for (String node : List.of("citizens.npc.select", "citizens.npc.create", "citizens.npc.create.villager",
                    "permissionaudit.command", "permissionaudit.flag", late.getNodeName())) remove(alice, node);
            providerMutation("removePermissionFromPlayer", new Class<?>[] {UUID.class, String.class}, alice.getUUID(), dynamic);
        }

        void contexts() throws Exception {
            permit(alice, "permissionaudit.dimension", " --context dimension=minecraft:overworld");
            permit(alice, "permissionaudit.world", " --context world=minecraft:overworld");
            permit(alice, "citizens.npc.follow.others", " --context dimension=minecraft:overworld");
            permit(alice, "permissionaudit.context-denial");
            user("deny", alice, "permissionaudit.context-denial", " --context dimension=minecraft:the_nether");
            permit(alice, "permissionaudit.server", " --context server=current");
            permit(alice, "permissionaudit.network", " --context network=current");
            permit(alice, "permissionaudit.wrong-server", " --context server=permission-audit-unrelated-server");
            check(PermissionUtil.hasPermission(alice, "permissionaudit.dimension")
                    && PermissionUtil.hasPermission(alice, "permissionaudit.world"), "dynamic_world_and_dimension_contexts_match");
            check(PermissionUtil.hasPermission(alice, "citizens.npc.follow.others"), "registered_node_uses_live_dimension_context");
            check(PermissionUtil.hasPermission(alice, "permissionaudit.server")
                    && PermissionUtil.hasPermission(alice, "permissionaudit.network")
                    && !PermissionUtil.hasPermission(alice, "permissionaudit.wrong-server"), "provider_resolves_server_and_network_contexts");
            alice.teleportTo(server.getLevel(Level.NETHER), 1, 64, 1, Set.of(), 0, 0);
            check(!PermissionUtil.hasPermission(alice, "permissionaudit.dimension")
                    && !PermissionUtil.hasPermission(alice, "permissionaudit.world"), "live_dimension_change_revokes_contextual_grants");
            check(!PermissionUtil.hasPermission(alice, "citizens.npc.follow.others"), "registered_node_updates_after_dimension_change");
            check(!PermissionUtil.hasPermission(elevated, "permissionaudit.context-denial"), "contextual_denial_overrides_global_grant_and_source_default");
            alice.teleportTo(level, 1, -60, 1, Set.of(), 0, 0);
            check(PermissionUtil.hasPermission(alice, "permissionaudit.dimension"), "returning_dimension_restores_contextual_grant");
            remove(alice, "permissionaudit.dimension", " --context dimension=minecraft:overworld");
            remove(alice, "permissionaudit.world", " --context world=minecraft:overworld");
            remove(alice, "citizens.npc.follow.others", " --context dimension=minecraft:overworld");
            remove(alice, "permissionaudit.context-denial", " --context dimension=minecraft:the_nether");
            remove(alice, "permissionaudit.context-denial");
            remove(alice, "permissionaudit.server", " --context server=current");
            remove(alice, "permissionaudit.network", " --context network=current");
            remove(alice, "permissionaudit.wrong-server", " --context server=permission-audit-unrelated-server");
        }

        @SuppressWarnings("unchecked")
        void dialogue() throws Exception {
            NPC npc = registry.createNPC(EntityType.VILLAGER, "PermissionDialogue");
            check(npc.spawn(new Location(level, 2, -60, 2)), "dialogue_fixture_npc_spawned");
            var controller = new InteractionsMod();
            NeoForge.EVENT_BUS.unregister(controller);
            Path folder = Files.createTempDirectory("citizens-permission-dialogue");
            Path yaml = folder.resolve("permission-story.yml");
            try {
                Files.writeString(yaml, """
                        starts_with: [NPC named PermissionDialogue]
                        requires_permission: true
                        can_be_started_on_air: true
                        conversation:
                          conversation1:
                            dialogue:
                              dialogue1:
                                text: [Permission audit]
                                time: -1
                        """);
                ConversationLibrary library = (ConversationLibrary) field(controller, "library");
                library.load(folder.toFile());
                var actions = InteractionsMod.class.getDeclaredField("actions"); actions.setAccessible(true);
                actions.set(controller, new Actions(new ItemLibrary(), new Economy()));
                Map<UUID, Session> sessions = (Map<UUID, Session>) field(controller, "sessions");
                controller.onRightClick(new NPCRightClickEvent(npc, alice));
                check(sessions.isEmpty(), "dialogue_permission_rejects_ungranted_player");
                permit(alice, "interactions.start.permission-story");
                controller.onRightClick(new NPCRightClickEvent(npc, alice));
                check(sessions.containsKey(alice.getUUID()), "dialogue_accepts_real_runtime_permission_grant");
                sessions.remove(alice.getUUID()).end(false);
                remove(alice, "interactions.start.permission-story");
                controller.onRightClick(new NPCRightClickEvent(npc, alice));
                check(sessions.isEmpty(), "dialogue_rechecks_permission_after_revoke");
            } finally { Files.deleteIfExists(yaml); Files.deleteIfExists(folder); npc.destroy(); }
        }

        void permit(AuditPlayer player, String node) throws Exception { permit(player, node, ""); }
        void permit(AuditPlayer player, String node, String context) throws Exception { user("add", player, node, context); }
        void forbid(AuditPlayer player, String node) throws Exception { user("deny", player, node, ""); }
        void remove(AuditPlayer player, String node) throws Exception { remove(player, node, ""); }
        void remove(AuditPlayer player, String node, String context) throws Exception { user("remove", player, node, context); }
        void user(String verb, AuditPlayer player, String node, String context) throws Exception {
            ok(console, "paradigm group user perm " + verb + " " + player.getUUID() + " "
                    + com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(node) + context);
        }
        void apiUserRule(AuditPlayer player, String node, boolean value) throws Exception {
            // The provider's boolean parameter is 'denied', rather than 'allowed'.
            providerMutation("addPermissionToPlayer", new Class<?>[] {UUID.class, String.class, boolean.class}, player.getUUID(), node, !value);
        }
        void providerMutation(String method, Class<?>[] signature, Object... arguments) throws Exception {
            Object services = Class.forName("eu.avalanche7.paradigm.Paradigm").getMethod("getServices").invoke(null);
            Object handler = Class.forName("eu.avalanche7.paradigm.core.Services").getMethod("getPermissionsHandler").invoke(services);
            Object result = Class.forName("eu.avalanche7.paradigm.modules.permissions.PermissionsHandler")
                    .getMethod(method, signature).invoke(handler, arguments);
            if (!Boolean.TRUE.equals(result)) throw new AssertionError("Provider mutation failed: " + method);
        }
        void ok(CommandSourceStack sender, String command) throws Exception {
            try {
                if (server.getCommands().getDispatcher().execute(command, sender) <= 0)
                    throw new AssertionError("Command returned failure: " + command);
            } catch (CommandSyntaxException failure) {
                throw new AssertionError("Command dispatch failed: " + command, failure);
            }
        }
        void denied(CommandSourceStack sender, String command) throws Exception {
            try { server.getCommands().getDispatcher().execute(command, sender); }
            catch (CommandSyntaxException expected) { return; }
            throw new AssertionError("Command unexpectedly succeeded: " + command);
        }
        int size() { int result = 0; for (NPC npc : registry) result++; return result; }
        PermissionUtil.Attachment hold(AuditPlayer player, List<String> permissions) {
            var result = PermissionUtil.grantTemporary(player, permissions); attachments.add(result); return result;
        }
        AuditPlayer player(String name) {
            return player(name, UUID.randomUUID());
        }
        AuditPlayer player(String name, UUID uuid) {
            var player = new AuditPlayer(server, level, name, uuid); players.add(player); player.setPos(1, -60, 1);
            var connection = new Connection(PacketFlow.SERVERBOUND);
            player.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) { connection.configurePacketHandler(channel.pipeline()); }
            });
            NetworkRegistry.configureMockConnection(connection);
            var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
            server.getPlayerList().placeNewPlayer(connection, player, cookie); return player;
        }
        void close() {
            if (closed) return;
            closed = true;
            attachments.forEach(PermissionUtil.Attachment::remove);
            PermissionUtil.setTemporaryPermissionGranter(null);
            if (isolated) registry.deregisterAll();
            for (AuditPlayer player : players) {
                if (server.getPlayerList().isOp(player.getGameProfile())) server.getPlayerList().deop(player.getGameProfile());
                if (server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
                else player.serverLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                if (player.channel != null) player.channel.finishAndReleaseAll();
            }
        }

        void drainStorage() throws Exception {
            if (!provider) { drained.complete(null); return; }
            Object services = Class.forName("eu.avalanche7.paradigm.Paradigm").getMethod("getServices").invoke(null);
            Object storage = Class.forName("eu.avalanche7.paradigm.core.Services").getMethod("getStorageService").invoke(services);
            // The provider uses one storage executor. Queue a barrier after fixture cleanup, then keep ticking
            // instead of blocking the server thread or shutting down while persistence callbacks are still queued.
            Class.forName("eu.avalanche7.paradigm.storage.StorageService")
                    .getMethod("runStorageAsync", String.class, Runnable.class)
                    .invoke(storage, "citizens-permission-audit-barrier", (Runnable) () -> drained.complete(null));
        }
    }

    private static Object field(Object instance, String name) throws Exception {
        var field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++; LOGGER.info("[PERMISSIONAUDIT] PASS {}", name);
    }
    private static final class AuditPlayer extends ServerPlayer {
        EmbeddedChannel channel;
        AuditPlayer(MinecraftServer server, ServerLevel level, String name, UUID uuid) {
            super(server, level, new GameProfile(uuid, name), ClientInformation.createDefault());
        }
        @Override public void sendSystemMessage(Component component) { }
    }
}
