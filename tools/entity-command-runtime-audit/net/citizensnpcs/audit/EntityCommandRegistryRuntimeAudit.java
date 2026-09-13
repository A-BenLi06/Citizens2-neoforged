package net.citizensnpcs.audit;

import com.google.common.collect.ImmutableSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.CatVariant;
import net.minecraft.world.entity.animal.FrogVariant;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.alchemy.Potion;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = "citizens", bus = EventBusSubscriber.Bus.MOD)
public final class EntityCommandRegistryRuntimeAudit {
    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("citizens_entity_audit", path); }
    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(Registries.CAT_VARIANT, id("cat"), () -> new CatVariant(id("textures/entity/cat.png")));
        event.register(Registries.FROG_VARIANT, id("frog"), () -> new FrogVariant(id("textures/entity/frog.png")));
        event.register(Registries.VILLAGER_TYPE, id("villager"), () -> new VillagerType("citizens_entity_audit"));
        event.register(Registries.VILLAGER_PROFESSION, id("profession"), () -> new VillagerProfession("citizens_entity_audit",
                poi -> false, poi -> false, ImmutableSet.of(), ImmutableSet.of(), SoundEvents.VILLAGER_WORK_FARMER));
        event.register(Registries.POTION, id("potion"), () -> new Potion(new MobEffectInstance(MobEffects.LUCK, 1200)));
        event.register(Registries.PARTICLE_TYPE, id("particle"), () -> new SimpleParticleType(false));
    }
}
