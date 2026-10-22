package net.yuuniverse.interactions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Runs both actual vanilla lava-ignition branches, including fluid-event result replacement. */
@EventBusSubscriber(modid="interactions")
public final class LavaLoggingRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("interactions");
    private static boolean done;
    private static int passed;
    private static long startedAt;
    private static final List<BlockPos> ignited=new ArrayList<>();

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(done || event.getServer().getTickCount()<20)return;
        done=true;
        var server=event.getServer(); var level=server.overworld();
        try {
            String phase=System.getenv("CITIZENS_LAVA_AUDIT_PHASE");
            startedAt=System.currentTimeMillis();
            if("recover".equals(phase)) {
                var state=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(server.getServerDirectory().resolve("lava-state.json"))).getAsJsonObject();
                startedAt=state.get("startedAt").getAsLong();
                for(var value:state.getAsJsonArray("ignited"))ignited.add(BlockPos.of(value.getAsLong()));
                check(ignited.size()==2,"restart_retains_both_original_lava_ignition_positions");
                NativeProvider.verify(level);
            }else {
                level.setWeatherParameters(6000,0,false,false);
                level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(true,server);
                for(int branch=0;branch<2;branch++) {
                    var origin=new BlockPos(32+branch*128,-60,32);
                    var target=prepare(level,origin);
                    ignite(level,origin,branch);
                    check(level.getBlockState(target).is(Blocks.FIRE),"actual_lava_branch_"+branch+"_creates_fire");
                    ignited.add(target);
                }
                level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(false,server);
                if("absent".equals(phase))check(!ModList.get().isLoaded("neoprotect"),"native_lava_ignition_works_without_optional_provider_classes");
                else {
                    NativeProvider.verify(level);
                    NativeProvider.configuration(level);
                    for(int branch=0;branch<2;branch++)for(int mode=0;mode<3;mode++) {
                        var origin=new BlockPos(544+branch*128+mode*256,-60,32); var target=prepare(level,origin);
                        boolean cancel=mode>0, prevent=mode==1, flagOnly=mode==2;
                        Consumer<BlockEvent.FluidPlaceBlockEvent> replace=change->{
                            if(change.getPos().equals(target)) {
                                if(cancel)change.setCanceled(true);
                                if(prevent)change.setNewState(Blocks.AIR.defaultBlockState());
                                else if(!flagOnly)change.setNewState(Blocks.STONE.defaultBlockState());
                            }
                        };
                        NeoForge.EVENT_BUS.addListener(replace);
                        try {
                            level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(true,server);
                            ignite(level,origin,branch);
                            check(prevent?level.getBlockState(target).isAir():level.getBlockState(target).is(flagOnly?Blocks.FIRE:Blocks.STONE),"native_fluid_event_result_is_preserved_branch_"+branch+"_mode_"+mode);
                            // The pinned EventHooks helper returns newState even when the canceled flag is set.
                            if(flagOnly)NativeProvider.one(level,target,"cancel_flag_alone_keeps_one_actual_native_fire_record_branch_"+branch);
                            else NativeProvider.none(level,target,"fluid_event_does_not_invent_fire_history_branch_"+branch+"_mode_"+mode);
                        }finally {NeoForge.EVENT_BUS.unregister(replace);}
                    }
                    var state=new com.google.gson.JsonObject();state.addProperty("startedAt",startedAt);
                    state.add("ignited",new com.google.gson.Gson().toJsonTree(ignited.stream().map(BlockPos::asLong).toList()));
                    java.nio.file.Files.writeString(server.getServerDirectory().resolve("lava-state.json"),state.toString());
                    server.saveEverything(true,true,true);
                }
            }
            LOG.info("[LAVAAUDIT] COMPLETE {} {} checks",passed,phase);
        }catch(Throwable failure){LOG.error("[LAVAAUDIT] FAILED",failure);}
        finally {
            level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(false,server);
            server.halt(false);
        }
    }
    private static BlockPos prepare(ServerLevel level,BlockPos origin) {
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.setChunkForced((origin.getX()>>4)+x,(origin.getZ()>>4)+z,true);
        for(var pos:BlockPos.betweenClosed(origin.offset(-2,0,-2),origin.offset(2,3,2)))level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
        level.setBlock(origin.east(),Blocks.OAK_PLANKS.defaultBlockState(),3);
        level.setBlock(origin,Blocks.LAVA.defaultBlockState(),3);
        return origin.east().above();
    }
    private static void ignite(ServerLevel level,BlockPos origin,int branch) {
        // Select the actual two native branches and their eastward candidate, without replacing any mutation.
        ((net.minecraft.world.level.material.LavaFluid)Fluids.LAVA).randomTick(level,origin,level.getFluidState(origin),new LegacyRandomSource(0) {
            int calls;
            @Override public int nextInt(int bound) {
                int call=calls++;
                if(bound!=3)throw new AssertionError("Unexpected native lava random bound");
                return call==0?branch:(call%2==1?2:1);
            }
        });
    }
    private static final class NativeProvider {
        private static void verify(ServerLevel level) {
            dev.northbladetech.neoprotect.NeoProtect.service().flush();
            for(var pos:ignited) {
                var rows=dev.northbladetech.neoprotect.NeoProtect.service().lookup(level,pos,0,100).stream().filter(row->row.timestamp()>=startedAt&&row.metadata().contains("fire")).toList();
                LOG.info("[LAVAAUDIT] pos={} rows={}",pos,rows);
                check(rows.size()==1,"one_native_lava_fire_record_per_ignition_position");
                var row=rows.getFirst();
                check(row.actorName().equals("unknown")&&row.actionType()==dev.northbladetech.neoprotect.model.ActionType.BLOCK_PLACE,
                        "natural_lava_keeps_unknown_actor_and_native_place_action");
                check(dev.northbladetech.neoprotect.util.NbtSerializers.deserializeTag(row.beforeState()).getString("Name").equals("minecraft:air")
                        &&dev.northbladetech.neoprotect.util.NbtSerializers.deserializeTag(row.afterState()).getString("Name").equals("minecraft:fire"),
                        "lava_ignition_retains_actual_air_and_fire_snapshots");
            }
        }
        private static void none(ServerLevel level,BlockPos target,String name) {
            dev.northbladetech.neoprotect.NeoProtect.service().flush();
            check(dev.northbladetech.neoprotect.NeoProtect.service().lookup(level,target,0,100).stream().noneMatch(row->row.timestamp()>=startedAt&&row.metadata().contains("fire")),name);
        }
        private static void one(ServerLevel level,BlockPos target,String name) {
            dev.northbladetech.neoprotect.NeoProtect.service().flush();
            check(dev.northbladetech.neoprotect.NeoProtect.service().lookup(level,target,0,100).stream().filter(row->row.timestamp()>=startedAt&&row.metadata().contains("fire")).count()==1,name);
        }
        private static void configuration(ServerLevel level) {
            boolean previous=dev.northbladetech.neoprotect.config.NeoProtectConfig.TRACK_FIRE.get();
            try {
                dev.northbladetech.neoprotect.config.NeoProtectConfig.TRACK_FIRE.set(false);
                var origin=new BlockPos(416,-60,32);var target=prepare(level,origin);
                level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(true,level.getServer());ignite(level,origin,1);
                check(level.getBlockState(target).is(Blocks.FIRE),"track_fire_false_preserves_actual_lava_ignition");
                none(level,target,"track_fire_false_writes_no_lava_fire_record");
            }finally {dev.northbladetech.neoprotect.config.NeoProtectConfig.TRACK_FIRE.set(previous);}
        }
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;LOG.info("[LAVAAUDIT] PASS {}",name);}
}
