package net.citizensnpcs.trait;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import com.mojang.datafixers.util.Pair;

import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCSeenByPlayerEvent;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitEventHandler;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Makes a player NPC look like whoever is looking at it: each viewer sees their own skin, and optionally their own name
 * and equipment, on the NPC.
 * <p>
 * Upstream can only do this when the PacketEvents library is installed — {@code /npc mirror} refuses outright without it,
 * because rewriting the tab-list and equipment packets per recipient is not something Bukkit can do. Nothing here needs a
 * third-party library: the skin already reaches each viewer through its own tab-list entry (see
 * {@code SkinPacketTracker}), and equipment is sent as a per-viewer packet from this trait.
 */
@TraitName("mirrortrait")
public class MirrorTrait extends Trait {
    @Persist
    private volatile boolean enabled;
    private volatile BiFunction<ServerPlayer, EquipmentSlot, ItemStack> equipmentFunction;
    @Persist
    private volatile boolean mirrorEquipment;
    @Persist
    private volatile boolean mirrorName;
    /** What each viewer was last shown, so unchanged equipment is not resent every tick. */
    private final Map<UUID, List<ItemStack>> sent = new HashMap<>();
    private int tickCounter;

    public MirrorTrait() {
        super("mirrortrait");
    }

    public BiFunction<ServerPlayer, EquipmentSlot, ItemStack> getEquipmentFunction() {
        return mirrorEquipment && equipmentFunction == null ? MIRROR_EQUIPMENT : equipmentFunction;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return whether this player should see themselves on the NPC. Takes the viewer so that an addon can override it
     *         per player, which is what upstream's signature is for.
     */
    public boolean isMirroring(ServerPlayer player) {
        return enabled;
    }

    public boolean isMirroringEquipment() {
        return mirrorEquipment;
    }

    public boolean mirrorName() {
        return mirrorName;
    }

    @TraitEventHandler
    public void onSeenByPlayer(NPCSeenByPlayerEvent event) {
        if (event.getNPC() != npc)
            return;
        sent.remove(event.getPlayer().getUUID());
        sendEquipment(event.getPlayer());
    }

    @Override
    public void onDespawn() {
        sent.clear();
    }

    @Override
    public void run() {
        if (!enabled || getEquipmentFunction() == null || !npc.isSpawned())
            return;
        // ten ticks is well inside the time it takes a player to notice their own gear changed, and keeps this off the
        // hot path for NPCs nobody is looking at
        if (++tickCounter % 10 != 0)
            return;
        Entity entity = npc.getEntity();
        if (!(entity.level() instanceof ServerLevel level))
            return;
        double range = Math.pow(level.getServer().getPlayerList().getViewDistance() * 16.0, 2);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(entity) <= range) {
                sendEquipment(player);
            } else {
                sent.remove(player.getUUID());
            }
        }
    }

    /** Sends this viewer the equipment they should see on the NPC, if it differs from what they were last sent. */
    private void sendEquipment(ServerPlayer viewer) {
        BiFunction<ServerPlayer, EquipmentSlot, ItemStack> function = getEquipmentFunction();
        if (function == null || !npc.isSpawned() || !isMirroring(viewer))
            return;
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>(SLOTS.length);
        List<ItemStack> snapshot = new ArrayList<>(SLOTS.length);
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = function.apply(viewer, slot);
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
            slots.add(Pair.of(slot, stack));
            snapshot.add(stack);
        }
        List<ItemStack> last = sent.get(viewer.getUUID());
        if (last != null && sameStacks(last, snapshot))
            return;
        sent.put(viewer.getUUID(), snapshot);
        viewer.connection.send(new ClientboundSetEquipmentPacket(npc.getEntity().getId(), slots));
    }

    private static boolean sameStacks(List<ItemStack> a, List<ItemStack> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i)))
                return false;
        }
        return true;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        sent.clear();
        if (npc.isSpawned()) {
            // the profile is only read when the NPC's tab-list entry is built, so it has to be sent again
            npc.despawn(DespawnReason.PENDING_RESPAWN);
            npc.spawn(npc.getStoredLocation(), SpawnReason.RESPAWN);
        }
    }

    public void setEquipmentFunction(BiFunction<ServerPlayer, EquipmentSlot, ItemStack> func) {
        this.equipmentFunction = func;
    }

    public void setMirrorEquipment(boolean mirrorEquipment) {
        this.mirrorEquipment = mirrorEquipment;
        sent.clear();
    }

    public void setMirrorName(boolean mirror) {
        mirrorName = mirror;
    }

    private static final EquipmentSlot[] SLOTS = { EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    /** The default: show the viewer exactly what they are wearing and holding. */
    private static final BiFunction<ServerPlayer, EquipmentSlot, ItemStack> MIRROR_EQUIPMENT = (player,
            slot) -> player.getItemBySlot(slot);
}
