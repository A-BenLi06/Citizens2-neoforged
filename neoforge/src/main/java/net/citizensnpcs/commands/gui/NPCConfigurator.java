package net.citizensnpcs.commands.gui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import net.citizensnpcs.api.gui.CitizensInventoryClickEvent;
import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.InventoryType;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.npc.NPC;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The page {@code /npc configgui} opens.
 * <p>
 * Upstream's version is a placeholder: the {@code ConfiguratorEvent}/{@code ConfiguratorInfo} scaffolding is built for a
 * grid of settings but only one slot is ever filled in, the sign that renames the NPC. That is ported as-is rather than
 * extended — adding settings would be designing a new screen, not moving this one across — so the scaffolding is kept
 * for whoever fills the rest in.
 * <p>
 * The material is a plain {@code OAK_SIGN}; upstream reaches for it through a fallback helper because the name changed in
 * an older Bukkit version, which is not a concern for a single target version.
 */
@Menu(title = "Configure NPC", type = InventoryType.CHEST, dimensions = { 5, 9 })
public class NPCConfigurator extends InventoryMenuPage {
    private NPC npc;

    private NPCConfigurator() {
        throw new UnsupportedOperationException();
    }

    public NPCConfigurator(NPC npc) {
        this.npc = npc;
    }

    @Override
    public void initialise(MenuContext ctx) {
        for (Map.Entry<Integer, ConfiguratorInfo> entry : SLOT_MAP.entrySet()) {
            ConfiguratorInfo info = entry.getValue();
            InventoryMenuSlot slot = ctx.getSlot(entry.getKey());
            slot.setItemStack(new ItemStack(info.item));
            slot.setClickHandler(evt -> info.clickHandler.accept(new ConfiguratorEvent(ctx, npc, slot, evt)));
            // run once with a null event so the slot's description shows the current value before anything is clicked
            info.clickHandler.accept(new ConfiguratorEvent(ctx, npc, slot, null));
        }
    }

    private static class ConfiguratorEvent {
        private final MenuContext ctx;
        private final CitizensInventoryClickEvent event;
        private final NPC npc;
        private final InventoryMenuSlot slot;

        public ConfiguratorEvent(MenuContext ctx, NPC npc, InventoryMenuSlot slot, CitizensInventoryClickEvent evt) {
            this.ctx = ctx;
            this.npc = npc;
            this.slot = slot;
            event = evt;
        }
    }

    private static class ConfiguratorInfo {
        private final Consumer<ConfiguratorEvent> clickHandler;
        private final net.minecraft.world.item.Item item;

        public ConfiguratorInfo(net.minecraft.world.item.Item item, Consumer<ConfiguratorEvent> handler) {
            this.item = item;
            clickHandler = handler;
        }
    }

    private static final Map<Integer, ConfiguratorInfo> SLOT_MAP = new LinkedHashMap<>();
    static {
        SLOT_MAP.put(0, new ConfiguratorInfo(Items.OAK_SIGN, evt -> {
            evt.slot.setDescription("Edit NPC name\n" + evt.npc.getName());
            if (evt.event != null) {
                evt.ctx.getMenu().transition(InputMenus.stringSetter(() -> evt.npc.getName(), evt.npc::setName));
            }
        }));
    }
}
