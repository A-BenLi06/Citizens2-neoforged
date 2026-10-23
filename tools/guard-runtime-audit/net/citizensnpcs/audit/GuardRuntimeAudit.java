package net.citizensnpcs.audit;

import java.util.*;
import com.mojang.authlib.GameProfile;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.npc.NPCRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.effect.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import tallestegg.guardvillagers.*;
import tallestegg.guardvillagers.common.entities.Guard;
import tallestegg.guardvillagers.configuration.GuardConfig;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid="citizens")
public final class GuardRuntimeAudit {
    private static boolean ran;
    private static int passed;
    private static final List<NPC> npcs=new ArrayList<>();
    private static ServerPlayer actorState;
    private static EmbeddedChannel channelState;
    private static Guard movingGuard;
    private static int movementStart;
    private static double originalDistance;
    private static boolean followGoalRan;
    private static boolean patrolGoalRan;
    private static boolean patrolPhase;
    private static BlockPos patrolAnchor;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var phase = System.getenv("CITIZENS_GUARD_AUDIT_PHASE");
        if (phase != null && phase.startsWith("restart-")) { GuardRestartRuntimeAudit.tick(event); return; }
        var server=event.getServer(); var level=server.overworld(); level.setChunkForced(0,0,true);
        if(ran) {
            if(movingGuard!=null) movement(server,level);
            return;
        }
        if(server.getTickCount()<20 || !level.isPositionEntityTicking(new BlockPos(1,-60,1))) return;
        ran=true;
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,server);
        // Retained failed runs may have saved attackers; every acceptance run starts with an isolated arena.
        var retainedMobs=new ArrayList<Mob>();
        for(var entity:level.getAllEntities()) if(entity instanceof Mob mob) retainedMobs.add(mob);
        for(var mob:retainedMobs) mob.discard();
        var actor=new ServerPlayer(server,level,new GameProfile(UUID.randomUUID(),"GuardActor"),ClientInformation.createDefault()); actor.setPos(1,-60,1);
        var channel=connect(actor);
        actorState=actor; channelState=channel;
        try {
            var profession=BuiltInRegistries.VILLAGER_PROFESSION.stream()
                    .filter(value->GuardConfig.COMMON.convertibleProfessions.get().contains(value.name())).findFirst().orElseThrow();
            var weapon=BuiltInRegistries.ITEM.stream().map(ItemStack::new).filter(stack->stack.is(GuardVillagerTags.GUARD_CONVERT)).findFirst().orElseThrow();
            actor.setShiftKeyDown(true); actor.setPose(Pose.CROUCHING);
            actor.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE,200,0));
            var vanilla=new Villager(EntityType.VILLAGER,level); vanilla.setVillagerData(vanilla.getVillagerData().setProfession(profession));
            vanilla.setPos(3,-60,1); vanilla.setCustomName(Component.literal("Guard recruit")); level.addFreshEntity(vanilla);
            actor.setItemSlot(EquipmentSlot.MAINHAND,weapon.copy());
            var initialGuards=guards(level); interact(actor,vanilla);
            var recruited=guards(level).stream().filter(guard->!initialGuards.contains(guard)).findFirst().orElseThrow();
            check(vanilla.isRemoved() && actor.getMainHandItem().isEmpty(),"native_recruitment_consumes_weapon_and_replaces_ordinary_villager");
            check(recruited.getMainHandItem().is(weapon.getItem()) && recruited.getCustomName().getString().equals("Guard recruit"),"native_recruitment_preserves_weapon_and_name");
            var npc=CitizensAPI.getNPCRegistry().createNPC(EntityType.VILLAGER,"Citizens villager"); npcs.add(npc);
            check(npc.spawn(new Location(level,5,-60,1)),"citizens_villager_spawn");
            var entity=(Villager)npc.getEntity(); entity.setVillagerData(entity.getVillagerData().setProfession(profession));
            actor.setItemSlot(EquipmentSlot.MAINHAND,weapon.copy());
            LoggerFactory.getLogger("citizens").info("[GUARDAUDIT] Citizens spawn goals={}, targets={}",entity.goalSelector.getAvailableGoals().size(),entity.targetSelector.getAvailableGoals().size());
            boolean reproduction=Boolean.getBoolean("citizens.guard.audit.reproduction");
            var before=guards(level); interact(actor,entity);
            if(reproduction) {
                check(entity.isRemoved() && guards(level).size()==before.size()+1 && actor.getMainHandItem().isEmpty(),
                        "reproduced_guard_recruitment_discards_citizens_owned_villager");
                LoggerFactory.getLogger("citizens").info("[GUARDAUDIT] COMPLETE {} checks (reproduction)",passed); return;
            }
            check(!entity.isRemoved() && npc.getEntity()==entity && NPCRegistries.lookup(entity)==npc
                    && guards(level).size()==before.size() && ItemStack.matches(actor.getMainHandItem(),weapon),"citizens_owned_villager_cannot_be_recruited_or_consume_weapon");
            check(entity.goalSelector.getAvailableGoals().isEmpty() && entity.targetSelector.getAvailableGoals().isEmpty(),"guard_spawn_hook_respects_citizens_ai_ownership");
            coexist(actor,level,recruited,profession,weapon);
            recruited.setTarget(null); recruited.setPatrolling(false); recruited.setPatrolPos(null); recruited.setFollowing(false);
            recruited.setPos(2,-60,5); actor.setPos(12,-60,5);
            follow(actor,recruited);
            check(recruited.isFollowing() && recruited.getOwnerId().equals(actor.getUUID()),"native_follow_payload_assigns_owner_and_starts_following");
            movingGuard=recruited; movementStart=server.getTickCount(); originalDistance=recruited.distanceToSqr(actor);
        } catch(Throwable failure) { LoggerFactory.getLogger("citizens").error("[GUARDAUDIT] FAILED",failure); }
        finally {
            if(movingGuard==null) cleanup(server,level);
        }
    }
    private static void coexist(ServerPlayer actor,ServerLevel level,Guard recruited,VillagerProfession profession,ItemStack weapon) {
        var registry=CitizensAPI.createNamedNPCRegistry("guard-audit",new net.citizensnpcs.api.npc.MemoryNPCDataStore());
        try {
            var npc=registry.createNPC(EntityType.VILLAGER,"Named packet villager"); npcs.add(npc);
            npc.getOrAddTrait(net.citizensnpcs.trait.PacketNPC.class);
            check(npc.spawn(new Location(level,6,-60,1)),"named_packet_npc_spawn");
            var packet=(Villager)npc.getEntity(); packet.setVillagerData(packet.getVillagerData().setProfession(profession));
            actor.setItemSlot(EquipmentSlot.MAINHAND,weapon.copy()); int count=guards(level).size();
            interact(actor,packet);
            check(!packet.isRemoved() && level.getEntity(packet.getUUID())==null && guards(level).size()==count
                    && ItemStack.matches(actor.getMainHandItem(),weapon),"named_packet_villager_ownership_prevents_conversion");
            var ai=registry.createNPC(EntityType.VILLAGER,"Native AI villager"); npcs.add(ai); ai.setUseMinecraftAI(true);
            check(ai.spawn(new Location(level,7,-60,1)),"opted_native_ai_villager_spawn");
            var aiEntity=(Villager)ai.getEntity(); aiEntity.setVillagerData(aiEntity.getVillagerData().setProfession(profession));
            check(!aiEntity.goalSelector.getAvailableGoals().isEmpty(),"explicit_native_ai_keeps_provider_goals");
            actor.setItemSlot(EquipmentSlot.MAINHAND,weapon.copy());
            var observed=new int[]{0}; var listener=new Object() {
                @SubscribeEvent public void click(net.citizensnpcs.api.event.NPCRightClickEvent event) {
                    if(event.getNPC()==ai) observed[0]++;
                }
            };
            NeoForge.EVENT_BUS.register(listener);
            try { var interaction=new PlayerInteractEvent.EntityInteract(actor,InteractionHand.MAIN_HAND,aiEntity); NeoForge.EVENT_BUS.post(interaction);
                check(observed[0]==1 && !interaction.isCanceled(),"ordinary_npc_click_event_preserved_without_blanket_cancellation");
                check(!aiEntity.isRemoved() && ItemStack.matches(actor.getMainHandItem(),weapon),"native_ai_opt_in_does_not_relinquish_npc_identity"); }
            finally { NeoForge.EVENT_BUS.unregister(listener); }
            actor.setShiftKeyDown(false); actor.setPose(Pose.STANDING); actor.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
            Mob ownedGolem=null;
            for(var type:List.of(EntityType.IRON_GOLEM,EntityType.ZOMBIE)) {
                var owned=registry.createNPC(type,"Owned "+EntityType.getKey(type)); npcs.add(owned);
                check(owned.spawn(new Location(level,8,-60,1)),"owned_mob_spawn_"+EntityType.getKey(type));
                var mob=(Mob)owned.getEntity();
                if(type==EntityType.IRON_GOLEM) ownedGolem=mob;
                check(mob.goalSelector.getAvailableGoals().isEmpty() && mob.targetSelector.getAvailableGoals().isEmpty(),
                        "provider_goal_injection_skipped_for_"+EntityType.getKey(type));
            }
            var attacker=new net.minecraft.world.entity.monster.Zombie(EntityType.ZOMBIE,level); attacker.setPos(10,-60,1); level.addFreshEntity(attacker);
            ai.getOrAddTrait(net.citizensnpcs.trait.TargetableTrait.class).setTargetable(true);
            recruited.setTarget(null); attacker.setTarget(aiEntity);
            check(recruited.getTarget()==attacker,"native_guard_assistance_keeps_targeted_villager_workflow");
            check(ownedGolem.getTarget()==null,"provider_assistance_does_not_assign_citizens_owned_golem_target");
            ownedGolem.setTarget(recruited);
            check(ownedGolem.getTarget()==recruited,"provider_does_not_clear_explicit_owned_golem_target");
            ownedGolem.setTarget(null);
            recruited.setTarget(null); attacker.setTarget(null);
            ai.setProtected(false); aiEntity.setInvulnerable(false); aiEntity.invulnerableTime=0;
            aiEntity.hurt(level.damageSources().mobAttack(attacker),2);
            check(recruited.getTarget()==attacker && ownedGolem.getTarget()==null,"hurt_assistance_preserves_native_guard_and_owned_golem_boundaries");
            recruited.setTarget(null);
            var optedGolem=registry.createNPC(EntityType.IRON_GOLEM,"Native AI golem"); npcs.add(optedGolem); optedGolem.setUseMinecraftAI(true);
            check(optedGolem.spawn(new Location(level,8,-60,2)),"native_ai_golem_spawn");
            attacker.setTarget(aiEntity);
            check(((Mob)optedGolem.getEntity()).getTarget()==attacker,"native_ai_golem_can_receive_provider_assistance");
            recruited.setTarget(null); attacker.setTarget(null); attacker.discard();
            var equipment=new ItemStack(Items.IRON_CHESTPLATE); equipment.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("Guard equipment"));
            recruited.setItemSlot(EquipmentSlot.CHEST,equipment); recruited.setOwnerId(actor.getUUID()); recruited.setFollowing(true);
            recruited.setPatrolling(true); recruited.setPatrolPos(new BlockPos(10,-60,10));
            check(recruited.isOwner(actor) && recruited.getOwner()==actor && !recruited.canAttack(actor),"native_guard_owner_and_target_exclusion");
            var saved=new net.minecraft.nbt.CompoundTag(); recruited.saveWithoutId(saved);
            var loaded=GuardEntityType.GUARD.get().create(level); loaded.load(saved);
            check(loaded.getOwnerId().equals(actor.getUUID()) && loaded.isFollowing() && loaded.isPatrolling()
                    && loaded.getPatrolPos().equals(new BlockPos(10,-60,10)) && ItemStack.matches(loaded.getItemBySlot(EquipmentSlot.CHEST),equipment),
                    "native_guard_equipment_follow_patrol_owner_save_load");
            check(NPCRegistries.lookup(recruited)==null && !recruited.goalSelector.getAvailableGoals().isEmpty(),"native_guards_keep_provider_combat_ownership");
            var target=registry.createNPC(EntityType.ZOMBIE,"Protected target"); npcs.add(target);
            check(target.spawn(new Location(level,9,-60,1)),"protected_target_spawn");
            var targetEntity=(net.minecraft.world.entity.LivingEntity)target.getEntity();
            recruited.setTarget(targetEntity);
            check(recruited.getTarget()==null,"citizens_default_targetability_prevents_guard_target");
            target.getOrAddTrait(net.citizensnpcs.trait.TargetableTrait.class).setTargetable(true);
            recruited.setTarget(targetEntity); check(recruited.getTarget()==targetEntity,"explicit_targetability_uses_native_guard_targeting");
            float health=targetEntity.getHealth(); targetEntity.hurt(level.damageSources().mobAttack(recruited),5);
            check(targetEntity.getHealth()==health,"protected_npc_blocks_native_guard_damage");
            target.setProtected(false); targetEntity.setInvulnerable(false); targetEntity.invulnerableTime=0;
            targetEntity.hurt(level.damageSources().mobAttack(recruited),5);
            check(targetEntity.getHealth()<health,"unprotected_npc_receives_native_guard_damage");
            recruited.setTarget(null);
            var team=level.getScoreboard().addPlayerTeam("guard-audit-allies");
            level.getScoreboard().addPlayerToTeam(recruited.getScoreboardName(),team);
            level.getScoreboard().addPlayerToTeam(targetEntity.getScoreboardName(),team);
            recruited.setTarget(targetEntity); check(recruited.getTarget()==null,"native_scoreboard_faction_excludes_allied_npc_target");
            level.getScoreboard().removePlayerTeam(team);
            var protectedKinds=GuardConfig.COMMON.mobsGuardsProtectHurt.get();
            check(protectedKinds.contains(aiEntity.getEncodeId()),"fixture_villager_is_in_native_guard_damage_protection_list");
            boolean previousFriendlyFire=GuardConfig.COMMON.guardArrowsHurtVillagers.get();
            var civilian=new Villager(EntityType.VILLAGER,level); civilian.setPos(4,-60,4); level.addFreshEntity(civilian);
            var arrow=new net.minecraft.world.entity.projectile.Arrow(EntityType.ARROW,level); arrow.setOwner(recruited);
            try {
                GuardConfig.COMMON.guardArrowsHurtVillagers.set(false);
                float civilianHealth=civilian.getHealth(); civilian.hurt(level.damageSources().arrow(arrow,recruited),3);
                check(civilian.getHealth()==civilianHealth,"native_guard_projectile_friendly_fire_disabled_preserves_villager");
                GuardConfig.COMMON.guardArrowsHurtVillagers.set(true); civilian.invulnerableTime=0;
                civilian.hurt(level.damageSources().arrow(arrow,recruited),3);
                check(civilian.getHealth()<civilianHealth,"native_guard_projectile_friendly_fire_enabled_damages_villager");
                ai.setProtected(true); aiEntity.setInvulnerable(true); aiEntity.invulnerableTime=0;
                float npcHealth=aiEntity.getHealth(); aiEntity.hurt(level.damageSources().arrow(arrow,recruited),3);
                check(aiEntity.getHealth()==npcHealth,"citizens_protection_remains_authoritative_when_native_friendly_fire_enabled");
            } finally { GuardConfig.COMMON.guardArrowsHurtVillagers.set(previousFriendlyFire); civilian.discard(); }
            actor.setShiftKeyDown(false); actor.setPose(Pose.STANDING);
            check(recruited.interact(actor,InteractionHand.MAIN_HAND)==net.minecraft.world.InteractionResult.SUCCESS
                    && actor.containerMenu instanceof tallestegg.guardvillagers.common.entities.GuardContainer,
                    "native_guard_equipment_menu_keeps_provider_access_workflow");
            actor.closeContainer();
        } finally {
            registry.deregisterAll(); CitizensAPI.removeNamedNPCRegistry("guard-audit");
        }
    }
    private static void movement(net.minecraft.server.MinecraftServer server,ServerLevel level) {
        try {
            followGoalRan|=movingGuard.goalSelector.getAvailableGoals().stream().anyMatch(goal->goal.isRunning() && goal.getGoal() instanceof Guard.FollowHeroGoal);
            if(patrolPhase) patrolGoalRan|=movingGuard.goalSelector.getAvailableGoals().stream().anyMatch(goal->goal.isRunning() && goal.getGoal() instanceof Guard.WalkBackToCheckPointGoal);
            if(server.getTickCount()-movementStart<60) return;
            if(!patrolPhase) {
                check(followGoalRan && movingGuard.distanceToSqr(actorState)<originalDistance/2,"native_guard_goal_ticks_move_toward_owner_around_citizens");
                follow(actorState,movingGuard);
                check(!movingGuard.isFollowing(),"native_follow_payload_toggles_following_off");
                patrol(actorState,movingGuard,false); patrolAnchor=movingGuard.blockPosition();
                check(movingGuard.isPatrolling() && movingGuard.getPatrolPos().equals(patrolAnchor),"native_patrol_payload_anchors_current_guard_position");
                movingGuard.getNavigation().stop(); movingGuard.setPos(patrolAnchor.getX()-7,-60,patrolAnchor.getZ());
                originalDistance=movingGuard.blockPosition().distSqr(patrolAnchor); movementStart=server.getTickCount(); patrolPhase=true; return;
            }
            // The provider waits over 200 world ticks before patrol admission; let actual ticking satisfy it.
            if((!patrolGoalRan || movingGuard.blockPosition().distSqr(patrolAnchor)>=originalDistance/2)
                    && server.getTickCount()-movementStart<360) return;
            LoggerFactory.getLogger("citizens").info("[GUARDAUDIT] patrol ran={}, current={}, anchor={}, target={}, goals={}",
                    patrolGoalRan,movingGuard.position(),patrolAnchor,movingGuard.getTarget(),movingGuard.goalSelector.getAvailableGoals().stream()
                            .filter(goal->goal.isRunning()).map(goal->goal.getGoal().getClass().getSimpleName()).toList());
            check(patrolGoalRan && movingGuard.blockPosition().distSqr(patrolAnchor)<originalDistance/2,"native_patrol_goal_ticks_return_toward_anchor");
            patrol(actorState,movingGuard,true);
            check(!movingGuard.isPatrolling() && movingGuard.getPatrolPos()==null,"native_patrol_payload_clears_anchor");
            LoggerFactory.getLogger("citizens").info("[GUARDAUDIT] COMPLETE {} checks",passed);
            movingGuard=null; cleanup(server,level);
        } catch(Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[GUARDAUDIT] FAILED",failure); movingGuard=null; cleanup(server,level);
        }
    }
    static void follow(ServerPlayer actor,Guard guard) {
        var payload=new tallestegg.guardvillagers.networking.GuardFollowPacket(guard.getId());
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            tallestegg.guardvillagers.networking.GuardFollowPacket.STREAM_CODEC.encode(buffer,payload);
            tallestegg.guardvillagers.networking.GuardFollowPacket.handle(
                    tallestegg.guardvillagers.networking.GuardFollowPacket.STREAM_CODEC.decode(buffer),
                    new net.neoforged.neoforge.network.handling.ServerPayloadContext(actor.connection,payload.type().id()));
        } finally { buffer.release(); }
    }
    static void patrol(ServerPlayer actor,Guard guard,boolean pressed) {
        var payload=new tallestegg.guardvillagers.networking.GuardSetPatrolPosPacket(guard.getId(),pressed);
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            tallestegg.guardvillagers.networking.GuardSetPatrolPosPacket.STREAM_CODEC.encode(buffer,payload);
            tallestegg.guardvillagers.networking.GuardSetPatrolPosPacket.setPatrolPosition(
                    tallestegg.guardvillagers.networking.GuardSetPatrolPosPacket.STREAM_CODEC.decode(buffer),
                    new net.neoforged.neoforge.network.handling.ServerPayloadContext(actor.connection,payload.type().id()));
        } finally { buffer.release(); }
    }
    private static void cleanup(net.minecraft.server.MinecraftServer server,ServerLevel level) {
        for(var npc:npcs) if(npc.getOwningRegistry().getByUniqueId(npc.getUniqueId())==npc) npc.destroy();
        for(var guard:guards(level)) guard.discard();
        server.getPlayerList().remove(actorState); channelState.finishAndReleaseAll(); server.halt(false);
    }
    static void interact(ServerPlayer actor,Entity target) { NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(actor,InteractionHand.MAIN_HAND,target)); }
    private static List<Guard> guards(ServerLevel level) { return level.getEntitiesOfClass(Guard.class,new net.minecraft.world.phys.AABB(-16,-80,-16,16,80,16)); }
    static EmbeddedChannel connect(ServerPlayer player) {
        var connection=new Connection(PacketFlow.SERVERBOUND);
        var channel=new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                Connection.configureInMemoryPipeline(channel.pipeline(),PacketFlow.SERVERBOUND); connection.configurePacketHandler(channel.pipeline());
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie=new CommonListenerCookie(player.getGameProfile(),0,ClientInformation.createDefault(),false,ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(player.registryAccess(),cookie.connectionType())));
        player.getServer().getPlayerList().placeNewPlayer(connection,player,cookie); return channel;
    }
    private static void check(boolean condition,String name) {
        if(!condition) throw new AssertionError(name); passed++; LoggerFactory.getLogger("citizens").info("[GUARDAUDIT] PASS {}",name);
    }
}
