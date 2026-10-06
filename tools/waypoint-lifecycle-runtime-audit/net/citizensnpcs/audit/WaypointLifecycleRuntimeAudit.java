package net.citizensnpcs.audit;

import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.trait.*;
import net.citizensnpcs.trait.waypoint.*;
import net.citizensnpcs.trait.waypoint.triggers.*;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid="citizens")
public final class WaypointLifecycleRuntimeAudit {
    private static MinecraftServer server;
    private static ServerLevel level;
    private static NPCRegistry registry;
    private static Actor alice;
    private static CommandSourceStack source;
    private static PermissionUtil.Attachment grant;
    private static final List<NPC> npcs=new ArrayList<>();
    private static final Queue<Step> steps=new ArrayDeque<>();
    private static BooleanSupplier waiting=()->true;
    private static boolean done,forced,initialized;
    private static int deadline,passed,stepNumber,sequence;
    private static NPC current;
    private static Waypoints trait;
    private static WaypointProvider provider;
    private static int actions;
    private static Probe probe;
    private static java.util.function.Consumer<net.citizensnpcs.api.ai.event.NavigationBeginEvent> begin;
    @SubscribeEvent public static void began(net.citizensnpcs.api.ai.event.NavigationBeginEvent event) { if(begin!=null) begin.accept(event); }
    @FunctionalInterface private interface Step { void run() throws Exception; }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if(done) return; server=event.getServer(); level=server.overworld();
        try {
            if(!forced) { check(Files.isRegularFile(Path.of("waypoint-lifecycle-audit-fixture.txt")),"isolated_fixture"); check(!CitizensAPI.getNPCRegistry().iterator().hasNext(),"empty_registry"); level.setChunkForced(0,0,true); forced=true; deadline=server.getTickCount()+1800; }
            if(server.getTickCount()>deadline) throw new AssertionError("timeout_step_"+stepNumber);
            if(!level.isPositionEntityTicking(new BlockPos(4,-58,4))) return;
            if(!initialized) { initialized=true; setup(); }
            alice.pump(); if(!waiting.getAsBoolean()) return; waiting=()->true;
            if(!steps.isEmpty()) { stepNumber++; steps.remove().run(); return; }
            close(); LoggerFactory.getLogger("citizens").info("[WAYPOINTLIFECYCLEAUDIT] COMPLETE {} checks",passed);
        } catch(Throwable failure) { LoggerFactory.getLogger("citizens").error("[WAYPOINTLIFECYCLEAUDIT] FAILED step="+stepNumber,failure); close(); }
    }
    private static void setup() {
        registry=CitizensAPI.getNPCRegistry(); alice=actor("WaypointAlice"); source=alice.player.createCommandSourceStack();
        grant=PermissionUtil.grantTemporary(alice.player,List.of("citizens.waypoints.add","citizens.waypoints.remove","citizens.waypoints.provider"));
        steps.add(()-> {
            current=npc(false,false); select(current); Waypoints trait=current.getOrAddTrait(Waypoints.class);
            LinearWaypointProvider route=(LinearWaypointProvider)trait.getCurrentProvider(); route.addWaypoint(new Waypoint(new Location(level,8,-58,8)));
            reject("wp provider missing-provider");
            check(trait.getCurrentProvider()==route && route.waypoints().iterator().hasNext(),"unknown_provider_preserves_route");
            current.destroy();
        });
        commands(); providers(); delays(); navigation();
    }

    private static void commands() {
        steps.add(()-> {
            current=npc(false,false); select(current);
            reject("wp add invalid 0 0"); reject("waypoints add 1 2 3 unknown:world"); reject("wp add 1 2 3 --index 9");
            check(!current.hasTrait(Waypoints.class),"invalid_add_does_not_attach_trait");
            run("wp add 8 -58 8"); trait=current.getTraitNullable(Waypoints.class);
            var route=(LinearWaypointProvider)trait.getCurrentProvider();
            check(route.waypoints().iterator().next().getLocation().getWorld()==level,"unspawned_add_uses_native_source_world");
            run("waypoints add 9 -58 9 --index 0");
            var list=(List<Waypoint>)route.waypoints(); check(list.size()==2&&list.get(0).getLocation().getX()==9,"ordered_insertion");
            reject("wp remove 9 -58 9 --index 99"); check(list.size()==2,"invalid_index_does_not_fall_through_to_coordinates");
            list.add(new Waypoint(new Location(level,12,-58,12,60,20))); run("wp remove 12 -58 12"); check(list.size()==2,"coordinate_removal_ignores_rotation");
            run("waypoints remove --index 0"); check(list.size()==1,"index_removal");
            reject("wp add 1 2 3 --unknown value"); current.destroy();
        });
    }

    private static void providers() {
        steps.add(()-> {
            current=npc(false,false); trait=current.getOrAddTrait(Waypoints.class);
            var route=(LinearWaypointProvider)trait.getCurrentProvider();
            var point=new Waypoint(new Location(level,8,-58,8)); point.addTrigger(new DelayTrigger(5)); route.addWaypoint(point);
            DataKey source=new MemoryDataKey(); trait.save(source);
            DataKey trigger=source.getRelative("linear.points.0.triggers.0");
            trigger.setString("type","missing_fixture_trigger");
            var unavailable=source.copy().getValuesDeep();
            trait.load(source);
            check(trait.getCurrentProvider()==null,"unknown_trigger_disables_incomplete_route");
            DataKey saved=new MemoryDataKey(); trait.save(saved);
            check(saved.getValuesDeep().equals(unavailable),"unknown_trigger_preserves_complete_route_data");
            trigger.setString("type","delay"); trigger.setString("delay","broken");
            var malformed=source.copy().getValuesDeep(); trait.load(source);
            check(trait.getCurrentProvider()==null,"malformed_trigger_disables_incomplete_route");
            saved=new MemoryDataKey(); trait.save(saved);
            check(saved.getValuesDeep().equals(malformed),"malformed_trigger_preserves_complete_route_data");
            trigger.setInt("delay",5); trait.load(source);
            var restored=(LinearWaypointProvider)trait.getCurrentProvider();
            check(restored!=null && restored.waypoints().iterator().next().getTriggers().size()==1
                    && ((DelayTrigger)restored.waypoints().iterator().next().getTriggers().get(0)).getDelay()==5,
                    "repaired_trigger_restores_route");
            current.destroy();
        });
        for(boolean packet:new boolean[]{false,true}) steps.add(()-> {
            current=npc(packet,false); trait=current.getOrAddTrait(Waypoints.class);
            CountingProvider first=new CountingProvider(); Waypoints.registerWaypointProvider(()->first,"audit-first");
            check(trait.setWaypointProvider("audit-first"),"install_counting_provider"); check(first.spawns==0&&first.attaches==1,"unspawned_provider_only_attaches");
            current.spawn(new Location(level,4,-58,4)); trait.onSpawn(); check(first.spawns==1,"provider_starts_once_per_entity");
            Waypoints.registerWaypointProvider(()->null,"audit-null"); check(!trait.setWaypointProvider("audit-null")&&trait.getCurrentProvider()==first,"null_factory_preserves_provider");
            Waypoints.registerWaypointProvider(()->{throw new IllegalStateException("fixture");},"audit-throw");
            try {trait.setWaypointProvider("audit-throw"); throw new AssertionError("factory_should_throw");} catch(IllegalStateException expected) {}
            check(trait.getCurrentProvider()==first&&first.removes==0,"throwing_factory_preserves_provider");
            CountingProvider second=new CountingProvider(); Waypoints.registerWaypointProvider(()->second,"audit-second");
            check(trait.setWaypointProvider("audit-second"),"switch_provider"); check(first.removes==1&&second.spawns==1,"switch_retires_old_provider_once");
            current.despawn(); check(second.removes==1,"despawn_retires_provider"); current.destroy(); check(second.removes==1,"destroy_does_not_repeat_despawn_cleanup");
        });
        steps.add(()-> {
            current=npc(false,true); trait=current.getOrAddTrait(Waypoints.class);
            CountingProvider old=new CountingProvider(), loaded=new CountingProvider();
            Waypoints.registerWaypointProvider(()->old,"audit-old"); trait.setWaypointProvider("audit-old");
            Waypoints.registerWaypointProvider(()->loaded,"audit-loaded"); MemoryDataKey data=new MemoryDataKey(); data.setString("provider","audit-loaded");
            trait.load(data); check(old.removes==1&&loaded.spawns==1,"reload_retires_old_behavior");
            current.removeTrait(Waypoints.class); check(loaded.removes==1,"trait_removal_retires_behavior"); current.destroy();
        });
        steps.add(()-> {
            current=npc(false,false); trait=current.getOrAddTrait(Waypoints.class);
            MemoryDataKey missing=new MemoryDataKey(); missing.setString("provider","unavailable-custom"); missing.setString("unavailable-custom.route.0","original"); missing.setInt("unavailable-custom.options.delay",12);
            List<String> options=new ArrayList<>(List.of("original")); missing.setRaw("unavailable-custom.options.list",options);
            trait.load(missing); options.add("later"); MemoryDataKey saved=new MemoryDataKey(); trait.save(saved);
            check(saved.getString("unavailable-custom.route.0").equals("original")&&trait.getCurrentProvider()==null,"unknown_provider_round_trip_preserves_payload");
            check(saved.getRaw("unavailable-custom.options.list").equals(List.of("original")),"unavailable_provider_data_is_detached_from_caller");
            check(trait.setWaypointProvider(""),"disable_provider"); MemoryDataKey disabled=new MemoryDataKey(); trait.save(disabled); trait.load(disabled);
            check(trait.getCurrentProvider()==null&&disabled.keyExists("provider")&&disabled.getString("provider").isEmpty(),"disabled_provider_stays_disabled_after_reload"); current.destroy();
        });
        steps.add(()-> {
            current=npc(false,true); trait=current.getOrAddTrait(Waypoints.class); CountingProvider old=new CountingProvider(), middle=new CountingProvider(), newer=new CountingProvider();
            Waypoints.registerWaypointProvider(()->old,"audit-old"); Waypoints.registerWaypointProvider(()->middle,"audit-middle"); Waypoints.registerWaypointProvider(()->newer,"audit-newer"); trait.setWaypointProvider("audit-old");
            old.removing=()->trait.setWaypointProvider("audit-newer");
            check(!trait.setWaypointProvider("audit-middle")&&trait.getCurrentProvider()==newer,"removal_callback_preserves_newer_switch");
            check(middle.spawns==0&&newer.spawns==1,"superseded_provider_never_starts");
            Waypoints.registerWaypointProvider(()->{trait.setWaypointProvider("audit-middle"); return new CountingProvider();},"audit-factory-reentry");
            check(!trait.setWaypointProvider("audit-factory-reentry")&&trait.getCurrentProvider()==middle,"factory_callback_preserves_newer_switch"); current.destroy();
        });
        steps.add(()-> {
            var guided=new GuidedWaypointProvider(); guided.addDestination(new Waypoint(new Location(level,3,-58,3))); guided.addDestination(new Waypoint(new Location(level,4,-58,4)));
            MemoryDataKey data=new MemoryDataKey(); guided.save(data); var one=new GuidedWaypointProvider(); one.addDestination(new Waypoint(new Location(level,5,-58,5))); one.save(data);
            var loaded=new GuidedWaypointProvider(); loaded.load(data); loaded.load(data);
            check(((Collection<?>)loaded.waypoints()).size()==1,"guided_save_and_reload_do_not_retain_removed_points_or_duplicate");
        });
    }

    private static Waypoint sequence(int delay) {
        Waypoint waypoint=new Waypoint(current.getStoredLocation()); waypoint.addTrigger(new DelayTrigger(delay));
        waypoint.addTrigger(new CountTrigger()); return waypoint;
    }
    private static void prepareDelay(String name,boolean packet) {
        current=npc(packet,true); trait=current.getOrAddTrait(Waypoints.class); trait.setWaypointProvider(name); provider=trait.getCurrentProvider(); actions=0;
    }
    private static void delays() {
        for(String name:List.of("linear","guided","wander")) for(boolean packet:new boolean[]{false,true}) {
            steps.add(()-> {prepareDelay(name,packet); sequence(4).onReach(current); check(provider.isPaused(),"delay_pauses_"+name+packet); delay(7);});
            steps.add(()-> {check(actions==1&&!provider.isPaused(),"delay_runs_once_and_releases_"+name+packet); current.destroy();});
            steps.add(()-> {prepareDelay(name,packet); provider.setPaused(true); sequence(4).onReach(current); delay(7);});
            steps.add(()-> {check(actions==1&&provider.isPaused(),"delay_preserves_existing_pause_"+name+packet); current.destroy();});
            steps.add(()-> {prepareDelay(name,packet); sequence(4).onReach(current); provider.setPaused(true); delay(7);});
            steps.add(()-> {check(actions==0&&provider.isPaused(),"later_explicit_pause_supersedes_delay_"+name+packet); current.destroy();});
        }
        for(String change:List.of("provider","remove","despawn","destroy","edit","recursive","snapshot")) {
            steps.add(()-> {
                prepareDelay("linear",false); Waypoint waypoint=sequence(8); waypoint.onReach(current);
                switch(change) {
                    case "provider" -> trait.setWaypointProvider("guided");
                    case "remove" -> current.removeTrait(Waypoints.class);
                    case "despawn" -> {current.despawn(); current.spawn(new Location(level,4,-58,4));}
                    case "destroy" -> current.destroy();
                    case "edit" -> ((LinearWaypointProvider)provider).addWaypoint(new Waypoint(new Location(level,9,-58,9)));
                    case "recursive" -> sequence(2).onReach(current);
                    case "snapshot" -> waypoint.getTriggers().clear();
                }
                delay(12);
            });
            steps.add(()-> {check(actions==(Set.of("recursive","snapshot").contains(change)?1:0),"delayed_sequence_ownership_"+change); current.destroy();});
        }
        steps.add(()-> {
            prepareDelay("linear",false); Waypoint waypoint=new Waypoint(current.getStoredLocation());
            waypoint.addTrigger(new WaypointTrigger(){public String description(){return "replace";} public void onWaypointReached(NPC npc,Location at){trait.setWaypointProvider("guided");}});
            waypoint.addTrigger(new CountTrigger()); waypoint.onReach(current); check(actions==0,"immediate_trigger_stops_after_provider_replacement"); current.destroy();
        });
    }

    private static void navigation() {
        for(String name:List.of("linear","guided")) {
            steps.add(()-> {
                current=npc(false,true); trait=current.getOrAddTrait(Waypoints.class); trait.setWaypointProvider(name); provider=trait.getCurrentProvider(); actions=0; probe=null;
                current.getNavigator().getDefaultParameters().locationStrategyFactory((npc,parameters,target)->probe=new Probe(target));
                Waypoint target=new Waypoint(new Location(level,12,-58,4)); target.addTrigger(new CountTrigger());
                if(provider instanceof LinearWaypointProvider linear) linear.addWaypoint(target);
                else {var guided=(GuidedWaypointProvider)provider; guided.addGuide(new Waypoint(new Location(level,8,-58,4))); guided.addDestination(target);}
                waiting=()->probe!=null;
            });
            steps.add(()-> {current.getNavigator().cancelNavigation(); provider.setPaused(true); delay(4);});
            steps.add(()-> {check(actions==0,"cancelled_segment_does_not_reach_waypoint_"+name); current.destroy();});
        }
        steps.add(()-> {
            current=npc(false,true); trait=current.getOrAddTrait(Waypoints.class); provider=trait.getCurrentProvider(); actions=0; probe=null;
            current.getNavigator().getDefaultParameters().locationStrategyFactory((npc,parameters,target)->probe=new Probe(target));
            Waypoint target=new Waypoint(new Location(level,12,-58,4)); target.addTrigger(new CountTrigger()); ((LinearWaypointProvider)provider).addWaypoint(target);
            begin=event->{if(event.getNavigator().getNPC()==current){begin=null; event.getNavigator().setTarget(new Location(level,20,-58,4));}};
            waiting=()->current.getNavigator().getTargetAsLocation()!=null&&current.getNavigator().getTargetAsLocation().getX()==20;
        });
        steps.add(()-> {
            var foreign=current.getNavigator().getPathStrategy(); trait.setWaypointProvider("guided");
            check(current.getNavigator().getPathStrategy()==foreign,"provider_cleanup_preserves_callback_navigation"); probe.done=true; delay(4);
        });
        steps.add(()-> {check(actions==0,"superseded_route_callback_not_attached_to_foreign_navigation"); current.destroy();});
        steps.add(()-> {
            current=npc(false,true); current.getEntity().setNoGravity(true); current.getEntity().setPos(4,-60,4);
            trait=current.getOrAddTrait(Waypoints.class); provider=trait.getCurrentProvider(); actions=0; probe=null;
            var linear=(LinearWaypointProvider)provider; linear.setCachePaths(true);
            current.getNavigator().getDefaultParameters().locationStrategyFactory((npc,parameters,target)->probe=new Probe(target));
            Waypoint target=new Waypoint(new Location(level,12,-60,4));
            target.addTrigger(new WaypointTrigger(){public String description(){return "pause-on-reach";} public void onWaypointReached(NPC npc,Location at){actions++; provider.setPaused(true);}});
            linear.addWaypoint(target); waiting=()->probe!=null;
        });
        steps.add(()-> {probe.done=true; waiting=()->actions==1;});
        steps.add(()-> {
            check(provider.isPaused(),"successful_arrival_fires_trigger_once");
            var field=LinearWaypointProvider.class.getDeclaredField("cachedPaths"); field.setAccessible(true);
            check(!((Map<?,?>)field.get(provider)).isEmpty(),"successful_route_is_cached");
            provider.setPaused(false); waiting=()->current.getNavigator().isNavigating();
        });
        steps.add(()-> {check(!(current.getNavigator().getPathStrategy() instanceof Probe),"cached_route_uses_supplied_native_path"); current.destroy();});
        for(boolean straight:new boolean[]{false,true}) steps.add(()-> {
            current=npc(false,true); final int[] callbacks={0}; final boolean[] configured={false};
            begin=event->{if(event.getNavigator().getNPC()==current){check(configured[0],"request_configured_before_begin_"+straight); begin=null; event.getNavigator().cancelNavigation();}};
            java.util.function.Consumer<net.citizensnpcs.api.ai.NavigatorParameters> configure=params->{configured[0]=true; params.addSingleUseCallback(reason->callbacks[0]++);};
            if(straight) current.getNavigator().setStraightLineTarget(new Location(level,12,-58,4),configure);
            else current.getNavigator().setTarget(List.of(new Vec3(4,-58,4),new Vec3(12,-58,4)),configure);
            check(callbacks[0]==1&&!current.getNavigator().isNavigating(),"request_callback_handles_begin_cancellation_"+straight); current.destroy();
        });
        steps.add(()-> {
            current=npc(false,true); current.getNavigator().getDefaultParameters().locationStrategyFactory((npc,parameters,target)->probe=new Probe(target));
            var goal=net.citizensnpcs.api.ai.goals.WanderGoal.builder(current).destinationPicker(npc->new Location(level,12,-58,4)).build();
            check(goal.shouldExecute(),"wander_starts_owned_request"); current.getNavigator().setTarget(new Location(level,20,-58,4));
            var foreign=current.getNavigator().getPathStrategy(); goal.pause(); check(current.getNavigator().getPathStrategy()==foreign,"wander_pause_preserves_replacement_navigation"); current.destroy();
        });
    }

    private static final class CountTrigger implements WaypointTrigger {
        public String description(){return "count";} public void onWaypointReached(NPC npc,Location at){actions++;}
    }
    public static final class CountingProvider implements WaypointProvider {
        int attaches,spawns,removes; boolean paused; Runnable removing;
        public WaypointEditor createEditor(CommandSourceStack sender,CommandContext args){return null;}
        public boolean isPaused(){return paused;} public void setPaused(boolean value){paused=value;}
        public void onAttach(NPC npc){attaches++;} public void onSpawn(NPC npc){spawns++;}
        public void onRemove(){removes++; if(removing!=null){Runnable callback=removing; removing=null; callback.run();}}
        public void load(DataKey key){} public void save(DataKey key){}
    }
    private static final class Probe extends net.citizensnpcs.api.ai.AbstractPathStrategy {
        final Location target; boolean done;
        Probe(Location target){super(net.citizensnpcs.api.ai.TargetType.LOCATION); this.target=target;}
        public Location getCurrentDestination(){return target;} public Location getTargetAsLocation(){return target;}
        public Iterable<Vec3> getPath(){return List.of(new Vec3(target.getX(),target.getY(),target.getZ()));}
        public boolean update(){return done;} public void stop(){}
    }
    private static NPC npc(boolean packet,boolean spawn) {
        NPC npc=registry.createNPC(EntityType.COW,"WaypointOpt"+sequence++); npcs.add(npc); npc.getOrAddTrait(Owner.class).setOwner(alice.player.getUUID()); npc.getOrAddTrait(LookClose.class).setEnabled(false);
        if(packet) npc.getOrAddTrait(PacketNPC.class); if(spawn) check(npc.spawn(new Location(level,4,-58,4)),"fixture_spawn"); return npc;
    }
    private static void select(NPC npc) { CitizensAPI.getDefaultNPCSelector().select(source,npc); }
    private static void run(String command) throws Exception { check(server.getCommands().getDispatcher().execute(command,source)==1,"dispatch_"+command); alice.pump(); }
    private static void reject(String command) throws Exception { try { check(server.getCommands().getDispatcher().execute(command,source)==0,"reject_"+command); } catch(CommandSyntaxException expected) { check(true,"reject_"+command); } }
    private static void delay(int ticks) { int until=server.getTickCount()+ticks; waiting=()->server.getTickCount()>=until; }
    private static void check(boolean value,String label) { if(!value) throw new AssertionError(label); passed++; }
    private static void close() {
        if(done) return; done=true; begin=null;
        try { for(NPC npc:npcs) npc.destroy(); if(grant!=null) grant.remove(); if(alice!=null) {server.getPlayerList().remove(alice.player); alice.channel.finishAndReleaseAll();} if(forced) level.setChunkForced(0,0,false); }
        catch(Throwable failure) {LoggerFactory.getLogger("citizens").error("[WAYPOINTLIFECYCLEAUDIT] FAILED cleanup",failure);} finally {server.halt(false);}
    }
    private static Actor actor(String name) {
        Actor actor = new Actor();
        ClientInformation information = ClientInformation.createDefault();
        actor.player = new AuditPlayer(name);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("waypoint-lifecycle-audit-capture", new ChannelOutboundHandlerAdapter() {
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
        AuditPlayer(String name) { super(WaypointLifecycleRuntimeAudit.server, WaypointLifecycleRuntimeAudit.level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault()); }
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
