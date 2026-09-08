package net.citizensnpcs.api.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Owner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Expands the {@code <placeholder>} syntax used in NPC names, chat lines and command trait arguments.
 * <p>
 * The syntax is unchanged from upstream. Two things differ: PlaceholderAPI integration is dropped, since it is a
 * Bukkit plugin with no NeoForge counterpart, and {@code CommandSender} becomes {@link CommandSourceStack}. Third-party
 * placeholder support is still available through {@link #registerNPCPlaceholder}, which is how another mod would plug
 * its own expansions in.
 */
public class Placeholders {
    private Placeholders() {
    }

    public static interface PlaceholderFunction {
        public String apply(NPC npc, CommandSourceStack sender, String input);
    }

    private static class PlaceholderProvider {
        final PlaceholderFunction func;
        final Pattern regex;

        PlaceholderProvider(Pattern regex, PlaceholderFunction func) {
            this.regex = regex;
            this.func = func;
        }
    }

    /** Clears every registered placeholder. Called on shutdown. */
    public static void clearRegistered() {
        PLACEHOLDERS.clear();
    }

    public static boolean containsPlaceholders(String text) {
        return text != null && PLAYER_PLACEHOLDER_MATCHER.matcher(text).find();
    }

    private static String getWorldReplacement(Location location, String group, Entity excluding) {
        if (location == null || location.getWorld() == null)
            return "";
        if (group.charAt(0) != '<') {
            group = '<' + group + '>';
        }
        ServerLevel level = location.getWorld();
        switch (group) {
            case "<random_player>":
            case "<random_world_player>": {
                Collection<ServerPlayer> players = group.equals("<random_player>")
                        ? level.getServer().getPlayerList().getPlayers()
                        : level.players();
                if (players.isEmpty())
                    break;
                ServerPlayer possible = Iterables.get(players, RANDOM.nextInt(players.size()), null);
                if (possible != null)
                    return possible.getGameProfile().getName();
                break;
            }
            case "<random_npc>":
            case "<random_npc_id>": {
                List<NPC> all = Lists.newArrayList(CitizensAPI.getNPCRegistry());
                if (!all.isEmpty()) {
                    NPC random = all.get(RANDOM.nextInt(all.size()));
                    return group.equals("<random_npc>") ? random.getFullName() : Integer.toString(random.getId());
                }
                break;
            }
            case "<nearest_npc_id>": {
                Optional<NPC> closest = level.getEntities(excluding, searchBox(location))
                        .stream().map(CitizensAPI.getNPCRegistry()::getNPC)
                        .filter(npc -> npc != null && npc.getEntity() != excluding)
                        .min((a, b) -> Double.compare(distanceSquared(a, location), distanceSquared(b, location)));
                if (closest.isPresent())
                    return Integer.toString(closest.get().getId());
                break;
            }
            case "<nearest_player>": {
                double min = Double.MAX_VALUE;
                ServerPlayer closest = null;
                for (ServerPlayer player : level.players()) {
                    if (player == excluding || CitizensAPI.getNPCRegistry().isNPC(player))
                        continue;
                    double dist = player.distanceToSqr(location.getX(), location.getY(), location.getZ());
                    if (dist > min || dist > SEARCH_RADIUS * SEARCH_RADIUS)
                        continue;
                    min = dist;
                    closest = player;
                }
                if (closest != null)
                    return closest.getGameProfile().getName();
                break;
            }
            case "<world>":
                return level.dimension().location().toString();
        }
        return "";
    }

    private static double distanceSquared(NPC npc, Location location) {
        Entity entity = npc.getEntity();
        return entity == null ? Double.MAX_VALUE
                : entity.distanceToSqr(location.getX(), location.getY(), location.getZ());
    }

    private static AABB searchBox(Location location) {
        return new AABB(location.getX() - SEARCH_RADIUS, location.getY() - SEARCH_RADIUS,
                location.getZ() - SEARCH_RADIUS, location.getX() + SEARCH_RADIUS, location.getY() + SEARCH_RADIUS,
                location.getZ() + SEARCH_RADIUS);
    }

    /**
     * How many providers have been registered, which callers use to tell whether a cached replacement is still valid.
     * <p>
     * Registration is add-only, so the count doubles as a generation number: if it has not moved, the same input still
     * produces the same output and a cached answer can be trusted.
     */
    public static int providerCount() {
        return PLACEHOLDERS.size();
    }

    public static void registerNPCPlaceholder(Pattern regex, PlaceholderFunction func) {
        if (regex.pattern().charAt(0) != '<') {
            regex = Pattern.compile('<' + regex.pattern() + '>', regex.flags());
        }
        PLACEHOLDERS.add(new PlaceholderProvider(regex, func));
    }

    public static String replace(String text, CommandSourceStack sender, NPC npc) {
        return replace(text, sender, npc, false);
    }

    private static String replace(String text, CommandSourceStack sender, NPC npc, boolean name) {
        text = replace(text, sender == null ? null : sender.getEntity() instanceof ServerPlayer
                ? (ServerPlayer) sender.getEntity() : null);
        if (npc == null || text == null)
            return text;
        StringBuffer out = new StringBuffer();
        Matcher matcher = PLACEHOLDER_MATCHER.matcher(text);
        while (matcher.find()) {
            String replacement = "";
            String group = matcher.group(1);
            switch (group) {
                case "uuid":
                    replacement = npc.getUniqueId().toString();
                    break;
                case "id":
                    replacement = Integer.toString(npc.getId());
                    break;
                case "npc":
                    replacement = name ? text : npc.getFullName();
                    break;
                case "owner":
                    replacement = npc.getOrAddTrait(Owner.class).getOwner();
                    break;
                default:
                    replacement = getWorldReplacement(Location.fromEntity(npc.getEntity()), group, npc.getEntity());
                    break;
            }
            matcher.appendReplacement(out, "");
            out.append(replacement);
        }
        matcher.appendTail(out);
        for (PlaceholderProvider entry : PLACEHOLDERS) {
            matcher = entry.regex.matcher(out.toString());
            out = new StringBuffer();
            while (matcher.find()) {
                String group = matcher.group().substring(1, matcher.group().length() - 1);
                matcher.appendReplacement(out, "");
                out.append(entry.func.apply(npc, sender, group));
            }
            matcher.appendTail(out);
        }
        return out.toString();
    }

    public static String replace(String text, ServerPlayer player) {
        if (text == null || player == null)
            return text;
        StringBuffer out = new StringBuffer();
        Matcher matcher = PLAYER_PLACEHOLDER_MATCHER.matcher(text);
        while (matcher.find()) {
            String replacement;
            String group = matcher.group(1);
            if (PLAYER_VARIABLES.contains(group)) {
                replacement = player.getGameProfile().getName();
            } else if (PLAYER_UUID_VARIABLES.contains(group)) {
                replacement = player.getUUID().toString();
            } else {
                replacement = getWorldReplacement(Location.fromEntity(player), group, player);
            }
            matcher.appendReplacement(out, "");
            out.append(replacement);
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String replaceName(String text, CommandSourceStack sender, NPC npc) {
        return replace(text, sender, npc, true);
    }

    private static final Pattern PLACEHOLDER_MATCHER = Pattern.compile(
            "<(id|uuid|npc|owner|random_player|random_world_player|random_npc|random_npc_id|nearest_npc_id|nearest_player|world)>");
    private static final List<PlaceholderProvider> PLACEHOLDERS = new ArrayList<>();
    private static final Pattern PLAYER_PLACEHOLDER_MATCHER = Pattern.compile(
            "(<player>|<p>|%player%|<player_uuid>|<random_player>|<random_world_player>|<random_npc>|<random_npc_id>|<nearest_npc_id>|<nearest_player>|<world>)");
    private static final Set<String> PLAYER_UUID_VARIABLES = ImmutableSet.of("<player_uuid>");
    private static final Set<String> PLAYER_VARIABLES = ImmutableSet.of("<player>", "<p>", "%player%");
    private static final Random RANDOM = new Random();
    private static final double SEARCH_RADIUS = 25;
}
