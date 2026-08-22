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
        npc.getOrAddTrait(MobType.class).setType(type);
        npcs.put(id, npc);
        uniqueNPCs.put(npc.getUniqueId(), npc);
        NeoForge.EVENT_BUS.post(new NPCCreateEvent(npc));
        // TODO(P6): upstream attaches ArmorStandTrait for armour stands and LookClose when the setting is on
        return npc;
    }

    @Override
    public NPC createNPCUsingItem(EntityType<?> type, String name, ItemStack item) {
        NPC npc = createNPC(type, name);
        if (!isItemCarrier(type))
            throw new UnsupportedOperationException("Not an item entity type");
        npc.data().set(NPC.Metadata.ITEM_AMOUNT, item.getCount());
        npc.data().set(NPC.Metadata.ITEM_ID,
                net.citizensnpcs.api.util.RegistryUtil.keyOf(item.getItem()));
        npc.setItemProvider(() -> item.copy());
        return npc;
    }

    /**
     * @return whether the type renders a held ItemStack, so {@code createNPCUsingItem} makes sense for it
     */
    private static boolean isItemCarrier(EntityType<?> type) {
        return type == EntityType.ITEM || type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME
                || type == EntityType.ITEM_DISPLAY || type == EntityType.BLOCK_DISPLAY
                || type == EntityType.FALLING_BLOCK || type == EntityType.OMINOUS_ITEM_SPAWNER
                || EntityType.getKey(type).getPath().contains("minecart");
    }

    @Override
    public void deregister(NPC npc) {
        npc.despawn(DespawnReason.REMOVAL);
        npcs.remove(npc.getId());
        uniqueNPCs.remove(npc.getUniqueId());
        if (saves != null) {
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

            @Override
            public boolean hasNext() {
                return itr.hasNext();
            }

            @Override
            public NPC next() {
                NPC npc = itr.next();
                if (npc != null && npc.getUniqueId() != null) {
                    lastUUID = npc.getUniqueId();
                }
                return npc;
            }

            @Override
            public void remove() {
                itr.remove();
                if (lastUUID != null) {
                    uniqueNPCs.remove(lastUUID);
                    lastUUID = null;
                }
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
