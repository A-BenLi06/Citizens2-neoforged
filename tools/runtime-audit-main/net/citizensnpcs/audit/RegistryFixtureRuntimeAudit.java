package net.citizensnpcs.audit;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Opt-in registry collision fixture; never included in release jars. */
@EventBusSubscriber(modid = "citizens", bus = EventBusSubscriber.Bus.MOD)
public final class RegistryFixtureRuntimeAudit {
    private static final ResourceLocation ATTRIBUTE = ResourceLocation.fromNamespaceAndPath("citizens_audit", "generic.max_health");
    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(Registries.ATTRIBUTE, ATTRIBUTE,
                () -> new RangedAttribute("attribute.citizens_audit.health", 6, 0, 1000));
    }

    @SubscribeEvent
    public static void attributes(net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent event) {
        event.add(net.minecraft.world.entity.EntityType.PIG,
                net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getHolder(ATTRIBUTE).orElseThrow());
    }
}
