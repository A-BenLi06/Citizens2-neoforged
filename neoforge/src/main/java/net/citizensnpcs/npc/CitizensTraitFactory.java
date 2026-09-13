package net.citizensnpcs.npc;

import net.citizensnpcs.trait.SentinelTrait;
import net.citizensnpcs.trait.PacketNPC;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import net.citizensnpcs.api.event.NPCCreateEvent;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitFactory;
import net.citizensnpcs.api.trait.TraitInfo;
import net.citizensnpcs.api.trait.TraitTemplateParser;
import net.citizensnpcs.api.trait.trait.CurrentLocation;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.trait.trait.PlayerFilter;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.trait.Age;
import net.citizensnpcs.trait.Anchors;
import net.citizensnpcs.trait.ArmorStandTrait;
import net.citizensnpcs.trait.AttributeTrait;
import net.citizensnpcs.trait.BatTrait;
import net.citizensnpcs.trait.BehaviorTrait;
import net.citizensnpcs.trait.BoundingBoxTrait;
import net.citizensnpcs.trait.ChunkTicketTrait;
import net.citizensnpcs.trait.ShopTrait;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.CommandTrait;
import net.citizensnpcs.trait.Controllable;
import net.citizensnpcs.trait.DisguiseTrait;
import net.citizensnpcs.trait.DropsTrait;
import net.citizensnpcs.trait.EnderCrystalTrait;
import net.citizensnpcs.trait.EndermanTrait;
import net.citizensnpcs.trait.EntityPoseTrait;
import net.citizensnpcs.trait.FollowTrait;
import net.citizensnpcs.trait.ForcefieldTrait;
import net.citizensnpcs.trait.GameModeTrait;
import net.citizensnpcs.trait.Gravity;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.trait.HorseModifiers;
import net.citizensnpcs.trait.HomeTrait;
import net.citizensnpcs.trait.ItemFrameTrait;
import net.citizensnpcs.trait.LeashedTrait;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.MountTrait;
import net.citizensnpcs.trait.OcelotModifiers;
import net.citizensnpcs.trait.PaintingTrait;
import net.citizensnpcs.trait.PausePathfindingTrait;
import net.citizensnpcs.trait.Poses;
import net.citizensnpcs.trait.Powered;
import net.citizensnpcs.trait.RabbitType;
import net.citizensnpcs.trait.RotationTrait;
import net.citizensnpcs.trait.Saddle;
import net.citizensnpcs.trait.MirrorTrait;
import net.citizensnpcs.trait.ScaledMaxHealthTrait;
import net.citizensnpcs.trait.SheepTrait;
import net.citizensnpcs.trait.SitTrait;
import net.citizensnpcs.trait.SkinLayers;
import net.citizensnpcs.trait.ScoreboardTrait;
import net.citizensnpcs.trait.SkinTrait;
import net.citizensnpcs.trait.SleepTrait;
import net.citizensnpcs.trait.SlimeSize;
import net.citizensnpcs.trait.SneakTrait;
import net.citizensnpcs.trait.TargetableTrait;
import net.citizensnpcs.trait.VillagerProfession;
import net.citizensnpcs.trait.WitherTrait;
import net.citizensnpcs.trait.WolfModifiers;
import net.citizensnpcs.trait.WoolColor;
import net.citizensnpcs.trait.text.Text;
import net.citizensnpcs.trait.waypoint.Waypoints;
import net.citizensnpcs.trait.versioned.AllayTrait;
import net.citizensnpcs.trait.versioned.AreaEffectCloudTrait;
import net.citizensnpcs.trait.versioned.ArmadilloTrait;
import net.citizensnpcs.trait.versioned.AxolotlTrait;
import net.citizensnpcs.trait.versioned.BeeTrait;
import net.citizensnpcs.trait.versioned.BoatTrait;
import net.citizensnpcs.trait.versioned.BossBarTrait;
import net.citizensnpcs.trait.versioned.CamelTrait;
import net.citizensnpcs.trait.versioned.CatTrait;
import net.citizensnpcs.trait.versioned.DisplayTrait;
import net.citizensnpcs.trait.versioned.EnderDragonTrait;
import net.citizensnpcs.trait.versioned.FoxTrait;
import net.citizensnpcs.trait.versioned.FrogTrait;
import net.citizensnpcs.trait.versioned.GoatTrait;
import net.citizensnpcs.trait.versioned.ItemDisplayTrait;
import net.citizensnpcs.trait.versioned.InteractionTrait;
import net.citizensnpcs.trait.versioned.LlamaTrait;
import net.citizensnpcs.trait.versioned.MushroomCowTrait;
import net.citizensnpcs.trait.versioned.PandaTrait;
import net.citizensnpcs.trait.versioned.ParrotTrait;
import net.citizensnpcs.trait.versioned.PhantomTrait;
import net.citizensnpcs.trait.versioned.PiglinTrait;
import net.citizensnpcs.trait.versioned.PolarBearTrait;
import net.citizensnpcs.trait.versioned.PotionEffectsTrait;
import net.citizensnpcs.trait.versioned.PufferFishTrait;
import net.citizensnpcs.trait.versioned.ShulkerTrait;
import net.citizensnpcs.trait.versioned.SnifferTrait;
import net.citizensnpcs.trait.versioned.SnowmanTrait;
import net.citizensnpcs.trait.versioned.SpellcasterTrait;
import net.citizensnpcs.trait.versioned.TextDisplayTrait;
import net.citizensnpcs.trait.versioned.TropicalFishTrait;
import net.citizensnpcs.trait.versioned.VexTrait;
import net.citizensnpcs.trait.versioned.VillagerTrait;
import net.citizensnpcs.trait.versioned.WardenTrait;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Registry of trait types, and the source of the default traits every new NPC receives.
 * <p>
 * Upstream registers around sixty traits here. This holds only the ones ported so far; the rest are added as their
 * packages land in P6. The registration mechanism itself is complete, so adding one is a single line.
 */
public class CitizensTraitFactory implements TraitFactory {
    private final List<TraitInfo> defaultTraits = new ArrayList<>();
    private final ClassValue<Integer> idOf = new ClassValue<Integer>() {
        @Override
        protected Integer computeValue(Class<?> type) {
            return next.getAndIncrement();
        }
    };
    private final AtomicInteger next = new AtomicInteger(0);
    private final Map<String, TraitInfo> registered = new HashMap<>();

    public CitizensTraitFactory() {
        registerTrait(TraitInfo.create(Age.class));
        registerTrait(TraitInfo.create(Anchors.class));
        registerTrait(TraitInfo.create(AllayTrait.class));
        registerTrait(TraitInfo.create(AreaEffectCloudTrait.class));
        registerTrait(TraitInfo.create(ArmadilloTrait.class));
        registerTrait(TraitInfo.create(ArmorStandTrait.class));
        registerTrait(TraitInfo.create(AttributeTrait.class));
        registerTrait(TraitInfo.create(AxolotlTrait.class));
        registerTrait(TraitInfo.create(BatTrait.class));
        registerTrait(TraitInfo.create(BeeTrait.class));
        registerTrait(TraitInfo.create(BehaviorTrait.class).optInToStats()
                .withTemplateParser(BehaviorTrait.createTemplateParser()));
        registerTrait(TraitInfo.create(BoatTrait.class));
        registerTrait(TraitInfo.create(BoundingBoxTrait.class));
        registerTrait(TraitInfo.create(BossBarTrait.class));
        registerTrait(TraitInfo.create(CamelTrait.class));
        registerTrait(TraitInfo.create(CatTrait.class));
        registerTrait(TraitInfo.create(ChunkTicketTrait.class));
        registerTrait(TraitInfo.create(ClickRedirectTrait.class));
        registerTrait(TraitInfo.create(CommandTrait.class));
        registerTrait(TraitInfo.create(Controllable.class).optInToStats());
        registerTrait(TraitInfo.create(CurrentLocation.class));
        registerTrait(TraitInfo.create(DisplayTrait.class));
        registerTrait(TraitInfo.create(DisguiseTrait.class));
        registerTrait(TraitInfo.create(DropsTrait.class));
        registerTrait(TraitInfo.create(EnderCrystalTrait.class));
        registerTrait(TraitInfo.create(EnderDragonTrait.class));
        registerTrait(TraitInfo.create(EndermanTrait.class));
        registerTrait(TraitInfo.create(Equipment.class));
        registerTrait(TraitInfo.create(EntityPoseTrait.class));
        registerTrait(TraitInfo.create(FollowTrait.class));
        registerTrait(TraitInfo.create(ForcefieldTrait.class));
        registerTrait(TraitInfo.create(FoxTrait.class));
        registerTrait(TraitInfo.create(FrogTrait.class));
        registerTrait(TraitInfo.create(GameModeTrait.class));
        registerTrait(TraitInfo.create(GoatTrait.class));
        registerTrait(TraitInfo.create(Gravity.class));
        registerTrait(TraitInfo.create(HologramTrait.class));
        registerTrait(TraitInfo.create(HorseModifiers.class));
        registerTrait(TraitInfo.create(HomeTrait.class));
        registerTrait(TraitInfo.create(Inventory.class));
        registerTrait(TraitInfo.create(ItemDisplayTrait.class));
        registerTrait(TraitInfo.create(InteractionTrait.class));
        registerTrait(TraitInfo.create(ItemFrameTrait.class));
        registerTrait(TraitInfo.create(LeashedTrait.class));
        registerTrait(TraitInfo.create(LlamaTrait.class));
        registerTrait(TraitInfo.create(LookClose.class));
        registerTrait(TraitInfo.create(MobType.class).asDefaultTrait());
        registerTrait(TraitInfo.create(MountTrait.class));
        registerTrait(TraitInfo.create(MushroomCowTrait.class));
        registerTrait(TraitInfo.create(OcelotModifiers.class));
        registerTrait(TraitInfo.create(Owner.class));
        registerTrait(TraitInfo.create(PacketNPC.class));
        registerTrait(TraitInfo.create(PaintingTrait.class));
        registerTrait(TraitInfo.create(PandaTrait.class));
        registerTrait(TraitInfo.create(ParrotTrait.class));
        registerTrait(TraitInfo.create(PhantomTrait.class));
        registerTrait(TraitInfo.create(PausePathfindingTrait.class));
        registerTrait(TraitInfo.create(PiglinTrait.class));
        registerTrait(TraitInfo.create(PlayerFilter.class).optInToStats());
        registerTrait(TraitInfo.create(PolarBearTrait.class));
        registerTrait(TraitInfo.create(Poses.class));
        registerTrait(TraitInfo.create(Powered.class));
        registerTrait(TraitInfo.create(PotionEffectsTrait.class));
        registerTrait(TraitInfo.create(PufferFishTrait.class));
        registerTrait(TraitInfo.create(RabbitType.class));
        registerTrait(TraitInfo.create(RotationTrait.class));
        registerTrait(TraitInfo.create(SentinelTrait.class));
        registerTrait(TraitInfo.create(Saddle.class));
        registerTrait(TraitInfo.create(ScoreboardTrait.class));
        // the shop trait needs the shop store, which only exists once the server is up
        registerTrait(TraitInfo.create(ShopTrait.class).optInToStats()
                .withSupplier(() -> new ShopTrait(Citizens.getInstance().getShops())));
        registerTrait(TraitInfo.create(MirrorTrait.class).optInToStats());
        registerTrait(TraitInfo.create(ScaledMaxHealthTrait.class));
        registerTrait(TraitInfo.create(SheepTrait.class));
        registerTrait(TraitInfo.create(ShulkerTrait.class));
        registerTrait(TraitInfo.create(SitTrait.class));
        registerTrait(TraitInfo.create(SkinLayers.class));
        registerTrait(TraitInfo.create(SkinTrait.class));
        registerTrait(TraitInfo.create(SlimeSize.class));
        registerTrait(TraitInfo.create(SneakTrait.class));
        registerTrait(TraitInfo.create(SnifferTrait.class));
        registerTrait(TraitInfo.create(SleepTrait.class));
        registerTrait(TraitInfo.create(SnowmanTrait.class));
        registerTrait(TraitInfo.create(Spawned.class));
        registerTrait(TraitInfo.create(SpellcasterTrait.class));
        registerTrait(TraitInfo.create(TargetableTrait.class));
        registerTrait(TraitInfo.create(Text.class));
        registerTrait(TraitInfo.create(TextDisplayTrait.class));
        registerTrait(TraitInfo.create(TropicalFishTrait.class));
        registerTrait(TraitInfo.create(VexTrait.class));
        registerTrait(TraitInfo.create(VillagerProfession.class));
        registerTrait(TraitInfo.create(VillagerTrait.class));
        registerTrait(TraitInfo.create(Waypoints.class));
        registerTrait(TraitInfo.create(WardenTrait.class));
        registerTrait(TraitInfo.create(WitherTrait.class));
        registerTrait(TraitInfo.create(WolfModifiers.class));
        registerTrait(TraitInfo.create(WoolColor.class));
        NeoForge.EVENT_BUS.register(this);
    }

    @Override
    public void deregisterTrait(TraitInfo info) {
        defaultTraits.remove(info);
        idOf.remove(info.getTraitClass());
        registered.values().remove(info);
    }

    @Override
    public int getId(Class<? extends Trait> clazz) {
        return idOf.get(clazz);
    }

    @Override
    public Collection<TraitInfo> getRegisteredTraits() {
        return registered.values();
    }

    @Override
    public TraitTemplateParser getTemplateParser(String name) {
        TraitInfo info = registered.get(name.toLowerCase(Locale.ROOT));
        return info == null ? null : info.getParser();
    }

    @Override
    public <T extends Trait> T getTrait(Class<T> clazz) {
        for (TraitInfo entry : registered.values()) {
            if (clazz == entry.getTraitClass())
                return entry.tryCreateInstance();
        }
        return null;
    }

    @Override
    public <T extends Trait> T getTrait(String name) {
        TraitInfo info = registered.get(name.toLowerCase(Locale.ROOT));
        return info == null ? null : info.tryCreateInstance();
    }

    @Override
    public Class<? extends Trait> getTraitClass(String name) {
        TraitInfo info = registered.get(name.toLowerCase(Locale.ROOT));
        return info == null ? null : info.getTraitClass();
    }

    @SubscribeEvent
    public void onNPCCreate(NPCCreateEvent event) {
        for (TraitInfo info : defaultTraits) {
            // The registry has already initialized required traits (notably the requested entity type). Defaults
            // fill missing traits; replacing an initialized MobType here would turn unspawned NPCs into players.
            if (!event.getNPC().hasTrait(info.getTraitClass())) event.getNPC().addTrait(info.tryCreateInstance());
        }
    }

    @Override
    public void registerTrait(TraitInfo info) {
        Objects.requireNonNull(info, "info cannot be null");
        info.checkValid();
        if (registered.containsKey(info.getTraitName()))
            throw new IllegalArgumentException("Trait name " + info.getTraitName() + " already registered");
        registered.put(info.getTraitName(), info);
        if (info.isDefaultTrait()) {
            defaultTraits.add(info);
        }
        info.registerListener();
    }

    public boolean trackStats(Trait trait) {
        TraitInfo info = registered.get(trait.getName());
        return info != null && info.shouldTrackStats();
    }
}
