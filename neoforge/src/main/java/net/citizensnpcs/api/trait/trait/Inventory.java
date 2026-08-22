package net.citizensnpcs.api.trait.trait;

import java.util.Arrays;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.ItemStorage;
import net.citizensnpcs.api.util.TextParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * Represents an NPC's inventory — the 72 slots {@code /npc inventory} opens.
 * <p>
 * The visible inventory is a plain {@link SimpleContainer} shown through a vanilla {@link ChestMenu}, so no part of the
 * Bukkit {@code InventoryHolder} / {@code InventoryClickEvent} machinery is needed. Because the menu operates directly
 * on that container, edits land in it as they happen; upstream's close-event copy-back exists only because Bukkit hands
 * out a detached snapshot. A {@link net.minecraft.world.ContainerListener} keeps slot 0 in step with the
 * {@link Equipment} hand slot, which is the one visible side effect of editing the inventory.
 * <p>
 * {@code null} means "slot empty" in {@link #getContents()}, matching upstream and {@link ItemStorage}.
 */
@TraitName("inventory")
public class Inventory extends Trait {
    private ItemStack[] contents = new ItemStack[SIZE];
    private boolean syncingHand;
    private SimpleContainer view;

    public Inventory() {
        super("inventory");
    }

    /**
     * Gets the contents of an NPC's inventory.
     *
     * @return ItemStack array of an NPC's inventory contents; entries are null when empty
     */
    public ItemStack[] getContents() {
        readBack();
        return contents;
    }

    /**
     * @return the container backing the open inventory screen, or null before the NPC has spawned
     */
    public Container getInventoryView() {
        return view;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        contents = parseContents(key);
    }

    @Override
    public void onDespawn() {
        readBack();
    }

    @Override
    public void onSpawn() {
        view = new SimpleContainer(viewSize());
        for (int i = 0; i < view.getContainerSize() && i < contents.length; i++) {
            view.setItem(i, contents[i] == null ? ItemStack.EMPTY : contents[i].copy());
        }
        view.addListener(container -> onViewChanged());
        setContents(contents);
    }

    /** Opens the NPC's inventory for the given player to edit. */
    public void openInventory(ServerPlayer sender) {
        if (view == null) {
            // an unspawned NPC has no entity container to mirror, but its stored contents are still editable
            onSpawn();
        }
        readBack();
        int rows = view.getContainerSize() / 9;
        Component title = TextParser.parse(npc.getName()).append(Component.literal("'s Inventory"));
        sender.openMenu(new SimpleMenuProvider(
                (id, playerInventory, player) -> new ChestMenu(menuType(rows), id, playerInventory, view, rows),
                title));
    }

    private static MenuType<ChestMenu> menuType(int rows) {
        switch (rows) {
            case 1:
                return MenuType.GENERIC_9x1;
            case 2:
                return MenuType.GENERIC_9x2;
            case 3:
                return MenuType.GENERIC_9x3;
            case 4:
                return MenuType.GENERIC_9x4;
            case 5:
                return MenuType.GENERIC_9x5;
            default:
                return MenuType.GENERIC_9x6;
        }
    }

    /**
     * Mirrors upstream's sizing: a player NPC shows its 36 real slots, an entity with its own container shows that
     * container's size, anything else gets the full 72 stored slots — then rounded up to whole rows and clamped to what
     * a chest screen can display.
     */
    private int viewSize() {
        Entity entity = npc.getEntity();
        int size = contents.length;
        if (entity instanceof ServerPlayer) {
            size = 36;
        } else {
            Container container = getEntityContainer(entity);
            if (container != null) {
                size = container.getContainerSize();
            }
        }
        int rem = size % 9;
        if (rem != 0) {
            size += 9 - rem;
        }
        return Math.max(9, Math.min(54, size));
    }

    /**
     * The entity's own container, when it has one. Players and horses expose theirs through dedicated accessors;
     * chest minecarts and similar implement {@link Container} directly.
     */
    private Container getEntityContainer(Entity entity) {
        if (entity instanceof ServerPlayer player)
            return player.getInventory();
        if (entity instanceof AbstractHorse horse)
            return horse.getInventory();
        if (entity instanceof Container container)
            return container;
        return null;
    }

    private void onViewChanged() {
        if (syncingHand || view == null)
            return;
        readBack();
        pushToEntity();
        if (npc.getEntity() instanceof LivingEntity) {
            syncingHand = true;
            try {
                npc.getOrAddTrait(Equipment.class).set(EquipmentSlot.HAND, contents[0]);
            } finally {
                syncingHand = false;
            }
        }
    }

    /** Copies the live container back into the stored array. */
    private void readBack() {
        if (view == null)
            return;
        for (int i = 0; i < contents.length; i++) {
            if (i >= view.getContainerSize()) {
                contents[i] = null;
                continue;
            }
            ItemStack item = view.getItem(i);
            contents[i] = item.isEmpty() ? null : item.copy();
        }
    }

    private ItemStack[] parseContents(DataKey key) throws NPCLoadException {
        ItemStack[] parsed = new ItemStack[SIZE];
        for (DataKey slotKey : key.getIntegerSubKeys()) {
            int slot = Integer.parseInt(slotKey.name());
            if (slot < 0 || slot >= parsed.length)
                continue;
            parsed[slot] = ItemStorage.loadItemStack(slotKey);
        }
        return parsed;
    }

    @Override
    public void save(DataKey key) {
        if (npc.isSpawned()) {
            readBack();
        }
        for (int slot = 0; slot < contents.length; slot++) {
            // clear the previous entry so a now-empty slot does not keep a stale item
            key.removeKey(String.valueOf(slot));
            if (contents[slot] != null) {
                ItemStorage.saveItem(key.getRelative(String.valueOf(slot)), contents[slot]);
            }
        }
    }

    /**
     * Sets the contents of an NPC's inventory.
     */
    public void setContents(ItemStack[] newContents) {
        contents = Arrays.copyOf(newContents, SIZE);
        if (view != null) {
            for (int i = 0; i < view.getContainerSize(); i++) {
                ItemStack item = i < contents.length ? contents[i] : null;
                view.setItem(i, item == null ? ItemStack.EMPTY : item.copy());
            }
        }
        pushToEntity();
    }

    /** Writes the stored contents into the entity's own container, when it has one. */
    private void pushToEntity() {
        Container dest = getEntityContainer(npc.getEntity());
        if (dest == null)
            return;
        int max = npc.getEntity() instanceof ServerPlayer ? 36 : dest.getContainerSize();
        for (int i = 0; i < max && i < contents.length; i++) {
            dest.setItem(i, contents[i] == null ? ItemStack.EMPTY : contents[i].copy());
        }
    }

    public void setItem(int slot, ItemStack item) {
        if (slot < 0 || slot >= contents.length)
            throw new IndexOutOfBoundsException("slot " + slot + " outside 0.." + (contents.length - 1));
        item = item == null || item.isEmpty() ? null : item.copy();
        contents[slot] = item;
        if (view != null && slot < view.getContainerSize()) {
            view.setItem(slot, item == null ? ItemStack.EMPTY : item.copy());
        }
        Container dest = getEntityContainer(npc.getEntity());
        if (dest != null && slot < dest.getContainerSize()) {
            dest.setItem(slot, item == null ? ItemStack.EMPTY : item.copy());
        }
        if (slot == 0 && npc.getEntity() instanceof LivingEntity && !syncingHand) {
            syncingHand = true;
            try {
                npc.getOrAddTrait(Equipment.class).set(EquipmentSlot.HAND, item);
            } finally {
                syncingHand = false;
            }
        }
    }

    /**
     * Sets slot 0 without pushing back to {@link Equipment} — the call comes from there.
     */
    void setItemInHand(ItemStack item) {
        item = item == null || item.isEmpty() ? null : item.copy();
        contents[0] = item;
        if (view != null && view.getContainerSize() > 0) {
            syncingHand = true;
            try {
                view.setItem(0, item == null ? ItemStack.EMPTY : item.copy());
            } finally {
                syncingHand = false;
            }
        }
    }

    @Override
    public String toString() {
        return "Inventory{" + Arrays.toString(contents) + "}";
    }

    private static final int SIZE = 72;
}
