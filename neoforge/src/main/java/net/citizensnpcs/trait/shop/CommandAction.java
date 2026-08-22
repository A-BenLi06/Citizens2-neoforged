package net.citizensnpcs.trait.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Runs commands as the cost or the reward of a trade.
 * <p>
 * The commands may run as the player or as the server, and optionally with operator permissions. Nothing is charged and
 * nothing can be rolled back: a command that has run has run, so the rollback is deliberately empty.
 */
public class CommandAction extends NPCShopAction {
    @Persist
    public List<String> commands = new ArrayList<>();
    @Persist
    public boolean op = false;
    @Persist
    public boolean server = false;

    public CommandAction() {
    }

    public CommandAction(List<String> commands) {
        this.commands = commands;
    }

    @Override
    public String describe() {
        String description = commands.size() + " command";
        for (int i = 0; i < commands.size(); i++) {
            description += "<br>" + commands.get(i);
            if (i == 3) {
                description += "...";
                break;
            }
        }
        return description;
    }

    @Override
    public int getMaxRepeats(Entity entity, InventoryMultiplexer inventory) {
        return -1;
    }

    @Override
    public Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        return run(entity, inventory, repeats);
    }

    @Override
    public Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        return run(entity, inventory, repeats);
    }

    private Transaction run(Entity entity, InventoryMultiplexer inventory, int repeats) {
        if (!(entity instanceof ServerPlayer player))
            return Transaction.fail();
        return Transaction.create(() -> true, () -> {
            for (int i = 0; i < repeats; i++) {
                for (String command : commands) {
                    Util.runCommand(null, player, command, op, !server);
                }
            }
            // a command may well have handed the player items, so anything staged from before it ran is stale
            inventory.refresh();
        }, () -> {
        });
    }

    @Menu(title = "Command editor", dimensions = { 4, 9 })
    public static class CommandActionEditor extends InventoryMenuPage {
        private CommandAction base;
        private Consumer<NPCShopAction> callback;

        public CommandActionEditor() {
        }

        public CommandActionEditor(CommandAction base, Consumer<NPCShopAction> callback) {
            this.base = base;
            this.callback = callback;
        }

        @Override
        public void initialise(MenuContext ctx) {
            for (int i = 0; i < 3 * 9; i++) {
                int idx = i;
                ctx.getSlot(i).clear();
                if (i < base.commands.size()) {
                    ctx.getSlot(i).setItemStack(new ItemStack(Items.FEATHER), "<white>Set command",
                            "Right click to remove<br>Currently: " + base.commands.get(i));
                }
                ctx.getSlot(i).setClickHandler(event -> {
                    if (event.isRightClick()) {
                        event.setCancelled(true);
                        if (idx < base.commands.size()) {
                            base.commands.remove(idx);
                            ctx.getSlot(idx).setItemStack(ItemStack.EMPTY);
                        }
                        return;
                    }
                    ctx.getMenu().transition(InputMenus
                            .stringSetter(() -> idx < base.commands.size() ? base.commands.get(idx) : "", res -> {
                                if (res == null || res.isEmpty()) {
                                    if (idx < base.commands.size()) {
                                        base.commands.remove(idx);
                                    }
                                    return;
                                }
                                if (idx < base.commands.size()) {
                                    base.commands.set(idx, res);
                                } else {
                                    base.commands.add(res);
                                }
                            }));
                });
            }
            ctx.getSlot(3 * 9 + 3).setItemStack(new ItemStack(Items.COMMAND_BLOCK), "Run commands as server",
                    base.server ? "<green>On" : "<red>Off");
            ctx.getSlot(3 * 9 + 3).addClickHandler(InputMenus.toggler(res -> base.server = res, base.server));
            ctx.getSlot(3 * 9 + 4).setItemStack(new ItemStack(Items.COMPARATOR), "Run commands as op",
                    base.op ? "<green>On" : "<red>Off");
            // upstream seeds this toggle from base.server, so the op button opens showing the wrong state and the first
            // click can flip it back to what it already was
            ctx.getSlot(3 * 9 + 4).addClickHandler(InputMenus.clickToggle(res -> {
                base.op = res;
                return res ? "<green>On" : "<red>Off";
            }, base.op));
        }

        @Override
        public void onClose(ServerPlayer player) {
            callback.accept(base.commands.isEmpty() ? null : base);
        }
    }

    public static class CommandActionGUI implements GUI {
        @Override
        public boolean canUse(ServerPlayer entity) {
            return PermissionUtil.hasPermission(entity, "citizens.npc.shop.editor.actions.edit-command");
        }

        @Override
        public InventoryMenuPage createEditor(NPCShopAction previous, Consumer<NPCShopAction> callback) {
            return new CommandActionEditor(previous == null ? new CommandAction() : (CommandAction) previous, callback);
        }

        @Override
        public ItemStack createMenuItem(NPCShopAction previous) {
            return Util.createItem("minecraft:command_block", "Command",
                    previous == null ? null : previous.describe());
        }

        @Override
        public boolean manages(NPCShopAction action) {
            return action instanceof CommandAction;
        }
    }
}
