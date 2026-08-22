package net.citizensnpcs.api.trait.trait;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.common.collect.Maps;
import com.mojang.datafixers.util.Pair;

import net.citizensnpcs.api.event.NPCEvent;
import net.citizensnpcs.api.event.NPCSeenByPlayerEvent;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPC.NPCUpdate;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitEventHandler;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.ItemStorage;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Represents an NPC's equipment.
 * <p>
 * Slot indices are unchanged from upstream so {@code saves.yml} stays readable across the port: HAND=0, HELMET=1,
 * CHESTPLATE=2, LEGGINGS=3, BOOTS=4, OFF_HAND=5, BODY=6, SADDLE=7.
 * <p>
 * <b>Cosmetic equipment</b> is what a viewer sees when the NPC is not really wearing anything. Upstream implements it
 * by rewriting outgoing {@code SetEquipment} packets through packetevents; this port has no packetevents, so it sends
 * its own {@link ClientboundSetEquipmentPacket} after vanilla's — last packet wins on the client. The moment a viewer
 * needs it is {@link NPCSeenByPlayerEvent}, which arrives from NeoForge's own tracking event rather than upstream's
 * Mixin-installed entity tracker.
 * <p>
 * {@code null} means "slot empty", matching upstream and {@link ItemStorage}; {@link ItemStack#EMPTY} is only used at
 * the Minecraft boundary, which rejects nulls.
 */
@TraitName("equipment")
public class Equipment extends Trait {
    private final ItemStack[] cosmetic = new ItemStack[8];
    private final ItemStack[] equipment = new ItemStack[8];

    public Equipment() {
        super("equipment");
    }

    private ItemStack clone(ItemStack item) {
        return item == null || item.isEmpty() ? null : item.copy();
    }

    /**
     * Get an NPC's equipment from the given slot.
     *
     * @param eslot
     *            Slot where the equipment is located
     * @return ItemStack from the given equipment slot, or null when empty
     */
    public ItemStack get(EquipmentSlot eslot) {
        int slot = eslot.getIndex();
        if (npc.getEntity() instanceof EnderMan && slot != 0)
            throw new IllegalArgumentException("Slot must be 0 for enderman");
        return clone(equipment[slot]);
    }

    /**
     * Gets the NPC's cosmetic equipment from the given slot. Nullable.
     */
    public ItemStack getCosmetic(EquipmentSlot slot) {
        return clone(cosmetic[slot.getIndex()]);
    }

    /**
     * Get all of an NPC's cosmetic equipment.
     */
    public ItemStack[] getCosmeticEquipment() {
        return cosmetic;
    }

    /**
     * Get all of an NPC's equipment.
     */
    public ItemStack[] getEquipment() {
        return equipment;
    }

    /**
     * Get all of the equipment as a {@link Map}.
     */
    public Map<EquipmentSlot, ItemStack> getEquipmentBySlot() {
        Map<EquipmentSlot, ItemStack> map = Maps.newEnumMap(EquipmentSlot.class);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            map.put(slot, clone(equipment[slot.getIndex()]));
        }
        return map;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            String name = slot.name().toLowerCase(Locale.ROOT);
            if (key.keyExists(name)) {
                equipment[slot.getIndex()] = ItemStorage.loadItemStack(key.getRelative(name));
            }
            if (key.keyExists("cosmetic_" + name)) {
                cosmetic[slot.getIndex()] = ItemStorage.loadItemStack(key.getRelative("cosmetic_" + name));
            }
        }
    }

    @Override
    public void onAttach() {
        npc.scheduleUpdate(NPCUpdate.PACKET);
        run();
    }

    /**
     * Sends the cosmetic overlay to a viewer who just started tracking the NPC. Only fires when the NPC has no real
     * equipment to show — with real gear on, that gear is what everyone is meant to see.
     */
    @TraitEventHandler(priority = EventPriority.LOWEST)
    private void onSeenByPlayer(NPCSeenByPlayerEvent event) {
        if (!hasCosmeticOnly())
            return;
        ClientboundSetEquipmentPacket packet = buildCosmeticPacket();
        if (packet != null) {
            event.getPlayer().connection.send(packet);
        }
    }

    private boolean hasCosmeticOnly() {
        for (ItemStack stack : equipment) {
            if (stack != null && !stack.isEmpty())
                return false;
        }
        for (ItemStack stack : cosmetic) {
            if (stack != null && !stack.isEmpty())
                return true;
        }
        return false;
    }

    private ClientboundSetEquipmentPacket buildCosmeticPacket() {
        Entity entity = npc.getEntity();
        if (!(entity instanceof LivingEntity))
            return null;
        List<Pair<net.minecraft.world.entity.EquipmentSlot, ItemStack>> slots = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            net.minecraft.world.entity.EquipmentSlot vanilla = slot.toVanilla();
            if (vanilla == null)
                continue;
            ItemStack item = cosmetic[slot.getIndex()];
            slots.add(Pair.of(vanilla, item == null ? ItemStack.EMPTY : item.copy()));
        }
        return slots.isEmpty() ? null : new ClientboundSetEquipmentPacket(entity.getId(), slots);
    }

    /** Re-sends the cosmetic overlay to everyone currently tracking the NPC. */
    private void broadcastCosmetic() {
        Entity entity = npc.getEntity();
        if (entity == null || !(entity.level() instanceof ServerLevel level) || !hasCosmeticOnly())
            return;
        ClientboundSetEquipmentPacket packet = buildCosmeticPacket();
        if (packet != null) {
            level.getChunkSource().broadcastAndSend(entity, packet);
        }
    }

    @Override
    public void onSpawn() {
        Entity entity = npc.getEntity();
        if (!(entity instanceof LivingEntity living))
            return;
        if (entity instanceof EnderMan enderman) {
            applyEndermanBlock(enderman, equipment[0]);
        } else {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                applyToEntity(living, slot, equipment[slot.getIndex()]);
            }
        }
        broadcastCosmetic();
    }

    @Override
    public void run() {
        Entity entity = npc.getEntity();
        if (!(entity instanceof LivingEntity living) || !npc.isUpdating(NPCUpdate.PACKET))
            return;
        // read back what the entity actually has, so gear picked up or changed by other code is persisted
        if (entity instanceof EnderMan enderman) {
            if (equipment[0] != null && enderman.getCarriedBlock() != null) {
                equipment[0] = new ItemStack(enderman.getCarriedBlock().getBlock());
            }
            return;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            net.minecraft.world.entity.EquipmentSlot vanilla = slot.toVanilla();
            if (vanilla == null) {
                if (slot == EquipmentSlot.SADDLE && entity instanceof Saddleable saddleable && !saddleable.isSaddled()) {
                    equipment[slot.getIndex()] = null;
                }
                continue;
            }
            if (!supportsSlot(living, slot)) {
                continue;
            }
            equipment[slot.getIndex()] = clone(living.getItemBySlot(vanilla));
        }
    }

    @Override
    public void save(DataKey key) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            String name = slot.name().toLowerCase(Locale.ROOT);
            saveOrRemove(key.getRelative(name), equipment[slot.getIndex()]);
            saveOrRemove(key.getRelative("cosmetic_" + name), cosmetic[slot.getIndex()]);
        }
    }

    private void saveOrRemove(DataKey key, ItemStack item) {
        if (item != null && !item.isEmpty()) {
            ItemStorage.saveItem(key, item);
        } else if (key.keyExists("")) {
            key.removeKey("");
        }
    }

    /**
     * Set the equipment in the given slot.
     *
     * @param eslot
     *            Equipment slot
     * @param item
     *            Item to equip, or null to clear the slot
     */
    public void set(EquipmentSlot eslot, ItemStack item) {
        NeoForge.EVENT_BUS.post(new NPCChangeEquipmentEvent(npc, eslot, item));
        int slot = eslot.getIndex();
        item = clone(item);
        equipment[slot] = item;
        if (slot == 0 && npc.hasTrait(Inventory.class)) {
            npc.getOrAddTrait(Inventory.class).setItemInHand(item);
        }
        Entity entity = npc.getEntity();
        if (!(entity instanceof LivingEntity living))
            return;
        if (entity instanceof EnderMan enderman) {
            if (slot != 0)
                throw new UnsupportedOperationException("Slot can only be 0 for enderman");
            applyEndermanBlock(enderman, item);
            return;
        }
        applyToEntity(living, eslot, item);
    }

    private void applyEndermanBlock(EnderMan enderman, ItemStack item) {
        if (item == null || item.isEmpty()) {
            enderman.setCarriedBlock(null);
        } else if (item.getItem() instanceof BlockItem block) {
            enderman.setCarriedBlock(block.getBlock().defaultBlockState());
        }
    }

    private void applyToEntity(LivingEntity living, EquipmentSlot slot, ItemStack item) {
        net.minecraft.world.entity.EquipmentSlot vanilla = slot.toVanilla();
        if (vanilla == null) {
            // SADDLE has no equipment slot before 1.21.5; saddling goes through the entity's own interface
            if (slot == EquipmentSlot.SADDLE && living instanceof Saddleable saddleable) {
                if (item == null || item.isEmpty()) {
                    if (saddleable.isSaddled() && living instanceof AbstractHorse horse) {
                        horse.getInventory().setItem(0, ItemStack.EMPTY);
                    }
                } else if (saddleable.isSaddleable()) {
                    saddleable.equipSaddle(item.copy(), (SoundSource) null);
                }
            }
            return;
        }
        if (!supportsSlot(living, slot))
            return;
        living.setItemSlot(vanilla, item == null ? ItemStack.EMPTY : item.copy());
    }

    /**
     * Vanilla lets any {@link LivingEntity} hold anything in any humanoid slot, but BODY is animal armour and only
     * means something on entities that accept it. Writing it elsewhere is silently ignored by the client, so it is
     * skipped rather than stored on an entity that will never show it.
     */
    private boolean supportsSlot(LivingEntity living, EquipmentSlot slot) {
        return slot != EquipmentSlot.BODY || living.canUseSlot(net.minecraft.world.entity.EquipmentSlot.BODY);
    }

    /**
     * Set the cosmetic equipment in the given slot. Viewers see this in place of an unequipped slot.
     */
    public void setCosmetic(EquipmentSlot slot, ItemStack stack) {
        cosmetic[slot.getIndex()] = clone(stack);
        broadcastCosmetic();
    }

    public enum EquipmentSlot {
        BODY(6),
        BOOTS(4),
        CHESTPLATE(2),
        HAND(0),
        HELMET(1),
        LEGGINGS(3),
        OFF_HAND(5),
        SADDLE(7);

        private final int index;

        EquipmentSlot(int index) {
            this.index = index;
        }

        public int getIndex() {
            return index;
        }

        /**
         * @return the Minecraft slot this maps to, or null when 1.21.1 has none — only SADDLE, which became a real
         *         equipment slot in a later version and is handled through {@link Saddleable} here
         */
        public net.minecraft.world.entity.EquipmentSlot toVanilla() {
            switch (this) {
                case BODY:
                    return net.minecraft.world.entity.EquipmentSlot.BODY;
                case BOOTS:
                    return net.minecraft.world.entity.EquipmentSlot.FEET;
                case CHESTPLATE:
                    return net.minecraft.world.entity.EquipmentSlot.CHEST;
                case HAND:
                    return net.minecraft.world.entity.EquipmentSlot.MAINHAND;
                case HELMET:
                    return net.minecraft.world.entity.EquipmentSlot.HEAD;
                case LEGGINGS:
                    return net.minecraft.world.entity.EquipmentSlot.LEGS;
                case OFF_HAND:
                    return net.minecraft.world.entity.EquipmentSlot.OFFHAND;
                case SADDLE:
                default:
                    return null;
            }
        }

        /** Reverse of {@link #toVanilla()}. Null for slots Citizens does not model. */
        public static EquipmentSlot fromVanilla(net.minecraft.world.entity.EquipmentSlot slot) {
            for (EquipmentSlot candidate : values()) {
                if (candidate.toVanilla() == slot)
                    return candidate;
            }
            return null;
        }
    }

    /**
     * Posted before an equipment slot changes. Informational — upstream does not let listeners cancel or rewrite the
     * change either.
     */
    public static class NPCChangeEquipmentEvent extends NPCEvent {
        private final EquipmentSlot slot;
        private final ItemStack stack;

        public NPCChangeEquipmentEvent(NPC npc, EquipmentSlot slot, ItemStack stack) {
            super(npc);
            this.slot = slot;
            this.stack = stack;
        }

        public EquipmentSlot getSlot() {
            return slot;
        }

        public ItemStack getStack() {
            return stack;
        }
    }
}
