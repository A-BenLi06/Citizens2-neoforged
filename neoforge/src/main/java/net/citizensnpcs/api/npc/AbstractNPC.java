package net.citizensnpcs.api.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.TeleportCause;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.citizensnpcs.api.util.RegistryUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.CitizensPlugin;
import net.citizensnpcs.api.ai.BehaviorController;
import net.citizensnpcs.api.ai.SimpleBehaviorController;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCAddTraitEvent;
import net.citizensnpcs.api.event.NPCCloneEvent;
import net.citizensnpcs.api.event.NPCRemoveEvent;
import net.citizensnpcs.api.event.NPCRemoveTraitEvent;
import net.citizensnpcs.api.event.NPCRenameEvent;
import net.citizensnpcs.api.event.NPCTeleportEvent;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitLookup;
import net.citizensnpcs.api.trait.TraitLookup.ArrayTraitLookup;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.StoredItems;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.EventBusUtil;

public abstract class AbstractNPC implements NPC {
    private final List<String> clearSaveData = new ArrayList<>();
    protected Object coloredNameComponentCache;
    protected String coloredNameStringCache;
    /** Name the cached {@link #nameIsRewrittenByPlaceholders()} answer was computed for. */
    private String namePlaceholderCachedFor;
    /** Provider count at that point; -1 so the first call always computes. */
    private int namePlaceholderGeneration = -1;
    private boolean namePlaceholderRewrites;
    private final BehaviorController goalController = new SimpleBehaviorController();
    private final int id;
    private final StoredItems<String> storedItems = new StoredItems<>();
    private boolean hasStoredItemProvider;
    private Supplier<ItemStack> itemProvider = () -> {
        Item id = data().has(NPC.Metadata.ITEM_ID)
                ? (Item) RegistryUtil.get(Item.class, data().<String> get(NPC.Metadata.ITEM_ID))
                : null;
        if (id == null || id == Items.AIR) {
            id = Items.STONE;
            Messaging.severe(getId(), "invalid item: converted to stone");
        }
        return new ItemStack(id, data().get(NPC.Metadata.ITEM_AMOUNT, 1));
    };
    private final MetadataStore metadata = new SimpleMetadataStore();
    private String name;
    protected final CitizensPlugin plugin;
    private final NPCRegistry registry;
    private final List<Runnable> runnables = new ArrayList<>();
    protected final TraitLookup traits;
    private final UUID uuid;

    protected AbstractNPC(UUID uuid, int id, String name, NPCRegistry registry, CitizensPlugin plugin) {
        this.uuid = uuid;
        this.id = id;
        this.registry = registry;
        this.plugin = plugin;
        this.traits = new ArrayTraitLookup(plugin.getTraitFactory().getRegisteredTraits().size() + 32);
        setNameInternal(name);
    }

    @Override
    public void addRunnable(Runnable runnable) {
        runnables.add(runnable);
    }

    @Override
    public void addTrait(Class<? extends Trait> clazz) {
        addTrait(plugin.getTraitFactory().getTrait(clazz));
    }

    @Override
    public void addTrait(Trait trait) {
        if (trait == null) {
            Messaging.severe("Cannot register a null trait. Was it registered properly?");
            return;
        }
        // clear existing trait
        Trait replaced = traits.get(trait.getTraitId());
        if (replaced != null) {
            Messaging.debug("NPC", this, "replacing trait", replaced, "with", trait);
            EventBusUtil.unregister(replaced);
            replaced.onRemove();
            runnables.remove(replaced);
        }
        trait.linkToNPC(this);
        EventBusUtil.register(trait);
        traits.add(trait.getTraitId(), trait);
        if (isSpawned()) {
            trait.onSpawn();
        }
        if (trait.isRunImplemented()) {
            runnables.add(trait);
        }
        NeoForge.EVENT_BUS.post(new NPCAddTraitEvent(this, trait));
    }

    @Override
    public NPC clone() {
        return copy();
    }

    @Override
    public NPC copy() {
        DataKey key = new MemoryDataKey();
        saveSnapshot(key);
        NPC copy = registry.createNPC(getOrAddTrait(MobType.class).getType(), getRawName());
        copy.load(key);

        for (Trait trait : copy.getTraits()) {
            trait.onCopy();
        }
        NeoForge.EVENT_BUS.post(new NPCCloneEvent(this, copy));
        return copy;
    }

    @Override
    public MetadataStore data() {
        return metadata;
    }

    @Override
    public void destroy() {
        NeoForge.EVENT_BUS.post(new NPCRemoveEvent(this));
        runnables.clear();
        traits.forEach(trait -> {
            EventBusUtil.unregister(trait);
            trait.onRemove(RemoveReason.DESTROYED);
        });
        traits.clear();
        goalController.clear();
        registry.deregister(this);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        AbstractNPC other = (AbstractNPC) obj;
        if (!Objects.equals(uuid, other.uuid))
            return false;
        return true;
    }

    @Override
    public BehaviorController getDefaultBehaviorController() {
        return goalController;
    }

    protected EntityType getEntityType() {
        return isSpawned() ? getEntity().getType() : getOrAddTrait(MobType.class).getType();
    }

    @Override
    public String getFullName() {
        int nameLength = EntityUtil.getMaxNameLength(getEntityType());
        String replaced = Placeholders.replaceName(
                coloredNameStringCache != null ? coloredNameStringCache : Messaging.parseComponents(name), null, this);
        if (Messaging.stripColor(replaced).length() > nameLength) {
            Messaging.severe("ID", id, "created with name length greater than " + nameLength + ", truncating", replaced,
                    "to", replaced.substring(0, nameLength));
            replaced = replaced.substring(0, nameLength);
        }
        return replaced;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public Supplier<ItemStack> getItemProvider() {
        return itemProvider;
    }

    @Override
    public UUID getMinecraftUniqueId() {
        if (getEntityType() == EntityType.PLAYER) {
            UUID uuid = getUniqueId();
            if (uuid.version() == 4) { // set version to 2
                long msb = uuid.getMostSignificantBits();
                msb &= ~0x0000000000004000L;
                msb |= 0x0000000000002000L;
                return new UUID(msb, uuid.getLeastSignificantBits());
            }
        }
        return getUniqueId();
    }

    @Override
    public String getName() {
        return Messaging.stripColor(coloredNameStringCache);
    }

    @Override
    public <T extends Trait> T getOrAddTrait(Class<T> clazz) {
        Trait trait = traits.get(plugin.getTraitFactory().getId(clazz));
        if (trait == null) {
            trait = plugin.getTraitFactory().getTrait(clazz);
            addTrait(trait);
        }
        return clazz.cast(trait);
    }

    @Override
    public NPCRegistry getOwningRegistry() {
        return registry;
    }

    @Override
    public String getRawName() {
        return name;
    }

    @Override
    public <T extends Trait> T getTrait(Class<T> trait) {
        return getOrAddTrait(trait);
    }

    @Override
    public <T extends Trait> T getTraitNullable(Class<T> clazz) {
        return clazz.cast(traits.get(CitizensAPI.getTraitFactory().getId(clazz)));
    }

    @Override
    public Iterable<Trait> getTraits() {
        return traits.list();
    }

    @Override
    public UUID getUniqueId() {
        return uuid;
    }

    @Override
    public int hashCode() {
        return 31 + uuid.hashCode();
    }

    @Override
    public boolean hasTrait(Class<? extends Trait> clazz) {
        return traits.has(plugin.getTraitFactory().getId(clazz));
    }

    @Override
    public void load(final DataKey root) {
        setNameInternal(root.getString("name"));
        storedItems.clear();
        hasStoredItemProvider = root.keyExists("itemprovider");
        if (root.keyExists("itemprovider")) {
            ItemStack item = storedItems.load("itemprovider", root.getRelative("itemprovider"));
            itemProvider = () -> item == null ? ItemStack.EMPTY : item.copy();
        }
        metadata.loadFrom(root.getRelative("metadata"));

        String traitNames = root.getString("traitnames");
        if (traitNames.isEmpty()) {
            Messaging.severe("Corrupted savedata (empty trait names) for NPC", this);
            return;
        }
        Set<String> loading = Sets.newHashSet(Splitter.on(',').omitEmptyStrings().split(traitNames));
        DataKey traits = root.getRelative("traits");
        for (String key : PRIORITY_TRAITS) {
            DataKey pkey = traits.getRelative(key);
            if (pkey.keyExists()) {
                loadTraitFromKey(pkey);
                loading.remove(key);
            }
        }
        for (DataKey key : Iterables.transform(loading, traits::getRelative)) {
            loadTraitFromKey(key);
        }
    }

    private void loadTraitFromKey(DataKey traitKey) {
        Class<? extends Trait> clazz = plugin.getTraitFactory().getTraitClass(traitKey.name());
        if (clazz == null) {
            Messaging.severeTr("citizens.notifications.trait-load-failed", traitKey.name(), getId());
            return;
        }
        Trait trait;
        if (hasTrait(clazz)) {
            trait = getTraitNullable(clazz);
        } else {
            trait = plugin.getTraitFactory().getTrait(clazz);
            if (trait == null) {
                Messaging.severeTr("citizens.notifications.trait-load-failed", traitKey.name(), getId());
                return;
            }
            addTrait(trait);
        }
        try {
            PersistenceLoader.load(trait, traitKey);
            trait.load(traitKey);
        } catch (Throwable ex) {
            if (Messaging.isDebugging()) {
                ex.printStackTrace();
            }
            Messaging.logTr("citizens.notifications.trait-load-failed", traitKey.name(), getId());
        }
    }

    @Override
    public void removeTrait(Class<? extends Trait> clazz) {
        Trait trait = traits.remove(plugin.getTraitFactory().getId(clazz));
        if (trait != null) {
            NeoForge.EVENT_BUS.post(new NPCRemoveTraitEvent(this, trait));
            clearSaveData.add("traits." + trait.getName());
            runnables.remove(trait);
            EventBusUtil.unregister(trait);
            trait.onRemove(RemoveReason.REMOVAL);
        }
    }

    @Override
    public boolean requiresNameHologram() {
        return (coloredNameStringCache != null && coloredNameStringCache.length() > 16
                && getEntityType() == EntityType.PLAYER)
                || data().get(NPC.Metadata.ALWAYS_USE_NAME_HOLOGRAM, false)
                || (coloredNameStringCache != null && coloredNameStringCache.contains("§")
                        && getEntityType() != EntityType.PLAYER)
                || nameIsRewrittenByPlaceholders();
    }

    /**
     * Whether running the name through {@link Placeholders} produces something different from the name itself.
     * <p>
     * This is the last term of {@link #requiresNameHologram()}, which {@code ScoreboardTrait.update()} calls on every NPC
     * on every tick. Answering it directly means a fresh {@code Matcher}, a {@code StringBuffer} and a result
     * {@code String} per NPC per tick — a few thousand throwaway objects a second on a server with a couple of hundred
     * NPCs, all to re-derive an answer that had not changed.
     * <p>
     * The answer depends only on the name and on which placeholder providers are registered, so it is cached against
     * both. It is not enough to test whether the name merely <em>contains</em> a placeholder: {@code <npc>} expands to the
     * name itself and so leaves it unchanged, and only a real replacement can tell that apart. So the replacement is still
     * run — just once per name instead of twenty times a second.
     */
    private boolean nameIsRewrittenByPlaceholders() {
        int generation = Placeholders.providerCount();
        if (generation != namePlaceholderGeneration || !Objects.equals(name, namePlaceholderCachedFor)) {
            namePlaceholderGeneration = generation;
            namePlaceholderCachedFor = name;
            namePlaceholderRewrites = !Objects.equals(Placeholders.replaceName(name, null, this), name);
        }
        return namePlaceholderRewrites;
    }

    @Override
    public void save(DataKey root) {
        if (!metadata.get(NPC.Metadata.SHOULD_SAVE, true))
            return;
        saveState(root, false);
        clearSaveData.clear();
    }

    @Override
    public void saveSnapshot(DataKey root) {
        saveState(root, true);
    }

    /** A strict snapshot must succeed before a destructive operation can rely on it. */
    protected void saveState(DataKey root, boolean strict) {
        Set<String> pendingRemoval = Sets.newHashSet(clearSaveData);
        metadata.saveTo(root.getRelative("metadata"));
        root.setString("name", name);
        root.setString("uuid", uuid.toString());

        if (data().has(NPC.Metadata.ITEM_ID) || hasStoredItemProvider) {
            ItemStack stack = itemProvider.get();
            storedItems.save("itemprovider", root.getRelative("itemprovider"), stack);
            if (hasStoredItemProvider && !storedItems.contains("itemprovider") && (stack == null || stack.isEmpty())) {
                // An explicitly empty supplier must survive reload instead of reactivating the metadata/default supplier.
                root.getRelative("itemprovider").setString("nbt", ItemStack.OPTIONAL_CODEC
                        .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, ItemStack.EMPTY).getOrThrow().toString());
            }
        } else {
            root.removeKey("itemprovider");
        }
        Set<String> traitNames = Splitter.on(',').omitEmptyStrings().splitToStream(root.getString("traitnames"))
                .collect(Collectors.toSet());
        traits.forEach(trait -> {
            pendingRemoval.remove("traits." + trait.getName());
            traitNames.add(trait.getName());

            DataKey traitKey = root.getRelative("traits." + trait.getName());
            try {
                trait.save(traitKey);
            } catch (Throwable t) {
                if (strict) throw new IllegalStateException("Could not snapshot trait " + trait.getName(), t);
                Messaging.severe("Saving trait", trait, "failed for NPC", this);
                t.printStackTrace();
                return;
            }
            try {
                PersistenceLoader.save(trait, traitKey);
            } catch (Throwable t) {
                if (strict) throw new IllegalStateException("Could not snapshot trait " + trait.getName(), t);
                Messaging.severe("PersistenceLoader failed saving trait", trait, "for NPC", this);
                t.printStackTrace();
                return;
            }
        });
        for (String clear : pendingRemoval) {
            if (clear.startsWith("traits.")) {
                traitNames.remove(clear.replace("traits.", ""));
            }
            root.removeKey(clear);
        }
        root.setString("traitnames", Joiner.on(',').join(traitNames));
    }

    @Override
    public void setItemProvider(Supplier<ItemStack> provider) {
        storedItems.clear();
        hasStoredItemProvider = true;
        this.itemProvider = provider;
        ItemStack stack = provider.get();
        if (stack != null && !stack.isEmpty()) {
            data().set(NPC.Metadata.ITEM_ID, RegistryUtil.keyOf(stack.getItem()));
            data().set(NPC.Metadata.ITEM_AMOUNT, stack.getCount());
        }
    }

    @Override
    public void setName(String name) {
        if (name.equals(this.name))
            return;

        NPCRenameEvent event = new NPCRenameEvent(this, this.name, name);
        NeoForge.EVENT_BUS.post(event);
        setNameInternal(event.getNewName());

        if (!isSpawned())
            return;

        Entity entity = getEntity();

        if (entity.getType() == EntityType.PLAYER && !requiresNameHologram()) {
            Location old = Location.fromEntity(entity);
            despawn(DespawnReason.PENDING_RESPAWN);
            spawn(old);
        }
    }

    protected void setNameInternal(String name) {
        this.name = name;
        coloredNameComponentCache = Messaging.minecraftComponentFromRawMessage(this.name);
        coloredNameStringCache = Messaging.parseComponents(this.name);
    }

    /**
     * Teleports {@code entity} and its passenger stack, remounting them once they have all arrived.
     * <p>
     * A cross-dimension teleport replaces the entity object (see
     * {@link EntityUtil#teleport(Entity, Location)}), so both the mount and the passenger have to be re-fetched before
     * remounting — upstream can reuse its references because Bukkit preserves entity identity across worlds.
     */
    private void teleport(final Entity entity, Location location, int delay, TeleportCause cause) {
        final Entity passenger = entity.getFirstPassenger();
        entity.ejectPassengers();
        boolean crossDimension = location.getWorld() != entity.level();
        if (crossDimension) {
            CitizensAPI.getScheduler().runEntityTaskLater(entity, () -> EntityUtil.teleport(entity, location),
                    delay++);
        } else {
            EntityUtil.teleport(entity, location);
        }
        if (passenger == null)
            return;
        teleport(passenger, location, delay++, cause);
        Runnable task = () -> {
            Entity mount = resolveAfterTeleport(entity, location);
            Entity rider = resolveAfterTeleport(passenger, location);
            if (mount != null && rider != null) {
                rider.startRiding(mount, true);
            }
        };
        if (crossDimension) {
            CitizensAPI.getScheduler().runEntityTaskLater(entity, task, delay);
        } else {
            task.run();
        }
    }

    /** @return the entity as it exists in the destination level, which may be a different object after a dimension change */
    private static Entity resolveAfterTeleport(Entity original, Location location) {
        if (!original.isRemoved())
            return original;
        return location.getWorld() == null ? null : location.getWorld().getEntity(original.getUUID());
    }

    @Override
    public void teleport(Location location, TeleportCause cause) {
        if (!isSpawned())
            return;
        NPCTeleportEvent event = new NPCTeleportEvent(this, location);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled())
            return;
        Entity entity = getEntity();
        while (entity.getVehicle() != null) {
            entity = entity.getVehicle();
        }
        location.getChunk();
        teleport(entity, location, 5, cause);
    }

    public void update() {
        // can modify itself during running
        for (int i = 0; i < runnables.size(); i++) {
            runnables.get(i).run();
        }
        if (isSpawned()) {
            goalController.run();
        }
    }

    private static final String[] PRIORITY_TRAITS = { "location", "type" };
}
