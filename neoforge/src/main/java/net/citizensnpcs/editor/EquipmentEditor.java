package net.citizensnpcs.editor;

import java.util.HashMap;
import java.util.Map;

import net.citizensnpcs.api.gui.InventoryMenu;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;

/**
 * {@code /npc equip}. Most NPCs get a menu; a few entity types are configured by handing them an item instead, because
 * that is how a player would treat the real animal — a sheep takes shears and dye, a horse opens its own saddlebags.
 * <p>
 * Upstream keys both tables on the {@code EntityType} enum and builds them in a static block, guarding each newer entity
 * with a {@code valueOf} in a try/catch. Entity types are registry objects here, so the tables are plain maps and every
 * type named is one that exists in this version.
 */
public class EquipmentEditor extends Editor {
    private InventoryMenu menu;
    private final NPC npc;
    private final ServerPlayer player;

    public EquipmentEditor(ServerPlayer player, NPC npc) {
        this.player = player;
        this.npc = npc;
    }

    @Override
    public void begin() {
        EntityType<?> type = npc.isSpawned() ? npc.getEntity().getType() : null;
        if (type != null && EQUIPPERS.containsKey(type) && !EQUIPPER_GUIS.containsKey(type)) {
            // handed-an-item types: tell the player to click the NPC, and wait for onRightClickNPC
            Messaging.sendTr(player.createCommandSourceStack(), Messages.EQUIPMENT_EDITOR_BEGIN);
            return;
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("npc", npc);
        Class<? extends InventoryMenuPage> page = type == null ? GenericEquipperGUI.class
                : EQUIPPER_GUIS.getOrDefault(type, GenericEquipperGUI.class);
        menu = InventoryMenu.createWithContext(page, ctx);
        // leaving the editor when the menu closes is what makes a second /npc equip reopen it rather than toggle it shut
        menu.addCloseCallback(() -> Editor.leave(player));
        menu.present(player);
    }

    @Override
    public void end() {
        if (menu != null) {
            InventoryMenu closing = menu;
            menu = null;
            closing.close();
            return;
        }
        Messaging.sendTr(player.createCommandSourceStack(), Messages.EQUIPMENT_EDITOR_END);
    }

    @Override
    protected boolean onRightClickNPC(ServerPlayer clicker, NPC clicked) {
        if (menu != null || clicker != player || !npc.equals(clicked) || !npc.isSpawned())
            return false;
        Equipper equipper = EQUIPPERS.get(npc.getEntity().getType());
        if (equipper == null)
            return false;
        equipper.equip(clicker, npc);
        return true;
    }

    private static final Map<EntityType<?>, Class<? extends InventoryMenuPage>> EQUIPPER_GUIS = new HashMap<>();
    private static final Map<EntityType<?>, Equipper> EQUIPPERS = new HashMap<>();

    static {
        EQUIPPER_GUIS.put(EntityType.PIG, SaddleEquipperGUI.class);
        EQUIPPER_GUIS.put(EntityType.STRIDER, SaddleEquipperGUI.class);
        EQUIPPER_GUIS.put(EntityType.ENDERMAN, EndermanEquipperGUI.class);
        EQUIPPERS.put(EntityType.SHEEP, new SheepEquipper());
        HorseEquipper horse = new HorseEquipper();
        for (EntityType<?> type : new EntityType<?>[] { EntityType.HORSE, EntityType.ZOMBIE_HORSE,
                EntityType.SKELETON_HORSE, EntityType.DONKEY, EntityType.MULE, EntityType.LLAMA,
                EntityType.TRADER_LLAMA, EntityType.CAMEL }) {
            EQUIPPERS.put(type, horse);
        }
    }
}
