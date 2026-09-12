package net.citizensnpcs.trait;

import java.util.Locale;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Which picture a painting NPC shows.
 * <p>
 * Painting variants are a <em>datapack</em> registry in 1.21.1 rather than a Bukkit enum, so the lookup needs the server
 * and pictures added by a datapack or another mod work with no code change. Bukkit's art names are the registry ids
 * uppercased, so existing saves read back unchanged.
 */
@TraitName("paintingtrait")
public class PaintingTrait extends Trait {
    private Holder<PaintingVariant> art;
    private String unresolvedArt;

    public PaintingTrait() {
        super("paintingtrait");
    }

    public Holder<PaintingVariant> getArt() {
        return art;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        String stored = key.getString("art");
        art = parse(stored);
        unresolvedArt = art == null ? stored : null;
    }

    @Override
    public void save(DataKey key) {
        String stored = art == null ? unresolvedArt
                : art.unwrapKey().map(k -> k.location().getNamespace().equals("minecraft")
                        ? k.location().getPath().toUpperCase(Locale.ROOT) : k.location().toString()).orElse(null);
        key.setString("art", stored == null ? "" : stored);
    }

    @Override
    public void run() {
        if (art != null && npc.getEntity() instanceof Painting painting) {
            painting.setVariant(art);
        }
    }

    public void setArt(Holder<PaintingVariant> art) {
        this.art = art;
        unresolvedArt = null;
    }

    /**
     * @return the variant, or null when the name is empty, matches nothing, or no server is running
     */
    @SuppressWarnings("unchecked")
    public static Holder<PaintingVariant> parse(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return null;
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        return id == null ? null
                : server.registryAccess().registryOrThrow(Registries.PAINTING_VARIANT).getHolder(id)
                        .map(h -> (Holder<PaintingVariant>) h).orElse(null);
    }
}
