package net.citizensnpcs.npc.skin;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

import net.citizensnpcs.Settings.Setting;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.citizensnpcs.trait.MirrorTrait;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.SkinProperty;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Pushes profile changes for a player NPC out to clients.
 * <p>
 * A vanilla client reads a player's skin from the tab-list entry it was sent, once, when the entry arrives. Mutating the
 * {@link com.mojang.authlib.GameProfile} afterwards changes nothing on screen — the entry has to be removed and re-added
 * for the client to re-read it. That is the whole reason this class exists, and the same reason upstream has its own
 * {@code SkinPacketTracker}.
 * <p>
 * Upstream additionally maintains per-viewer tracking state so it can re-send to one player at a time, which it needs
 * because Bukkit gives it no access to the entity tracker. {@link net.minecraft.server.level.ServerChunkCache#broadcast}
 * does that job here, so only the add/remove sequencing is left.
 */
public final class SkinPacketTracker {
    private SkinPacketTracker() {
    }

    /**
     * Re-sends the NPC's tab-list entry so that viewers pick up the current profile.
     * <p>
     * The NPC is removed from the list again afterwards when it should not be listed, which is the default — an NPC that
     * lingers in the tab list is the usual complaint about naive skin implementations.
     */
    public static void respawn(EntityHumanNPC entity) {
        if (entity == null || entity.isRemoved())
            return;
        ServerLevel level = (ServerLevel) entity.level();

        sendToTracking(level, entity, new ClientboundPlayerInfoRemovePacket(List.of(entity.getUUID())));
        NPC mirroring = entity.getNPC();
        if (mirroring != null && mirroring.hasTrait(MirrorTrait.class)
                && mirroring.getTraitNullable(MirrorTrait.class).isEnabled()) {
            // Each actual viewer needs a different profile. Sending to the whole dimension would leave stale profile
            // entries on clients that never tracked this NPC and will never receive its stop-tracking cleanup.
            for (ServerPlayer viewer : level.getChunkSource().chunkMap.getPlayersWatching(entity)) {
                send(entity, viewer, EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED));
            }
        } else {
            sendToTracking(level, entity, packet(entity, EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED)));
        }

        NPC npc = entity.getNPC();
        if (npc == null || npc.shouldRemoveFromTabList()) {
            // Retain the skin profile and reassert the live listed policy after the configured refresh delay.
            // A later explicit show operation must not be undone by this earlier refresh.
            net.citizensnpcs.api.CitizensAPI.getScheduler().runTaskLater(() -> {
                if (!entity.isRemoved()) {
                    sendToTracking(level, entity, packet(entity, EnumSet.of(Action.UPDATE_LISTED)));
                }
            }, Setting.TABLIST_REMOVE_PACKET_DELAY.asTicks());
        }
    }

    private static void sendToTracking(ServerLevel level, EntityHumanNPC entity,
            net.minecraft.network.protocol.Packet<?> packet) {
        level.getChunkSource().broadcast(entity, packet);
        // broadcast reaches viewers but not the NPC's own (nonexistent) client, which is what we want
    }

    /**
     * Sends the NPC's profile to one viewer that is about to start tracking it.
     * <p>
     * This has to happen <em>before</em> the entity spawn packet, which is why {@code ServerEntityMixin} calls it from the
     * head of {@code ServerEntity.sendPairingData} rather than from {@code PlayerEvent.StartTracking}: a 1.21 client
     * refuses to create a player entity it has no tab-list entry for, logging "Server attempted to add player prior to
     * sending player info" and dropping it. Without this the NPC exists on the server, reports itself as spawned, and is
     * simply never drawn.
     * <p>
     * The entry always carries explicit listed state. An unlisted entry still supplies its skin to the player entity.
     */
    public static void sendTo(EntityHumanNPC entity, ServerPlayer viewer) {
        if (entity == null || entity.isRemoved() || viewer == null)
            return;
        send(entity, viewer, EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED));
    }

    /**
     * Drops the tab-list entry again when a viewer stops tracking the NPC, so a client that walks past a few hundred NPCs
     * does not keep an entry for every one of them.
     */
    public static void removeFrom(EntityHumanNPC entity, ServerPlayer viewer) {
        if (entity == null || viewer == null)
            return;
        viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(entity.getUUID())));
    }

    /**
     * Shows or hides the NPC's client tab-list entry. The Java world-player-list setting is independent.
     * <p>
     * The entry has to exist for the client to read the skin off it, so hiding means "listed = false" rather than removing
     * the entry outright.
     */
    public static void setListed(EntityHumanNPC entity, boolean listed) {
        if (entity == null || entity.isRemoved() || !(entity.level() instanceof ServerLevel level))
            return;
        NPC npc = entity.getNPC();
        if (npc != null) {
            npc.data().setPersistent(NPC.Metadata.REMOVE_FROM_TABLIST, !listed);
        }
        sendToTracking(level, entity, packet(entity, EnumSet.of(Action.UPDATE_LISTED)));
    }

    /**
     * Sends one tab-list packet to one viewer, using that viewer's own profile when {@link MirrorTrait} is mirroring —
     * which is how each player comes to see themselves on the NPC.
     */
    private static void send(EntityHumanNPC entity, ServerPlayer viewer, EnumSet<Action> actions) {
        if (Messaging.isDebugging()) {
            // what actually goes on the wire, so "the NPC has the wrong skin" can be split into "the server never sent
            // the texture" and "the client did not use what was sent"
            Collection<Property> textures = entity.getGameProfile().getProperties().get(SkinProperty.TEXTURES_KEY);
            Property first = textures.isEmpty() ? null : textures.iterator().next();
            Messaging.debug("skin ->", viewer.getGameProfile().getName(), "for NPC",
                    entity.getNPC() == null ? -1 : entity.getNPC().getId(), "profile", entity.getGameProfile().getId(),
                    "name", entity.getGameProfile().getName(), "textures=" + textures.size(),
                    first == null ? "(none)"
                            : "property-name=" + first.name() + " value=" + first.value().length() + "ch signed="
                                    + (first.signature() != null),
                    "actions=" + actions);
        }
        GameProfile mirrored = mirroredProfile(entity, viewer);
        if (mirrored == null) {
            viewer.connection.send(packet(entity, actions));
            return;
        }
        entity.setProfileOverride(mirrored);
        try {
            viewer.connection.send(packet(entity, actions));
        } finally {
            entity.setProfileOverride(null);
        }
    }

    private static ClientboundPlayerInfoUpdatePacket packet(EntityHumanNPC entity, EnumSet<Action> actions) {
        ClientboundPlayerInfoUpdatePacket result = new ClientboundPlayerInfoUpdatePacket(actions, List.of(entity));
        var entry = result.entries().getFirst();
        boolean listed = entity.getNPC() != null && !entity.getNPC().shouldRemoveFromTabList();
        result.entries = List.of(new ClientboundPlayerInfoUpdatePacket.Entry(entry.profileId(), entry.profile(), listed,
                entry.latency(), entry.gameMode(), entry.displayName(), entry.chatSession()));
        return result;
    }

    /**
     * @return a profile carrying the viewer's skin, and their name too if the trait asks for it, or null when this viewer
     *         should just see the NPC as itself
     */
    private static GameProfile mirroredProfile(EntityHumanNPC entity, ServerPlayer viewer) {
        NPC npc = entity.getNPC();
        MirrorTrait mirror = npc == null ? null : npc.getTraitNullable(MirrorTrait.class);
        if (mirror == null || !mirror.isMirroring(viewer))
            return null;
        GameProfile source = viewer.getGameProfile();
        // the NPC's own id has to stay: it is what ties the entry to the entity the client is already tracking
        GameProfile mirrored = new GameProfile(entity.getUUID(),
                mirror.mirrorName() ? source.getName() : entity.getGameProfile().getName());
        mirrored.getProperties().putAll(source.getProperties());
        return mirrored;
    }
}
