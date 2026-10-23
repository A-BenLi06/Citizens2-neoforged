package net.citizensnpcs.audit;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;
import tallestegg.guardvillagers.GuardVillagerTags;
import tallestegg.guardvillagers.common.entities.Guard;
import tallestegg.guardvillagers.configuration.GuardConfig;

/** Native world/player and Citizens store restart; never constructs guards from saved test NBT. */
public final class GuardRestartRuntimeAudit {
    private static int stage, passed, movementStart;
    private static JsonObject state, result;
    private static ServerPlayer actor;
    private static EmbeddedChannel channel;
    private static Guard follower, patroller;
    private static double followDistance, patrolDistance;
    private static boolean followRan, patrolRan;

    public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer(); var level = server.overworld();
        if (stage == 99) return;
        level.setChunkForced(0, 0, true);
        if (server.getTickCount() < 20 || !level.isPositionEntityTicking(new BlockPos(1,-60,1))) return;
        try {
            if (stage == 0) {
                stage = 1;
                if ("restart-seed".equals(System.getenv("CITIZENS_GUARD_AUDIT_PHASE"))) seed(server, level);
                else recover(server, level);
                return;
            }
            followRan |= running(follower, Guard.FollowHeroGoal.class);
            patrolRan |= running(patroller, Guard.WalkBackToCheckPointGoal.class);
            var anchor = patroller.getPatrolPos();
            if ((!followRan || !patrolRan || follower.distanceToSqr(actor) >= followDistance/2
                    || patroller.blockPosition().distSqr(anchor) >= patrolDistance/2)
                    && server.getTickCount()-movementStart < 420) return;
            check(followRan && follower.distanceToSqr(actor) < followDistance/2, "restarted_native_follow_goal_moves_original_guard_toward_reconnected_owner");
            check(patrolRan && patroller.blockPosition().distSqr(anchor) < patrolDistance/2, "restarted_native_patrol_goal_moves_original_guard_toward_saved_anchor");
            check(snapshot(follower).equals(state.get("follower")) && snapshot(patroller).equals(state.get("patroller")),
                    "native_post_restart_movement_preserves_guard_identity_owner_modes_and_complete_inventory");
            GuardRuntimeAudit.follow(actor, follower);
            check(!follower.isFollowing(), "restarted_follow_payload_toggles_original_guard_off");
            GuardRuntimeAudit.patrol(actor, patroller, true);
            check(!patroller.isPatrolling() && patroller.getPatrolPos() == null, "restarted_patrol_payload_clears_original_guard_anchor");
            check(follower.interact(actor,InteractionHand.MAIN_HAND) == net.minecraft.world.InteractionResult.SUCCESS
                    && actor.containerMenu instanceof tallestegg.guardvillagers.common.entities.GuardContainer,
                    "reconnected_owner_opens_saved_guard_native_equipment_menu");
            actor.closeContainer();
            result.add("finalFollower", snapshot(follower)); result.add("finalPatroller", snapshot(patroller));
            result.addProperty("recoveryChecks",passed);
            persist(server);
            write(server.getServerDirectory().resolve("guard-restart-recovery.json"),result);
            complete(server,"restart-recover");
        } catch(Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[GUARDAUDIT] FAILED restart",failure); finish(server);
        }
    }

    private static void seed(MinecraftServer server, ServerLevel level) throws Exception {
        check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "restart_seed_has_fresh_citizens_store");
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        actor = new ServerPlayer(server,level,new GameProfile(UUID.randomUUID(),"GuardRestart"),ClientInformation.createDefault());
        actor.setPos(12,-60,5); channel = GuardRuntimeAudit.connect(actor);
        actor.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE,1200,0));
        var profession = BuiltInRegistries.VILLAGER_PROFESSION.stream()
                .filter(value -> GuardConfig.COMMON.convertibleProfessions.get().contains(value.name())).findFirst().orElseThrow();
        var weapon = BuiltInRegistries.ITEM.stream().map(ItemStack::new).filter(stack -> stack.is(GuardVillagerTags.GUARD_CONVERT)).findFirst().orElseThrow();
        weapon.set(DataComponents.CUSTOM_NAME,Component.literal("Restart recruitment weapon"));
        follower = recruit(level,profession,weapon,new BlockPos(3,-60,8),"Restart follower");
        patroller = recruit(level,profession,weapon,new BlockPos(11,-60,8),"Restart patroller");
        var armour = new ItemStack(Items.IRON_CHESTPLATE); armour.set(DataComponents.CUSTOM_NAME,Component.literal("Restart guard armour")); armour.setDamageValue(9);
        follower.setItemSlot(EquipmentSlot.CHEST,armour.copy()); patroller.setItemSlot(EquipmentSlot.CHEST,armour.copy());
        var shield = new ItemStack(Items.SHIELD); shield.set(DataComponents.CUSTOM_NAME,Component.literal("Restart guard shield"));
        follower.setItemSlot(EquipmentSlot.OFFHAND,shield.copy()); patroller.setItemSlot(EquipmentSlot.OFFHAND,shield.copy());
        GuardRuntimeAudit.follow(actor,follower);
        GuardRuntimeAudit.follow(actor,patroller); GuardRuntimeAudit.follow(actor,patroller);
        GuardRuntimeAudit.patrol(actor,patroller,false);
        check(follower.isFollowing() && !follower.isPatrolling() && !patroller.isFollowing()
                && patroller.isPatrolling() && patroller.getPatrolPos().equals(patroller.blockPosition()),
                "native_payloads_configure_distinct_persistent_follow_and_patrol_modes");
        state = new JsonObject(); state.addProperty("actor",actor.getUUID().toString());
        state.addProperty("profession",BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).toString());
        state.add("weapon",stack(weapon,server));
        var npcs = new JsonArray();
        for (int index=0; index<5; index++) {
            boolean villager=index<3, ai=index==1 || index==4, packet=index==2;
            var npc=CitizensAPI.getNPCRegistry().createNPC(villager?EntityType.VILLAGER:EntityType.IRON_GOLEM,"Restart NPC "+index);
            npc.setUseMinecraftAI(ai);
            if(packet) npc.getOrAddTrait(PacketNPC.class);
            if(villager) npc.getOrAddTrait(net.citizensnpcs.trait.VillagerProfession.class).setProfession(profession);
            check(npc.spawn(new Location(level,3+index*2,-60,3)),"persistent_npc_spawn_"+index);
            npcs.add(npcSnapshot(npc));
        }
        state.add("npcs",npcs); state.add("follower",snapshot(follower)); state.add("patroller",snapshot(patroller));
        check(NPCRegistries.lookup(follower)==null && NPCRegistries.lookup(patroller)==null, "recruited_guards_remain_native_provider_owned");
        state.addProperty("seedChecks",passed);
        persist(server);
        write(server.getServerDirectory().resolve("guard-restart-state.json"),state);
        complete(server,"restart-seed");
    }

    private static Guard recruit(ServerLevel level, VillagerProfession profession, ItemStack weapon, BlockPos pos, String name) {
        var villager=new Villager(EntityType.VILLAGER,level); villager.setVillagerData(villager.getVillagerData().setProfession(profession));
        villager.setPos(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5); villager.setCustomName(Component.literal(name)); level.addFreshEntity(villager);
        var before=new HashSet<UUID>(); for(var entity:level.getAllEntities()) if(entity instanceof Guard) before.add(entity.getUUID());
        actor.setShiftKeyDown(true); actor.setPose(Pose.CROUCHING); actor.setItemInHand(InteractionHand.MAIN_HAND,weapon.copy());
        GuardRuntimeAudit.interact(actor,villager);
        var recruited=new ArrayList<Guard>(); for(var entity:level.getAllEntities()) if(entity instanceof Guard guard && !before.contains(guard.getUUID())) recruited.add(guard);
        check(villager.isRemoved() && actor.getMainHandItem().isEmpty() && recruited.size()==1, "native_restart_recruitment_"+name);
        var guard=recruited.getFirst(); guard.setPersistenceRequired();
        check(ItemStack.matches(guard.getMainHandItem(),weapon), "native_recruitment_retains_complete_weapon_"+name);
        actor.setShiftKeyDown(false); actor.setPose(Pose.STANDING);
        return guard;
    }

    private static void recover(MinecraftServer server, ServerLevel level) throws Exception {
        state=JsonParser.parseString(Files.readString(server.getServerDirectory().resolve("guard-restart-state.json"))).getAsJsonObject();
        follower=guard(level,state.getAsJsonObject("follower")); patroller=guard(level,state.getAsJsonObject("patroller"));
        check(server.getPlayerList().getPlayer(UUID.fromString(state.get("actor").getAsString()))==null
                && follower.getOwner()==null && patroller.getOwner()==null, "fresh_process_restores_guards_before_owner_reconnects");
        check(snapshot(follower).equals(state.get("follower")) && snapshot(patroller).equals(state.get("patroller")),
                "native_world_restores_exact_guard_uuids_owners_modes_components_and_all_inventory_slots");
        check(NPCRegistries.lookup(follower)==null && NPCRegistries.lookup(patroller)==null
                && !follower.goalSelector.getAvailableGoals().isEmpty() && !patroller.goalSelector.getAvailableGoals().isEmpty(),
                "restored_guards_keep_native_provider_goals_and_entity_ownership");
        long count=0; for(var entity:level.getAllEntities()) if(entity instanceof Guard) count++;
        check(count==2,"native_world_restores_each_original_recruited_guard_once");
        var npcs=new ArrayList<NPC>();
        for(var value:state.getAsJsonArray("npcs")) {
            var saved=value.getAsJsonObject(); var npc=CitizensAPI.getNPCRegistry().getByUniqueId(UUID.fromString(saved.get("uuid").getAsString()));
            check(npc!=null && npc.isSpawned() && npcSnapshot(npc).equals(saved),"citizens_store_restores_original_npc_identity_traits_and_entity_"+saved.get("id").getAsInt());
            var mob=(Mob)npc.getEntity();
            check(npc.useMinecraftAI() ? !mob.goalSelector.getAvailableGoals().isEmpty()
                    : mob.goalSelector.getAvailableGoals().isEmpty() && mob.targetSelector.getAvailableGoals().isEmpty(),
                    "restart_spawn_hook_preserves_explicit_ai_ownership_"+npc.getId());
            check(npc.hasTrait(PacketNPC.class) ? level.getEntity(mob.getUUID())==null : level.getEntity(mob.getUUID())==mob,
                    "restart_preserves_world_or_packet_entity_backend_"+npc.getId());
            npcs.add(npc);
        }
        int npcCount=0; for(var npc:CitizensAPI.getNPCRegistry()) npcCount++;
        check(npcCount==5,"restart_restores_no_duplicate_citizens_records");
        actor=new ServerPlayer(server,level,new GameProfile(UUID.fromString(state.get("actor").getAsString()),"GuardRestart"),ClientInformation.createDefault());
        actor.setPos(12,-60,5); channel=GuardRuntimeAudit.connect(actor); actor.setPos(12,-60,5);
        actor.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE,1200,0));
        check(follower.getOwner()==actor && patroller.getOwner()==actor && follower.isOwner(actor) && !follower.canAttack(actor),
                "native_restored_owner_uuid_resolves_to_current_reconnected_player");
        var weapon=ItemStack.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),state.get("weapon")).getOrThrow();
        actor.setShiftKeyDown(true); actor.setPose(Pose.CROUCHING);
        for(var npc:npcs) if(npc.getEntity() instanceof Villager villager) {
            actor.setItemInHand(InteractionHand.MAIN_HAND,weapon.copy());
            var clicks=new int[]{0}; var listener=new Object() {
                @SubscribeEvent public void click(net.citizensnpcs.api.event.NPCRightClickEvent event) { if(event.getNPC()==npc) clicks[0]++; }
            };
            NeoForge.EVENT_BUS.register(listener);
            try { GuardRuntimeAudit.interact(actor,villager); } finally { NeoForge.EVENT_BUS.unregister(listener); }
            check(!villager.isRemoved() && npc.getEntity()==villager && NPCRegistries.lookup(villager)==npc
                    && ItemStack.matches(actor.getMainHandItem(),weapon) && clicks[0]==1,
                    "restarted_villager_preserves_npc_click_identity_and_weapon_without_native_conversion_"+npc.getId());
        }
        actor.setShiftKeyDown(false); actor.setPose(Pose.STANDING); actor.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        var ownedGolem=(Mob)npcs.get(3).getEntity(); var optedGolem=(Mob)npcs.get(4).getEntity();
        var victim=(Villager)npcs.get(1).getEntity(); npcs.get(1).getOrAddTrait(net.citizensnpcs.trait.TargetableTrait.class).setTargetable(true);
        var attacker=new net.minecraft.world.entity.monster.Zombie(EntityType.ZOMBIE,level); attacker.setPos(10,-60,1); level.addFreshEntity(attacker);
        follower.setTarget(null); patroller.setTarget(null); ownedGolem.setTarget(null); optedGolem.setTarget(null);
        attacker.setTarget(victim);
        check((follower.getTarget()==attacker || patroller.getTarget()==attacker) && ownedGolem.getTarget()==null
                && optedGolem.getTarget()==attacker,"restart_assistance_preserves_native_guard_owned_golem_and_opted_ai_boundaries");
        attacker.setTarget(null); attacker.discard(); follower.setTarget(null); patroller.setTarget(null); optedGolem.setTarget(null);
        result=state.deepCopy(); result.add("restoredFollower",snapshot(follower)); result.add("restoredPatroller",snapshot(patroller));
        actor.setPos(12,-60,12); follower.getNavigation().stop(); follower.setPos(3,-60,12);
        var anchor=patroller.getPatrolPos(); patroller.getNavigation().stop(); patroller.setPos(anchor.getX()-7,-60,anchor.getZ());
        followDistance=follower.distanceToSqr(actor); patrolDistance=patroller.blockPosition().distSqr(anchor);
        movementStart=server.getTickCount();
    }

    private static Guard guard(ServerLevel level, JsonObject saved) {
        if (!(level.getEntity(UUID.fromString(saved.get("uuid").getAsString())) instanceof Guard guard)) throw new AssertionError("Saved native guard missing");
        return guard;
    }
    private static boolean running(Guard guard, Class<?> type) { return guard.goalSelector.getAvailableGoals().stream().anyMatch(goal->goal.isRunning() && type.isInstance(goal.getGoal())); }
    private static JsonObject npcSnapshot(NPC npc) {
        var value=new JsonObject(); value.addProperty("id",npc.getId()); value.addProperty("uuid",npc.getUniqueId().toString());
        value.addProperty("entityUuid",npc.getEntity().getUUID().toString()); value.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(npc.getEntity().getType()).toString());
        value.addProperty("nativeAI",npc.useMinecraftAI()); value.addProperty("packet",npc.hasTrait(PacketNPC.class));
        if(npc.getEntity() instanceof Villager villager) value.addProperty("profession",BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession()).toString());
        return value;
    }
    private static JsonObject snapshot(Guard guard) {
        var value=new JsonObject(); value.addProperty("uuid",guard.getUUID().toString()); value.addProperty("owner",guard.getOwnerId().toString());
        value.addProperty("name",guard.getCustomName().getString()); value.addProperty("following",guard.isFollowing()); value.addProperty("patrolling",guard.isPatrolling());
        var anchor=guard.getPatrolPos(); if(anchor==null) value.add("anchor",JsonNull.INSTANCE); else value.add("anchor",new Gson().toJsonTree(List.of(anchor.getX(),anchor.getY(),anchor.getZ())));
        var inventory=new JsonArray(); for(int slot=0;slot<guard.guardInventory.getContainerSize();slot++) inventory.add(stack(guard.guardInventory.getItem(slot),guard.getServer()));
        value.add("inventory",inventory); return value;
    }
    private static JsonElement stack(ItemStack stack, MinecraftServer server) { return ItemStack.OPTIONAL_CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),stack).getOrThrow(); }
    private static void persist(MinecraftServer server) { CitizensAPI.getNPCRegistry().saveToStore(); server.getPlayerList().saveAll(); server.saveEverything(true,true,true); }
    private static void write(Path path, JsonObject value) throws Exception {
        try(var file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) { var bytes=StandardCharsets.UTF_8.encode(value.toString()); while(bytes.hasRemaining()) file.write(bytes); file.force(true); }
    }
    private static void check(boolean condition,String name) { if(!condition) throw new AssertionError(name); passed++; LoggerFactory.getLogger("citizens").info("[GUARDRESTART] PASS {}",name); }
    private static void complete(MinecraftServer server,String phase) { LoggerFactory.getLogger("citizens").info("[GUARDRESTART] COMPLETE {} checks phase={}",passed,phase); LoggerFactory.getLogger("citizens").info("[GUARDAUDIT] COMPLETE restart"); finish(server); }
    private static void finish(MinecraftServer server) {
        stage=99;
        if(actor!=null && actor.connection!=null) server.getPlayerList().remove(actor);
        if(channel!=null) channel.finishAndReleaseAll();
        server.halt(false);
    }
}
