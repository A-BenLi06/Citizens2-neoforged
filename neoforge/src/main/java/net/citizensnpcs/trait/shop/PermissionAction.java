package net.citizensnpcs.trait.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A cost or reward paid in permissions — a shop that sells an unlock, or one that only serves players who already hold a
 * permission.
 * <p>
 * Upstream writes permissions through Vault. The port uses {@link PermissionUtil.PermissionWriter} and its reversible
 * change capability. Without that capability the action is unavailable and the editor hides the button.
 */
public class PermissionAction extends NPCShopAction {
    @Persist
    public List<String> permissions = new ArrayList<>();

    public PermissionAction() {
    }

    public PermissionAction(List<String> permissions) {
        this.permissions = permissions;
    }

    @Override
    public String describe() {
        String description = permissions.size() + " permissions";
        for (int i = 0; i < permissions.size(); i++) {
            description += "<br>" + permissions.get(i);
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
        return change(entity, true);
    }

    @Override
    public Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        return change(entity, false);
    }

    private Transaction change(Entity entity, boolean grant) {
        if (!(entity instanceof ServerPlayer player))
            return Transaction.fail();
        if (!PermissionUtil.canWriteReversiblePermissions())
            return unavailable();
        // Resolve once: an edited list or a changing placeholder must not redirect compensation to another node.
        List<String> resolved = permissions.stream().map(permission -> resolve(permission, player)).toList();
        PermissionUtil.PermissionChange change = PermissionUtil.preparePermissionChange(player, resolved, grant);
        return change == null ? unavailable() : Transaction.create(change::isPossible, change::apply, change::rollback);
    }

    private static String resolve(String permission, ServerPlayer player) {
        return Placeholders.replace(permission, player);
    }

    /** A trade cannot consume payment when the permission provider cannot supply a reversible change. */
    private static Transaction unavailable() {
        return Transaction.create(() -> {
            Messaging.severe("An NPC shop requires reversible permission changes, but no compatible provider is available");
            return false;
        }, () -> {
        }, () -> {
        });
    }

    @Menu(title = "Permissions editor", dimensions = { 3, 9 })
    public static class PermissionActionEditor extends InventoryMenuPage {
        private PermissionAction base;
        private Consumer<NPCShopAction> callback;

        public PermissionActionEditor() {
        }

        public PermissionActionEditor(PermissionAction base, Consumer<NPCShopAction> callback) {
            this.base = base;
            this.callback = callback;
        }

        @Override
        public void initialise(MenuContext ctx) {
            for (int i = 0; i < 3 * 9; i++) {
                int idx = i;
                ctx.getSlot(i).clear();
                if (i < base.permissions.size()) {
                    ctx.getSlot(i).setItemStack(new ItemStack(Items.FEATHER), "<white>Set permission",
                            "Right click to remove<br>Currently: " + base.permissions.get(i));
                }
                ctx.getSlot(i).setClickHandler(event -> {
                    if (event.isRightClick()) {
                        event.setCancelled(true);
                        if (idx < base.permissions.size()) {
                            base.permissions.remove(idx);
                            ctx.getSlot(idx).setItemStack(ItemStack.EMPTY);
                        }
                        return;
                    }
                    ctx.getMenu().transition(InputMenus
                            .stringSetter(() -> idx < base.permissions.size() ? base.permissions.get(idx) : "", res -> {
                                if (res == null || res.isEmpty()) {
                                    if (idx < base.permissions.size()) {
                                        base.permissions.remove(idx);
                                    }
                                    return;
                                }
                                if (idx < base.permissions.size()) {
                                    base.permissions.set(idx, res);
                                } else {
                                    base.permissions.add(res);
                                }
                            }));
                });
            }
        }

        @Override
        public void onClose(ServerPlayer player) {
            callback.accept(base.permissions.isEmpty() ? null : base);
        }
    }

    public static class PermissionActionGUI implements GUI {
        @Override
        public boolean canUse(ServerPlayer entity) {
            return PermissionUtil.canWriteReversiblePermissions()
                    && PermissionUtil.hasPermission(entity, "citizens.npc.shop.editor.actions.edit-permission");
        }

        @Override
        public InventoryMenuPage createEditor(NPCShopAction previous, Consumer<NPCShopAction> callback) {
            return new PermissionActionEditor(previous == null ? new PermissionAction() : (PermissionAction) previous,
                    callback);
        }

        @Override
        public ItemStack createMenuItem(NPCShopAction previous) {
            // upstream hides the button when Vault is missing; the same applies with no permission writer installed
            if (!PermissionUtil.canWritePermissions())
                return null;
            return Util.createItem("minecraft:paper", "Permission", previous == null ? null : previous.describe());
        }

        @Override
        public boolean manages(NPCShopAction action) {
            return action instanceof PermissionAction;
        }
    }
}
