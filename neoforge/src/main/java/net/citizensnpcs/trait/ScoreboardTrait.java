package net.citizensnpcs.trait;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.util.Util;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Gives each NPC a scoreboard team of its own, which is how three otherwise unreachable things get controlled: whether
 * the nameplate is shown, whether players can push the NPC, and what colour it glows.
 * <p>
 * The team is <em>never put on the server's scoreboard</em>. It lives on a throwaway {@link Scoreboard} and is sent to
 * players as {@link ClientboundSetPlayerTeamPacket}s. That is deliberate: a server with a hundred NPCs would otherwise
 * accumulate a hundred teams in {@code scoreboard.dat}, show them all in {@code /team list}, and collide with whatever
 * the server operator's own scoreboard is doing.
 * <p>
 * This replaces upstream's {@code trait/scoreboard} package — about 680 lines whose bulk is an abstraction over three
 * backends (a real Bukkit scoreboard, a Folia packet implementation, and the megavex scoreboard library). None of those
 * exist here, so the abstraction goes with them and only the packet path remains. Which viewers have been sent the team
 * is tracked per trait rather than in upstream's central per-player metadata registry, since each trait owns exactly one
 * team.
 */
@TraitName("scoreboardtrait")
public class ScoreboardTrait extends Trait {
    private boolean changed;
    @Persist
    private ChatFormatting color;
    private String lastEntry;
    private ChatFormatting previousGlowingColor;
    private final Scoreboard scoreboard = new Scoreboard();
    /** Viewers that currently hold this team, so updates and removals reach exactly them. */
    private final Set<UUID> sentTo = new HashSet<>();
    @Persist
    private Set<String> tags = new HashSet<>(Set.of("CITIZENS_NPC"));
    private PlayerTeam team;

    public ScoreboardTrait() {
        super("scoreboardtrait");
    }

    /** Names the team after the NPC and puts the given entry in it. */
    public void createTeam(String entityName) {
        String teamName = Util.getTeamName(npc.getUniqueId());
        npc.data().set(NPC.Metadata.SCOREBOARD_FAKE_TEAM_NAME, teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
        }
        if (lastEntry != null && !lastEntry.equals(entityName)) {
            scoreboard.removePlayerFromTeam(lastEntry, team);
        }
        scoreboard.addPlayerToTeam(entityName, team);
        lastEntry = entityName;
        changed = true;
    }

    public ChatFormatting getColor() {
        return color;
    }

    public Set<String> getTags() {
        return tags;
    }

    @Override
    public void onDespawn(DespawnReason reason) {
        previousGlowingColor = null;
        removeFromViewers();
        npc.data().remove(NPC.Metadata.SCOREBOARD_FAKE_TEAM_NAME);
        team = null;
        lastEntry = null;
    }

    @Override
    public void onRemove() {
        onDespawn(DespawnReason.REMOVAL);
    }

    @Override
    public void onSpawn() {
        changed = true;
        Entity entity = npc.getEntity();
        entity.getTags().clear();
        entity.getTags().addAll(tags);
    }

    private void removeFromViewers() {
        if (team == null || sentTo.isEmpty())
            return;
        ClientboundSetPlayerTeamPacket packet = ClientboundSetPlayerTeamPacket.createRemovePacket(team);
        for (ServerPlayer viewer : onlinePlayers()) {
            if (sentTo.remove(viewer.getUUID())) {
                viewer.connection.send(packet);
            }
        }
        sentTo.clear();
    }

    public void setColor(ChatFormatting color) {
        if (color != null && !color.isColor())
            throw new IllegalArgumentException("team colours must be colours, not formatting codes");
        this.color = color;
        changed = true;
    }

    public void setTags(Set<String> tags) {
        this.tags = tags;
    }

    /**
     * Recomputes the team and pushes it to viewers. Called every tick from {@code CitizensNPC.update()}; the work is
     * skipped unless something actually changed, or a viewer has not been sent the team yet.
     */
    public void update() {
        if (!npc.isSpawned())
            return;
        Entity entity = npc.getEntity();
        if (!entity.getTags().equals(tags)) {
            tags = new HashSet<>(entity.getTags());
        }
        if (!Setting.USE_SCOREBOARD_TEAMS.asBoolean()) {
            removeFromViewers();
            npc.data().remove(NPC.Metadata.SCOREBOARD_FAKE_TEAM_NAME);
            team = null;
            lastEntry = null;
            return;
        }
        // a player NPC joins by its profile name because that is the entry the client matches; anything else has no
        // name of its own on the client, so its UUID is used
        String entry = entity instanceof ServerPlayer player ? player.getGameProfile().getName()
                : npc.getUniqueId().toString();
        if (team == null || !entry.equals(lastEntry)) {
            createTeam(entry);
        }
        String forceVisible = npc.data().<Object> get(NPC.Metadata.NAMEPLATE_VISIBLE, true).toString();
        boolean nameVisible = !npc.requiresNameHologram()
                && (forceVisible.equals("true") || forceVisible.equals("hover"));
        Team.Visibility visibility = nameVisible ? Team.Visibility.ALWAYS : Team.Visibility.NEVER;
        if (visibility != team.getNameTagVisibility()) {
            team.setNameTagVisibility(visibility);
            changed = true;
        }
        Team.CollisionRule collide = npc.data().<Boolean> get(NPC.Metadata.COLLIDABLE, !npc.isProtected())
                ? Team.CollisionRule.ALWAYS
                : Team.CollisionRule.NEVER;
        if (collide != team.getCollisionRule()) {
            team.setCollisionRule(collide);
            changed = true;
        }
        if (color != null && color != previousGlowingColor) {
            team.setColor(color);
            previousGlowingColor = color;
            changed = true;
        }
        ClientboundSetPlayerTeamPacket add = null;
        ClientboundSetPlayerTeamPacket update = null;
        for (ServerPlayer viewer : onlinePlayers()) {
            if (sentTo.contains(viewer.getUUID())) {
                if (!changed) {
                    continue;
                }
                if (update == null) {
                    update = ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false);
                }
                viewer.connection.send(update);
            } else {
                if (add == null) {
                    add = ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true);
                }
                viewer.connection.send(add);
                sentTo.add(viewer.getUUID());
            }
        }
        changed = false;
    }

    private Iterable<ServerPlayer> onlinePlayers() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? Set.of() : server.getPlayerList().getPlayers();
    }
}
