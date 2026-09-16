package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.PathStrategy;
import net.citizensnpcs.api.ai.TargetType;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.npc.AbstractNPC;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.npc.ai.NPCSwimming;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.npc.skin.SkinPacketTracker;
import net.citizensnpcs.trait.AttributeTrait;
import net.citizensnpcs.trait.Gravity;
import net.citizensnpcs.trait.MirrorTrait;
import net.citizensnpcs.trait.SkinTrait;
import net.citizensnpcs.util.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class MovementListRuntimeAudit {
    private static State state;
    private static boolean forced, finished;
    private static int passed;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (finished) return;
        var server = event.getServer(); var level = server.overworld();
        if (!forced) {
            for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) level.setChunkForced(x, z, true);
            forced = true;
        }
        try {
            for (int x = 0; x <= 4; x++) for (int z = 0; z <= 4; z++) {
                if (!level.areEntitiesLoaded(ChunkPos.asLong(x, z)) || !level.isPositionEntityTicking(new BlockPos(x * 16, -60, z * 16))) {
                    if (server.getTickCount() > 1500) throw new AssertionError("Fixture loading timed out");
                    return;
                }
            }
            if (state == null) state = new State(server);
            if (state.next()) return;
            LoggerFactory.getLogger("citizens").info("[MOVEMENTLISTAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[MOVEMENTLISTAUDIT] FAILED " + (state == null ? "setup" : state.phase), failure);
        }
        finished = true;
        try { if (state != null) state.close(); }
        catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[MOVEMENTLISTAUDIT] FAILED cleanup", failure); }
        server.halt(false);
    }

    @FunctionalInterface private interface Test { void run() throws Exception; }
    private record Step(String name, Test test) { }
    private static final class State {
        final MinecraftServer server;
        final ServerLevel level;
        final List<Step> steps = new ArrayList<>();
        final List<Actor> actors = new ArrayList<>();
        final List<NPC> created = new ArrayList<>();
        final List<PermissionUtil.Attachment> permissions = new ArrayList<>();
        final boolean oldWorldList = Setting.REMOVE_PLAYERS_FROM_PLAYER_LIST.asBoolean();
        final boolean oldTab = Setting.DISABLE_TABLIST.asBoolean();
        final float oldWater = Setting.NPC_WATER_SPEED_MODIFIER.asFloat();
        Actor alice, bob, far;
        NPC human, mirrored, pig, fish, waterHuman, walker, membershipMover;
        EntityHumanNPC originalHuman;
        double initialWaterY, initialWalkerX;
        int initialTicks, cursor;
        String phase;
        boolean isolated;

        State(MinecraftServer server) throws Exception {
            this.server = server; level = server.overworld();
            if (!Files.exists(Path.of("movement-list-audit-fixture.txt")) || CitizensAPI.getNPCRegistry().iterator().hasNext())
                throw new AssertionError("Movement/list audit requires an empty isolated fixture");
            isolated = true;
            Setting.REMOVE_PLAYERS_FROM_PLAYER_LIST.set(true); Setting.DISABLE_TABLIST.set(true);
            alice = actor("MovementAlice", 2.5, 2.5, "viewer-a"); bob = actor("MovementBob", 7.5, 2.5, "viewer-b");
            far = actor("MovementFar", 256, 256, "viewer-far");
            permissions.add(PermissionUtil.grantTemporary(alice.player, List.of("citizens.npc.swim", "citizens.npc.select",
                    "citizens.npc.playerlist", "citizens.npc.attribute")));
            for (int x = 16; x <= 30; x++) for (int z = 16; z <= 30; z++) {
                level.setBlock(new BlockPos(x, -61, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = -60; y <= -56; y++) level.setBlock(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState(), 3);
            }
            human = npc(EntityType.PLAYER, "ListPlayer"); mirrored = npc(EntityType.PLAYER, "MirrorPlayer");
            mirrored.getOrAddTrait(MirrorTrait.class).setEnabled(true); mirrored.getOrAddTrait(MirrorTrait.class).setMirrorName(true);
            spawn(human, 4.5, -60, 4.5); spawn(mirrored, 8.5, -60, 4.5); originalHuman = (EntityHumanNPC) human.getEntity();
            pig = npc(EntityType.PIG, "WaterPig"); fish = npc(EntityType.COD, "WaterFish"); waterHuman = npc(EntityType.PLAYER, "WaterPlayer");
            defaultsAndCommands();
            spawn(pig, 19.5, -59, 19.5); spawn(fish, 24.5, -59, 24.5); spawn(waterHuman, 27.5, -59, 19.5);
            waterHuman.data().setPersistent(NPC.Metadata.SWIM, true);
            walker = npc(EntityType.PLAYER, "Walker"); spawn(walker, 38.5, -60, 4.5);
            ((LivingEntity) walker.getEntity()).setHealth(6);
            initialWalkerX = walker.getEntity().getX(); walker.getEntity().setDeltaMovement(0.2, 0, 0);
            initialWaterY = waterHuman.getEntity().getY(); initialTicks = originalHuman.tickCount;
            script();
        }

        boolean next() throws Exception {
            for (Actor actor : actors) actor.pumpChunks();
            if (cursor == steps.size()) return false;
            Step step = steps.get(cursor++); phase = step.name; step.test.run(); return true;
        }
        void step(String name, Test test) { steps.add(new Step(name, test)); }
        void waitTicks(int ticks) { for (int i = 0; i < ticks; i++) step("wait_" + steps.size(), () -> { }); }

        void script() {
            waitTicks(12);
            step("initial_lists", () -> initialLists());
            step("swim_policy_and_step_height", () -> { swimming(); stepHeight(); });
            step("player_navigation", () -> {
                walker.getNavigator().setTarget(new Location(level, 44.5, -60, 4.5));
                check(walker.getNavigator().isNavigating(), "player_navigation_starts");
            });
            step("tab_visibility", () -> tabVisibility());
            waitTicks(4);
            step("delayed_visibility", () -> {
                check(Boolean.TRUE.equals(alice.listed.get(human.getEntity().getUUID())), "delayed_hide_respects_new_visible_override");
                check(!level.players().contains(human.getEntity()), "tab_visibility_does_not_change_world_membership");
                select(human); ok("npc playerlist -a");
                check(level.players().contains(human.getEntity()) && !human.shouldRemoveFromPlayerList(), "playerlist_add_changes_native_world_membership");
                SkinPacketTracker.setListed((EntityHumanNPC) human.getEntity(), false);
                check(level.players().contains(human.getEntity()) && human.shouldRemoveFromTabList(), "tab_hiding_keeps_world_membership");
                ok("npc playerlist -r");
                check(!level.players().contains(human.getEntity()) && level.getEntity(human.getEntity().getUUID()) == human.getEntity(), "playerlist_remove_keeps_live_entity");
                mirrorRefresh();
            });
            step("configuration_overrides", () -> configuration());
            waitTicks(2);
            step("membership_lifecycle", () -> {
                var moving = (EntityHumanNPC) membershipMover.getEntity();
                check(moving.getLastSectionPos().equals(net.minecraft.core.SectionPos.of(moving)), "included_player_npc_updates_chunk_index_after_movement");
                check(level.players().contains(moving), "included_player_npc_retains_world_membership_after_movement");
                membershipMover.destroy();
                level.players().add((EntityHumanNPC) human.getEntity());
                ((AbstractNPC) human).update();
                check(!level.players().contains(human.getEntity()), "live_policy_repairs_reintroduced_world_player_membership");
            });
            waitTicks(110);
            step("native_motion_and_respawn", () -> {
                check(walker.getEntity().getX() > initialWalkerX + 0.1, "player_velocity_produces_native_movement");
                check(walker.getEntity().getX() > 43 && !walker.getNavigator().isNavigating(), "player_navigation_reaches_native_target");
                check(((LivingEntity) walker.getEntity()).getHealth() == 6, "default_player_tick_does_not_auto_heal");
                check(((EntityHumanNPC) walker.getEntity()).getFoodData().getFoodLevel() == 20, "default_player_tick_does_not_drain_food");
                check(waterHuman.getEntity().getY() > initialWaterY + 0.2, "player_swim_moves_up_through_real_water");
                check(originalHuman.tickCount > initialTicks + 100, "removed_world_player_keeps_ticking_in_loaded_chunk");
                UUID id = human.getEntity().getUUID(); human.despawn();
                alice.pump(); check(!alice.listed.containsKey(id), "despawn_removes_client_profile");
                spawn(human, 4.5, -60, 4.5);
                check(human.getEntity() != originalHuman && !level.players().contains(human.getEntity()), "respawn_restores_world_list_policy");
            });
            waitTicks(10);
            step("respawn_profile", () -> {
                alice.pump(); check(Boolean.FALSE.equals(alice.listed.get(human.getEntity().getUUID())), "respawn_restores_hidden_tab_profile");
                check(((LivingEntity) human.getEntity()).getAttributeValue(Attributes.STEP_HEIGHT) == 1, "respawn_restores_player_step_height");
                var data = new MemoryDataKey(); human.saveSnapshot(data);
                check(data.getBoolean("metadata.removefromplayerlist") && data.getBoolean("metadata.removefromtablist"), "separate_list_overrides_persist");
            });
        }

        void defaultsAndCommands() throws Exception {
            check(!NPCSwimming.isEnabled(pig, level) && NPCSwimming.isEnabled(fish, level), "native_swim_defaults_before_spawn");
            fish.data().setPersistent(NPC.Metadata.USE_MINECRAFT_AI, true);
            check(!NPCSwimming.isEnabled(fish, level), "minecraft_ai_owns_default_aquatic_swimming"); fish.data().remove(NPC.Metadata.USE_MINECRAFT_AI);
            select(pig); ok("npc swim"); check(NPCSwimming.isEnabled(pig, level), "toggle_swim_uses_effective_land_default");
            select(fish); ok("npc swim"); check(!NPCSwimming.isEnabled(fish, level), "toggle_swim_uses_effective_aquatic_default");
            ok("npc swim --set true");
            var copy = fish.copy(); check(copy.data().<Boolean>get(NPC.Metadata.SWIM), "swim_override_survives_copy"); copy.destroy();
            bad("npc swim --set invalid"); bad("npc swim --unknown true");
            check(fish.data().<Boolean>get(NPC.Metadata.SWIM), "invalid_swim_flags_preserve_override");
            fish.getOrAddTrait(Owner.class).setOwner(bob.player.getUUID()); bad("npc swim --set false");
            check(fish.data().<Boolean>get(NPC.Metadata.SWIM), "swim_ownership_denial_preserves_override");
            fish.getOrAddTrait(Owner.class).setOwner(alice.player.getUUID());
        }

        void initialLists() {
            check(human.shouldRemoveFromPlayerList() && human.shouldRemoveFromTabList(), "reference_list_defaults_are_applied");
            check(!level.players().contains(human.getEntity()) && !server.getPlayerList().getPlayers().contains(human.getEntity()), "default_npc_absent_from_world_and_login_player_lists");
            check(level.players().contains(alice.player) && level.players().contains(bob.player), "real_players_remain_in_world_list");
            var viewers = level.getChunkSource().chunkMap.getPlayersWatching(human.getEntity());
            LoggerFactory.getLogger("citizens").info("[MOVEMENTLISTAUDIT] viewers={}, chunkView={}, pending={}",
                    viewers.size(), alice.player.getChunkTrackingView().contains(human.getEntity().chunkPosition().x, human.getEntity().chunkPosition().z),
                    alice.player.connection.chunkSender.isPending(human.getEntity().chunkPosition().toLong()));
            check(viewers.contains(alice.player), "fixture_has_actual_entity_tracking");
            alice.pump();
            check(Boolean.FALSE.equals(alice.listed.get(human.getEntity().getUUID())), "initial_profile_is_explicitly_unlisted");
            check(alice.profileBeforeSpawn(human.getEntity()), "npc_profile_precedes_player_entity_spawn");
            check(!far.listed.containsKey(human.getEntity().getUUID()), "out_of_range_player_has_no_npc_profile");
        }

        void swimming() throws Exception {
            check(pig.getEntity().isInWater() && fish.getEntity().isInWater() && waterHuman.getEntity().isInWater(), "native_water_flags_update_for_mob_and_player_npcs");
            impulse(pig, new Vec3(0.1, -0.2, 0.3));
            check(vectorClose(pig.getEntity().getDeltaMovement(), new Vec3(0.1, -0.16, 0.3)), "explicit_land_swim_adds_reference_impulse");
            impulse(pig, new Vec3(0.1, -0.2, 0.3), false);
            check(vectorClose(pig.getEntity().getDeltaMovement(), new Vec3(0.1, -0.2, 0.3)), "failed_buoyancy_chance_keeps_native_velocity");
            impulse(fish, Vec3.ZERO);
            check(vectorClose(fish.getEntity().getDeltaMovement(), new Vec3(0, 0.02, 0)), "aquatic_swim_uses_reference_lower_impulse");
            pig.data().setPersistent(NPC.Metadata.SWIM, false); impulse(pig, Vec3.ZERO);
            check(vectorClose(pig.getEntity().getDeltaMovement(), Vec3.ZERO), "disabled_swim_adds_no_impulse"); pig.data().setPersistent(NPC.Metadata.SWIM, true);
            pig.getOrAddTrait(Gravity.class).setHasGravity(false); impulse(pig, Vec3.ZERO);
            check(vectorClose(pig.getEntity().getDeltaMovement(), Vec3.ZERO), "idle_no_gravity_skips_buoyancy");
            Location up = new Location(level, 20.5, pig.getEntity().getY() + 2, 20.5);
            pig.getNavigator().setTarget(params -> new ControlledPath(up));
            Setting.NPC_WATER_SPEED_MODIFIER.set(1.5F); impulse(pig, new Vec3(0.1, -0.2, 0.3));
            check(vectorClose(pig.getEntity().getDeltaMovement(), new Vec3(0.15, -0.26, 0.45)), "navigating_swim_scales_motion_and_rises_despite_gravity_override");
            pig.data().setPersistent(NPC.Metadata.WATER_SPEED_MODIFIER, 2F);
            Location down = new Location(level, 20.5, pig.getEntity().getY() - 1, 20.5);
            pig.getNavigator().setTarget(params -> new ControlledPath(down)); impulse(pig, new Vec3(0.1, -0.2, 0.3));
            check(vectorClose(pig.getEntity().getDeltaMovement(), new Vec3(0.2, -0.4, 0.6)), "descending_path_uses_override_without_upward_impulse");
            Location same = new Location(level, 20.5, pig.getEntity().getY(), 20.5);
            pig.getNavigator().setTarget(params -> new ControlledPath(same)); impulse(pig, Vec3.ZERO);
            check(vectorClose(pig.getEntity().getDeltaMovement(), Vec3.ZERO), "level_path_does_not_add_upward_impulse");
            pig.getNavigator().setTarget(params -> new ControlledPath(up) {
                @Override public boolean update() { pig.getEntity().setDeltaMovement(0.1, -0.2, 0.3); return false; }
            });
            ((AbstractNPC) pig).update();
            // This assertion concerns navigation/scaling order; either native buoyancy roll is valid.
            // The exact triggered and untriggered impulses are independently checked above.
            Vec3 actual = pig.getEntity().getDeltaMovement();
            check(vectorClose(actual, new Vec3(0.2, -0.36, 0.6)) || vectorClose(actual, new Vec3(0.2, -0.4, 0.6)),
                    "navigation_cannot_overwrite_water_speed_adjustment");
            pig.getNavigator().cancelNavigation(); pig.getOrAddTrait(Gravity.class).setHasGravity(true);
            pig.data().remove(NPC.Metadata.WATER_SPEED_MODIFIER); Setting.NPC_WATER_SPEED_MODIFIER.set(oldWater);
            pig.getEntity().setDeltaMovement(Vec3.ZERO); fish.getEntity().setDeltaMovement(Vec3.ZERO);
        }

        void impulse(NPC npc, Vec3 start) throws Exception { impulse(npc, start, true); }

        void impulse(NPC npc, Vec3 start, boolean trigger) throws Exception {
            npc.getEntity().setDeltaMovement(start);
            // XORShiftRNG ignores Random.setSeed(long). Scope a known 160-bit state to this synchronous probe,
            // using its own reentrant lock and restoring every word afterward. No product RNG hook is needed.
            var random = Util.getFastRandom();
            var lockField = random.getClass().getDeclaredField("lock"); lockField.setAccessible(true);
            var lock = (java.util.concurrent.locks.ReentrantLock) lockField.get(random);
            java.lang.reflect.Field[] fields = new java.lang.reflect.Field[5]; int[] previous = new int[5];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = random.getClass().getDeclaredField("state" + (i + 1)); fields[i].setAccessible(true);
            }
            lock.lock();
            try {
                for (int i = 0; i < fields.length; i++) previous[i] = fields[i].getInt(random);
                try {
                    int[] state = {1, 2, 3, 4, trigger ? 5 : 8_200_000};
                    for (int i = 0; i < fields.length; i++) fields[i].setInt(random, state[i]);
                    if ((random.nextFloat() <= 0.85F) != trigger) throw new AssertionError("Invalid RNG fixture state");
                    for (int i = 0; i < fields.length; i++) fields[i].setInt(random, state[i]);
                    NPCSwimming.update(npc, npc.getEntity());
                } finally {
                    for (int i = 0; i < fields.length; i++) fields[i].setInt(random, previous[i]);
                }
            } finally { lock.unlock(); }
        }

        void stepHeight() {
            check(((LivingEntity) human.getEntity()).getAttributeValue(Attributes.STEP_HEIGHT) == 1, "default_player_step_height_is_one");
            for (EntityType<?> type : List.of(EntityType.HORSE, EntityType.DONKEY, EntityType.MULE, EntityType.LLAMA, EntityType.CAMEL)) {
                NPC horse = npc(type, "HorseStep"); spawn(horse, 45.5, -60, 12.5);
                check(((LivingEntity) horse.getEntity()).getAttributeValue(Attributes.STEP_HEIGHT) == 1, "horse_family_step_default_" + EntityType.getKey(type).getPath());
                horse.destroy();
            }
            NPC custom = npc(EntityType.PLAYER, "CustomStep"); custom.getOrAddTrait(AttributeTrait.class).setAttributeValue(Attributes.STEP_HEIGHT, 0.25);
            spawn(custom, 34.5, -60, 8.5);
            check(((LivingEntity) custom.getEntity()).getAttributeValue(Attributes.STEP_HEIGHT) == 0.25, "explicit_step_attribute_is_not_overwritten");
            custom.despawn(); spawn(custom, 34.5, -60, 8.5);
            check(((LivingEntity) custom.getEntity()).getAttributeValue(Attributes.STEP_HEIGHT) == 0.25, "step_attribute_override_survives_respawn");
            for (int x = 35; x <= 37; x++) level.setBlockAndUpdate(new BlockPos(x, -60, 8), Blocks.STONE.defaultBlockState());
            Entity entity = custom.getEntity(); entity.setOnGround(true); entity.move(MoverType.SELF, new Vec3(1, 0, 0));
            check(entity.getY() < -59.9 && entity.getX() < 35, "low_step_override_cannot_walk_one_block");
            custom.getOrAddTrait(AttributeTrait.class).setAttributeValue(Attributes.STEP_HEIGHT, 1);
            entity.setPos(34.5, -60, 8.5); entity.setOnGround(true); entity.move(MoverType.SELF, new Vec3(1, 0, 0));
            check(entity.getY() >= -59.01 && entity.getX() > 35, "one_block_step_height_changes_actual_collision_movement");
            custom.destroy();
        }

        void tabVisibility() {
            alice.clear(); SkinPacketTracker.respawn((EntityHumanNPC) human.getEntity()); alice.pump();
            check(alice.updates(human.getEntity().getUUID()).stream().allMatch(p -> p.entries().stream().allMatch(e -> !e.listed())), "skin_refresh_does_not_relist_hidden_npc");
            SkinPacketTracker.setListed((EntityHumanNPC) human.getEntity(), true); alice.pump();
            check(Boolean.TRUE.equals(alice.listed.get(human.getEntity().getUUID())), "explicit_tab_show_encodes_true");
            check(human.shouldRemoveFromPlayerList() && !human.shouldRemoveFromTabList(), "tab_override_is_independent_of_world_list_flag");
            var packet = alice.updates(human.getEntity().getUUID()).getLast();
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
            try {
                ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.encode(buffer, packet);
                var decoded = ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer);
                check(decoded.entries().getFirst().listed(), "listed_state_survives_wire_encoding");
            } finally { buffer.release(); }
        }

        void mirrorRefresh() {
            alice.clear(); bob.clear(); far.clear(); SkinPacketTracker.respawn((EntityHumanNPC) mirrored.getEntity());
            alice.pump(); bob.pump(); far.pump();
            var a = alice.updates(mirrored.getEntity().getUUID()).stream().filter(p -> p.actions().contains(Action.ADD_PLAYER)).findFirst().orElseThrow().entries().getFirst();
            var b = bob.updates(mirrored.getEntity().getUUID()).stream().filter(p -> p.actions().contains(Action.ADD_PLAYER)).findFirst().orElseThrow().entries().getFirst();
            check(a.profile().getName().equals(alice.player.getGameProfile().getName()) && b.profile().getName().equals(bob.player.getGameProfile().getName()), "mirror_refresh_keeps_per_viewer_names");
            check(a.profile().getProperties().get("textures").iterator().next().value().equals("viewer-a")
                    && b.profile().getProperties().get("textures").iterator().next().value().equals("viewer-b"), "mirror_refresh_keeps_per_viewer_skin_properties");
            check(far.updates(mirrored.getEntity().getUUID()).isEmpty(), "mirror_refresh_does_not_leak_profiles_to_untracked_players");
        }

        void configuration() {
            Setting.REMOVE_PLAYERS_FROM_PLAYER_LIST.set(false); Setting.DISABLE_TABLIST.set(false);
            NPC inherited = npc(EntityType.PLAYER, "InheritedLists");
            check(!inherited.shouldRemoveFromPlayerList() && !inherited.shouldRemoveFromTabList(), "global_false_defaults_are_read");
            spawn(inherited, 10.5, -60, 6.5);
            check(level.players().contains(inherited.getEntity()), "global_world_list_default_applies_on_spawn");
            SkinPacketTracker.sendTo((EntityHumanNPC) inherited.getEntity(), alice.player); alice.pump();
            check(Boolean.TRUE.equals(alice.listed.get(inherited.getEntity().getUUID())), "global_tab_default_is_visible_on_initial_profile");
            inherited.data().setPersistent(NPC.Metadata.REMOVE_FROM_PLAYERLIST, true);
            inherited.data().setPersistent(NPC.Metadata.REMOVE_FROM_TABLIST, true);
            ((AbstractNPC) inherited).update(); SkinPacketTracker.sendTo((EntityHumanNPC) inherited.getEntity(), alice.player); alice.pump();
            check(!level.players().contains(inherited.getEntity()) && Boolean.FALSE.equals(alice.listed.get(inherited.getEntity().getUUID())), "per_npc_true_overrides_global_false_defaults");
            inherited.data().setPersistent(NPC.Metadata.REMOVE_FROM_PLAYERLIST, false);
            inherited.data().setPersistent(NPC.Metadata.REMOVE_FROM_TABLIST, false);
            Setting.REMOVE_PLAYERS_FROM_PLAYER_LIST.set(true); Setting.DISABLE_TABLIST.set(true);
            ((AbstractNPC) inherited).update(); SkinPacketTracker.sendTo((EntityHumanNPC) inherited.getEntity(), alice.player); alice.pump();
            check(level.players().contains(inherited.getEntity()) && Boolean.TRUE.equals(alice.listed.get(inherited.getEntity().getUUID())), "per_npc_false_overrides_global_true_defaults");
            membershipMover = inherited;
            inherited.getEntity().setPos(34.5, -60, 6.5);
        }

        NPC npc(EntityType<?> type, String name) {
            NPC result = CitizensAPI.getNPCRegistry().createNPC(type, name); created.add(result);
            result.getOrAddTrait(Owner.class).setOwner(alice.player.getUUID()); result.getOrAddTrait(Spawned.class).setSpawned(false);
            if (type == EntityType.PLAYER) result.getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false);
            return result;
        }
        void spawn(NPC npc, double x, double y, double z) { check(npc.spawn(new Location(level, x, y, z)), "spawn_" + npc.getName()); }
        void select(NPC npc) { CitizensAPI.getDefaultNPCSelector().select(source(), npc); }
        CommandSourceStack source() { return alice.player.createCommandSourceStack().withPermission(0); }
        void ok(String command) throws Exception {
            try { if (server.getCommands().getDispatcher().execute(command, source()) <= 0) throw new AssertionError(command); }
            catch (CommandSyntaxException failure) { throw new AssertionError(command, failure); }
        }
        void bad(String command) throws Exception {
            try { server.getCommands().getDispatcher().execute(command, source()); }
            catch (CommandSyntaxException expected) { return; }
            throw new AssertionError("Unexpected success: " + command);
        }
        Actor actor(String name, double x, double z, String texture) {
            var profile = new GameProfile(UUID.randomUUID(), name); profile.getProperties().put("textures", new Property("textures", texture));
            var player = new ServerPlayer(server, level, profile, ClientInformation.createDefault()); player.setPos(x, -60, z);
            Actor actor = new Actor(player); actors.add(actor); Connection connection = new Connection(PacketFlow.SERVERBOUND);
            actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {
                    connection.configurePacketHandler(channel.pipeline());
                    channel.pipeline().addLast("movement-list-audit", new ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                            actor.capture(message); super.write(context, message, promise);
                        }
                    });
                }
            });
            NetworkRegistry.configureMockConnection(connection);
            var cookie = new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
            server.getPlayerList().placeNewPlayer(connection, player, cookie); return actor;
        }
        void close() {
            permissions.forEach(PermissionUtil.Attachment::remove);
            Setting.REMOVE_PLAYERS_FROM_PLAYER_LIST.set(oldWorldList); Setting.DISABLE_TABLIST.set(oldTab); Setting.NPC_WATER_SPEED_MODIFIER.set(oldWater);
            if (isolated) CitizensAPI.getNPCRegistry().deregisterAll();
            for (Actor actor : actors) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
        }
    }

    private static final class Actor {
        final ServerPlayer player;
        EmbeddedChannel channel;
        final List<Object> packets = new ArrayList<>();
        final Map<UUID, Boolean> listed = new HashMap<>();
        int pendingBatchAcks;
        Actor(ServerPlayer player) { this.player = player; }
        void capture(Object message) {
            if (message instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(message);
            if (message instanceof ClientboundChunkBatchFinishedPacket) pendingBatchAcks++;
            if (message instanceof ClientboundPlayerInfoUpdatePacket update) for (var entry : update.entries()) {
                if (update.actions().contains(Action.ADD_PLAYER)) listed.putIfAbsent(entry.profileId(), false);
                if (update.actions().contains(Action.UPDATE_LISTED)) listed.put(entry.profileId(), entry.listed());
            }
            else if (message instanceof ClientboundPlayerInfoRemovePacket remove) remove.profileIds().forEach(listed::remove);
        }
        void pumpChunks() {
            player.connection.chunkSender.sendNextChunks(player);
            pump();
            while (pendingBatchAcks > 0) {
                pendingBatchAcks--;
                player.connection.chunkSender.onChunkBatchReceivedByClient(64);
            }
            player.serverLevel().getChunkSource().move(player);
        }
        void pump() { channel.runPendingTasks(); }
        void clear() { pump(); packets.clear(); }
        List<ClientboundPlayerInfoUpdatePacket> updates(UUID id) {
            pump(); return packets.stream().filter(ClientboundPlayerInfoUpdatePacket.class::isInstance)
                    .map(ClientboundPlayerInfoUpdatePacket.class::cast).filter(p -> p.entries().stream().anyMatch(e -> e.profileId().equals(id))).toList();
        }
        boolean profileBeforeSpawn(Entity entity) {
            pump(); int profile = -1, spawn = -1;
            for (int i = 0; i < packets.size(); i++) {
                Object packet = packets.get(i);
                if (profile == -1 && packet instanceof ClientboundPlayerInfoUpdatePacket info && info.actions().contains(Action.ADD_PLAYER)
                        && info.entries().stream().anyMatch(e -> e.profileId().equals(entity.getUUID()))) profile = i;
                if (spawn == -1 && packet instanceof ClientboundAddEntityPacket add && add.getId() == entity.getId()) spawn = i;
            }
            return profile >= 0 && spawn > profile;
        }
    }
    private static class ControlledPath implements PathStrategy {
        final Location destination;
        ControlledPath(Location destination) { this.destination = destination; }
        public void clearCancelReason() { }
        public CancelReason getCancelReason() { return null; }
        public Location getCurrentDestination() { return destination; }
        public Iterable<Vec3> getPath() { return List.of(); }
        public Location getTargetAsLocation() { return destination; }
        public TargetType getTargetType() { return TargetType.LOCATION; }
        public void stop() { }
        public boolean update() { return false; }
    }
    private static boolean vectorClose(Vec3 actual, Vec3 expected) { return actual.distanceToSqr(expected) < 1.0E-10; }
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("citizens").info("[MOVEMENTLISTAUDIT] PASS {}", name);
    }
}
