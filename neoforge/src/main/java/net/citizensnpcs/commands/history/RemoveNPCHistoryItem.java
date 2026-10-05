package net.citizensnpcs.commands.history;

import java.util.ArrayList;
import java.util.UUID;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCSelector;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.npc.CitizensNPCRegistry;
import net.citizensnpcs.trait.ShopTrait;
import net.citizensnpcs.trait.shop.NPCShop;
import net.citizensnpcs.trait.shop.StoredShops;
import net.citizensnpcs.util.Messages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.EntityType;

/** Undoing a removal recreates the NPC from the data it was saved with, keeping its id and UUID. */
public class RemoveNPCHistoryItem implements CommandHistoryItem {
    private final int id;
    private final DataKey key;
    private final DataKey shopKey;
    private final EntityType<?> type;
    private final UUID uuid;
    private final NPCRegistry registry;
    private final boolean defaultRegistry;
    private final boolean temporaryRegistry;
    private final boolean namedRegistry;

    public RemoveNPCHistoryItem(NPC from) {
        key = new MemoryDataKey();
        DataKey shopSnapshot = null;
        var resumes = new ArrayList<Runnable>();
        try {
            ShopTrait trait = from.getTraitNullable(ShopTrait.class);
            if (trait != null) {
                resumes.add(trait.getStorage().suspendSessions());
                NPCShop shop = trait.getExistingDefaultShop();
                if (shop != null && shop.getStorage() != trait.getStorage()) resumes.add(shop.getStorage().suspendSessions());
            }
            from.saveSnapshot(key);
            NPCShop shop = trait == null ? null : trait.getExistingDefaultShop();
            if (shop != null) {
                shopSnapshot = new MemoryDataKey();
                PersistenceLoader.save(shop, shopSnapshot.getRelative(from.getUniqueId().toString()));
            }
        } finally { resumes.forEach(Runnable::run); }
        shopKey = shopSnapshot;
        type = from.getOrAddTrait(MobType.class).getType();
        uuid = from.getUniqueId();
        id = from.getId();
        registry = from.getOwningRegistry();
        defaultRegistry = registry == CitizensAPI.getNPCRegistry();
        temporaryRegistry = registry == CitizensAPI.getTemporaryNPCRegistry();
        namedRegistry = CitizensAPI.getNamedNPCRegistry(registry.getName()) == registry;
    }

    @Override
    public void undo(CommandSourceStack sender, NPCSelector selector) throws CommandException {
        NPCRegistry destination = defaultRegistry ? CitizensAPI.getNPCRegistry()
                : temporaryRegistry ? CitizensAPI.getTemporaryNPCRegistry()
                : namedRegistry ? CitizensAPI.getNamedNPCRegistry(registry.getName()) : registry;
        if (destination == null) throw new CommandException(Messages.UNKNOWN_NPC_REGISTRY, registry.getName());
        if (destination.getById(id) != null || destination.getByUniqueId(uuid) != null)
            throw new CommandException(Messages.UNDO_ID_CONFLICT, id);
        StoredShops shops = Citizens.getInstance().getShops();
        String shopId = uuid.toString();
        if (shopKey != null && shops.npcShops.containsKey(shopId))
            throw new CommandException(Messages.UNDO_SHOP_CONFLICT, id);
        NPCShop restoredShop = shopKey == null ? null
                : PersistenceLoader.loadStrict(new NPCShop(shopId), shopKey.getRelative(shopId).copyWithRoot());
        NPC npc = null;
        try {
            if (restoredShop != null) {
                if (shops.npcShops.putIfAbsent(shopId, restoredShop) != null)
                    throw new IllegalStateException("NPC shop changed during undo preparation");
            }
            npc = destination instanceof CitizensNPCRegistry nativeRegistry
                    ? nativeRegistry.createNPCForRestore(type, uuid, id, key.getString("name"))
                    : destination.createNPC(type, uuid, id, key.getString("name"));
            npc.load(key.copyWithRoot());
            if (destination.getById(id) != npc || destination.getByUniqueId(uuid) != npc)
                throw new IllegalStateException("Restored NPC lost registry ownership");
            if (restoredShop != null && (npc.getTraitNullable(ShopTrait.class) == null
                    || shops.npcShops.get(shopId) != restoredShop))
                throw new IllegalStateException("Restored NPC shop lost ownership");
            selector.select(sender, npc);
        } catch (RuntimeException | Error failure) {
            if (npc != null) {
                try {
                    if (destination instanceof CitizensNPCRegistry nativeRegistry) nativeRegistry.discardRuntimeNPC(npc);
                    else npc.destroy();
                } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            if (restoredShop != null) {
                try { shops.removeNPCShop(shopId, restoredShop); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }
}
