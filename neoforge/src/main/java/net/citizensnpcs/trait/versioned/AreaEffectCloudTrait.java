package net.citizensnpcs.trait.versioned;

import java.util.Locale;
import java.util.Optional;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

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
                : serializeParticle(particle));
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

    /** Loads native particle syntax; unavailable or invalid definitions remain intact in the saved trait. */
    public static ParticleOptions parseParticle(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        try {
            return parseParticle(raw, registries());
        } catch (CommandSyntaxException | IllegalArgumentException failure) {
            Messaging.warn("Cannot use '" + raw + "' as an area effect cloud particle: " + failure.getMessage());
            return null;
        }
    }

    /** Registry-backed vanilla syntax, including each particle type's own options and validation. */
    public static ParticleOptions parseParticle(String raw, HolderLookup.Provider registries) throws CommandSyntaxException {
        String input = raw.trim();
        int options = input.indexOf('{');
        if (options < 0) options = input.length();
        // Retain legacy case-insensitive IDs without modifying case-sensitive NBT keys or component text.
        StringReader reader = new StringReader(input.substring(0, options).toLowerCase(Locale.ROOT) + input.substring(options));
        ParticleOptions result = ParticleArgument.readParticle(reader, registries);
        reader.skipWhitespace();
        if (reader.canRead()) throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
        return result;
    }

    private static HolderLookup.Provider registries() {
        var server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY) : server.registryAccess();
    }

    private static String serializeParticle(ParticleOptions particle) {
        CompoundTag encoded = (CompoundTag) ParticleTypes.CODEC
                .encodeStart(registries().createSerializationContext(NbtOps.INSTANCE), particle).getOrThrow();
        String id = encoded.getString("type");
        encoded.remove("type");
        // The dispatch codec owns the fields. Store the same id{options} syntax the native command parser accepts.
        return id + (encoded.isEmpty() ? "" : encoded.toString());
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
