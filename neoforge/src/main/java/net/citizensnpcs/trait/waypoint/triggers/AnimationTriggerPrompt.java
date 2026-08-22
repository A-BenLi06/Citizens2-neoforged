package net.citizensnpcs.trait.waypoint.triggers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.PlayerAnimation;
import net.citizensnpcs.util.Util;
import net.minecraft.server.level.ServerPlayer;

/** Collects animations, and optionally a position to play them at, until the player types {@code finish}. */
public class AnimationTriggerPrompt implements WaypointTriggerPrompt {
    private final List<PlayerAnimation> animations = new ArrayList<>();
    private Location at;

    @Override
    public ChatPrompt acceptInput(ChatPromptSession session, String input) {
        ServerPlayer player = session.getPlayer();
        if (input.equalsIgnoreCase("back"))
            return (ChatPrompt) session.getSessionData("previous");
        if (input.startsWith("at ")) {
            at = parseLocation(player, input.substring(3).trim());
            if (at == null) {
                Messaging.sendErrorTr(player.createCommandSourceStack(), Messages.INVALID_TRIGGER_TELEPORT_FORMAT);
            } else {
                Messaging.sendTr(player.createCommandSourceStack(), Messages.WAYPOINT_TRIGGER_ANIMATION_AT_SET,
                        Util.prettyPrintLocation(at));
            }
            return this;
        }
        if (input.equalsIgnoreCase("finish")) {
            session.setSessionData(CREATED_TRIGGER_KEY, new AnimationTrigger(animations, at));
            return (ChatPrompt) session.getSessionData(RETURN_PROMPT_KEY);
        }
        PlayerAnimation animation = Util.matchEnum(PlayerAnimation.values(), input);
        if (animation == null) {
            Messaging.sendErrorTr(player.createCommandSourceStack(), Messages.INVALID_ANIMATION, input,
                    getValidAnimations());
            return this;
        }
        animations.add(animation);
        Messaging.sendTr(player.createCommandSourceStack(), Messages.ANIMATION_ADDED, input);
        return this;
    }

    @Override
    public WaypointTrigger createFromShortInput(ChatPromptSession session, String input) {
        PlayerAnimation animation = Util.matchEnum(PlayerAnimation.values(), input);
        return animation == null ? null : new AnimationTrigger(List.of(animation), at);
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        Messaging.sendTr(session.getPlayer().createCommandSourceStack(), Messages.ANIMATION_TRIGGER_PROMPT,
                getValidAnimations());
        return "";
    }

    /**
     * {@code x,y,z} in the player's own level, which is the only form the animation trigger needs. Upstream routes this
     * through {@code CommandContext.parseLocation}; that is an instance method on a parsed command here, so the two
     * numbers-and-commas cases are handled directly.
     */
    private static Location parseLocation(ServerPlayer player, String raw) {
        String[] parts = raw.split("[,: ]+");
        if (parts.length < 3)
            return null;
        try {
            return new Location(player.serverLevel(), Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String getValidAnimations() {
        List<String> names = new ArrayList<>();
        for (PlayerAnimation animation : PlayerAnimation.values()) {
            names.add(animation.name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", names);
    }
}
