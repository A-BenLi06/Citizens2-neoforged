package net.citizensnpcs.audit;

import java.io.File;
import java.util.List;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.Unpooled;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.trait.versioned.AreaEffectCloudTrait;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.joml.Vector3f;

/** Native codecs and real commands, including an optional parameterized provider across JVM restarts. */
@EventBusSubscriber(modid = "citizens", bus = EventBusSubscriber.Bus.MOD)
public final class CloudParticleRuntimeAudit {
    private record Sample(String command, ParticleOptions expected) { }
    private record ProviderParticle(int amount, String label) implements ParticleOptions {
        static final MapCodec<ProviderParticle> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.intRange(0, 100).fieldOf("amount").forGetter(ProviderParticle::amount),
                Codec.STRING.fieldOf("label").forGetter(ProviderParticle::label)).apply(instance, ProviderParticle::new));
        static final StreamCodec<RegistryFriendlyByteBuf, ProviderParticle> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ProviderParticle::amount, ByteBufCodecs.STRING_UTF8, ProviderParticle::label, ProviderParticle::new);
        @Override public ParticleType<?> getType() { return PROVIDER; }
    }
    private static final ParticleType<ProviderParticle> PROVIDER = new ParticleType<>(false) {
        @Override public MapCodec<ProviderParticle> codec() { return ProviderParticle.CODEC; }
        @Override public StreamCodec<? super RegistryFriendlyByteBuf, ProviderParticle> streamCodec() { return ProviderParticle.STREAM_CODEC; }
    };
    @SubscribeEvent public static void register(RegisterEvent event) {
        if (Boolean.getBoolean("citizens.audit.effectProvider"))
            event.register(Registries.PARTICLE_TYPE, EntityCommandRegistryRuntimeAudit.id("returning_particle"), () -> PROVIDER);
    }

    static void run(MinecraftServer server, ServerPlayer player) throws Exception {
        var source = player.createCommandSourceStack().withPermission(4);
        var registry = CitizensAPI.getNPCRegistry();
        NPC npc = registry.createNPC(EntityType.AREA_EFFECT_CLOUD, "ParticleAudit");
        npc.getOrAddTrait(Owner.class).setOwner(player.getUUID());
        npc.getOrAddTrait(Spawned.class).setSpawned(false);
        CitizensAPI.getDefaultNPCSelector().select(source, npc);
        var samples = List.of(
                new Sample("FLAME", ParticleTypes.FLAME),
                new Sample("dust{color:[0.25,0.5,1.0],scale:1.75}", new DustParticleOptions(new Vector3f(0.25F, 0.5F, 1), 1.75F)),
                new Sample("dust_color_transition{from_color:[1,0,0],to_color:[0,0,1],scale:2}", new DustColorTransitionOptions(new Vector3f(1,0,0), new Vector3f(0,0,1), 2)),
                new Sample("block{block_state:{Name:\"minecraft:oak_log\",Properties:{axis:\"x\"}}}", new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X))),
                new Sample("item{item:\"minecraft:paper\"}", new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.PAPER))),
                new Sample("shriek{delay:17}", new ShriekParticleOption(17)));
        for (Sample sample : samples) {
            check(server.getCommands().getDispatcher().execute("npc areaeffectcloud --particle `" + sample.command + "`", source) > 0, "command_" + sample.command);
            var trait = npc.getTrait(AreaEffectCloudTrait.class);
            check(equal(server, trait.getParticle(), sample.expected), "stored_options_" + sample.command);
            var saved = new MemoryDataKey(); trait.save(saved);
            var restored = new AreaEffectCloudTrait(); restored.load(saved);
            check(equal(server, restored.getParticle(), sample.expected), "codec_roundtrip_" + sample.command);
            NPC copy = npc.copy();
            check(equal(server, copy.getTrait(AreaEffectCloudTrait.class).getParticle(), sample.expected), "npc_copy_" + sample.command); copy.destroy();
            check(npc.spawn(new Location(server.overworld(), 4, -60, 56)), "spawn_" + sample.command);
            check(equal(server, ((AreaEffectCloud) npc.getEntity()).getParticle(), sample.expected), "live_options_" + sample.command);
            check(server.getCommands().getDispatcher().execute("npc areaeffectcloud --particle `" + sample.command + "`", source) > 0
                    && equal(server, ((AreaEffectCloud) npc.getEntity()).getParticle(), sample.expected), "live_command_" + sample.command);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
            try {
                ParticleTypes.STREAM_CODEC.encode(buffer, ((AreaEffectCloud) npc.getEntity()).getParticle());
                check(equal(server, ParticleTypes.STREAM_CODEC.decode(buffer), sample.expected), "wire_options_" + sample.command);
            } finally { buffer.release(); }
            npc.despawn(); npc.getOrAddTrait(Spawned.class).setSpawned(false);
        }
        ItemStack item = new ItemStack(Items.PAPER);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("MiXeD name with spaces"));
        var expectedItem = new ItemParticleOption(ParticleTypes.ITEM, item);
        var trait = npc.getTrait(AreaEffectCloudTrait.class); trait.setParticle(expectedItem);
        var disk = new YamlStorage(new File("cloud-particle-audit/item.yml")); trait.save(disk.getKey("")); disk.save();
        var reload = new YamlStorage(new File("cloud-particle-audit/item.yml")); reload.load();
        var restored = new AreaEffectCloudTrait(); restored.load(reload.getKey(""));
        check(equal(server, restored.getParticle(), expectedItem), "item_components_and_case_survive_disk");
        String serialized = reload.getKey("").getString("particle");
        check(server.getCommands().getDispatcher().execute("npc areaeffectcloud --particle `" + serialized + "`", source) > 0
                && equal(server, trait.getParticle(), expectedItem), "serialized_item_is_native_command_syntax");
        var before = new MemoryDataKey(); trait.save(before);
        for (String invalid : List.of("dust", "dust{color:bad,scale:1}", "flame trailing", "flame{} trailing", "item{item:\"absent:item\"}", "absent:particle{value:7}")) {
            boolean rejected = false;
            try { server.getCommands().getDispatcher().execute("npc areaeffectcloud --radius 9 --particle `" + invalid + "`", source); }
            catch (CommandSyntaxException expected) { rejected = true; }
            check(rejected && trait.getRadius() == null && equal(server, trait.getParticle(), expectedItem), "invalid_command_is_atomic_" + invalid);
            var raw = new MemoryDataKey(); raw.setString("particle", invalid);
            restored.load(raw); var kept = new MemoryDataKey(); restored.save(kept);
            check(restored.getParticle() == null && kept.getString("particle").equals(invalid), "invalid_raw_retained_" + invalid);
        }
        restored.setParticle(ParticleTypes.SMOKE); var cleared = new MemoryDataKey(); restored.save(cleared);
        check(cleared.getString("particle").equals("minecraft:smoke"), "explicit_replace_clears_unresolved_particle");
        restored.setParticle(null); restored.save(cleared);
        check(cleared.getString("particle").isEmpty(), "explicit_clear_particle");
        providerRecovery(server, npc, player);
        npc.destroy();
    }

    private static void providerRecovery(MinecraftServer server, NPC npc, ServerPlayer player) throws Exception {
        var file = new File("cloud-particle-audit/provider.yml");
        boolean present = Boolean.getBoolean("citizens.audit.effectProvider");
        boolean existed = file.isFile();
        if (present && !existed) throw new AssertionError("Run the absent-provider phase before recovery");
        var disk = new YamlStorage(file);
        if (existed) disk.load();
        else disk.getKey("").setString("particle", EntityCommandRegistryRuntimeAudit.id("returning_particle") + "{amount:7,label:\"MiXeD Label\"}");
        String raw = disk.getKey("").getString("particle");
        String id = EntityCommandRegistryRuntimeAudit.id("returning_particle").toString();
        if (!raw.startsWith(id + "{")) throw new AssertionError("Missing provider particle fixture definition");
        var rawOptions = net.minecraft.nbt.TagParser.parseTag(raw.substring(id.length()));
        check(rawOptions.getInt("amount") == 7 && rawOptions.getString("label").equals("MiXeD Label"), "provider_fixture_contains_original_parameters");
        var trait = npc.getTrait(AreaEffectCloudTrait.class); trait.load(disk.getKey(""));
        if (present) {
            check(new ProviderParticle(7, "MiXeD Label").equals(trait.getParticle()), "returning_provider_resolves_all_options");
            var source = player.createCommandSourceStack().withPermission(4);
            CitizensAPI.getDefaultNPCSelector().select(source, npc);
            check(server.getCommands().getDispatcher().execute("npc areaeffectcloud --particle `" + raw + "`", source) > 0, "custom_parameter_command");
            check(npc.spawn(new Location(server.overworld(), 4, -60, 56))
                    && ((AreaEffectCloud) npc.getEntity()).getParticle().equals(new ProviderParticle(7, "MiXeD Label")), "custom_parameter_applied_live");
            npc.despawn();
        } else check(trait.getParticle() == null, "missing_provider_not_substituted");
        trait.save(disk.getKey("")); disk.save();
        var reloaded = new YamlStorage(file); reloaded.load();
        if (!present) check(reloaded.getKey("").getString("particle").equals(raw), "missing_provider_raw_survives_disk");
        else {
            var restored = new AreaEffectCloudTrait(); restored.load(reloaded.getKey(""));
            check(equal(server, restored.getParticle(), new ProviderParticle(7, "MiXeD Label")), "custom_parameters_survive_disk");
        }
    }
    private static boolean equal(MinecraftServer server, ParticleOptions actual, ParticleOptions expected) {
        return actual != null && encoded(server, actual).equals(encoded(server, expected));
    }
    private static Tag encoded(MinecraftServer server, ParticleOptions value) {
        return ParticleTypes.CODEC.encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), value).getOrThrow();
    }
    private static void check(boolean result, String name) { EntityCommandRuntimeAudit.check(result, "cloud_particle_" + name); }
}
