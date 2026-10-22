package net.yuuniverse.interactions;

import java.util.*;
import com.daqem.grieflogger.database.service.Services;
import dev.northbladetech.neoprotect.NeoProtect;
import dev.northbladetech.neoprotect.model.ActionType;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Observes actual native world mutation; never posts a fabricated fire/explosion event. */
@EventBusSubscriber(modid="interactions")
public final class EnvironmentalLoggingRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("interactions");
    private static final BlockPos fire=new BlockPos(32,-60,32), owned=new BlockPos(160,-60,32), unknown=new BlockPos(320,-60,32), wildFire=new BlockPos(480,-60,32);
    private static final List<BlockPos> burned=new ArrayList<>(), wildBurned=new ArrayList<>(), exploded=new ArrayList<>(), unattributed=new ArrayList<>();
    private static long startedAt;
    private static final boolean absent="absent".equals(System.getenv("CITIZENS_ENVIRONMENT_AUDIT_PHASE"));
    private static ServerPlayer actor;
    private static io.netty.channel.embedded.EmbeddedChannel channel;
    private static int stage, readyTick, passed;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(stage==99)return;
        var server=event.getServer(); var level=server.overworld();
        if(server.getTickCount()<20)return;
        try {
            if(stage==0) {
                stage=1;
                if("recover".equals(System.getenv("CITIZENS_ENVIRONMENT_AUDIT_PHASE"))){NativeProvider.recover(server);finish(server);return;}
                actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"EnvironmentActor"),ClientInformation.createDefault());
                actor.setPos(32.5,-60,34.5);channel=connect(actor);
                actor.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.CREATIVE);
                startedAt=System.currentTimeMillis();
                level.setWeatherParameters(6000,0,false,false);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK).set(true,server);
                for(var center:List.of(fire,owned,unknown,wildFire)) {
                    for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.setChunkForced((center.getX()>>4)+x,(center.getZ()>>4)+z,true);
                    for(var pos:BlockPos.betweenClosed(center.offset(-4,0,-4),center.offset(4,4,4)))level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
                }
                var wood=new ArrayList<BlockPos>();
                for(var pos:BlockPos.betweenClosed(fire.offset(-3,-1,-3),fire.offset(3,-1,3)))level.setBlock(pos,Blocks.NETHERRACK.defaultBlockState(),3);
                for(var direction:Direction.Plane.HORIZONTAL) {
                    var pos=fire.relative(direction);wood.add(pos);level.setBlock(pos,Blocks.OAK_PLANKS.defaultBlockState(),3);
                }
                var flint=new ItemStack(Items.FLINT_AND_STEEL);actor.setItemInHand(InteractionHand.MAIN_HAND,flint);
                var hit=new BlockHitResult(Vec3.atCenterOf(fire.below()).add(0,0.5,0),Direction.UP,fire.below(),false);
                check(actor.gameMode.useItemOn(actor,level,flint,InteractionHand.MAIN_HAND,hit).consumesAction()&&level.getBlockState(fire).is(Blocks.FIRE),"actual_flint_use_ignites_native_fire");
                for(int attempt=0;attempt<80;attempt++) {
                    var burning=new ArrayList<BlockPos>();
                    for(var pos:BlockPos.betweenClosed(fire.offset(-3,0,-3),fire.offset(3,2,3)))if(level.getBlockState(pos).is(Blocks.FIRE))burning.add(pos.immutable());
                    for(var pos:burning)level.getBlockState(pos).tick(level,pos,net.minecraft.util.RandomSource.create(91L+attempt));
                    if(wood.stream().anyMatch(pos->!level.getBlockState(pos).is(Blocks.OAK_PLANKS)))break;
                }
                for(var pos:wood)if(!level.getBlockState(pos).is(Blocks.OAK_PLANKS))burned.add(pos);
                check(!burned.isEmpty(),"actual_native_fire_ticks_destroy_flammable_blocks");
                for(var pos:BlockPos.betweenClosed(wildFire.offset(-3,-1,-3),wildFire.offset(3,-1,3)))level.setBlock(pos,Blocks.NETHERRACK.defaultBlockState(),3);
                var wildWood=new ArrayList<BlockPos>();
                for(var direction:Direction.Plane.HORIZONTAL){var pos=wildFire.relative(direction);wildWood.add(pos);level.setBlock(pos,Blocks.OAK_PLANKS.defaultBlockState(),3);}
                level.setBlock(wildFire,Blocks.FIRE.defaultBlockState(),3);
                for(int attempt=0;attempt<80;attempt++) {
                    if(level.getBlockState(wildFire).is(Blocks.FIRE))level.getBlockState(wildFire).tick(level,wildFire,net.minecraft.util.RandomSource.create(91L+attempt));
                    if(wildWood.stream().anyMatch(pos->!level.getBlockState(pos).is(Blocks.OAK_PLANKS)))break;
                }
                for(var pos:wildWood)if(!level.getBlockState(pos).is(Blocks.OAK_PLANKS))wildBurned.add(pos);
                check(!wildBurned.isEmpty(),"native_fire_without_a_player_ignition_destroys_distant_wood");
                // Freeze further spread after observing the actual changes, before querying their independent logs.
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK).set(false,server);
                explode(level,owned,true,exploded);explode(level,unknown,false,unattributed);
                readyTick=server.getTickCount()+45;return;
            }
            if(server.getTickCount()<readyTick)return;
            if(absent)check(!net.neoforged.fml.ModList.get().isLoaded("neoprotect"),"native_fire_and_explosions_work_without_optional_neoprotect_classes");
            else {NativeProvider.verify(level);NativeProvider.configuration(level);NativeProvider.save(server);}
            LOG.info("[ENVIRONMENTAUDIT] COMPLETE {} checks",passed);finish(server);
        }catch(Throwable failure){LOG.error("[ENVIRONMENTAUDIT] FAILED",failure);finish(server);}
    }
    private static void explode(ServerLevel level,BlockPos center,boolean attributed,List<BlockPos> changed) {
        var targets=new ArrayList<BlockPos>();
        for(var pos:BlockPos.betweenClosed(center.offset(-1,0,-1),center.offset(1,0,1))){targets.add(pos.immutable());level.setBlock(pos,Blocks.OAK_PLANKS.defaultBlockState(),3);}
        var tnt=new PrimedTnt(level,center.getX()+0.5,center.getY()+0.5,center.getZ()+0.5,attributed?actor:null);
        level.explode(tnt,tnt.getX(),tnt.getY(),tnt.getZ(),4F,Level.ExplosionInteraction.TNT);
        for(var pos:targets)if(level.getBlockState(pos).isAir())changed.add(pos);
        check(!changed.isEmpty(),attributed?"actual_player_owned_tnt_destroys_target_blocks":"actual_unowned_tnt_destroys_distant_target_blocks");
    }
    // Keep provider-typed lambda descriptors outside the automatically scanned subscriber.
    // NeoForge reflects every subscriber method even when the absent phase skips its body.
    private static final class NativeProvider {
    private static void verify(ServerLevel level) {
        NeoProtect.service().flush();
        check(NeoProtect.service().lookup(level,fire,0,100).stream().filter(row->row.actorUuid().equals(actor.getUUID())&&row.actionType()==ActionType.BLOCK_PLACE).count()==1,"direct_player_ignition_keeps_one_native_placement_record");
        for(var pos:burned) {
            var rows=NeoProtect.service().lookup(level,pos,0,100);
            LOG.info("[ENVIRONMENTAUDIT] fire pos={} rows={}",pos,rows);
            check(rows.stream().filter(row->row.actorUuid().equals(actor.getUUID())&&row.metadata().contains("fire")
                    &&dev.northbladetech.neoprotect.util.NbtSerializers.deserializeTag(row.beforeState()).getString("Name").equals("minecraft:oak_planks")).count()==1,"fire_destruction_retains_one_original_igniter_record");
            var retained=rows.stream().filter(row->row.actorUuid().equals(actor.getUUID())&&row.metadata().contains("fire")
                    &&dev.northbladetech.neoprotect.util.NbtSerializers.deserializeTag(row.beforeState()).getString("Name").equals("minecraft:oak_planks")).findFirst().orElseThrow();
            var result=dev.northbladetech.neoprotect.util.NbtSerializers.deserializeBlockState(level,retained.afterState());
            check(result.getBlock()==level.getBlockState(pos).getBlock()&&(result.isAir()?retained.actionType()==ActionType.BLOCK_BREAK:result.is(Blocks.FIRE)&&retained.actionType()==ActionType.BLOCK_PLACE),"fire_snapshot_retains_native_result_and_corresponding_logger_action");
        }
        for(var pos:wildBurned)check(NeoProtect.service().lookup(level,pos,0,100).stream().filter(row->row.timestamp()>=startedAt&&row.actorName().equals("unknown")&&row.metadata().contains("fire")
                &&dev.northbladetech.neoprotect.util.NbtSerializers.deserializeTag(row.beforeState()).getString("Name").equals("minecraft:oak_planks")).count()==1,"distant_native_fire_retains_unknown_cause_instead_of_borrowing_igniter");
        for(var pos:exploded)check(NeoProtect.service().lookup(level,pos,0,100).stream().filter(row->row.actorUuid().equals(actor.getUUID())&&row.actionType()==ActionType.BLOCK_BREAK&&row.metadata().contains("explosion")).count()==1,"owned_tnt_retains_one_native_owner_record_per_destroyed_block");
        for(var pos:unattributed)check(NeoProtect.service().lookup(level,pos,0,100).stream().filter(row->row.timestamp()>=startedAt&&row.actorName().equals("unknown")&&row.actionType()==ActionType.BLOCK_BREAK&&row.metadata().contains("explosion")).count()==1,"distant_unowned_tnt_keeps_unknown_actor_instead_of_borrowing_player");
        check(exploded.stream().allMatch(pos->Services.BLOCK.getBlockHistory(level,pos).isEmpty())&&unattributed.stream().allMatch(pos->Services.BLOCK.getBlockHistory(level,pos).isEmpty()),"grieflogger_does_not_fabricate_player_break_events_for_environmental_explosions");
    }
    private static void configuration(ServerLevel level) {
        var center=new BlockPos(640,-60,32);level.setChunkForced(center.getX()>>4,center.getZ()>>4,true);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK).set(true,level.getServer());
        for(var pos:BlockPos.betweenClosed(center.offset(-2,-1,-2),center.offset(2,-1,2)))level.setBlock(pos,Blocks.NETHERRACK.defaultBlockState(),3);
        var wood=center.east();level.setBlock(wood,Blocks.OAK_PLANKS.defaultBlockState(),3);level.setBlock(center,Blocks.FIRE.defaultBlockState(),3);
        boolean previous=dev.northbladetech.neoprotect.config.NeoProtectConfig.TRACK_FIRE.get();
        try {
            dev.northbladetech.neoprotect.config.NeoProtectConfig.TRACK_FIRE.set(false);
            for(int attempt=0;attempt<100&&level.getBlockState(wood).is(Blocks.OAK_PLANKS);attempt++)if(level.getBlockState(center).is(Blocks.FIRE))
                level.getBlockState(center).tick(level,center,net.minecraft.util.RandomSource.create(91L+attempt));
            check(!level.getBlockState(wood).is(Blocks.OAK_PLANKS),"disabled_logger_fire_tracking_preserves_actual_native_burning");
            NeoProtect.service().flush();
            check(NeoProtect.service().lookup(level,wood,0,100).stream().noneMatch(row->row.timestamp()>=startedAt&&row.metadata().contains("fire")),"provider_track_fire_false_is_honored_without_writing_fire_records");
        }finally {
            dev.northbladetech.neoprotect.config.NeoProtectConfig.TRACK_FIRE.set(previous);
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK).set(false,level.getServer());
        }
    }
    private static void save(MinecraftServer server)throws Exception {
        var state=new com.google.gson.JsonObject();state.addProperty("actor",actor.getUUID().toString());
        state.addProperty("startedAt",startedAt);
        var json=new com.google.gson.Gson();state.add("burned",json.toJsonTree(burned.stream().map(BlockPos::asLong).toList()));
        state.add("exploded",json.toJsonTree(exploded.stream().map(BlockPos::asLong).toList()));state.add("unattributed",json.toJsonTree(unattributed.stream().map(BlockPos::asLong).toList()));
        state.add("wildBurned",json.toJsonTree(wildBurned.stream().map(BlockPos::asLong).toList()));
        java.nio.file.Files.writeString(server.getServerDirectory().resolve("environment-state.json"),state.toString());server.saveEverything(true,true,true);
    }
    private static void recover(MinecraftServer server)throws Exception {
        var state=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(server.getServerDirectory().resolve("environment-state.json"))).getAsJsonObject();
        actor=new ServerPlayer(server,server.overworld(),new com.mojang.authlib.GameProfile(UUID.fromString(state.get("actor").getAsString()),"EnvironmentActor"),ClientInformation.createDefault());channel=connect(actor);
        startedAt=state.get("startedAt").getAsLong();
        for(var value:state.getAsJsonArray("burned"))burned.add(BlockPos.of(value.getAsLong()));
        for(var value:state.getAsJsonArray("exploded"))exploded.add(BlockPos.of(value.getAsLong()));
        for(var value:state.getAsJsonArray("unattributed"))unattributed.add(BlockPos.of(value.getAsLong()));
        for(var value:state.getAsJsonArray("wildBurned"))wildBurned.add(BlockPos.of(value.getAsLong()));
        check(!burned.isEmpty()&&!exploded.isEmpty()&&!unattributed.isEmpty(),"restart_uses_original_native_environmental_targets");verify(server.overworld());LOG.info("[ENVIRONMENTAUDIT] COMPLETE {} restart checks",passed);
    }
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;LOG.info("[ENVIRONMENTAUDIT] PASS {}",name);}
    private static void finish(MinecraftServer server){stage=99;if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    private static io.netty.channel.embedded.EmbeddedChannel connect(ServerPlayer player) {
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel=new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelInitializer<io.netty.channel.Channel>() {
            @Override protected void initChannel(io.netty.channel.Channel channel) {net.minecraft.network.Connection.configureInMemoryPipeline(channel.pipeline(),net.minecraft.network.protocol.PacketFlow.SERVERBOUND);connection.configurePacketHandler(channel.pipeline());}
        });
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        var cookie=new net.minecraft.server.network.CommonListenerCookie(player.getGameProfile(),0,ClientInformation.createDefault(),false,net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(net.minecraft.network.RegistryFriendlyByteBuf.decorator(player.registryAccess(),cookie.connectionType())));
        player.getServer().getPlayerList().placeNewPlayer(connection,player,cookie);return channel;
    }
}
