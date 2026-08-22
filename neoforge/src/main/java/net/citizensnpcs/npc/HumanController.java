package net.citizensnpcs.npc;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.npc.skin.Skin;
import net.citizensnpcs.trait.SkinTrait;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Builds player-type NPCs.
 * <p>
 * The one mob type that needs its own controller: {@code EntityType.PLAYER} has no factory, so the entity is
 * constructed directly as an {@link EntityHumanNPC}.
 * <p>
 * The NPC's name doubles as the skin owner, matching upstream — {@code /npc create Notch player} shows Notch's skin.
 * The name is truncated to 16 characters for the profile because that is the protocol limit on a player name; the NPC's
 * own display name is unaffected.
 */
public class HumanController extends AbstractEntityController {
    @Override
    protected Entity createEntity(Location at, NPC npc) {
        ServerLevel level = at.getWorld();
        if (level == null)
            throw new IllegalStateException("cannot create an NPC entity in an unloaded level");

        String name = npc.getName();
        String profileName = name.length() > 16 ? name.substring(0, 16) : name;
        UUID uuid = npc.getMinecraftUniqueId();
        GameProfile profile = new GameProfile(uuid, profileName);

        EntityHumanNPC human = new EntityHumanNPC(level.getServer(), level, profile, npc);
        human.moveTo(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        human.setYHeadRot(at.getYaw());
        NPCRegistries.link(human, npc);

        // the skin arrives asynchronously; the entity is usable immediately and updates when the lookup lands
        SkinTrait skin = npc.getTraitNullable(SkinTrait.class);
        if (skin != null) {
            skin.applyTo(human);
        } else {
            // no explicit skin set: the NPC's name is the skin owner, as upstream does
            Skin.get(npc, profileName).apply(human);
        }
        return human;
    }

    @Override
    public void spawn(Location at, java.util.function.Consumer<Boolean> callback) {
        super.spawn(at, added -> {
            if (!added) {
                Messaging.debug("Level rejected player NPC at", at);
            }
            callback.accept(added);
        });
    }
}
