package net.citizensnpcs.npc.entity;

import com.mojang.authlib.GameProfile;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.network.EmptyConnection;
import net.citizensnpcs.network.EmptyPacketListener;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.SkinLayers;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.SectionPos;

/**
 * A player NPC: a real {@link ServerPlayer} with no client behind it.
 * <p>
 * This is the one entity type that genuinely needs a subclass. Every other mob works as a stock vanilla instance
 * (see {@code MobEntityController}), but {@code EntityType.PLAYER} has no factory — {@code type.create(level)} returns
 * null — because vanilla only ever builds players from a login. So the instance is constructed directly and given a
 * dummy {@link EmptyConnection}, which vanilla requires to be non-null and live.
 * <p>
 * Differences from upstream's {@code EntityHumanNPC}:
 * <ul>
 * <li>No {@code CraftPlayer} wrapper and no {@code NPCHolder} — {@link NPCRegistries} maps entity to NPC instead.</li>
 * <li>Upstream reflects into {@code ServerPlayer.connection} through a per-version {@code MethodHandle}; the field is
 * public in NeoForge, so it is assigned directly.</li>
 * <li>Upstream's {@code updatePathfindingRange}/{@code getBukkitEntity} indirection is dropped along with NMS.</li>
 * </ul>
 */
public class EntityHumanNPC extends ServerPlayer {
    private volatile GameProfile profileOverride;
    private final NPC npc;

    public EntityHumanNPC(MinecraftServer server, ServerLevel level, GameProfile profile, NPC npc) {
        super(server, level, profile, ALL_SKIN_LAYERS);
        this.npc = npc;

        // vanilla dereferences connection unconditionally, so it must exist before the entity ticks
        EmptyConnection connection = new EmptyConnection(PacketFlow.CLIENTBOUND);
        this.connection = new EmptyPacketListener(server, connection, this,
                CommonListenerCookie.createInitial(profile, false));
    }

    /**
     * The profile the client is told to draw this NPC with.
     * <p>
     * Normally the NPC's own, but {@link MirrorTrait} needs each viewer to be sent a different one, and a tab-list entry
     * captures whatever this returns at the moment the packet is built. An override is therefore set for the length of
     * one packet — and because a fresh profile object is handed out each time, the packet keeps its own copy and is safe
     * to serialise later on the network thread.
     */
    @Override
    public GameProfile getGameProfile() {
        GameProfile override = profileOverride;
        return override != null ? override : super.getGameProfile();
    }

    public void setProfileOverride(GameProfile profile) {
        profileOverride = profile;
    }

    @Override
    public void die(DamageSource cause) {
        if (dead)
            return;
        super.die(cause);
        // a dead ServerPlayer would normally await a respawn packet that will never arrive, so drop the entity
        setRemoved(RemovalReason.KILLED);
    }

    public NPC getNPC() {
        return npc;
    }

    /** Applies the Java world-player-list setting independently of the client's tab-list visibility. */
    public void updatePlayerListMembership() {
        if (npc == null || isRemoved()) return;
        ServerLevel level = serverLevel();
        boolean remove = npc.shouldRemoveFromPlayerList();
        boolean included = level.players().contains(this);
        if (included != remove) return;
        if (remove) level.players().remove(this);
        else level.players().add(this);
        level.getChunkSource().chunkMap.updatePlayerStatus(this, !remove);
        level.updateSleepingPlayerList();
    }

    /**
     * The client settings a player NPC starts with.
     * <p>
     * {@link ClientInformation#createDefault()} leaves the model-part mask at zero, because a real player's client sends
     * its own settings right after joining and nothing ever does that for an NPC. The visible effect is a player NPC
     * rendered with no outer skin layers at all — no hat, no jacket, no sleeves. Upstream avoids it by writing the synced
     * byte directly in its entity subclass; here the mask is simply passed in, which needs no access widening.
     * {@link net.citizensnpcs.trait.SkinLayers} overrides it per NPC.
     */
    private static final ClientInformation ALL_SKIN_LAYERS = new ClientInformation("en_us", 2, ChatVisiblity.FULL, true,
            SkinLayers.ALL_LAYERS_MASK, Player.DEFAULT_MAIN_HAND, false, false);

    @Override
    public void tick() {
        if (npc == null) {
            super.tick();
            return;
        }
        super.tick();
        // Real players receive their base/physics tick through the network listener. An NPC has no ticking client
        // connection, so run that part here. Default NPCs must not inherit Player.tick's automatic pickup/hunger/healing.
        super.baseTick();
        if (!isPassenger() && (npc.getNavigator().isNavigating() || !isNoGravity() && !npc.isFlyable())) {
            if (npc.isFlyable()) move(MoverType.SELF, getDeltaMovement());
            else travel(Vec3.ZERO);
        }
        if (npc.useMinecraftAI()) getFoodData().tick(this);
        updatePlayerListMembership();
        if (!npc.shouldRemoveFromPlayerList() && !getLastSectionPos().equals(SectionPos.of(this)))
            serverLevel().getChunkSource().move(this);
    }
}
