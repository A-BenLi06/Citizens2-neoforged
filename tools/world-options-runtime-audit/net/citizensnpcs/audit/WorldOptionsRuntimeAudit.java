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
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.*;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.trait.*;
import net.citizensnpcs.util.Anchor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class WorldOptionsRuntimeAudit {
    private static MinecraftServer server;
    private static ServerLevel level, nether;
    private static NPCRegistry registry;
    private static Actor alice;
    private static CommandSourceStack source;
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<Entity> controls = new ArrayList<>();
    private static final List<PermissionUtil.Attachment> grants = new ArrayList<>();
    private static final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private static Consumer<NPCTeleportEvent> teleport;
    private static int start = -1, passed, sequence, deadline;
    private static boolean done, forced;
    private static String sharedRegistry;

    @SubscribeEvent public static void teleport(NPCTeleportEvent e) { if (teleport != null) teleport.accept(e); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer(); level = server.overworld(); nether = server.getLevel(Level.NETHER);
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("world-options-audit-fixture.txt")), "isolated_fixture");
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_default_registry");
                level.setChunkForced(0, 0, true); nether.setChunkForced(0, 0, true); forced = true;
                deadline = server.getTickCount() + 600;
            }
            if (server.getTickCount() > deadline) throw new AssertionError("fixture_timeout");
            if (!level.isPositionEntityTicking(new BlockPos(8, -58, 8)) || !nether.isPositionEntityTicking(new BlockPos(4, 70, 4))) return;
            if (start < 0) {
                start = server.getTickCount(); registry = CitizensAPI.getNPCRegistry();
                alice = actor("WorldOptionsAlice"); source = alice.player.createCommandSourceStack();
                grants.add(PermissionUtil.grantTemporary(alice.player, List.of("citizens.npc.anchor", "citizens.npc.chunkload", "citizens.npc.collidable")));
                anchors(); metadata(); tickets(); fluids();
                close();
                LoggerFactory.getLogger("citizens").info("[WORLDOPTIONSAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[WORLDOPTIONSAUDIT] FAILED", failure); close();
        }
    }

    private static void anchors() throws Exception {
        NPC npc = npc(EntityType.COW, false, false); select(npc);
        reject("anchor --save \"\""); reject("anchor --save \"bad;name\"");
        reject("anchor --save Invalid --location NaN,0,0");
        check(!npc.hasTrait(Anchors.class), "invalid_anchor_does_not_attach_trait");
        alice.player.setPos(4, -58, 4); alice.player.setYRot(0); alice.player.setYHeadRot(90); alice.player.setXRot(0); source = alice.player.createCommandSourceStack();
        set(new BlockPos(4, -57, 8), Blocks.STONE.defaultBlockState());
        CommandContext context = new CommandContext(source, new String[] { "npc", "anchor" });
        Location sender = context.getSenderLocation(), target = context.getSenderTargetBlockLocation();
        check(sender.getZ() == 4 && target.getZ() == 8.5, "source_and_cursor_caches_are_independent sender=" + sender + " target=" + target);
        run("anchor --save Gate -c");
        Anchors anchors = npc.getTraitNullable(Anchors.class);
        at(anchors.getAnchor("Gate").getLocation(), 4.5, -57, 8.5, "cursor_anchor_position");
        reject("anchor --save gate");
        run("anchor --save Explicit -c --location 7,-58,7,minecraft:overworld");
        at(anchors.getAnchor("Explicit").getLocation(), 7, -58, 7, "explicit_location_wins_over_cursor");
        Location mutable = new Location(level, 9, -58, 9);
        anchors.addAnchor("Copy", mutable); mutable.setX(100);
        anchors.getAnchor("Copy").getLocation().setX(200);
        check(anchors.getAnchor("Copy").getLocation().getX() == 9, "anchor_locations_are_defensive_copies");
        MemoryDataKey saved = new MemoryDataKey(); anchors.save(saved);
        Anchors loaded = new Anchors(); loaded.linkToNPC(npc); loaded.load(saved);
        at(loaded.getAnchor("Gate").getLocation(), 4.5, -57, 8.5, "anchor_round_trip");
        MemoryDataKey legacy = new MemoryDataKey();
        legacy.setString("list.0", "Missing;missing_world;1;2;3");
        legacy.setString("list.1", "Bad;minecraft:overworld;NaN;2;3");
        loaded.load(legacy);
        check(!loaded.getAnchor("Missing").isLoaded() && !loaded.getAnchor("Bad").isLoaded(), "unavailable_anchor_not_redirected_to_overworld");
        MemoryDataKey retained = new MemoryDataKey(); loaded.save(retained);
        check(retained.getString("list.0").equals(legacy.getString("list.0")) && retained.getString("list.1").equals(legacy.getString("list.1")),
                "unavailable_anchor_data_retained");
        check(npc.spawn(new Location(level, 2, -58, 2)), "spawn_anchor_target");
        run("anchor --assume Explicit"); at(npc.getStoredLocation(), 7, -58, 7, "assume_anchor");
        teleport = e -> { if (e.getNPC() == npc) e.setCanceled(true); };
        run("anchor --assume Copy"); teleport = null;
        at(npc.getStoredLocation(), 7, -58, 7, "anchor_teleport_cancellation");
        run("anchor --save Here -a"); at(npc.getStoredLocation(), 4, -58, 4, "save_and_assume_here");
        run("anchor --assume Copy --remove Gate");
        check(anchors.getAnchor("Gate") != null, "assume_precedes_remove");
        run("anchor --remove Copy"); check(anchors.getAnchor("Copy") == null, "remove_anchor");
        alice.player.messages.clear();
        run("anchor"); check(alice.player.messages.stream().anyMatch(value -> value.contains("Gate") && value.contains("4")), "anchor_listing_has_location");
        reject("anchor --unknown yes");
        npc.destroy();
    }

    private static void metadata() throws Exception {
        for (boolean baseline : new boolean[] { false, true }) {
            SimpleMetadataStore store = new SimpleMetadataStore();
            store.setPersistent(NPC.Metadata.KEEP_CHUNK_LOADED, baseline);
            store.setTemporary(NPC.Metadata.KEEP_CHUNK_LOADED, !baseline);
            check(store.<Boolean>get(NPC.Metadata.KEEP_CHUNK_LOADED) != baseline, "temporary_runtime_value");
            MemoryDataKey saved = new MemoryDataKey(); store.saveTo(saved);
            check(saved.getBoolean(NPC.Metadata.KEEP_CHUNK_LOADED.getKey()) == baseline, "temporary_save_keeps_persistent_value");
            MetadataStore copy = store.clone(); MemoryDataKey cloned = new MemoryDataKey(); copy.saveTo(cloned);
            check(cloned.getBoolean(NPC.Metadata.KEEP_CHUNK_LOADED.getKey()) == baseline, "clone_keeps_persistent_backing");
            store.loadFrom(saved);
            check(store.<Boolean>get(NPC.Metadata.KEEP_CHUNK_LOADED) == baseline, "reload_removes_temporary_override");
            store.setTemporary(NPC.Metadata.KEEP_CHUNK_LOADED, !baseline); store.setPersistent(NPC.Metadata.KEEP_CHUNK_LOADED, !baseline);
            store.saveTo(saved); check(saved.getBoolean(NPC.Metadata.KEEP_CHUNK_LOADED.getKey()) != baseline, "permanent_write_replaces_override");
        }
        SimpleMetadataStore absent = new SimpleMetadataStore(); absent.setTemporary(NPC.Metadata.KEEP_CHUNK_LOADED, true);
        MemoryDataKey saved = new MemoryDataKey(); absent.saveTo(saved);
        check(!saved.keyExists(NPC.Metadata.KEEP_CHUNK_LOADED.getKey()), "temporary_only_value_is_not_saved");
    }

    private static void tickets() throws Exception {
        for (boolean packet : new boolean[] { false, true }) {
            NPC npc = npc(EntityType.COW, packet, false); select(npc);
            run("chunkload -t");
            check(npc.data().get(NPC.Metadata.KEEP_CHUNK_LOADED, false) && !ticket(level, 0, 0), "unspawned_setting_does_not_force_chunk");
            npc.spawn(new Location(level, 4, -58, 4));
            check(ticket(level, 0, 0), "spawn_acquires_requested_ticket");
            run("cload -t"); check(!ticket(level, 0, 0), "disable_releases_immediately");
            MemoryDataKey state = new MemoryDataKey(); npc.data().saveTo(state);
            check(!state.getBoolean(NPC.Metadata.KEEP_CHUNK_LOADED.getKey()), "temporary_commands_preserve_false_baseline");
            run("chunkload"); check(ticket(level, 0, 0), "permanent_enable_acquires_immediately");
            npc.data().saveTo(state); check(state.getBoolean(NPC.Metadata.KEEP_CHUNK_LOADED.getKey()), "permanent_chunkload_saved");
            run("chunkload -t"); check(!ticket(level, 0, 0), "temporary_disable_releases_ticket");
            MemoryDataKey fresh = new MemoryDataKey(); npc.data().saveTo(fresh);
            check(fresh.getBoolean(NPC.Metadata.KEEP_CHUNK_LOADED.getKey()), "temporary_disable_preserves_true_baseline");
            run("chunkload -t"); check(ticket(level, 0, 0), "temporary_reenable_acquires_ticket");
            ChunkTicketTrait old = npc.getTraitNullable(ChunkTicketTrait.class);
            npc.addTrait(new ChunkTicketTrait()); old.run(); old.onRemove();
            check(ticket(level, 0, 0), "retired_trait_cannot_release_replacement_ticket");
            npc.teleport(new Location(level, 20, -58, 4), TeleportCause.PLUGIN); npc.getTraitNullable(ChunkTicketTrait.class).run();
            check(!ticket(level, 0, 0) && ticket(level, 1, 0), "ticket_follows_chunk_transfer");
            npc.teleport(new Location(nether, 4, 70, 4), TeleportCause.PLUGIN); npc.getTraitNullable(ChunkTicketTrait.class).run();
            check(!ticket(level, 1, 0) && ticket(nether, 0, 0), "ticket_follows_dimension_transfer");
            npc.removeTrait(ChunkTicketTrait.class);
            check(!ticket(nether, 0, 0), "trait_removal_releases_ticket");
            old.onSpawn(); check(!ticket(level, 0, 0) && !ticket(nether, 0, 0), "retired_trait_cannot_reacquire");
            npc.destroy();
        }
        NPC base = npc(EntityType.COW, false, false); base.data().setPersistent(NPC.Metadata.KEEP_CHUNK_LOADED, true);
        base.spawn(new Location(level, 4, -58, 4));
        check(base.hasTrait(ChunkTicketTrait.class) && ticket(level, 0, 0), "metadata_only_spawn_installs_ticket_trait");
        sharedRegistry = "world-options-shared"; NPCRegistry shared = CitizensAPI.createInMemoryNPCRegistry(sharedRegistry);
        NPC twin = shared.createNPC(EntityType.COW, base.getUniqueId(), base.getId(), "Shared"); npcs.add(twin);
        twin.getOrAddTrait(PacketNPC.class); twin.data().setPersistent(NPC.Metadata.KEEP_CHUNK_LOADED, true);
        twin.spawn(new Location(level, 5, -58, 5)); base.destroy();
        check(ticket(level, 0, 0), "same_owner_key_remains_until_last_trait_releases");
        twin.destroy(); check(!ticket(level, 0, 0), "last_shared_ticket_owner_releases");
        check(level.getForcedChunks().contains(new ChunkPos(0, 0).toLong()), "independent_vanilla_forceload_preserved");
    }

    private static void fluids() throws Exception {
        for (Block liquid : new Block[] { Blocks.WATER, Blocks.LAVA }) {
            FluidType type = liquid == Blocks.WATER ? NeoForgeMod.WATER_TYPE.value() : NeoForgeMod.LAVA_TYPE.value();
            // Remove the previous liquid before replacement: lava's native placement hook solidifies beside water.
            for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) set(new BlockPos(x, -58, z), Blocks.AIR.defaultBlockState());
            for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) {
                set(new BlockPos(x, -59, z), Blocks.STONE.defaultBlockState());
                set(new BlockPos(x, -58, z), liquid.defaultBlockState().setValue(LiquidBlock.LEVEL, x == 7 ? 0 : x == 8 ? 3 : 7));
            }
            for (EntityType<?> entityType : new EntityType<?>[] { EntityType.COW, EntityType.PLAYER }) for (boolean packet : new boolean[] { false, true }) {
                NPC npc = npc(entityType, packet, true); select(npc); Entity entity = npc.getEntity();
                entity.setPos(8.5, -58, 8.5);
                Vec3 motion = new Vec3(0.01, 0.02, 0.03);
                entity.setDeltaMovement(motion); entity.updateFluidHeightAndDoFluidPushing();
                check(entity.getDeltaMovement().equals(motion) && entity.getFluidTypeHeight(type) > 0, "protected_default_still_tracks_fluid_" + entityType + packet + liquid
                        + " motion=" + entity.getDeltaMovement() + " height=" + entity.getFluidTypeHeight(type));
                run("collidable --fluids true"); entity.setDeltaMovement(motion); entity.updateFluidHeightAndDoFluidPushing();
                check(entity.getDeltaMovement().x > motion.x && entity.getFluidTypeHeight(type) > 0, "enabled_native_current_" + entityType + packet + liquid);
                check(!npc.data().has(NPC.Metadata.COLLIDABLE), "fluid_option_does_not_toggle_collisions");
                run("pushable --fluids false"); entity.setDeltaMovement(motion); entity.updateFluidHeightAndDoFluidPushing();
                check(entity.getDeltaMovement().equals(motion) && entity.getFluidTypeHeight(type) > 0, "disabled_current_preserves_motion_and_contact");
                MemoryDataKey saved = new MemoryDataKey(); npc.data().saveTo(saved);
                check(saved.keyExists(NPC.Metadata.FLUID_PUSHABLE.getKey()) && !saved.getBoolean(NPC.Metadata.FLUID_PUSHABLE.getKey()), "fluid_option_persists");
                reject("collidable --fluids maybe"); check(!npc.isPushableByFluids(), "invalid_fluid_option_is_atomic");
                run("collidable"); check(npc.data().get(NPC.Metadata.COLLIDABLE, false) && !npc.isPushableByFluids(), "collision_toggle_independent_of_fluids");
                npc.data().remove(NPC.Metadata.FLUID_PUSHABLE); npc.setProtected(false);
                entity.setDeltaMovement(motion); entity.updateFluidHeightAndDoFluidPushing();
                check(entity.getDeltaMovement().x > motion.x, "unprotected_default_allows_native_current");
                npc.data().setPersistent(NPC.Metadata.FLUID_PUSHABLE, false);
                ((LivingEntity) entity).die(entity.damageSources().genericKill());
                check(!npc.isSpawned() && net.citizensnpcs.npc.NPCDeaths.visualOwner(entity) == npc, "dead_npc_retains_visual_owner");
                entity.setDeltaMovement(motion); entity.updateFluidHeightAndDoFluidPushing();
                check(entity.getDeltaMovement().equals(motion) && entity.getFluidTypeHeight(type) > 0, "death_visual_retains_fluid_policy");
                npc.destroy();
            }
            Entity vanilla = EntityType.COW.create(level); controls.add(vanilla); vanilla.setPos(8.5, -58, 8.5);
            Vec3 before = new Vec3(0.01, 0.02, 0.03); vanilla.setDeltaMovement(before); vanilla.updateFluidHeightAndDoFluidPushing();
            check(vanilla.getDeltaMovement().x > before.x && vanilla.getFluidTypeHeight(type) > 0, "vanilla_fluid_control");
            for (boolean packet : new boolean[] { false, true }) {
                NPC turtle = npc(EntityType.TURTLE, packet, true); Entity entity = turtle.getEntity();
                turtle.data().setPersistent(NPC.Metadata.FLUID_PUSHABLE, true);
                entity.setPos(8.5, -58, 8.5); entity.setDeltaMovement(before); entity.updateFluidHeightAndDoFluidPushing();
                check(!entity.isPushedByFluid(type) && entity.getDeltaMovement().equals(before) && entity.getFluidTypeHeight(type) > 0,
                        "explicit_enable_preserves_native_no_push_gate_" + packet + liquid);
                turtle.destroy();
            }
        }
    }

    private static boolean ticket(ServerLevel world, int x, int z) {
        ForcedChunksSavedData data = world.getDataStorage().get(ForcedChunksSavedData.factory(), ForcedChunksSavedData.FILE_ID);
        return data != null && data.getEntityForcedChunks().getTickingChunks().values().stream().anyMatch(set -> set.contains(ChunkPos.asLong(x, z)));
    }
    private static NPC npc(EntityType<?> type, boolean packet, boolean spawned) {
        NPC npc = registry.createNPC(type, "WorldOpt" + sequence++); npcs.add(npc);
        npc.getOrAddTrait(Owner.class).setOwner(alice.player.getUUID()); npc.getOrAddTrait(LookClose.class).setEnabled(false);
        npc.data().setPersistent(NPC.Metadata.KEEP_CHUNK_LOADED, false);
        if (type == EntityType.PLAYER) npc.getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false);
        if (packet) npc.getOrAddTrait(PacketNPC.class);
        if (spawned) { check(npc.spawn(new Location(level, 4, -58, 4)), "fixture_npc_spawn"); npc.getEntity().setNoGravity(true); }
        return npc;
    }
    private static void set(BlockPos pos, BlockState state) { blocks.putIfAbsent(pos, level.getBlockState(pos)); level.setBlock(pos, state, 2); }
    private static void select(NPC npc) { CitizensAPI.getDefaultNPCSelector().select(source, npc); }
    private static void run(String command) throws Exception { check(server.getCommands().getDispatcher().execute("npc " + command, source) == 1, "dispatch_" + command); alice.pump(); }
    private static void reject(String command) throws Exception { try { check(server.getCommands().getDispatcher().execute("npc " + command, source) == 0, "reject_" + command); } catch (CommandSyntaxException expected) { check(true, "reject_" + command); } }
    private static void at(Location loc, double x, double y, double z, String label) { check(loc != null && loc.getWorld() == level && Math.abs(loc.getX()-x)<1e-6 && Math.abs(loc.getY()-y)<1e-6 && Math.abs(loc.getZ()-z)<1.0e-6, label + " actual=" + loc); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; }
    private static void close() {
        if (done) return; done = true; teleport = null;
        try {
            for (NPC npc : npcs) if (npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc || npc.getEntity() != null) npc.destroy();
            for (Entity entity : controls) entity.discard();
            if (sharedRegistry != null) CitizensAPI.removeNamedNPCRegistry(sharedRegistry);
            blocks.forEach((pos, state) -> level.setBlock(pos, state, 2));
            grants.forEach(PermissionUtil.Attachment::remove);
            if (alice != null) { server.getPlayerList().remove(alice.player); alice.channel.finishAndReleaseAll(); }
            if (forced) { level.setChunkForced(0, 0, false); nether.setChunkForced(0, 0, false); }
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[WORLDOPTIONSAUDIT] FAILED cleanup", failure); }
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
                channel.pipeline().addLast("world-options-audit-capture", new ChannelOutboundHandlerAdapter() {
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
        final List<String> messages = new ArrayList<>();
        AuditPlayer(String name) { super(WorldOptionsRuntimeAudit.server, WorldOptionsRuntimeAudit.level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
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
