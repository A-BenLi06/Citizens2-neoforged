package net.yuuniverse.interactions;

import java.nio.file.Files;
import java.nio.file.Path;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.trait.AttributeTrait;
import net.citizensnpcs.trait.PaintingTrait;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Painting;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class RegistryPersistenceRuntimeAudit {
    private static boolean ran;
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || net.citizensnpcs.audit.FixtureRuntimeAudit.elapsedTicks(event.getServer()) < 95) return;
        ran = true;
        var registry = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
        var painting = registry.createNPC(EntityType.PAINTING, "RegistryPaintingAudit");
        var pig = registry.createNPC(EntityType.PIG, "RegistryAttributeAudit");
        Path file = null;
        try {
            var customArt = PaintingTrait.parse("citizens_audit:alban");
            var vanillaArt = PaintingTrait.parse("ALBAN");
            check(customArt != null && vanillaArt != null && !customArt.equals(vanillaArt), "same_path_paintings_resolve_distinctly");
            var customAttribute = AttributeTrait.parse("citizens_audit:generic.max_health");
            var vanillaAttribute = AttributeTrait.parse("GENERIC_MAX_HEALTH");
            check(customAttribute != null && vanillaAttribute != null && !customAttribute.equals(vanillaAttribute)
                    && vanillaAttribute.equals(AttributeTrait.parse("minecraft:generic.max_health")), "legacy_attribute_alias_remains_vanilla");
            file = Files.createTempFile("citizens-registry-audit", ".yml");
            var storage = new YamlStorage(file.toFile());
            var art = painting.getOrAddTrait(PaintingTrait.class);
            art.setArt(customArt);
            art.save(storage.getKey("painting"));
            check(storage.getKey("painting").getString("art").equals("citizens_audit:alban"), "painting_save_keeps_namespace");
            var attributes = pig.getOrAddTrait(AttributeTrait.class);
            attributes.setAttributeValue(customAttribute, 34);
            attributes.setAttributeValue(vanillaAttribute, 12);
            attributes.save(storage.getKey("pig"));
            var values = (java.util.Map<?, ?>) storage.getKey("pig").getRaw("attributes");
            check(values.size() == 2 && values.containsKey("citizens_audit:generic.max_health")
                    && values.containsKey("GENERIC_MAX_HEALTH"), "attribute_save_keeps_literal_dotted_registry_key");
            storage.save();
            var reloaded = new YamlStorage(file.toFile());
            if (!reloaded.load()) throw new AssertionError("YAML reload failed");
            art.setArt(null);
            art.load(reloaded.getKey("painting"));
            check(customArt.equals(art.getArt()), "custom_painting_survives_yaml_reload");
            attributes.load(reloaded.getKey("pig"));
            check(attributes.getAttributeValue(customAttribute) == 34 && attributes.getAttributeValue(vanillaAttribute) == 12,
                    "same_path_attributes_survive_yaml_reload_independently");
            var position = new Location(event.getServer().overworld(), 0, -40, 0);
            if (!painting.spawn(position) || !pig.spawn(position)) throw new AssertionError("NPC spawn failed");
            art.run();
            check(((Painting) painting.getEntity()).getVariant().equals(customArt), "loaded_painting_applies_custom_variant");
            var living = (LivingEntity) pig.getEntity();
            check(living.getAttributeBaseValue(customAttribute) == 34 && living.getAttributeBaseValue(vanillaAttribute) == 12,
                    "loaded_attributes_apply_to_modded_entity");
            painting.despawn();
            pig.despawn();
            if (!painting.spawn(position) || !pig.spawn(position)) throw new AssertionError("NPC respawn failed");
            art.run();
            check(((Painting) painting.getEntity()).getVariant().equals(customArt)
                    && ((LivingEntity) pig.getEntity()).getAttributeBaseValue(customAttribute) == 34,
                    "custom_registry_settings_survive_respawn");
            art.setArt(vanillaArt);
            art.save(reloaded.getKey("painting"));
            check(reloaded.getKey("painting").getString("art").equals("ALBAN"), "vanilla_painting_save_retains_legacy_name");
            var control = EntityType.PIG.create(event.getServer().overworld());
            double defaultHealth = control.getAttributeBaseValue(vanillaAttribute);
            double defaultCustom = control.getAttributeBaseValue(customAttribute);
            control.discard();
            check(defaultHealth != vanillaAttribute.value().getDefaultValue()
                    && defaultCustom != customAttribute.value().getDefaultValue(), "reset_fixture_distinguishes_type_and_registry_defaults");
            attributes.resetToDefaultValue(vanillaAttribute);
            check(((LivingEntity) pig.getEntity()).getAttributeBaseValue(vanillaAttribute) == vanillaAttribute.value().getDefaultValue(),
                    "reset_matches_legacy_attribute_default_health");
            attributes.resetToDefaultValue(customAttribute);
            check(((LivingEntity) pig.getEntity()).getAttributeBaseValue(customAttribute) == customAttribute.value().getDefaultValue(),
                    "reset_uses_attribute_default_despite_modified_type_default");
            attributes.save(reloaded.getKey("pig"));
            check(!attributes.hasAttribute(vanillaAttribute) && !attributes.hasAttribute(customAttribute)
                    && ((java.util.Map<?, ?>) reloaded.getKey("pig").getRaw("attributes")).isEmpty(), "reset_removes_saved_overrides");
            pig.despawn();
            if (!pig.spawn(position)) throw new AssertionError("reset respawn failed");
            check(((LivingEntity) pig.getEntity()).getAttributeBaseValue(vanillaAttribute) == defaultHealth
                    && ((LivingEntity) pig.getEntity()).getAttributeBaseValue(customAttribute) == defaultCustom,
                    "respawn_uses_type_defaults_after_override_removal");
            reloaded.getKey("pig").setRaw("attributes", java.util.Map.of("missing_fixture:generic.attribute", 41.25));
            attributes.load(reloaded.getKey("pig"));
            attributes.save(reloaded.getKey("pig"));
            check(((java.util.Map<?, ?>) reloaded.getKey("pig").getRaw("attributes"))
                    .get("missing_fixture:generic.attribute").equals(41.25), "missing_attribute_survives_save");
            reloaded.getKey("painting").setString("art", "missing_fixture:lost_painting");
            art.load(reloaded.getKey("painting"));
            art.save(reloaded.getKey("painting"));
            check(art.getArt() == null && reloaded.getKey("painting").getString("art").equals("missing_fixture:lost_painting"),
                    "missing_painting_id_survives_save");
            reloaded.save();
            var absentReload = new YamlStorage(file.toFile());
            if (!absentReload.load()) throw new AssertionError("unresolved YAML reload failed");
            attributes.load(absentReload.getKey("pig"));
            art.load(absentReload.getKey("painting"));
            attributes.save(absentReload.getKey("pig"));
            art.save(absentReload.getKey("painting"));
            check(((java.util.Map<?, ?>) absentReload.getKey("pig").getRaw("attributes"))
                    .get("missing_fixture:generic.attribute").equals(41.25)
                    && absentReload.getKey("painting").getString("art").equals("missing_fixture:lost_painting"),
                    "missing_registry_values_survive_yaml_reload");
            attributes.setAttributeValue(customAttribute, 17);
            attributes.save(absentReload.getKey("pig"));
            check(((java.util.Map<?, ?>) absentReload.getKey("pig").getRaw("attributes")).size() == 2,
                    "editing_known_attribute_preserves_missing_entries");
            art.setArt(customArt);
            art.save(absentReload.getKey("painting"));
            check(absentReload.getKey("painting").getString("art").equals("citizens_audit:alban"),
                    "explicit_painting_selection_replaces_missing_id");
            art.load(reloaded.getKey("painting"));
            art.setArt(null);
            art.save(absentReload.getKey("painting"));
            check(absentReload.getKey("painting").getString("art").isEmpty(), "explicit_painting_clear_removes_missing_id");
            LoggerFactory.getLogger("interactions").info("[REGISTRYPERSISTAUDIT] COMPLETE 21/21");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[REGISTRYPERSISTAUDIT] FAILED", failure);
        } finally {
            painting.destroy();
            pig.destroy();
            if (file != null) try { Files.deleteIfExists(file); } catch (Exception failure) {
                LoggerFactory.getLogger("interactions").warn("Could not remove registry audit file {}", file, failure);
            }
        }
    }
    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[REGISTRYPERSISTAUDIT] PASS {}", name);
    }
}
