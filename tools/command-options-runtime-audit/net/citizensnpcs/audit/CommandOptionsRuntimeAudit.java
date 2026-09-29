package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.AbstractPathStrategy;
import net.citizensnpcs.api.ai.TargetType;
import net.citizensnpcs.api.ai.event.NavigationBeginEvent;
import net.citizensnpcs.api.event.NPCAddTraitEvent;
import net.citizensnpcs.api.event.NPCTeleportEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.TeleportCause;
import net.citizensnpcs.trait.HomeTrait;
import net.citizensnpcs.trait.ChunkTicketTrait;
import net.citizensnpcs.trait.HomeTrait.ReturnStrategy;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.trait.Powered;
import net.citizensnpcs.trait.ScoreboardTrait;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.handler.DefaultPermissionHandler;
import net.neoforged.neoforge.server.permission.handler.IPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class CommandOptionsRuntimeAudit {
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<String> messages = new ArrayList<>();
    private static NPC creep, named, home;
    private static ServerPlayer player;
    private static EmbeddedChannel channel;
    private static Consumer<NavigationBeginEvent> navigation;
    private static Consumer<NPCTeleportEvent> teleport;
    private static Consumer<EntityJoinLevelEvent> join;
    private static Consumer<NPCAddTraitEvent> addTrait;
    private static boolean done, forced, permitted = true;
    private static int passed, deadline;

    @SubscribeEvent public static void navigation(NavigationBeginEvent event) { if (navigation != null) navigation.accept(event); }
    @SubscribeEvent public static void teleport(NPCTeleportEvent event) { if (teleport != null) teleport.accept(event); }
    @SubscribeEvent public static void join(EntityJoinLevelEvent event) { if (join != null) join.accept(event); }
    @SubscribeEvent public static void addTrait(NPCAddTraitEvent event) { if (addTrait != null) addTrait.accept(event); }
    @SubscribeEvent public static void permissions(PermissionGatherEvent.Handler event) {
        event.addPermissionHandler(AuditPermissions.ID, AuditPermissions::new);
    }
    private static final class AuditPermissions implements IPermissionHandler {
        static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("citizens", "command_options_audit");
        private final DefaultPermissionHandler defaults;
        AuditPermissions(Collection<PermissionNode<?>> nodes) { defaults = new DefaultPermissionHandler(nodes); }
        public ResourceLocation getIdentifier() { return ID; }
        public Set<PermissionNode<?>> getRegisteredNodes() { return defaults.getRegisteredNodes(); }
        @SuppressWarnings("unchecked") public <T> T getPermission(ServerPlayer sender, PermissionNode<T> node,
                PermissionDynamicContext<?>... context) {
            if (sender == player && node.getType() == PermissionTypes.BOOLEAN) {
                Boolean value = switch (node.getNodeName()) {
                    case "citizens.npc.home", "citizens.npc.name", "citizens.npc.powered" -> permitted;
                    case "citizens.admin", "citizens.ignore-owner" -> false;
                    default -> null;
                };
                if (value != null) return (T) value;
            }
            return defaults.getPermission(sender, node, context);
        }
        public <T> T getOfflinePermission(UUID sender, PermissionNode<T> node, PermissionDynamicContext<?>... context) {
            return defaults.getOfflinePermission(sender, node, context);
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        var server = event.getServer();
        if (!forced) { forced = true; server.overworld().setChunkForced(0, 0, true); }
        if (server.getTickCount() < 10 || !server.overworld().isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
        try {
            if (deadline == 0) {
                check(Files.isRegularFile(Path.of("command-options-audit-fixture.txt")), "isolated_fixture");
                creep = create(server, EntityType.CREEPER, "OptionsCreeper", false);
                check(command(server, creep, "powered --set false") == 1 && !((Creeper) creep.getEntity()).isPowered(),
                        "explicit_false_does_not_toggle");
                admit(server);
                powered(server);
                names(server);
                homes(server);
                homeRoutes(server);
                authorization(server);
                named.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, true);
                check(command(server, named, "name -h") == 1, "hover_before_real_ticks");
                home.getTrait(HomeTrait.class).setReturnStrategy(ReturnStrategy.TELEPORT);
                home.getTrait(HomeTrait.class).setHomeLocation(new Location(server.overworld(), 2.5, -60, 2.5));
                home.getTrait(HomeTrait.class).setDelayTicks(3);
                home.getTrait(HomeTrait.class).setDistanceBlocks(-1);
                home.teleport(new Location(server.overworld(), 8.5, -60, 2.5), TeleportCause.PLUGIN);
                deadline = server.getTickCount() + 20;
            } else if (server.getTickCount() >= deadline) {
                check(home.getStoredLocation().distanceSquared(home.getTrait(HomeTrait.class).getHomeLocation()) < 0.01,
                        "home_returns_during_real_ticks");
                check(!named.getEntity().isCustomNameVisible() && named.getEntity().getCustomName() != null,
                        "hover_keeps_targetable_name_without_always_visible_metadata");
                done = true;
                LoggerFactory.getLogger("citizens").info("[COMMANDOPTIONSAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) {
            done = true; LoggerFactory.getLogger("citizens").error("[COMMANDOPTIONSAUDIT] FAILED", failure);
        } finally {
            if (done) try {
                navigation = null; teleport = null; join = null; addTrait = null;
                for (NPC npc : npcs) npc.destroy();
                if (player != null && player.connection != null) server.getPlayerList().remove(player);
                if (channel != null) channel.finishAndReleaseAll();
                server.overworld().setChunkForced(0, 0, false);
            } finally { server.halt(false); }
        }
    }
    private static NPC create(MinecraftServer server, EntityType<?> type, String name, boolean packet) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(type, name); npcs.add(npc);
        if (packet) npc.getOrAddTrait(PacketNPC.class);
        check(npc.spawn(new Location(server.overworld(), 4.5, -60, 4.5)), "spawn_" + name); return npc;
    }
    private static CommandSourceStack source(MinecraftServer server) {
        return server.createCommandSourceStack().withSource(new CommandSource() {
            public void sendSystemMessage(Component message) { messages.add(message.getString()); }
            public boolean acceptsSuccess() { return true; }
            public boolean acceptsFailure() { return true; }
            public boolean shouldInformAdmins() { return false; }
        });
    }
    private static int command(MinecraftServer server, NPC npc, String command) throws Exception {
        return command(server, source(server), npc, command);
    }
    private static int command(MinecraftServer server, CommandSourceStack source, NPC npc, String command) throws Exception {
        messages.clear();
        CitizensAPI.getDefaultNPCSelector().select(source, npc);
        try { return server.getCommands().getDispatcher().execute("npc " + command, source); }
        catch (CommandSyntaxException invalid) { messages.add(invalid.getRawMessage().getString()); return 0; }
    }
    private static MemoryDataKey save(Trait trait) {
        var key = new MemoryDataKey();
        var section = key.getRelative("trait");
        trait.save(section); PersistenceLoader.save(trait, section); return key;
    }
    private static void powered(MinecraftServer server) throws Exception {
        for (boolean packet : List.of(false, true)) {
            NPC npc = packet ? create(server, EntityType.CREEPER, "VirtualPowered", true) : creep;
            for (boolean value : List.of(true, true, false, false))
                check(command(server, npc, "powered --set " + value) == 1 && ((Creeper) npc.getEntity()).isPowered() == value,
                        "explicit_power_" + packet + value);
            check(command(server, npc, "powered") == 1 && ((Creeper) npc.getEntity()).isPowered(), "power_toggle_" + packet);
            var before = save(npc.getTrait(Powered.class)).getValuesDeep();
            for (String invalid : List.of("--set perhaps", "--set \"\"", "--unknown true", "extra", "-x"))
                check(command(server, npc, "powered " + invalid) == 0
                        && save(npc.getTrait(Powered.class)).getValuesDeep().equals(before), "power_reject_" + packet + invalid);
            var retired = npc.getTrait(Powered.class); var restored = new Powered(); PersistenceLoader.load(restored, save(retired).getRelative("trait"));
            npc.addTrait(restored); retired.setPowered(false); retired.onSpawn();
            check(((Creeper) npc.getEntity()).isPowered(), "retired_powered_cannot_change_entity_" + packet);
            check(npc.despawn(), "powered_despawn_" + packet);
            check(command(server, npc, "powered --set true") == 1, "unspawned_power_configuration_" + packet);
            join = event -> { if (event.getEntity().getUUID().equals(npc.getUniqueId()))
                check(((Creeper) event.getEntity()).isPowered(), "powered_before_native_admission"); };
            check(npc.spawn(npc.getStoredLocation()), "powered_respawn_" + packet); join = null;
            check(((Creeper) npc.getEntity()).isPowered(), "powered_persistence_" + packet);
        }
    }
    private static void names(MinecraftServer server) throws Exception {
        named = create(server, EntityType.COW, "NameOptions", false);
        for (boolean packet : List.of(false, true)) {
            NPC npc = packet ? create(server, EntityType.COW, "VirtualName", true) : named;
            check(command(server, npc, "name -h") == 1 && "hover".equals(npc.data().get(NPC.Metadata.NAMEPLATE_VISIBLE)),
                    "name_hover_flag_" + packet);
            check(command(server, npc, "name") == 1 && Boolean.parseBoolean(npc.data().get(NPC.Metadata.NAMEPLATE_VISIBLE).toString()),
                    "hover_toggle_to_visible_" + packet);
            check(command(server, npc, "hidename") == 1 && !Boolean.parseBoolean(npc.data().get(NPC.Metadata.NAMEPLATE_VISIBLE).toString()),
                    "name_alias_toggles_" + packet);
            for (String value : List.of("true", "false", "hover")) check(command(server, npc, "name " + value) == 1
                    && value.equals(npc.data().get(NPC.Metadata.NAMEPLATE_VISIBLE).toString()), "explicit_name_" + packet + value);
            for (String invalid : List.of("maybe", "\"\"", "true extra", "--unknown true", "-x")) {
                Object before = npc.data().get(NPC.Metadata.NAMEPLATE_VISIBLE);
                check(command(server, npc, "name " + invalid) == 0 && before.equals(npc.data().get(NPC.Metadata.NAMEPLATE_VISIBLE)),
                        "name_reject_" + packet + invalid);
            }
            check(npc.isUpdating(NPC.NPCUpdate.PACKET), "name_requests_packet_refresh_" + packet);
            var snapshot = new MemoryDataKey(); npc.saveSnapshot(snapshot);
            check("hover".equals(snapshot.getString("metadata.nameplate-visible")), "hover_persists_" + packet);
        }
    }
    private static void homes(MinecraftServer server) throws Exception {
        home = create(server, EntityType.COW, "HomeOptions", false);
        check(command(server, home, "home") == 1 && !home.hasTrait(HomeTrait.class), "empty_home_does_not_set_console_position");
        player.setPos(6.5, -60, 7.5); player.setYRot(75); player.setXRot(-20);
        home.getOrAddTrait(Owner.class).setOwner(player.getUUID());
        var source = player.createCommandSourceStack().withPermission(4);
        check(command(server, source, home, "home -h") == 1, "home_here_player");
        HomeTrait trait = home.getTrait(HomeTrait.class);
        check(trait.getHomeLocation().distanceSquared(Location.of(player)) < 0.01 && trait.getHomeLocation().getYaw() == 75,
                "home_here_captures_player_location");
        check(command(server, source, home, "home -h --location 3,-60,4,minecraft:overworld,40,10 -pt --delay 1.5s --distance 5") == 1,
                "combined_home_command");
        check(trait.getHomeLocation().getX() == 3 && trait.getHomeLocation().getYaw() == 40 && trait.getDelayTicks() == 30
                && trait.getDistanceBlocks() == 5 && trait.getReturnStrategy() == ReturnStrategy.TELEPORT,
                "home_location_and_teleport_precedence");
        for (String[] duration : List.of(new String[] { "40", "40" }, new String[] { "5t", "5" },
                new String[] { "PT2S", "40" }, new String[] { "-1t", "-1" }, new String[] { "0.05s", "1" }))
            check(command(server, home, "home --delay " + duration[0]) == 1 && trait.getDelayTicks() == Integer.parseInt(duration[1]),
                    "home_duration_" + duration[0]);
        var before = save(trait).getValuesDeep();
        for (String invalid : List.of("--delay bad", "--delay \"\"", "--delay 200000000s", "--delay PT999999999999999999H",
                "--distance NaN", "--distance Infinity", "--location 0,0,0,missing:world", "--location invalid", "--unknown true", "extra", "-x"))
            check(command(server, home, "home -p --distance 9 " + invalid) == 0 && save(trait).getValuesDeep().equals(before),
                    "home_atomic_rejection_" + invalid);
        check(command(server, home, "home -h --distance 9") == 0 && save(trait).getValuesDeep().equals(before), "console_here_is_atomic");
        check(command(server, home, "home -c") == 1 && trait.getHomeLocation() == null, "clear_extension");
        check(command(server, home, "home --location 2,-60,2,minecraft:overworld --delay 3t --distance 0 -t") == 1,
                "configure_runtime_home");
        homeBehavior(server);
    }
    private static void homeBehavior(MinecraftServer server) {
        HomeTrait trait = home.getTrait(HomeTrait.class); var destination = trait.getHomeLocation();
        home.teleport(new Location(server.overworld(), 8, -60, 2), TeleportCause.PLUGIN);
        trait.onDespawn(); for (int i = 0; i < 3; i++) trait.run();
        check(home.getStoredLocation().getX() == 8, "home_waits_configured_ticks"); trait.run();
        check(home.getStoredLocation().distanceSquared(destination) < 0.01, "home_returns_after_delay");
        var saved = save(trait); var restored = new HomeTrait(); PersistenceLoader.load(restored, saved.getRelative("trait")); home.addTrait(restored);
        check(restored.getDelayTicks() == trait.getDelayTicks() && restored.getDistanceBlocks() == trait.getDistanceBlocks()
                && restored.getReturnStrategy() == trait.getReturnStrategy()
                && restored.getHomeLocation().distanceSquared(destination) < 0.01
                && restored.getHomeLocation().getYaw() == destination.getYaw()
                && restored.getHomeLocation().getPitch() == destination.getPitch(), "home_persistence_roundtrip");
        trait.setDelayTicks(-1); trait.setHomeLocation(new Location(server.overworld(), 12, -60, 12)); trait.run();
        check(home.getStoredLocation().distanceSquared(destination) < 0.01, "retired_home_cannot_teleport");
    }
    private static void homeRoutes(MinecraftServer server) {
        for (boolean packet : List.of(false, true)) {
            NPC npc = create(server, EntityType.COW, "HomeRoutes" + packet, packet);
            var navigator = npc.getNavigator();
            var defaults = navigator.getDefaultParameters();
            double margin = defaults.distanceMargin(), pathMargin = defaults.pathDistanceMargin();
            double teleportMargin = defaults.destinationTeleportMargin();
            HomeTrait trait = npc.getOrAddTrait(HomeTrait.class);
            Location destination = new Location(server.overworld(), 9.5, -60, 4.5);
            trait.setHomeLocation(destination); trait.setReturnStrategy(ReturnStrategy.PATHFIND);
            navigation = event -> {
                if (event.getNavigator() != navigator) return;
                var parameters = navigator.getLocalParameters();
                check(parameters.distanceMargin() == 0.9 && parameters.pathDistanceMargin() == 0
                        && parameters.destinationTeleportMargin() == 1, "home_parameters_before_begin_" + packet);
            };
            trait.run(); navigation = null;
            check(navigator.isNavigating() && navigator.getTargetAsLocation().distanceSquared(destination) < 0.01,
                    "home_starts_location_route_" + packet);
            check(defaults.distanceMargin() == margin && defaults.pathDistanceMargin() == pathMargin
                    && defaults.destinationTeleportMargin() == teleportMargin, "home_keeps_default_parameters_" + packet);
            trait.setHomeLocation(null);
            check(!navigator.isNavigating(), "clearing_home_cancels_owned_route_" + packet);

            trait.setHomeLocation(destination); trait.run();
            navigator.setTarget(new Location(server.overworld(), 7.5, -60, 4.5));
            var external = navigator.getPathStrategy();
            trait.setHomeLocation(null);
            check(navigator.getPathStrategy() == external && navigator.getLocalParameters().distanceMargin() == margin,
                    "clearing_home_preserves_external_route_" + packet);
            navigator.cancelNavigation();

            trait.setHomeLocation(destination);
            navigation = event -> { if (event.getNavigator() == navigator) { navigation = null; trait.setHomeLocation(null); } };
            trait.run(); navigation = null;
            check(!navigator.isNavigating(), "begin_clear_cancels_home_" + packet);
            trait.setHomeLocation(destination);
            navigation = event -> {
                if (event.getNavigator() != navigator) return;
                navigation = null;
                navigator.setTarget(new Location(server.overworld(), 7.5, -60, 4.5));
                trait.setHomeLocation(null);
            };
            trait.run(); navigation = null;
            check(navigator.isNavigating() && navigator.getLocalParameters().distanceMargin() == margin
                    && navigator.getTargetAsLocation().getX() == 7.5, "begin_replacement_keeps_its_parameters_" + packet);
            navigator.cancelNavigation();

            trait.setHomeLocation(destination);
            PassiveRoute[] prepared = new PassiveRoute[1];
            int[] begins = { 0 };
            defaults.locationStrategyFactory((owner, parameters, target) -> {
                check(parameters.distanceMargin() == 0.9 && parameters.pathDistanceMargin() == 0
                        && parameters.destinationTeleportMargin() == 1, "home_parameters_before_factory_" + packet);
                trait.setHomeLocation(null);
                return prepared[0] = new PassiveRoute(target);
            });
            navigation = event -> { if (event.getNavigator() == navigator) begins[0]++; };
            trait.run(); navigation = null;
            check(!navigator.isNavigating() && begins[0] == 0 && prepared[0].stopped,
                    "factory_clear_retires_pending_home_" + packet);
            defaults.locationStrategyFactory(null);
            check(navigator.getLocalParameters() == defaults, "pending_cancel_restores_default_parameters_" + packet);

            trait.setHomeLocation(destination); trait.run();
            var pausedHome = navigator.getPathStrategy(); navigator.setPaused(true); trait.run();
            check(navigator.getPathStrategy() == pausedHome, "paused_home_is_not_replaced_" + packet);
            trait.setHomeLocation(null);
            check(navigator.getPathStrategy() == null && navigator.getLocalParameters() == defaults,
                    "paused_home_can_be_cleared_" + packet);
            navigator.setPaused(false);
            navigator.setTarget(new Location(server.overworld(), 7.5, -60, 4.5));
            external = navigator.getPathStrategy(); navigator.setPaused(true);
            trait.setHomeLocation(destination); trait.run(); trait.setHomeLocation(null);
            check(navigator.getPathStrategy() == external, "paused_external_route_is_preserved_" + packet);
            navigator.cancelNavigation(); navigator.setPaused(false);

            trait.setHomeLocation(destination);
            int[] factories = { 0 };
            defaults.locationStrategyFactory((owner, parameters, target) -> {
                check(++factories[0] == 1, "home_factory_reentry_is_bounded_" + packet);
                trait.run();
                return new PassiveRoute(target);
            });
            trait.run();
            check(navigator.getPathStrategy() != null, "home_factory_reentry_retains_route_" + packet);
            trait.setHomeLocation(null); defaults.locationStrategyFactory(null);

            trait.setHomeLocation(destination);
            defaults.locationStrategyFactory((owner, parameters, target) -> {
                trait.run();
                return new PassiveRoute(target);
            });
            navigator.setTarget(new Location(server.overworld(), 7.5, -60, 4.5));
            check(navigator.getTargetAsLocation().getX() == 7.5 && navigator.getLocalParameters().distanceMargin() == margin,
                    "home_does_not_replace_pending_external_route_" + packet);
            navigator.cancelNavigation(); defaults.locationStrategyFactory(null);

            defaults.locationStrategyFactory((owner, parameters, target) -> {
                defaults.locationStrategyFactory(null);
                navigator.setTarget(new Location(server.overworld(), 7.5, -60, 4.5));
                trait.setHomeLocation(null);
                return prepared[0] = new PassiveRoute(target);
            });
            trait.run();
            check(navigator.getTargetAsLocation().getX() == 7.5 && prepared[0].stopped
                    && navigator.getLocalParameters().distanceMargin() == margin,
                    "factory_replacement_survives_home_cleanup_" + packet);
            navigator.cancelNavigation();

            trait.setHomeLocation(destination);
            defaults.locationStrategyFactory((owner, parameters, target) -> { throw new IllegalStateException("fixture failure"); });
            boolean propagated = false;
            try { trait.run(); } catch (IllegalStateException expected) { propagated = expected.getMessage().equals("fixture failure"); }
            check(propagated && navigator.getPathStrategy() == null && navigator.getLocalParameters() == defaults,
                    "factory_failure_releases_pending_parameters_" + packet);
            defaults.locationStrategyFactory(null); trait.run();
            check(navigator.getPathStrategy() != null, "home_can_retry_after_factory_failure_" + packet);
            trait.setHomeLocation(null);

            PassiveRoute paused = new PassiveRoute(destination);
            navigator.setTarget(parameters -> paused); navigator.setPaused(true);
            navigator.setTarget(new Location(server.overworld(), 7.5, -60, 4.5));
            check(paused.stopped && navigator.getPathStrategy() != paused, "paused_replacement_stops_previous_strategy_" + packet);
            navigator.cancelNavigation(); navigator.setPaused(false);

            trait.setHomeLocation(new Location(server.getLevel(Level.NETHER), 2.5, 80, 2.5)); trait.run();
            check(navigator.getPathStrategy() == null && npc.getEntity().level() == server.overworld(),
                    "pathfinding_does_not_teleport_across_dimensions_" + packet);
            trait.setReturnStrategy(ReturnStrategy.TELEPORT);
            int[] requests = { 0 };
            teleport = event -> {
                if (event.getNPC() != npc) return;
                check(++requests[0] == 1 && event.getTo().getWorld() == server.getLevel(Level.NETHER),
                        "home_teleport_reentry_is_bounded_" + packet);
                trait.run(); event.setCanceled(true);
            };
            trait.run(); teleport = null;
            check(requests[0] == 1 && npc.getEntity().level() == server.overworld(), "canceled_home_teleport_keeps_source_" + packet);

            trait.setReturnStrategy(ReturnStrategy.PATHFIND); trait.setHomeLocation(destination);
            defaults.locationStrategyFactory((owner, parameters, target) -> {
                npc.removeTrait(HomeTrait.class);
                return prepared[0] = new PassiveRoute(target);
            });
            trait.run(); defaults.locationStrategyFactory(null);
            check(!npc.hasTrait(HomeTrait.class) && navigator.getPathStrategy() == null && prepared[0].stopped,
                    "factory_removal_cannot_leave_home_route_" + packet);

            HomeTrait next = npc.getOrAddTrait(HomeTrait.class);
            next.setHomeLocation(destination); next.setReturnStrategy(ReturnStrategy.PATHFIND);
            npc.removeTrait(ChunkTicketTrait.class); begins[0] = 0;
            navigation = event -> { if (event.getNavigator() == navigator) begins[0]++; };
            addTrait = event -> {
                if (event.getNPC() == npc && event.getTrait() instanceof ChunkTicketTrait) next.setHomeLocation(null);
            };
            next.run(); addTrait = null;
            check(navigator.getPathStrategy() == null && begins[0] == 0, "chunk_trait_callback_cancels_home_before_begin_" + packet);

            next.setHomeLocation(destination); npc.removeTrait(ChunkTicketTrait.class);
            addTrait = event -> {
                if (event.getNPC() != npc || !(event.getTrait() instanceof ChunkTicketTrait)) return;
                addTrait = null;
                navigator.setTarget(new Location(server.overworld(), 7.5, -60, 4.5));
            };
            next.run(); addTrait = null;
            check(navigator.getTargetAsLocation().getX() == 7.5 && begins[0] == 1,
                    "chunk_trait_replacement_receives_one_begin_" + packet);
            navigator.cancelNavigation(); begins[0] = 0;
            defaults.locationStrategyFactory((owner, parameters, target) -> new PassiveRoute(target) {
                private boolean requested;
                @Override public Location getTargetAsLocation() {
                    if (!requested) {
                        requested = true; defaults.locationStrategyFactory(null);
                        navigator.setTarget(new Location(server.overworld(), 7.5, -60, 4.5));
                    }
                    return super.getTargetAsLocation();
                }
            });
            next.run(); navigation = null;
            check(navigator.getTargetAsLocation().getX() == 7.5 && begins[0] == 1,
                    "target_accessor_replacement_receives_one_begin_" + packet);
            navigator.cancelNavigation(); next.setHomeLocation(null);
        }
    }
    private static class PassiveRoute extends AbstractPathStrategy {
        private final Location target;
        boolean stopped;
        PassiveRoute(Location target) { super(TargetType.LOCATION); this.target = target.clone(); }
        public Location getCurrentDestination() { return target.clone(); }
        public Iterable<Vec3> getPath() { return List.of(); }
        public Location getTargetAsLocation() { return target.clone(); }
        public void stop() { stopped = true; }
        public boolean update() { return false; }
    }
    private static void authorization(MinecraftServer server) throws Exception {
        var source = player.createCommandSourceStack().withPermission(0);
        for (NPC npc : List.of(creep, named, home)) npc.getOrAddTrait(Owner.class).setOwner(player.getUUID());
        for (String command : List.of("powered --set false", "name -h", "home --delay 2s")) {
            NPC target = command.startsWith("powered") ? creep : command.startsWith("name") ? named : home;
            check(command(server, source, target, command) == 1, "authorized_" + command);
            permitted = false; check(command(server, source, target, command) == 0, "permission_required_" + command); permitted = true;
            target.getOrAddTrait(Owner.class).setOwner(UUID.randomUUID());
            check(command(server, source, target, command) == 0, "ownership_required_" + command);
            target.getOrAddTrait(Owner.class).setOwner(player.getUUID());
            check(command(server, source, null, command) == 0, "selection_required_" + command);
        }
        check(command(server, named, "powered --set true") == 0 && !named.hasTrait(Powered.class), "powered_requires_creeper");
    }
    private static void admit(MinecraftServer server) {
        player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "OptionsAudit"), ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) { connection.configurePacketHandler(channel.pipeline()); }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, player, cookie); player.setPos(6.5, -60, 4.5);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label + "; latest command feedback: " + messages);
        passed++; LoggerFactory.getLogger("citizens").info("[COMMANDOPTIONSAUDIT] PASS {}", label);
    }
}
