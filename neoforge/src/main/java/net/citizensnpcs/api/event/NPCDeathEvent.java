package net.citizensnpcs.api.event;

import java.util.List;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;

/**
 * Called while an NPC's death drops are being decided, so listeners can add to, remove from or replace them.
 * <p>
 * Upstream wraps Bukkit's {@code EntityDeathEvent}, whose drop list is directly mutable. The NeoForge counterpart is
 * {@link net.neoforged.neoforge.event.entity.living.LivingDropsEvent}, which holds item <em>entities</em> rather than
 * stacks; {@link net.citizensnpcs.EventListen} unwraps them into this list and rebuilds them afterwards, so the list
 * behaves the same way — including removals.
 */
public class NPCDeathEvent extends NPCEvent {
    private final List<ItemStack> drops;
    private final DamageSource source;

    public NPCDeathEvent(NPC npc, DamageSource source, List<ItemStack> drops) {
        super(npc);
        this.source = source;
        this.drops = drops;
    }

    /**
     * @return the mutable list of stacks the NPC is about to drop
     */
    public List<ItemStack> getDrops() {
        return drops;
    }

    /**
     * @return what killed the NPC
     */
    public DamageSource getSource() {
        return source;
    }
}
