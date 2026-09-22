package net.citizensnpcs.api.trait.trait;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.StoredItems;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.TextParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Persistent NPC inventory with a menu backed by the same container the entity uses. Native pickups, menu edits and
 * API writes therefore share one live inventory. Entities without a container use the stored slots directly.
 */
@TraitName("inventory")
public class Inventory extends Trait {
    private ItemStack[] contents = new ItemStack[SIZE];
    private final StoredItems<Integer> stored = new StoredItems<>();
    private boolean syncingHand;
    private InventoryView view;
    private final Set<ServerPlayer> viewers = new HashSet<>();

    public Inventory() { super("inventory"); }

    /** Empty slots use null for API and stored-data compatibility. */
    public ItemStack[] getContents() { readBack(); return contents; }
    public Container getInventoryView() { return view; }
    public Set<Integer> getUnresolvedSlots() { return stored.keys(); }

    @Override
    public void onAttach() {
        if (npc.isSpawned()) readBack();
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        contents = new ItemStack[SIZE];
        stored.clear();
        for (DataKey slotKey : key.getIntegerSubKeys()) {
            int slot = Integer.parseInt(slotKey.name());
            if (slot >= 0 && slot < contents.length) contents[slot] = stored.load(slot, slotKey);
        }
    }

    @Override
    public void onSpawn() {
        closeViewers();
        pushToEntity();
        view = new InventoryView(viewSize());
    }

    @Override
    public void onDespawn() { readBack(); closeViewers(); view = null; }

    @Override
    public void onRemove() { closeViewers(); view = null; }

    public void openInventory(ServerPlayer sender) {
        readBack();
        if (view == null || view.getContainerSize() != viewSize()) {
            closeViewers();
            view = new InventoryView(viewSize());
        }
        int rows = view.getContainerSize() / 9;
        Component title = TextParser.parse(Messaging.tr("citizens.inventory.title", npc.getFullName()));
        sender.openMenu(new SimpleMenuProvider(
                (id, playerInventory, player) -> {
                    InventoryView bound = view;
                    ChestMenu menu = new ChestMenu(menuType(rows), id, playerInventory, bound, rows);
                    // ChestMenu's ordinary Slots do not consult Container.canPlaceItem. Padded cells must reject
                    // placement through clicks and quick moves, otherwise readBack would discard those items.
                    for (int i = 0; i < bound.getContainerSize(); i++) {
                        Slot original = menu.slots.get(i);
                        final int slot = i;
                        Slot replacement = new Slot(bound, i, original.x, original.y) {
                            @Override public boolean mayPlace(ItemStack stack) { return bound.canPlaceItem(slot, stack); }
                        };
                        replacement.index = original.index;
                        menu.slots.set(i, replacement);
                    }
                    return menu;
                }, title));
    }

    private void closeViewers() {
        for (ServerPlayer player : List.copyOf(viewers)) {
            if (player.containerMenu instanceof ChestMenu menu && menu.getContainer() == view) player.closeContainer();
        }
        viewers.clear();
    }

    private static MenuType<ChestMenu> menuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    private int viewSize() {
        Container container = getEntityContainer();
        int size = npc.getEntity() instanceof ServerPlayer ? 36 : container == null ? contents.length : container.getContainerSize();
        return Math.max(9, Math.min(54, ((size + 8) / 9) * 9));
    }

    private Container getEntityContainer() {
        Entity entity = npc.getEntity();
        if (entity instanceof ServerPlayer player) return player.getInventory();
        if (entity instanceof AbstractHorse horse) return horse.getInventory();
        return entity instanceof Container container ? container : null;
    }

    private void readBack() {
        Container source = getEntityContainer();
        if (source == null) return;
        for (int i = 0; i < contents.length; i++)
            contents[i] = i < source.getContainerSize() ? copy(source.getItem(i)) : null;
    }

    @Override
    public void save(DataKey key) {
        if (npc.isSpawned()) readBack();
        for (int slot = 0; slot < contents.length; slot++) {
            stored.save(slot, key.getRelative(String.valueOf(slot)), contents[slot]);
        }
    }

    public void setContents(ItemStack[] newContents) {
        stored.clear();
        contents = new ItemStack[SIZE];
        for (int i = 0; i < contents.length && i < newContents.length; i++) contents[i] = copy(newContents[i]);
        pushToEntity();
        syncHand();
    }

    /** Main player slots are restored here; Equipment owns armor/off-hand restoration. */
    private void pushToEntity() {
        Container destination = getEntityContainer();
        if (destination == null) return;
        int size = npc.getEntity() instanceof ServerPlayer ? 36 : destination.getContainerSize();
        for (int i = 0; i < size && i < contents.length; i++) destination.setItem(i, nonnull(contents[i]).copy());
    }

    public void setItem(int slot, ItemStack item) {
        if (slot < 0 || slot >= contents.length) throw new IndexOutOfBoundsException("Inventory slot " + slot);
        stored.clear(slot);
        contents[slot] = copy(item);
        Container destination = getEntityContainer();
        if (destination != null && slot < destination.getContainerSize()) destination.setItem(slot, nonnull(item).copy());
        if (slot == handSlot()) syncHand();
    }

    private int handSlot() {
        if (npc.getEntity() instanceof ServerPlayer player) return player.getInventory().selected;
        return getEntityContainer() == null ? 0 : -1;
    }

    private void syncHand() {
        int slot = handSlot();
        if (syncingHand || slot < 0 || !(npc.getEntity() instanceof LivingEntity)) return;
        Container source = getEntityContainer();
        ItemStack item = source == null ? contents[slot] : source.getItem(slot);
        Equipment equipment = npc.getTraitNullable(Equipment.class);
        if (equipment == null && (item == null || item.isEmpty())) return;
        if (equipment != null && ItemStack.matches(nonnull(equipment.get(EquipmentSlot.HAND)), nonnull(item))) return;
        syncingHand = true;
        try { npc.getOrAddTrait(Equipment.class).set(EquipmentSlot.HAND, item); }
        finally { syncingHand = false; }
    }

    /** Equipment writes the native hand itself. This notification must not replace a horse's saddle slot. */
    void setItemInHand(ItemStack item) {
        int slot = handSlot();
        if (slot >= 0) { stored.clear(slot); contents[slot] = copy(item); }
    }

    private static ItemStack copy(ItemStack item) { return item == null || item.isEmpty() ? null : item.copy(); }
    private static ItemStack nonnull(ItemStack item) { return item == null ? ItemStack.EMPTY : item; }

    private final class InventoryView implements Container {
        private final int size;
        InventoryView(int size) { this.size = size; }
        @Override public int getContainerSize() { return size; }
        @Override public boolean isEmpty() {
            for (int i = 0; i < size; i++) if (!getItem(i).isEmpty()) return false;
            return true;
        }
        @Override public ItemStack getItem(int slot) {
            if (slot < 0 || slot >= size) return ItemStack.EMPTY;
            Container source = getEntityContainer();
            return source == null ? nonnull(contents[slot])
                    : slot < source.getContainerSize() ? source.getItem(slot) : ItemStack.EMPTY;
        }
        @Override public ItemStack removeItem(int slot, int count) {
            ItemStack stack = getItem(slot);
            if (stack.isEmpty() || count <= 0) return ItemStack.EMPTY;
            ItemStack removed = stack.split(count);
            if (stack.isEmpty()) setItem(slot, ItemStack.EMPTY);
            setChanged();
            return removed;
        }
        @Override public ItemStack removeItemNoUpdate(int slot) {
            ItemStack result = getItem(slot);
            setItem(slot, ItemStack.EMPTY);
            return result;
        }
        @Override public void setItem(int slot, ItemStack stack) { Inventory.this.setItem(slot, stack); }
        @Override public void setChanged() {
            Container source = getEntityContainer();
            if (source != null) source.setChanged();
            readBack(); syncHand();
        }
        @Override public boolean stillValid(Player player) {
            return npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc && npc.getTraitNullable(Inventory.class) == Inventory.this;
        }
        @Override public void startOpen(Player player) { if (player instanceof ServerPlayer serverPlayer) viewers.add(serverPlayer); }
        @Override public void stopOpen(Player player) { if (player instanceof ServerPlayer serverPlayer) viewers.remove(serverPlayer); }
        @Override public void clearContent() { for (int i = 0; i < size; i++) setItem(i, ItemStack.EMPTY); }
        @Override public boolean canPlaceItem(int slot, ItemStack item) {
            Container source = getEntityContainer();
            return source == null || slot < source.getContainerSize() && source.canPlaceItem(slot, item);
        }
        @Override public int getMaxStackSize() {
            Container source = getEntityContainer();
            return source == null ? Container.super.getMaxStackSize() : source.getMaxStackSize();
        }
    }

    @Override public String toString() { return "Inventory{" + Arrays.toString(contents) + "}"; }
    private static final int SIZE = 72;
}
