package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.EntityUtil;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Pushes players out of a box around the NPC.
 * <p>
 * The push has to be sent to the client, not just applied server-side: a player's movement is client-authoritative, so
 * setting the delta movement on the server entity alone would be overwritten by the next movement packet. Bukkit's
 * {@code Player#setVelocity} sends the packet as part of the setter, which is why upstream needs no equivalent step.
 */
@TraitName("forcefieldtrait")
public class ForcefieldTrait extends Trait {
    @Persist
    private Double height;
    @Persist
    private Double strength;
    @Persist
    private Double verticalStrength;
    @Persist
    private Double width;

    public ForcefieldTrait() {
        super("forcefieldtrait");
    }

    public double getHeight() {
        return height == null ? npc.getEntity().getBbHeight() : height;
    }

    public double getStrength() {
        return strength == null ? 0.1 : strength;
    }

    public double getVerticalStrength() {
        return verticalStrength == null ? 0 : verticalStrength;
    }

    public double getWidth() {
        return width == null ? npc.getEntity().getBbWidth() : width;
    }

    @Override
    public void run() {
        if (!npc.isSpawned())
            return;
        double height = getHeight();
        double half = getWidth() / 1.9;
        double strength = getStrength();
        Vec3 base = npc.getEntity().position();
        AABB box = new AABB(base.x - half, base.y, base.z - half, base.x + half, base.y + height, base.z + half);
        for (ServerPlayer player : EntityUtil.getNearbyVisiblePlayers(npc.getEntity(), box)) {
            Vec3 diff = player.position().subtract(base);
            if (diff.lengthSqr() == 0) {
                continue;
            }
            Vec3 push = diff.normalize().multiply(1, 0, 1).add(0, getVerticalStrength(), 0).scale(strength);
            player.setDeltaMovement(player.getDeltaMovement().add(push));
            // a player's own movement is client-authoritative, so the new motion has to be sent or the next movement
            // packet simply overwrites it
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
        }
    }

    public void setHeight(Double height) {
        this.height = height;
    }

    public void setStrength(Double strength) {
        this.strength = strength;
    }

    public void setVerticalStrength(Double verticalStrength) {
        this.verticalStrength = verticalStrength;
    }

    public void setWidth(Double width) {
        this.width = width;
    }
}
