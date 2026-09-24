package net.citizensnpcs.api.gui;

import net.citizensnpcs.api.util.Messaging;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Native rename packets edit a per-viewer draft. The paper and result are controls, never transferable items. */
final class CitizensAnvilMenu extends AnvilMenu {
    private final InventoryMenu owner;
    private final InputMenus.StringInputPage page;
    private boolean submitting;
    private String text;

    CitizensAnvilMenu(int id, Inventory inventory, InventoryMenu owner, InputMenus.StringInputPage page, String initial) {
        super(id, inventory);
        this.owner = owner;
        this.page = page;
        // Reuse native slot indices, containers and positions while protecting both inputs and the virtual result.
        for (int i = INPUT_SLOT; i <= RESULT_SLOT; i++) {
            Slot original = getSlot(i);
            Slot control = new Slot(original.container, original.getContainerSlot(), original.x, original.y) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
            };
            control.index = i;
            slots.set(i, control);
        }
        text = initial == null ? "" : initial;
        inputSlots.setItem(INPUT_SLOT, paper());
    }

    static boolean canRepresent(String value) {
        return value == null || value.length() <= MAX_NAME_LENGTH && value.equals(StringUtil.filterText(value));
    }

    private ItemStack paper() {
        ItemStack paper = new ItemStack(Items.PAPER);
        // A custom empty name enables a genuinely empty field without using a translated sentinel item name.
        paper.set(DataComponents.CUSTOM_NAME, Component.literal(text));
        return paper;
    }

    @Override
    public void createResult() {
        resultSlots.setItem(0, paper());
        setMaximumCost(0);
    }

    @Override
    public boolean setItemName(String name) {
        if (submitting || !owner.isCurrent(this, player))
            return false;
        String filtered = StringUtil.filterText(name);
        if (filtered.length() > MAX_NAME_LENGTH)
            return false;
        boolean changed = !filtered.equals(text);
        text = filtered;
        inputSlots.setItem(INPUT_SLOT, paper());
        // Vanilla predicts repair results locally, even when the name is unchanged or empty. Resend the controls and
        // zero cost so that prediction cannot hide confirmation or impose an experience requirement.
        sendAllDataToRemote();
        return changed;
    }

    @Override
    public void clicked(int index, int button, ClickType type, Player clicker) {
        if (submitting || !owner.isCurrent(this, clicker))
            return;
        if (index >= INPUT_SLOT && index <= RESULT_SLOT && type != ClickType.QUICK_CRAFT) {
            boolean pendingDrag = quickcraftStatus != 0;
            resetQuickCraft();
            if (!pendingDrag && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)
                    && (button == 0 || button == 1)) {
                submitting = true;
                try {
                    if (index == INPUT_SLOT) {
                        owner.transitionBack();
                    } else if (index == RESULT_SLOT && page.accept(text.isEmpty() ? null : text)
                            && owner.isCurrent(this, clicker)) {
                        owner.transitionBack();
                    }
                } catch (Exception failure) {
                    Messaging.severe("Error handling text input");
                    failure.printStackTrace();
                    owner.close();
                } finally {
                    submitting = false;
                }
            }
        } else if (type == ClickType.QUICK_CRAFT && getQuickcraftHeader(button) == 1
                && (index < 0 || index >= slots.size())) {
            resetQuickCraft();
        } else if (index >= -1 && index < slots.size() || index == -999) {
            super.clicked(index, button, type, clicker);
        }
        if (owner.isCurrent(this, clicker) && (type != ClickType.QUICK_CRAFT || quickcraftStatus == 0)) {
            sendAllDataToRemote();
        }
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.container == player.getInventory() && super.canDragTo(slot);
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container == player.getInventory() && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    protected boolean canMoveIntoInputSlots(ItemStack stack) {
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return owner.isCurrent(this, player) && index > RESULT_SLOT && index < slots.size()
                ? super.quickMoveStack(player, index) : ItemStack.EMPTY;
    }

    @Override
    protected boolean mayPickup(Player player, boolean hasItem) {
        return false;
    }

    @Override
    protected void onTake(Player player, ItemStack stack) {
        // No repair, experience cost, material consumption, sound or block damage for a configuration control.
    }

    @Override
    public boolean stillValid(Player player) {
        return owner.isCurrent(this, player);
    }

    @Override
    public void removed(Player player) {
        resetQuickCraft();
        // ContainerLevelAccess.NULL prevents the virtual input from being returned; native removal returns the cursor.
        super.removed(player);
        owner.onContainerClosed(this, player);
    }
}
