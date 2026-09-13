package net.citizensnpcs.trait.versioned;

import java.util.Locale;
import java.util.Optional;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * An area effect cloud NPC's size, lifetime, particle and potion.
 * <p>
 * Vanilla keeps the potion and the tint together in one record, so both are written at once. The colour keeps upstream's
 * on-disk shape — a single ARGB int — even though vanilla's custom colour has no alpha channel.
 * <p>
 * Two departures from upstream. Its {@code radiusPerTick} branch calls {@code setRadius}, so configuring the shrink rate
 * silently resized the cloud instead; that is a plain bug and the right setter is used here. And the particle and potion
 * are registry lookups rather than Bukkit enums, so ones added by other mods work with no code change.
 */
@TraitName("areaeffectcloudtrait")
public class AreaEffectCloudTrait extends Trait {
    @Persist
    private Integer color;
    @Persist
    private Integer duration;
    private ParticleOptions particle;
    @Persist
    private Float radius;
    @Persist
    private Float radiusPerTick;
    private Holder<Potion> type;
    private String unresolvedParticle;
    private String unresolvedPotion;

    public AreaEffectCloudTrait() {
        super("areaeffectcloudtrait");
    }

    public Integer getColor() {
        return color;
    }

    public Integer getDuration() {
        return duration;
    }

    public ParticleOptions getParticle() {
        return particle;
    }

    public Float getRadius() {
        return radius;
    }

    public Float getRadiusPerTick() {
        return radiusPerTick;
    }

    public Holder<Potion> getPotionType() {
        return type;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        String rawParticle = key.getString("particle"), rawPotion = key.getString("type");
        particle = parseParticle(rawParticle);
        type = parsePotion(rawPotion);
        unresolvedParticle = particle == null && !rawParticle.isEmpty() ? rawParticle : null;
        unresolvedPotion = type == null && !rawPotion.isEmpty() ? rawPotion : null;
    }

    @Override
    public void save(DataKey key) {
        key.setString("particle", unresolvedParticle != null ? unresolvedParticle : particle == null ? ""
                : BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType()).toString());
        key.setString("type", unresolvedPotion != null ? unresolvedPotion
                : type == null ? "" : type.unwrapKey().map(k -> k.location().toString()).orElse(""));
    }

    @Override
    public void onSpawn() {
        if (!(npc.getCosmeticEntity() instanceof AreaEffectCloud cloud))
            return;
        if (radius != null) {
            cloud.setRadius(radius);
        }
        if (radiusPerTick != null) {
            cloud.setRadiusPerTick(radiusPerTick);
        }
        if (duration != null) {
            cloud.setDuration(duration);
        }
        if (particle != null) {
            cloud.setParticle(particle);
        }
        if (type != null || color != null) {
            // one record holds both, so neither can be set without the other
            cloud.setPotionContents(new PotionContents(Optional.ofNullable(type),
                    color == null ? Optional.empty() : Optional.of(color & 0xFFFFFF), java.util.List.of()));
        }
    }

    /** Accepts a bare name or a full id; null when empty, and warns when the name matches no particle. */
    public static ParticleOptions parseParticle(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        Object particle = id == null ? null : BuiltInRegistries.PARTICLE_TYPE.get(id);
        if (particle instanceof ParticleOptions options)
            return options;
        // a particle that takes parameters (dust, block, item …) is a type without a ready-made options instance, and
        // Citizens has nowhere to store those parameters
        Messaging.warn("Cannot use '" + raw + "' as an area effect cloud particle: unknown, or it needs parameters.");
        return null;
    }

    /** Accepts a bare name or a full id; null when empty or unknown. */
    @SuppressWarnings("unchecked")
    public static Holder<Potion> parsePotion(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        return id == null ? null
                : BuiltInRegistries.POTION.getHolder(id).map(h -> (Holder<Potion>) h).orElse(null);
    }

    public void setColor(Integer color) {
        this.color = color;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public void setParticle(ParticleOptions particle) {
        unresolvedParticle = null;
        this.particle = particle;
    }

    public void setPotionType(Holder<Potion> type) {
        unresolvedPotion = null;
        this.type = type;
    }

    public void setRadius(Float radius) {
        this.radius = radius;
    }

    public void setRadiusPerTick(Float radiusPerTick) {
        this.radiusPerTick = radiusPerTick;
    }
}
