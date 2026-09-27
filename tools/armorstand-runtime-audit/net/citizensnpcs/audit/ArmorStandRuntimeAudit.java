package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.trait.ArmorStandTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Rotations;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
public final class ArmorStandRuntimeAudit {
    private static final String[] LIMBS = { "head", "body", "leftarm", "leftleg", "rightarm", "rightleg" };
    private static final String[] FIELDS = { "head", "body", "leftArm", "leftLeg", "rightArm", "rightLeg" };
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<String> messages = new ArrayList<>();
    private static final Rotations[] expected = new Rotations[6];
    private static NPC npc;
    private static ServerPlayer player;
    private static EmbeddedChannel channel;
    private static boolean done, allowCommand = true;
    private static int passed, deadline, entityTicks;

    @SubscribeEvent public static void permissions(PermissionGatherEvent.Handler event) {
        event.addPermissionHandler(AuditPermissions.ID, AuditPermissions::new);
    }

    private static final class AuditPermissions implements IPermissionHandler {
        static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("citizens", "armorstand_audit");
        private final DefaultPermissionHandler defaults;
        AuditPermissions(Collection<PermissionNode<?>> nodes) { defaults = new DefaultPermissionHandler(nodes); }
        public ResourceLocation getIdentifier() { return ID; }
        public Set<PermissionNode<?>> getRegisteredNodes() { return defaults.getRegisteredNodes(); }
        @SuppressWarnings("unchecked")
        public <T> T getPermission(ServerPlayer source, PermissionNode<T> node, PermissionDynamicContext<?>... contexts) {
            if (source == player && node.getType() == PermissionTypes.BOOLEAN) {
                Boolean value = switch (node.getNodeName()) {
                    case "citizens.npc.armorstand" -> allowCommand;
                    case "citizens.admin", "citizens.ignore-owner" -> false;
                    default -> null;
                };
                if (value != null) return (T) value;
            }
            return defaults.getPermission(source, node, contexts);
        }
        public <T> T getOfflinePermission(UUID source, PermissionNode<T> node, PermissionDynamicContext<?>... contexts) {
            return defaults.getOfflinePermission(source, node, contexts);
        }
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (done || server.getTickCount() < 10) return;
        try {
            if (deadline == 0) {
                check(Files.isRegularFile(Path.of("armorstand-audit-fixture.txt")), "isolated_fixture");
                npc = create(EntityType.ARMOR_STAND, "ArmorStandAudit");
                check(npc.spawn(new Location(server.overworld(), 0.5, -60, 0.5)), "spawned");
                commands(server);
                entityTicks = entity().tickCount;
                deadline = server.getTickCount() + 20;
            } else if (server.getTickCount() >= deadline) {
                check(entity().tickCount > entityTicks, "native_entity_ticked");
                poses(expected, "real_ticks");
                parser();
                invalid(server);
                lifecycle(server);
                virtual(server);
                authorization(server);
                done = true;
                LoggerFactory.getLogger("citizens").info("[ARMORSTANDAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) {
            done = true;
            LoggerFactory.getLogger("citizens").error("[ARMORSTANDAUDIT] FAILED", failure);
        } finally {
            if (done) try {
                for (NPC entry : npcs) entry.destroy();
                if (player != null && player.connection != null) server.getPlayerList().remove(player);
                if (channel != null) channel.finishAndReleaseAll();
            }
            finally { server.halt(false); }
        }
    }

    private static NPC create(EntityType<?> type, String name) {
        NPC created = CitizensAPI.getNPCRegistry().createNPC(type, name); npcs.add(created); return created;
    }

    private static ArmorStand entity() { return (ArmorStand) npc.getEntity(); }
    private static ArmorStandTrait trait() { return npc.getTraitNullable(ArmorStandTrait.class); }
    private static Rotations[] current() {
        ArmorStand stand = entity();
        return new Rotations[] { stand.getHeadPose(), stand.getBodyPose(), stand.getLeftArmPose(), stand.getLeftLegPose(),
                stand.getRightArmPose(), stand.getRightLegPose() };
    }
    private static void poses(Rotations[] wanted, String label) {
        Rotations[] actual = current();
        for (int i = 0; i < wanted.length; i++) check(close(actual[i], wanted[i]), label + "_" + LIMBS[i]);
    }
    private static boolean close(Rotations first, Rotations second) {
        return first != null && second != null && Math.abs(first.getX() - second.getX()) < 0.001
                && Math.abs(first.getY() - second.getY()) < 0.001 && Math.abs(first.getZ() - second.getZ()) < 0.001;
    }
    private static CommandSourceStack source(MinecraftServer server) {
        return server.createCommandSourceStack().withSource(new CommandSource() {
            public void sendSystemMessage(Component message) { messages.add(message.getString()); }
            public boolean acceptsSuccess() { return true; }
            public boolean acceptsFailure() { return true; }
            public boolean shouldInformAdmins() { return false; }
        });
    }
    private static int command(MinecraftServer server, CommandSourceStack source, String options) throws Exception {
        try { return server.getCommands().getDispatcher().execute("npc armorstand " + options, source); }
        catch (CommandSyntaxException expectedFailure) {
            messages.add(expectedFailure.getRawMessage().getString()); return 0;
        }
    }
    private static int command(MinecraftServer server, String options) throws Exception {
        var source = source(server); CitizensAPI.getDefaultNPCSelector().select(source, npc);
        return command(server, source, options);
    }
    private static MemoryDataKey saved() {
        var key = new MemoryDataKey(); trait().save(key); PersistenceLoader.save(trait(), key); return key;
    }

    private static void commands(MinecraftServer server) throws Exception {
        for (int i = 0; i < LIMBS.length; i++) {
            expected[i] = new Rotations(10 + i, -20 - i, 30 + i);
            String angles = (10 + i) + "d," + Math.toRadians(-20 - i) + "r," + Math.toRadians(30 + i);
            check(command(server, "--" + LIMBS[i] + "pose " + angles) == 1, "command_" + LIMBS[i]);
            check(close(current()[i], expected[i]), "immediate_" + LIMBS[i]);
            Rotations metadata = expected[i];
            check(entity().getEntityData().getNonDefaultValues().stream().anyMatch(value -> value.value().equals(metadata)),
                    "native_metadata_" + LIMBS[i]);
        }
        check(command(server, "--visible false --small true --gravity false --marker true --arms false --baseplate false") == 1,
                "combined_switches");
        check(entity().isInvisible() && entity().isSmall() && entity().isNoGravity() && entity().isMarker()
                && !entity().isShowArms() && entity().isNoBasePlate(), "switches_apply_immediately");
        poses(expected, "switches_preserve_poses");
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < LIMBS.length; i++) {
            combined.append(" --").append(LIMBS[i]).append("pose \"").append(10 + i).append("d, ")
                    .append(-20 - i).append("d, ").append(30 + i).append("d\"");
        }
        check(command(server, combined.toString()) == 1, "all_six_quoted_poses_in_one_command");
        poses(expected, "combined_poses");
    }

    private static void parser() {
        var context = new CommandContext(null, new String[] { "npc", "armorstand" });
        check(close(context.parseEulerAngle(" 90d, -1.5707963267948966r, 3.141592653589793 "), new Rotations(90, -90, 180)),
                "parser_trim_and_mixed_units");
        check(close(context.parseEulerAngle("-450d,720d,0d"), new Rotations(-90, 0, 0)), "native_angle_normalization");
        for (String invalid : invalidAngles()) {
            try { context.parseEulerAngle(invalid); throw new AssertionError("accepted " + invalid); }
            catch (IllegalArgumentException expectedFailure) { check(true, "parser_rejection_" + invalid); }
        }
    }

    private static List<String> invalidAngles() {
        return List.of("", "0", "0,1", "0,1,2,3", "0,,2", "0,1,", ",1,2", "NaN,0,0", "Infinityd,0,0",
                "0,-Infinityr,0", "0,0,1e309", "1e307r,0,0", "1e100d,0,0", "0,0,broken", "d,0,0");
    }

    private static void invalid(MinecraftServer server) throws Exception {
        var before = saved().getValuesDeep(); Rotations[] poses = current();
        for (String limb : LIMBS) for (String angle : invalidAngles()) {
            messages.clear();
            check(command(server, "--visible true --bodypose 55d,55d,55d --" + limb + "pose \"" + angle + "\"") == 0,
                    "invalid_command_" + limb + "_" + angle);
            check(saved().getValuesDeep().equals(before), "atomic_rejection_" + limb + "_" + angle);
            check(!messages.isEmpty(), "rejection_feedback_" + limb + "_" + angle);
        }
        for (String invalid : List.of("--headpose", "--visible maybe", "--small 1", "--marker no", "--gravity yep",
                "--arms invalid", "--baseplate nope", "--unknown 1", "extra", "-x")) {
            check(command(server, "--headpose 2d,3d,4d " + invalid) == 0 && saved().getValuesDeep().equals(before),
                    "strict_rejection_" + invalid);
        }
        poses(poses, "rejection_native_poses");
        npc.removeTrait(ArmorStandTrait.class);
        check(command(server, "--headpose 1,2") == 0 && !npc.hasTrait(ArmorStandTrait.class), "invalid_does_not_attach_trait");
        check(command(server, "--headpose 1d,2d,3d") == 1 && npc.hasTrait(ArmorStandTrait.class), "valid_attaches_trait");
    }

    private static void lifecycle(MinecraftServer server) throws Exception {
        ArmorStandTrait trait = trait();
        Rotations external = new Rotations(61, 62, 63); entity().setRightArmPose(external);
        check(command(server, "--headpose 90d,0d,0d") == 1 && close(entity().getRightArmPose(), external),
                "command_preserves_unrelated_external_pose");
        var wanted = new Rotations(11, 12, 13);
        trait.setHeadPose(wanted); trait.setBodyPose(wanted); trait.setLeftArmPose(wanted); trait.setLeftLegPose(wanted);
        trait.setRightArmPose(wanted); trait.setRightLegPose(wanted);
        for (Rotations pose : current()) check(close(pose, wanted), "api_pose_applies_immediately");
        trait.run(); for (Rotations pose : current()) check(close(pose, wanted), "api_pose_survives_trait_tick");
        entity().setBodyPose(external);
        var snapshot = new MemoryDataKey(); npc.saveSnapshot(snapshot);
        check(Math.abs(snapshot.getDouble("traits.armorstandtrait.body.x") - Math.toRadians(61)) < 0.00001,
                "snapshot_captures_external_pose_before_tick_in_legacy_radians");
        entity().setLeftLegPose(external);
        check(npc.despawn(), "despawn");
        check(close(trait.getLeftLegPose(), external), "despawn_captures_external_pose_before_tick");
        check(command(server, "--headpose 180d,-90d,45d --small true") == 1 && !npc.isSpawned(), "unspawned_configuration");
        var disk = saved();
        check(Math.abs(disk.getDouble("head.x") - Math.PI) < 0.00001, "saved_head_is_radians");
        var restored = new ArmorStandTrait(); PersistenceLoader.load(restored, disk); npc.addTrait(restored);
        check(npc.spawn(npc.getStoredLocation()), "respawn");
        check(close(entity().getHeadPose(), new Rotations(180, -90, 45)) && entity().isSmall(), "restored_pose_and_flags");
        check(close(entity().getBodyPose(), external) && close(entity().getLeftLegPose(), external), "external_poses_restored");
        var legacy = new MemoryDataKey();
        for (int i = 0; i < FIELDS.length; i++) {
            legacy.setDouble(FIELDS[i] + ".x", Math.toRadians(40 + i));
            legacy.setDouble(FIELDS[i] + ".y", Math.toRadians(-50 - i));
            legacy.setDouble(FIELDS[i] + ".z", Math.toRadians(60 + i));
        }
        var imported = new ArmorStandTrait(); PersistenceLoader.load(imported, legacy); npc.addTrait(imported);
        var read = current();
        for (int i = 0; i < FIELDS.length; i++) {
            check(close(read[i], new Rotations(40 + i, -50 - i, 60 + i)), "legacy_load_" + FIELDS[i]);
            check(Math.abs(saved().getDouble(FIELDS[i] + ".y") - legacy.getDouble(FIELDS[i] + ".y")) < 0.00001,
                    "legacy_roundtrip_" + FIELDS[i]);
        }
        var before = saved().getValuesDeep();
        restored.setHeadPose(wanted); restored.setBodyPose(wanted); restored.setLeftArmPose(wanted);
        restored.setLeftLegPose(wanted); restored.setRightArmPose(wanted); restored.setRightLegPose(wanted);
        restored.setVisible(false); restored.onSpawn(); restored.onPreSpawn(); restored.run(); restored.onDespawn();
        check(saved().getValuesDeep().equals(before), "retired_trait_cannot_change_replacement");
        poses(read, "retired_trait_native_poses");
    }

    private static void virtual(MinecraftServer server) throws Exception {
        NPC original = npc;
        try {
            npc = create(EntityType.ARMOR_STAND, "VirtualStand"); npc.getOrAddTrait(PacketNPC.class);
            check(command(server, "--headpose 20d,30d,40d --gravity false") == 1 && !npc.isSpawned(),
                    "never_spawned_command_configuration");
            check(npc.spawn(new Location(server.overworld(), 4.5, -60, 0.5)), "virtual_spawn");
            check(server.overworld().getEntity(npc.getEntity().getUUID()) == null, "virtual_not_in_world_index");
            check(close(entity().getHeadPose(), new Rotations(20, 30, 40)), "virtual_initial_pose");
            check(command(server, "--bodypose -45d,90d,180d") == 1, "virtual_command");
            check(close(entity().getBodyPose(), new Rotations(-45, 90, 180)), "virtual_immediate_pose");
            trait().run();
            check(close(trait().getBodyPose(), new Rotations(-45, 90, 180)), "virtual_pose_survives_tick");
        } finally { npc = original; }
    }

    private static void authorization(MinecraftServer server) throws Exception {
        player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "StandAudit"),
                ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) { connection.configurePacketHandler(channel.pipeline()); }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false,
                ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setPos(4.5, -60, 4.5);
        player.connection.send(new ClientboundSetEntityDataPacket(entity().getId(), entity().getEntityData().getNonDefaultValues()));
        channel.runPendingTasks(); channel.checkException();
        check(player.connection.getConnection().isConnected(), "native_pose_packet_encodes");
        var source = source(server).withEntity(player).withPermission(0);
        npc.getOrAddTrait(Owner.class).setOwner(player.getUUID());
        CitizensAPI.getDefaultNPCSelector().select(source, npc);
        check(command(server, source, "--headpose 7d,8d,9d") == 1, "owner_with_permission");
        var before = saved().getValuesDeep(); allowCommand = false;
        check(command(server, source, "--headpose 0d,0d,0d") == 0, "permission_required"); allowCommand = true;
        npc.getOrAddTrait(Owner.class).setOwner(UUID.randomUUID());
        check(command(server, source, "--headpose 0d,0d,0d") == 0, "ownership_required");
        check(saved().getValuesDeep().equals(before), "denied_commands_preserve_pose");
        NPC cow = create(EntityType.COW, "WrongType");
        CitizensAPI.getDefaultNPCSelector().select(source(server), cow);
        check(command(server, source(server), "--headpose 0d,0d,0d") == 0 && !cow.hasTrait(ArmorStandTrait.class),
                "wrong_type_rejected_without_trait");
        CitizensAPI.getDefaultNPCSelector().deselect(source);
        check(command(server, source, "--headpose 0d,0d,0d") == 0, "selection_required");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[ARMORSTANDAUDIT] PASS {}", label);
    }
}
