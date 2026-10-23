package net.citizensnpcs.audit;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.modules.holograms.HologramDefinition;
import eu.avalanche7.paradigm.modules.holograms.HologramService;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.trait.HologramTrait;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

/** Actual provider-owned rendering and reload, isolated from physical-client acceptance. */
@EventBusSubscriber(modid="citizens")
public final class ParadigmHologramRuntimeAudit {
    private static final String ID="reader_lifetime";
    private static final List<String> LINES=List.of("Owner reader lifetime", "Literal $5 \\ Unicode 雪");
    private static int stage, passed, next, deadline;
    private static HologramService service;
    private static NPC npc;
    private static String config;
    private static Map<String,String> initial;
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        String phase=System.getenv("PARADIGM_HOLOGRAM_AUDIT_PHASE");
        if(phase==null||stage==99)return;
        var server=event.getServer(); var level=server.overworld();
        level.setChunkForced(0,0,true);
        if(server.getTickCount()<20||!level.isPositionEntityTicking(new BlockPos(1,-60,1)))return;
        try {
            if(deadline==0)deadline=server.getTickCount()+400;
            if(server.getTickCount()>deadline)throw new AssertionError("Native hologram lifecycle timed out at stage "+stage);
            if(server.getTickCount()<next)return;
            Path file=server.getServerDirectory().resolve("config/paradigm/holograms.json");
            Path seed=server.getServerDirectory().resolve("hologram-audit-seed.json");
            if(stage==0) {
                check(ParadigmAPI.getServices()!=null,"actual_provider_services_available");
                service=ParadigmAPI.getServices().getHologramService();
                check(service!=null&&service.supported()&&service.capabilities().textDisplay(),"actual_neoforge_text_display_platform_available");
                check(service.snapshot().enabled,"provider_holograms_enabled_on_existing_file");
                check(String.valueOf(net.neoforged.neoforge.server.permission.PermissionAPI.getActivePermissionHandler()).equals("paradigm:internal"),"provider_permission_handler_remains_active");
                if(phase.equals("seed")) {
                    check(!Files.exists(seed)&&service.definition(ID)==null,"fresh_native_acceptance_has_no_prior_owned_definition");
                    var definition=new HologramDefinition();definition.dimension=level.dimension().location().toString();
                    definition.x=3.125;definition.y=-59.5;definition.z=3.875;definition.lines=new ArrayList<>(LINES);
                    definition.viewDistance=37.5;definition.refreshIntervalSeconds=1;definition.lineSpacing=0.42;
                    service.put(ID,definition);service.updateSettings(true,51,1);
                    npc=CitizensAPI.getNPCRegistry().createNPC(EntityType.PIG,"Hologram peer");
                    npc.getOrAddTrait(HologramTrait.class).addLine("Citizens owned");
                    check(npc.spawn(new Location(level,6,-60,6)),"citizens_peer_spawns_beside_provider_hologram");
                }else if(phase.equals("recover")) {
                    var prior=JsonParser.parseString(Files.readString(seed)).getAsJsonObject();
                    check(JsonParser.parseString(Files.readString(file)).equals(prior.get("config")),"restart_preserves_complete_original_hologram_configuration");
                    check(service.definition(ID)!=null&&service.definition(ID).lines.equals(LINES),"restart_loads_original_line_order_and_literals");
                    check(service.snapshot().defaultViewDistance==51&&service.snapshot().defaultRefreshIntervalSeconds==1,"restart_preserves_global_hologram_settings");
                }else throw new IllegalArgumentException("Unknown native hologram phase");
                config=Files.readString(file);
                check(service.definition(ID).lines.equals(LINES),"owner_definition_contains_exact_static_lines");
                stage=1;next=server.getTickCount()+40;return;
            }
            var displays=new TreeMap<String,String>();
            var texts=new TreeMap<String,String>();
            for(var entity:level.getAllEntities())if(entity instanceof Display.TextDisplay display) {
                for(String tag:entity.getTags())if(tag.startsWith("paradigm_hologram:line:"+ID+":")) {
                    check(displays.put(tag,entity.getUUID().toString())==null,"one_actual_entity_per_provider_line_key");
                    var data=entity.saveWithoutId(new CompoundTag());
                    texts.put(tag,Objects.requireNonNull(Component.Serializer.fromJson(data.getString("text"),server.registryAccess())).getString());
                }
            }
            check(displays.size()==LINES.size(),"actual_world_contains_all_provider_owned_text_displays");
            for(int index=0;index<LINES.size();index++)check(LINES.get(index).equals(texts.get("paradigm_hologram:line:"+ID+":"+index)),"native_text_display_preserves_exact_line_"+index);
            check(service.runtimeStatus().values().stream().anyMatch(value->value.id().equals(ID)&&value.persistent()&&value.chunkLoaded()&&value.renderedEntities()==LINES.size()),"owner_runtime_status_matches_actual_loaded_entities");
            if(stage==1) {
                initial=displays;service.reload();
                check(service.snapshot().enabled&&Files.readString(file).equals(config),"live_native_reload_keeps_enabled_configuration_byte_stable");
                stage=2;next=server.getTickCount()+40;return;
            }
            check(initial.equals(displays),"live_reload_reuses_original_entities_without_duplicates");
            if(npc!=null)check(npc.isSpawned()&&npc.getEntity().isAlive(),"citizens_peer_survives_provider_reload");
            var result=new JsonObject();result.addProperty("phase",phase);result.addProperty("checks",passed);
            result.add("config",JsonParser.parseString(config));result.add("entities",JSON.toJsonTree(displays));result.add("texts",JSON.toJsonTree(texts));
            Path state=phase.equals("seed")?seed:server.getServerDirectory().resolve("hologram-audit-recovered.json");
            Files.writeString(state,JSON.toJson(result),StandardOpenOption.CREATE_NEW);
            LoggerFactory.getLogger("citizens").info("[PARADIGMHOLOAUDIT] COMPLETE {} checks",passed);
            finish(server);
        }catch(Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[PARADIGMHOLOAUDIT] FAILED",failure);finish(server);
        }
    }
    private static void finish(net.minecraft.server.MinecraftServer server) {
        stage=99;if(npc!=null)npc.destroy();server.halt(false);
    }
    private static void check(boolean value,String name) {
        if(!value)throw new AssertionError(name);passed++;LoggerFactory.getLogger("citizens").info("[PARADIGMHOLOAUDIT] PASS {}",name);
    }
}
