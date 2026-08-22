package net.citizensnpcs.util;

import java.util.List;
import java.util.function.Supplier;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.npc.ai.NPCHolder;
import net.citizensnpcs.trait.SitTrait;
import net.citizensnpcs.trait.SleepTrait;
import net.citizensnpcs.trait.SneakTrait;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * An animation played on a player-type NPC.
 * <p>
 * Three shapes of animation live behind one enum, which is why upstream routes each through a different mechanism:
 * <ul>
 * <li><b>One-shot animations</b> (swings, crits, waking) are a single packet to nearby clients and leave no state.</li>
 * <li><b>Persistent poses</b> (sit, sleep, sneak) are state the NPC has to keep across respawns, so they are delegated to
 * the trait that owns them rather than faked with a packet.</li>
 * <li><b>Synced entity flags</b> (using an item, gliding) live in the entity data and so replicate to any client that
 * looks, without a targeted packet.</li>
 * </ul>
 * Upstream reaches these through its NMS bridge and a pile of Bukkit metadata keys; here the entity methods are called
 * directly, and the "sitting" metadata convention disappears with them.
 */
public enum PlayerAnimation {
    ARM_SWING,
    ARM_SWING_OFFHAND,
    CRIT,
    EAT_FOOD,
    HURT,
    LEAVE_BED,
    MAGIC_CRIT,
    SIT,
    SLEEP,
    SNEAK,
    START_ELYTRA,
    START_USE_MAINHAND_ITEM,
    START_USE_OFFHAND_ITEM,
    STOP_ELYTRA,
    STOP_SITTING,
    STOP_SLEEPING,
    STOP_SNEAKING,
    STOP_USE_ITEM;

    /** Plays to everyone within the default 64-block radius. */
    public void play(ServerPlayer player) {
        play(player, DEFAULT_RADIUS);
    }

    public void play(ServerPlayer player, int radius) {
        play(player, () -> EntityUtil.getNearbyVisiblePlayers(player, radius));
    }

    public void play(ServerPlayer player, Iterable<ServerPlayer> to) {
        play(player, () -> to);
    }

    public void play(ServerPlayer player, Supplier<Iterable<ServerPlayer>> to) {
        NPC npc = player instanceof NPCHolder holder ? holder.getNPC() : null;
        switch (this) {
            case SNEAK:
            case STOP_SNEAKING:
                if (npc != null) {
                    npc.getOrAddTrait(SneakTrait.class).setSneaking(this == SNEAK);
                } else {
                    player.setShiftKeyDown(this == SNEAK);
                }
                return;
            case SIT:
                if (npc != null) {
                    npc.getOrAddTrait(SitTrait.class).setSitting(npc.getStoredLocation());
                }
                // a real player cannot be seated without a vehicle to seat them on, which is an NPC-only trick
                return;
            case STOP_SITTING:
                if (npc != null) {
                    npc.getOrAddTrait(SitTrait.class).setSitting(null);
                } else {
                    player.stopRiding();
                }
                return;
            case SLEEP:
            case STOP_SLEEPING:
                if (npc != null) {
                    npc.getOrAddTrait(SleepTrait.class).setSleeping(this == SLEEP ? npc.getStoredLocation() : null);
                } else if (this == STOP_SLEEPING) {
                    player.stopSleeping();
                }
                return;
            case START_ELYTRA:
            case STOP_ELYTRA:
                player.setSharedFlag(FALL_FLYING_FLAG, this == START_ELYTRA);
                return;
            case EAT_FOOD:
            case START_USE_MAINHAND_ITEM:
                // eating is not a separate animation in vanilla: it is the using-item flag plus whatever is held, and
                // the client draws the eating particles from that. Upstream sends it through its own bridge instead.
                player.startUsingItem(InteractionHand.MAIN_HAND);
                return;
            case START_USE_OFFHAND_ITEM:
                player.startUsingItem(InteractionHand.OFF_HAND);
                return;
            case STOP_USE_ITEM:
                player.stopUsingItem();
                return;
            default:
                break;
        }
        Packet<?> packet = packetFor(player);
        if (packet == null)
            return;
        for (ServerPlayer viewer : to.get()) {
            viewer.connection.send(packet);
        }
    }

    /** @return the one-shot packet for this animation, or null when it has none */
    private Packet<?> packetFor(Entity entity) {
        switch (this) {
            case ARM_SWING:
                return new ClientboundAnimatePacket(entity, ClientboundAnimatePacket.SWING_MAIN_HAND);
            case ARM_SWING_OFFHAND:
                return new ClientboundAnimatePacket(entity, ClientboundAnimatePacket.SWING_OFF_HAND);
            case CRIT:
                return new ClientboundAnimatePacket(entity, ClientboundAnimatePacket.CRITICAL_HIT);
            case MAGIC_CRIT:
                return new ClientboundAnimatePacket(entity, ClientboundAnimatePacket.MAGIC_CRITICAL_HIT);
            case LEAVE_BED:
                return new ClientboundAnimatePacket(entity, ClientboundAnimatePacket.WAKE_UP);
            case HURT:
                // 1.20 split the hurt animation out of the animate packet into its own
                return entity instanceof LivingEntity living ? new ClientboundHurtAnimationPacket(living) : null;
            default:
                return null;
        }
    }

    /** Plays to a single viewer. */
    public void play(ServerPlayer player, ServerPlayer to) {
        play(player, () -> List.of(to));
    }

    private static final int DEFAULT_RADIUS = 64;
    private static final int FALL_FLYING_FLAG = 7;
}
