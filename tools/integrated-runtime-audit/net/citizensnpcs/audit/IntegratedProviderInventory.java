package net.citizensnpcs.audit;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Records the resolved runtime inventory, including nested and bridged providers. */
@EventBusSubscriber(modid="citizens")
public final class IntegratedProviderInventory {
    private static boolean recorded;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        String phase=System.getenv("CITIZENS_INTEGRATED_AUDIT_PHASE");
        if(phase==null||recorded)return;
        recorded=true;
        try {
            var providers=new JsonArray();
            for(var mod:ModList.get().getMods()) {
                var value=new JsonObject();value.addProperty("id",mod.getModId());
                value.addProperty("version",mod.getVersion().toString());providers.add(value);
            }
            Files.writeString(event.getServer().getServerDirectory().resolve("runs").resolve(phase).resolve("loaded-providers.json"),
                    new Gson().toJson(providers),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }catch(Exception failure){
            org.slf4j.LoggerFactory.getLogger("citizens").error("[INTEGRATEDAUDIT] FAILED resolved provider inventory",failure);
            event.getServer().halt(false);
        }
    }
}
