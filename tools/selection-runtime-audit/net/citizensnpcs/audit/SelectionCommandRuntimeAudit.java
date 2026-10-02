package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.event.NPCCreateEvent;
import net.citizensnpcs.api.expr.*;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.*;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.trait.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class SelectionCommandRuntimeAudit {
    private static MinecraftServer server;
    private static ServerLevel level;
    private static NPCRegistry registry, named;
    private static Actor alice, bob;
    private static final List<NPC> all = new ArrayList<>();
    private static final List<String> names = new ArrayList<>();
    private static final List<PermissionUtil.Attachment> grants = new ArrayList<>();
    private static PermissionUtil.Attachment selectGrant;
    private static int start = -1, deadline, passed, outputStart;
    private static boolean forced, done;
    private static String selectionMessage;
    private static final String SPECIAL_TAG = "Blue &a [[literal]] > '$page' \"quoted\" \\ path";
    private static NPC own, foreign, serverOwned;

    @SubscribeEvent public static void created(NPCCreateEvent event) { if (start >= 0) all.add(event.getNPC()); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer(); level = server.overworld();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("selection-audit-fixture.txt")), "isolated_fixture");
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_default_registry");
                level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 600;
            }
            if (server.getTickCount() > deadline) throw new AssertionError("fixture_timeout");
            if (!level.isPositionEntityTicking(new BlockPos(4, -58, 4))) return;
            if (start < 0) {
                start = server.getTickCount(); registry = CitizensAPI.getNPCRegistry();
                alice = actor("SelectionAlice"); bob = actor("SelectionBob");
                bob.player.setPos(12, -58, 12);
                selectionMessage = net.citizensnpcs.Settings.Setting.SELECTION_MESSAGE.asString();
                grant(alice.player, "citizens.npc.list", "citizens.npc.tp", "citizens.npc.tphere", "citizens.npc.remove");
                selectGrant = grant(alice.player, "citizens.npc.select");
                own = npc(registry, "OwnOne", alice.player.getUUID(), EntityType.COW);
                foreign = npc(registry, "ForeignOne", bob.player.getUUID(), EntityType.PIG);
                serverOwned = npc(registry, "ServerOne", null, EntityType.COW);
                own.getOrAddTrait(ScoreboardTrait.class).getTags().add("blue");
                foreign.getOrAddTrait(ScoreboardTrait.class).getTags().add("blue");
                for (int i = 0; i < 12; i++) npc(registry, "Page" + i, alice.player.getUUID(), EntityType.COW)
                        .getOrAddTrait(ScoreboardTrait.class).getTags().add(SPECIAL_TAG);
                lists(); explicitSelection(); expressions(); geometry();
                close();
                LoggerFactory.getLogger("citizens").info("[SELECTIONCOMMANDAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[SELECTIONCOMMANDAUDIT] FAILED", failure); close();
        }
    }

    private static void lists() throws Exception {
        run("list");
        check(text().contains("OwnOne") && !text().contains("ForeignOne") && !text().contains("ServerOne"), "default_player_list_is_owned");
        run("list -a"); check(text().contains("ForeignOne") && text().contains("ServerOne"), "all_lists_all_owners");
        run("list --owner " + bob.player.getUUID()); check(text().contains("ForeignOne") && !text().contains("OwnOne"), "owner_uuid_filter");
        run("list --owner " + bob.player.getGameProfile().getName()); check(text().contains("ForeignOne"), "online_owner_name_filter");
        run("list --owner SERVER"); check(text().contains("ServerOne") && !text().contains("OwnOne"), "server_owner_filter");
        run("list --tag blue --owner SERVER"); check(text().contains("OwnOne") && text().contains("ForeignOne") && !text().contains("ServerOne"), "tag_precedes_owner");
        run("list --tag blue --type pig"); check(text().contains("ForeignOne") && !text().contains("OwnOne"), "type_intersects_primary_filter");
        run("list -a --tag absent --type pig"); check(text().contains("ForeignOne"), "all_precedes_tag");
        for (String bad : List.of("list --type missing_type", "list --registry missing_registry", "list 0", "list --page nope", "list --unknown yes"))
            reject(bad);
        run("list --tag " + CommandContext.quoteArgument(SPECIAL_TAG));
        String next = links().stream().filter(value -> value.startsWith("/npc list")).findFirst().orElseThrow();
        check(new CommandContext(alice.player.createCommandSourceStack(), next.substring(1).split(" ")).getFlag("tag").equals(SPECIAL_TAG),
                "quoted_tag_survives_page_command");
        execute(next);
        check(text().contains("2/2") && !text().contains("ForeignOne"), "page_link_retains_query");
        String previous = links().stream().filter(value -> value.startsWith("/npc list")).findFirst().orElseThrow();
        check(previous.split(" --page ", -1).length == 2, "pagination_does_not_accumulate_page_flags");
        execute(previous); check(text().contains("1/2"), "previous_page_round_trip");
        run("list 2 --page 1 --tag " + CommandContext.quoteArgument(SPECIAL_TAG));
        check(text().contains("1/2"), "page_flag_precedes_positional_page");
        for (String value : List.of("two words", "a \"quoted\" value", "back\\slash", "'single' and \"double\"", "$page > 2")) {
            CommandContext context = new CommandContext(alice.player.createCommandSourceStack(),
                    ("npc list --tag " + CommandContext.quoteArgument(value)).split(" "));
            check(context.getFlag("tag").equals(value), "quoted_argument_round_trip");
        }
        NPC before = selected();
        reject("list --filter " + CommandContext.quoteArgument("npc.id > 0"));
        check(selected() == before, "filter_requires_admin_without_selection_mutation");
    }

    private static void explicitSelection() throws Exception {
        run("select " + own.getId()); check(selected() == own, "select_id");
        reject("select " + own.getId()); check(selected() == own, "already_selected_identity");
        run("sel " + foreign.getUniqueId()); check(selected() == foreign, "select_uuid");
        run("select OwnOne"); check(selected() == own, "select_positional_name");
        net.citizensnpcs.Settings.Setting.SELECTION_MESSAGE.set("");
        run("select " + foreign.getId()); check(text().isEmpty() && selected() == foreign, "selection_message_can_be_disabled");
        net.citizensnpcs.Settings.Setting.SELECTION_MESSAGE.set("Chosen <id>");
        run("select " + own.getId()); check(text().equals("Chosen " + own.getId()), "configured_selection_message_uses_target");
        net.citizensnpcs.Settings.Setting.SELECTION_MESSAGE.set(selectionMessage);
        NPC numericName = npc(registry, "999991", alice.player.getUUID(), EntityType.COW);
        run("select --name 999991"); check(selected() == numericName, "name_flag_is_not_numeric_id");
        String name = "Selection \"scope\""; names.add(name);
        named = CitizensAPI.createInMemoryNPCRegistry(name);
        NPC shadow = named.createNPC(EntityType.COW, own.getUniqueId(), own.getId(), "RegistryTwin");
        configure(shadow, alice.player.getUUID()); shadow.getOrAddTrait(CurrentLocation.class).setLocation(new Location(level, 9, -58, 9));
        run("select " + own.getId()); run("select " + shadow.getId() + " --registry " + CommandContext.quoteArgument(name));
        check(selected() == shadow, "same_id_and_uuid_in_other_registry_is_distinct");
        run("list -a --registry " + CommandContext.quoteArgument(name));
        check(text().contains("RegistryTwin") && !text().contains("OwnOne"), "list_scoped_registry");
        String tp = links().stream().filter(value -> value.startsWith("/npc tp ")).findFirst().orElseThrow();
        execute(tp);
        check(Math.abs(alice.player.getX() - 9) < 1e-6 && Math.abs(alice.player.getZ() - 10) < 1e-6, "list_tp_action_retains_registry");
        run("list -a --registry " + CommandContext.quoteArgument(name));
        String remove = links().stream().filter(value -> value.startsWith("/npc remove ")).findFirst().orElseThrow();
        execute(remove);
        check(!exists(shadow) && exists(own) && selected() == null, "list_remove_action_does_not_cross_registry");
        NPC restored = named.createNPC(EntityType.COW, own.getUniqueId(), own.getId(), "RestoredTwin"); configure(restored, alice.player.getUUID());
        run("select " + restored.getId() + " --registry " + CommandContext.quoteArgument(name));
        restored.destroy();
        NPC again = named.createNPC(EntityType.COW, own.getUniqueId(), own.getId(), "ReloadedIdentity"); configure(again, alice.player.getUUID());
        check(selected() == again, "selection_survives_same_registry_reload_identity");
        CitizensAPI.removeNamedNPCRegistry(name);
        check(selected() == null, "removed_registry_does_not_fall_back_to_global_uuid");
        named = CitizensAPI.createInMemoryNPCRegistry(name);
        NPC reloaded = named.createNPC(EntityType.COW, own.getUniqueId(), own.getId(), "ReloadedTwin"); configure(reloaded, alice.player.getUUID());
        check(selected() == reloaded, "logical_named_registry_selection_rebinds");
        run("select " + own.getId());
        run("select --uuid " + own.getUniqueId() + " --registry " + CommandContext.quoteArgument(name));
        check(selected() == reloaded, "global_uuid_override_respects_registry");
    }

    private static void expressions() throws Exception {
        PermissionUtil.Attachment admin = grant(alice.player, "citizens.admin");
        run("select " + own.getId());
        run("list --filter " + CommandContext.quoteArgument("npc.id == " + foreign.getId()));
        check(text().contains("ForeignOne") && !text().contains("OwnOne"), "expression_binds_each_candidate_not_selected_npc");
        run("list --filter " + CommandContext.quoteArgument("npc.id >= 0"));
        String next = links().stream().filter(value -> value.startsWith("/npc list")).findFirst().orElseThrow();
        execute(next); check(text().contains("2/"), "expression_page_link_round_trip");
        reject("list --filter " + CommandContext.quoteArgument("("));
        check(!CitizensAPI.getExpressionRegistry().parseValue("" + (char)96 + "(" + (char)96).evaluateAsBoolean(new ExpressionScope()),
                "invalid_expression_does_not_become_truthy_literal");
        reject("list --filter " + CommandContext.quoteArgument("missingengine" + (char)96 + "1" + (char)96));
        String name = "selection-mutation"; names.add(name);
        NPCRegistry mutation = CitizensAPI.createInMemoryNPCRegistry(name);
        npc(mutation, "GoneA", alice.player.getUUID(), EntityType.COW); npc(mutation, "GoneB", alice.player.getUUID(), EntityType.COW);
        Set<NPC> evaluated = Collections.newSetFromMap(new IdentityHashMap<>());
        CitizensAPI.getExpressionRegistry().registerEngine(new ExpressionEngine() {
            public String getName() { return "queryaudit"; }
            public CompiledExpression compile(String input) {
                return scope -> {
                    check(scope.getPlayer() == alice.player && scope.getNPC() != null, "expression_receives_player_and_npc_context");
                    NPC candidate = scope.getNPC(); evaluated.add(candidate); candidate.destroy(); return true;
                };
            }
        });
        run("list --registry " + name + " --filter " + CommandContext.quoteArgument("queryaudit" + (char)96 + "remove" + (char)96));
        check(evaluated.size() == 2 && !text().contains("GoneA") && !text().contains("GoneB"), "expression_mutation_uses_snapshot_and_omits_retired_rows");
        admin.remove();
        NPC one = npc(registry, "Choice", alice.player.getUUID(), EntityType.COW);
        NPC two = npc(registry, "Choice", alice.player.getUUID(), EntityType.COW);
        run("select Choice"); check(ChatPrompts.isActive(alice.player), "duplicate_name_prompts");
        ChatPrompts.acceptInput(alice.player, Integer.toString(two.getId())); check(selected() == two, "prompt_selects_candidate");
        run("select Choice"); selectGrant.remove();
        ChatPrompts.acceptInput(alice.player, Integer.toString(one.getId())); check(selected() == two, "prompt_rechecks_selection_permission");
        selectGrant = grant(alice.player, "citizens.npc.select");
        run("select Choice"); UUID uuid = one.getUniqueId(); int id = one.getId(); one.destroy();
        NPC replacement = registry.createNPC(EntityType.COW, uuid, id, "NewChoice"); configure(replacement, alice.player.getUUID());
        ChatPrompts.acceptInput(alice.player, Integer.toString(id));
        check(selected() == two && ChatPrompts.isActive(alice.player), "prompt_rejects_replaced_candidate");
        ChatPrompts.acceptInput(alice.player, "exit");
        reject("select --range NaN"); reject("select --range -1"); reject("select --registry missing_registry");
        reject("select OwnOne --name ForeignOne"); reject("select --id 1 --uuid " + own.getUniqueId());
    }

    private static void geometry() throws Exception {
        move(new Location(level, 4, -58, 4, 0, 0));
        NPC near = npc(registry, "Nearest", alice.player.getUUID(), EntityType.COW);
        NPC ray = npc(registry, "Ray", alice.player.getUUID(), EntityType.ARMOR_STAND);
        check(near.spawn(new Location(level, 5, -58, 4)), "spawn_near"); check(ray.spawn(new Location(level, 4, -58, 12)), "spawn_ray");
        alice.pump(); run("select"); check(selected() == ray, "ray_target_precedes_nearest");
        run("select " + near.getId()); run("select --range 1000000"); check(selected() == ray, "large_range_uses_loaded_entity_scan");
        ray.despawn(); run("select"); check(selected() == near, "nearby_fallback");
        near.despawn();
        NPC farther = npc(registry, "DefaultRange", alice.player.getUUID(), EntityType.COW);
        check(farther.spawn(new Location(level, 16, -58, 4)), "spawn_default_range");
        run("select"); check(selected() == farther, "default_range_is_fifteen");
        reject("select --range 5"); check(selected() == farther, "range_failure_preserves_selection");
        farther.despawn();
        NPC virtual = npc(registry, "VirtualRay", alice.player.getUUID(), EntityType.ARMOR_STAND);
        virtual.getOrAddTrait(PacketNPC.class); check(virtual.spawn(new Location(level, 4, -58, 9)), "spawn_virtual");
        virtual.getTraitNullable(PacketNPC.class).run(); alice.pump();
        run("select --range 10"); check(selected() == virtual, "paired_virtual_target_can_be_selected");
        virtual.getOrAddTrait(PlayerFilter.class).addPlayer(alice.player.getUUID());
        virtual.getTraitNullable(PacketNPC.class).run();
        reject("select --range 10"); check(selected() == virtual, "hidden_virtual_target_not_implicitly_selected");
        virtual.despawn();
        NPC parent = npc(registry, "RedirectParent", alice.player.getUUID(), EntityType.COW);
        check(parent.spawn(new Location(level, 25, -58, 25)), "spawn_parent");
        NPC helper = npc(CitizensAPI.getTemporaryNPCRegistry(), "Helper", null, EntityType.ARMOR_STAND);
        helper.addTrait(new ClickRedirectTrait(parent));
        check(helper.spawn(new Location(level, 4, -58, 8)), "spawn_helper");
        run("select"); check(selected() == parent, "temporary_helper_redirects_into_requested_registry");
        helper.addTrait(new ClickRedirectTrait(helper));
        reject("select --range 10"); check(selected() == parent, "redirect_cycle_is_not_a_target");
        helper.destroy(); parent.despawn();
        NPC a = npc(registry, "RangeName", alice.player.getUUID(), EntityType.COW);
        NPC b = npc(registry, "RangeName", alice.player.getUUID(), EntityType.COW);
        a.spawn(new Location(level, 6, -58, 4)); b.spawn(new Location(level, 100, -58, 100));
        run("select RangeName --range 5"); check(selected() == a, "name_range_filter");
        run("select " + b.getId() + " --range 1"); check(selected() == b, "explicit_id_is_not_limited_by_distance");
        CommandSourceStack console = server.createCommandSourceStack().withPosition(new Vec3(6, -58, 4)).withRotation(new Vec2(0, 0));
        check(server.getCommands().getDispatcher().execute("npc select --range 1", console) == 1
                && CitizensAPI.getDefaultNPCSelector().getSelected(console) == a, "native_console_position_selection");
    }

    private static NPC npc(NPCRegistry in, String name, UUID owner, EntityType<?> type) {
        NPC npc = in.createNPC(type, name); configure(npc, owner); return npc;
    }
    private static void configure(NPC npc, UUID owner) { npc.getOrAddTrait(Owner.class).setOwner(owner); npc.getOrAddTrait(LookClose.class).setEnabled(false); npc.getOrAddTrait(CurrentLocation.class).setLocation(new Location(level, 4, -58, 4)); }
    private static NPC selected() { return CitizensAPI.getDefaultNPCSelector().getSelected(alice.player.createCommandSourceStack()); }
    private static boolean exists(NPC npc) { return npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc; }
    private static PermissionUtil.Attachment grant(ServerPlayer player, String... permissions) { var result = PermissionUtil.grantTemporary(player, List.of(permissions)); grants.add(result); return result; }
    private static void move(Location location) { check(EntityUtil.teleport(alice.player, location) != null, "fixture_player_move"); alice.pump(); }
    private static void run(String command) throws Exception { outputStart = alice.player.messages.size(); check(server.getCommands().getDispatcher().execute("npc " + command, alice.player.createCommandSourceStack()) == 1, "dispatch_" + command); alice.pump(); }
    private static void execute(String command) throws Exception { outputStart = alice.player.messages.size(); check(server.getCommands().getDispatcher().execute(command.startsWith("/") ? command.substring(1) : command, alice.player.createCommandSourceStack()) == 1, "native_click_command"); alice.pump(); }
    private static void reject(String command) throws Exception { try { check(server.getCommands().getDispatcher().execute("npc " + command, alice.player.createCommandSourceStack()) == 0, "reject_" + command); } catch (CommandSyntaxException expected) { check(true, "reject_" + command); } }
    private static String text() { return String.join("\n", alice.player.messages.subList(outputStart, alice.player.messages.size())); }
    private static List<String> links() {
        Set<String> links = new LinkedHashSet<>();
        for (Component component : alice.player.components.subList(outputStart, alice.player.components.size())) component.visit((style, value) -> {
            ClickEvent click = style.getClickEvent();
            if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND) links.add(click.getValue());
            return Optional.empty();
        }, Style.EMPTY);
        return List.copyOf(links);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; }
    private static void close() {
        if (done) return; done = true; ChatPrompts.abandonAll();
        if (selectionMessage != null) net.citizensnpcs.Settings.Setting.SELECTION_MESSAGE.set(selectionMessage);
        try {
            for (NPC npc : List.copyOf(all)) if (exists(npc) || npc.getEntity() != null) npc.destroy();
            for (String name : names) if (CitizensAPI.getNamedNPCRegistry(name) != null) CitizensAPI.removeNamedNPCRegistry(name);
            grants.forEach(PermissionUtil.Attachment::remove);
            for (Actor actor : new Actor[] { alice, bob }) if (actor != null) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
            if (forced) level.setChunkForced(0, 0, false);
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[SELECTIONCOMMANDAUDIT] FAILED cleanup", failure); }
        finally { server.halt(false); }
    }

    private static Actor actor(String name) {
        Actor actor = new Actor();
        ClientInformation information = ClientInformation.createDefault();
        actor.player = new AuditPlayer(name);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("relocation-audit-capture", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof Packet<?> packet) actor.capture(packet);
                        super.write(context, message, promise);
                    }
                });
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(actor.player.getGameProfile(), 0, information, false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, actor.player, cookie);
        actor.player.setPos(4, -58, 4); actor.player.setNoGravity(true);
        return actor;
    }
    private static final class AuditPlayer extends ServerPlayer {
        final List<String> messages = new ArrayList<>(); final List<Component> components = new ArrayList<>();
        AuditPlayer(String name) { super(SelectionCommandRuntimeAudit.server, SelectionCommandRuntimeAudit.level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); components.add(message.copy()); }
    }
    private static final class Actor {
        AuditPlayer player; EmbeddedChannel channel; int batches;
        final List<Integer> teleports = new ArrayList<>();
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            if (packet instanceof ClientboundChunkBatchFinishedPacket) batches++;
            if (packet instanceof ClientboundPlayerPositionPacket position) teleports.add(position.getId());
        }
        void pump() {
            channel.runPendingTasks();
            while (batches > 0) { batches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            for (int id : List.copyOf(teleports)) player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
            teleports.clear();
            Object value; while ((value = channel.readOutbound()) != null) ReferenceCountUtil.release(value);
        }
    }
}
