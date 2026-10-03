package net.citizensnpcs.audit;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.Cow;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = "citizens", bus = EventBusSubscriber.Bus.MOD)
public final class DatastoreProviderRuntimeAudit {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("citizens_datastore_audit", "animal");
    static EntityType<Cow> type;

    @SubscribeEvent public static void register(RegisterEvent event) {
        if (!"present".equals(System.getProperty("citizens.datastore.audit.phase"))) return;
        event.register(Registries.ENTITY_TYPE, ID, () -> type = EntityType.Builder.of(Cow::new, MobCategory.CREATURE)
                .sized(0.9F, 1.4F).build(ID.toString()));
    }

    @SubscribeEvent public static void attributes(EntityAttributeCreationEvent event) {
        if (type != null) event.put(type, Cow.createAttributes().build());
    }
}
