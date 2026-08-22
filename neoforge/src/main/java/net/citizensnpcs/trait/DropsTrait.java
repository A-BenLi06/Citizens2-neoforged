package net.citizensnpcs.trait;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import java.util.HashMap;
import java.util.Map;

import net.citizensnpcs.api.gui.CitizensInventoryClickEvent;
import net.citizensnpcs.api.gui.PercentageSlotHandler;
import net.citizensnpcs.api.gui.InventoryAction;
import net.citizensnpcs.api.gui.InventoryMenu;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.InventoryType;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitEventHandler;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Extra items an NPC drops when it dies, each with its own chance.
 * <p>
 * The editor is a chest GUI: items on the even rows are what drops, and the barrier below each one carries its chance.
 */
@TraitName("dropstrait")
public class DropsTrait extends Trait {
    @Persist(reify = true)
    private List<ItemDrop> drops = new ArrayList<>();

    public DropsTrait() {
        super("dropstrait");
    }

    public void addDrop(ItemStack drop, double chance) {
        drops.add(new ItemDrop(drop, chance));
    }

    public List<ItemDrop> getDrops() {
        return drops;
    }

    @TraitEventHandler
    public void onNPCDeath(NPCDeathEvent event) {
        Random random = Util.getFastRandom();
        for (ItemDrop drop : drops) {
            if (drop.drop != null && !drop.drop.isEmpty() && random.nextDouble() < drop.chance) {
                event.getDrops().add(drop.drop.copy());
            }
        }
    }

    /** Opens the drops editor for this player. */
    public void displayEditor(ServerPlayer sender) {
        InventoryMenu.createSelfRegistered(new DropsGUI(this)).present(sender);
    }

    @Menu(title = "Add items for drops", type = InventoryType.CHEST, dimensions = { 5, 9 })
    public static class DropsGUI extends InventoryMenuPage {
        private final Map<Integer, Double> chances = new HashMap<>();
        private Container container;
        private DropsTrait trait;

        private DropsGUI() {
            throw new UnsupportedOperationException();
        }

        public DropsGUI(DropsTrait trait) {
            this.trait = trait;
        }

        @Override
        public void initialise(MenuContext ctx) {
            container = ctx.getContainer();
            int k = 0;
            for (int i = 1; i < 5; i += 2) {
                for (int j = 0; j < 9; j++) {
                    int islot = (i - 1) * 9 + j;
                    int chance = 100;
                    if (k < trait.drops.size()) {
                        ItemDrop drop = trait.drops.get(k++);
                        chance = (int) Math.floor(drop.chance * 100.0);
                        chances.put(islot, drop.chance);
                        container.setItem(islot, drop.drop.copy());
                    }
                    InventoryMenuSlot slot = ctx.getSlot(i * 9 + j);
                    int shown = chance;
                    slot.setItemStack(new ItemStack(Items.BARRIER), "Drop chance <yellow>" + shown + "%");
                    slot.setClickHandler(new PercentageSlotHandler(pct -> {
                        if (chances.containsKey(islot)) {
                            chances.put(islot, pct / 100.0);
                        }
                        return "Drop chance <yellow>" + pct + "%";
                    }, chance));
                }
            }
        }

        @Override
        public void onClick(InventoryMenuSlot slot, CitizensInventoryClickEvent event) {
            // the barrier rows are the chance controls; the rows above them are the player's to fill
            if (!slot.getCurrentItemNonNull().isEmpty() && slot.getCurrentItemNonNull().is(Items.BARRIER))
                return;
            event.setCancelled(false);
            if (event.getAction() == InventoryAction.PICKUP_ALL || event.getAction() == InventoryAction.PICKUP_HALF) {
                chances.remove(event.getSlot());
            } else if (event.getAction() == InventoryAction.PLACE_ALL
                    || event.getAction() == InventoryAction.PLACE_ONE) {
                chances.putIfAbsent(event.getSlot(), 1.0);
            }
        }

        @Override
        public void onClose(ServerPlayer player) {
            List<ItemDrop> drops = new ArrayList<>();
            for (int i = 0; i < 5; i += 2) {
                for (int j = 0; j < 9; j++) {
                    int slot = i * 9 + j;
                    ItemStack stack = container.getItem(slot);
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }
                    drops.add(new ItemDrop(stack.copy(), chances.getOrDefault(slot, 1.0)));
                }
            }
            trait.drops = drops;
        }
    }

    /** One entry: what to drop, and how likely it is. */
    public static class ItemDrop {
        @Persist
        double chance;
        @Persist
        ItemStack drop;

        public ItemDrop() {
        }

        public ItemDrop(ItemStack drop, double chance) {
            this.drop = drop;
            this.chance = chance;
        }

        public double getChance() {
            return chance;
        }

        public ItemStack getDrop() {
            return drop;
        }
    }
}
