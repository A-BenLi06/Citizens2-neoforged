package net.citizensnpcs.npc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.common.collect.Maps;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.trait.ArmorStandTrait;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.api.CitizensPlugin;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCCreateEvent;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCDataStore;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.npc.RemoveReason;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.util.EventBusUtil;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;

public class CitizensNPCRegistry implements NPCRegistry {
    private final String name;
    private final Int2ObjectOpenHashMap<NPC> npcs = new Int2ObjectOpenHashMap<>();
    private final CitizensPlugin plugin;
    private final NPCDataStore saves;
    private final Map<UUID, NPC> uniqueNPCs = Maps.newConcurrentMap();

    public CitizensNPCRegistry(NPCDataStore store, CitizensPlugin plugin) {
        this(store, plugin, "");
    }

    public CitizensNPCRegistry(NPCDataStore store, CitizensPlugin cplugin, String registryName) {
        saves = store;
        plugin = cplugin;
        name = registryName;
    }

    @Override
    public NPC createNPC(EntityType<?> type, String name) {
        return createNPC(type, UUID.randomUUID(), generateIntegerId(), name);
    }

    @Override
    public NPC createNPC(EntityType<?> type, String name, Location loc) {
        NPC npc = createNPC(type, name);
        npc.spawn(loc, SpawnReason.PLUGIN);
        return npc;
    }

    @Override
    public NPC createNPC(EntityType<?> type, UUID uuid, int id, String name) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        CitizensNPC npc = new CitizensNPC(uuid, id, name, EntityControllers.createForType(type), this, plugin);
        try {
            npc.prepareCreation(() -> {
                npcs.put(id, npc);
                uniqueNPCs.put(npc.getUniqueId(), npc);
                npc.getOrAddTrait(MobType.class).setType(type);
                if (npcs.get(id) != npc || uniqueNPCs.get(uuid) != npc)
                    throw new net.citizensnpcs.api.exception.NPCCreationException("NPC initialization was superseded");
                npc.initializeCreationName();
                if (npcs.get(id) != npc || uniqueNPCs.get(uuid) != npc)
                    throw new net.citizensnpcs.api.exception.NPCCreationException("NPC name initialization was superseded");
                NPCCreateEvent event = new NPCCreateEvent(npc);
                NeoForge.EVENT_BUS.post(event);
                if (event.isCanceled() || npcs.get(id) != npc || uniqueNPCs.get(uuid) != npc)
                    throw new net.citizensnpcs.api.exception.NPCCreationException("NPC creation was canceled or superseded");
                if (type == EntityType.ARMOR_STAND) npc.getOrAddTrait(ArmorStandTrait.class);
                if (Setting.DEFAULT_LOOK_CLOSE.asBoolean()) npc.getOrAddTrait(LookClose.class);
                if (npcs.get(id) != npc || uniqueNPCs.get(uuid) != npc)
                    throw new net.citizensnpcs.api.exception.NPCCreationException("NPC initialization was superseded");
            });
            return npc;
        } catch (RuntimeException | Error failure) {
            try { npc.destroy(); } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public NPC createNPCUsingItem(EntityType<?> type, String name, ItemStack item) {
        if (!isItemCarrier(type))
            throw new UnsupportedOperationException("Not an item entity type");
        Objects.requireNonNull(item, "item cannot be null");
        if (item.isEmpty()) throw new IllegalArgumentException("Item cannot be empty");
        if (isBlockItemCarrier(type) && !(item.getItem() instanceof net.minecraft.world.item.BlockItem))
            throw new IllegalArgumentException("This entity requires a block item");
        ItemStack provided = item.copy();
        NPC npc = createNPC(type, name);
        npc.data().set(NPC.Metadata.ITEM_AMOUNT, provided.getCount());
        npc.data().set(NPC.Metadata.ITEM_ID,
                net.citizensnpcs.api.util.RegistryUtil.keyOf(provided.getItem()));
        npc.setItemProvider(() -> provided.copy());
        return npc;
    }

    /**
     * @return whether the type renders a held ItemStack, so {@code createNPCUsingItem} makes sense for it
     */
    public static boolean isItemCarrier(EntityType<?> type) {
        return type == EntityType.ITEM || type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME
                || type == EntityType.ITEM_DISPLAY || type == EntityType.OMINOUS_ITEM_SPAWNER || isBlockItemCarrier(type);
    }

    public static boolean isBlockItemCarrier(EntityType<?> type) {
        return type == EntityType.BLOCK_DISPLAY || type == EntityType.FALLING_BLOCK
                || EntityType.getKey(type).getPath().contains("minecart");
    }

    @Override
    public void deregister(NPC npc) {
        NPCLifetimes.cancel(npc);
        npc.despawn(DespawnReason.REMOVAL);
        boolean ownsId = npcs.get(npc.getId()) == npc;
        if (ownsId) npcs.remove(npc.getId());
        if (uniqueNPCs.get(npc.getUniqueId()) == npc) uniqueNPCs.remove(npc.getUniqueId());
        if (saves != null && ownsId) {
            saves.clearData(npc);
        }
    }

    @Override
    public void deregisterAll() {
        Iterator<NPC> itr = iterator();
        while (itr.hasNext()) {
            NPC npc = itr.next();
            npc.despawn(DespawnReason.REMOVAL);
            for (Trait t : npc.getTraits()) {
                EventBusUtil.unregister(t);
                t.onRemove(RemoveReason.REMOVAL);
            }
            itr.remove();
            if (saves != null) {
                saves.clearData(npc);
            }
        }
    }

    @Override
    public void despawnNPCs(DespawnReason reason) {
        Iterator<NPC> itr = iterator();
        while (itr.hasNext()) {
            NPC npc = itr.next();
            try {
                npc.despawn(reason);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            itr.remove();
        }
    }

    private int generateIntegerId() {
        return saves.createUniqueNPCId(this);
    }

    @Override
    public NPC getById(int id) {
        if (id < 0)
            throw new IllegalArgumentException("invalid id");
        return npcs.get(id);
    }

    @Override
    public NPC getByUniqueId(UUID uuid) {
        return uniqueNPCs.get(normalise(uuid));
    }

    @Override
    public NPC getByUniqueIdGlobal(UUID uuid) {
        NPC npc = getByUniqueId(uuid);
        if (npc != null)
            return npc;
        for (NPCRegistry registry : CitizensAPI.getNPCRegistries()) {
            if (registry == this) {
                continue;
            }
            NPC other = registry.getByUniqueId(uuid);
            if (other != null)
                return other;
        }
        return null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public NPC getNPC(Entity entity) {
        return NPCRegistries.lookup(entity);
    }

    @Override
    public boolean isNPC(Entity entity) {
        return getNPC(entity) != null;
    }

    @Override
    public Iterator<NPC> iterator() {
        return new Iterator<NPC>() {
            Iterator<NPC> itr = npcs.values().iterator();
            UUID lastUUID;
            NPC lastNPC;

            @Override
            public boolean hasNext() {
                return itr.hasNext();
            }

            @Override
            public NPC next() {
                NPC npc = itr.next();
                lastNPC = npc;
                if (npc != null && npc.getUniqueId() != null) {
                    lastUUID = npc.getUniqueId();
                }
                return npc;
            }

            @Override
            public void remove() {
                if (lastNPC != null) NPCLifetimes.cancel(lastNPC);
                itr.remove();
                if (lastUUID != null) {
                    if (uniqueNPCs.get(lastUUID) == lastNPC) uniqueNPCs.remove(lastUUID);
                    lastUUID = null;
                }
                lastNPC = null;
            }
        };
    }

    /**
     * Player NPCs get a version-2 UUID so the client does not confuse them with real accounts; lookups normalise back
     * to version 4 so either form finds the NPC. Same rule as upstream.
     */
    private static UUID normalise(UUID uuid) {
        if (uuid.version() != 2)
            return uuid;
        long msb = uuid.getMostSignificantBits();
        msb &= ~0x0000000000002000L;
        msb |= 0x0000000000004000L;
        return new UUID(msb, uuid.getLeastSignificantBits());
    }

    @Override
    public void saveToStore() {
        saves.storeAll(this);
        saves.saveToDiskImmediate();
    }

    @Override
    public Iterable<NPC> sorted() {
        List<NPC> vals = new ArrayList<>(npcs.values());
        vals.sort(Comparator.comparing(NPC::getId));
        return vals;
    }
}
