package net.citizensnpcs.trait.shop;

import java.util.function.Consumer;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.expr.CompiledExpression;
import net.citizensnpcs.api.expr.ExpressionEngine.ExpressionCompileException;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Gates a trade on an expression: the trade is only possible while the expression is true. Nothing is taken and nothing
 * is given, so there is nothing to roll back.
 */
public class ConditionAction extends NPCShopAction {
    @Persist
    private String condition;
    private CompiledExpression expression;

    public ConditionAction() {
    }

    public ConditionAction(String expression) {
        this.condition = expression;
    }

    private void compile() {
        try {
            expression = CitizensAPI.getExpressionRegistry().compile(condition);
        } catch (ExpressionCompileException e) {
            Messaging.severe("Could not compile shop condition", condition, "-", e.getMessage());
        }
    }

    @Override
    public String describe() {
        return condition;
    }

    @Override
    public int getMaxRepeats(Entity entity, InventoryMultiplexer inventory) {
        return -1;
    }

    @Override
    public Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        return evaluate(entity);
    }

    @Override
    public Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory, int repeats) {
        return evaluate(entity);
    }

    private Transaction evaluate(Entity entity) {
        if (condition == null)
            return Transaction.success();
        if (expression == null) {
            compile();
        }
        // an expression that would not compile blocks the trade rather than waving it through, which is the same answer
        // a false condition gives and the safe one for a shop
        if (expression == null)
            return Transaction.fail();
        return Transaction.create(() -> expression
                .evaluateAsBoolean(ExpressionScope.create(entity instanceof ServerPlayer player ? player : null)),
                () -> {
                }, () -> {
                });
    }

    public void setExpression(String expression) {
        condition = CitizensAPI.getExpressionRegistry().applyDefaultExpressionMarkup(expression);
        this.expression = null;
    }

    public static class ConditionActionGUI implements GUI {
        @Override
        public boolean canUse(ServerPlayer entity) {
            return PermissionUtil.hasPermission(entity, "citizens.npc.shop.editor.actions.edit-condition");
        }

        @Override
        public InventoryMenuPage createEditor(NPCShopAction previous, Consumer<NPCShopAction> callback) {
            ConditionAction action = previous == null ? new ConditionAction() : (ConditionAction) previous;
            return InputMenus.stringSetter("Condition", () -> action.condition, s -> {
                action.setExpression(s);
                callback.accept(action);
            });
        }

        @Override
        public ItemStack createMenuItem(NPCShopAction previous) {
            return Util.createItem("minecraft:enchanted_book", "Condition",
                    previous == null ? null : previous.describe());
        }

        @Override
        public boolean manages(NPCShopAction action) {
            return action instanceof ConditionAction;
        }
    }
}
