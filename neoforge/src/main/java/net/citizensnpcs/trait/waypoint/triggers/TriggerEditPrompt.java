package net.citizensnpcs.trait.waypoint.triggers;

import java.util.List;

import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.trait.waypoint.Waypoint;
import net.citizensnpcs.trait.waypoint.WaypointEditor;
import net.citizensnpcs.util.Messages;
import net.minecraft.server.level.ServerPlayer;

/**
 * The top of the trigger editor: lists the current waypoint's triggers, and takes {@code add} or
 * {@code remove_trigger <n>}.
 */
public class TriggerEditPrompt implements ChatPrompt {
    private final WaypointEditor editor;

    public TriggerEditPrompt(WaypointEditor editor) {
        this.editor = editor;
    }

    @Override
    public ChatPrompt acceptInput(ChatPromptSession session, String rawInput) {
        String input = rawInput.toLowerCase().trim();
        if (input.startsWith("remove_trigger")) {
            Waypoint waypoint = editor.getCurrentWaypoint();
            if (waypoint == null)
                return this;
            List<WaypointTrigger> triggers = waypoint.getTriggers();
            // upstream unboxes Ints.tryParse straight into an int, so a non-numeric index throws
            Integer index = parseIndex(input.replaceFirst("remove_trigger", "").trim());
            if (index != null && index >= 0 && index < triggers.size()) {
                triggers.remove(index.intValue());
            }
            return this;
        }
        if (input.contains("add")) {
            session.setSessionData("said", false);
            return new TriggerAddPrompt();
        }
        return this;
    }

    @Override
    public String getPromptText(ChatPromptSession session) {
        ServerPlayer player = session.getPlayer();
        WaypointTrigger created = (WaypointTrigger) session.getSessionData(WaypointTriggerPrompt.CREATED_TRIGGER_KEY);
        if (created != null) {
            if (editor.getCurrentWaypoint() != null) {
                editor.getCurrentWaypoint().addTrigger(created);
                Messaging.sendTr(player.createCommandSourceStack(), Messages.WAYPOINT_TRIGGER_ADDED_SUCCESSFULLY,
                        created.description());
            } else {
                Messaging.sendErrorTr(player.createCommandSourceStack(), Messages.WAYPOINT_TRIGGER_EDITOR_INACTIVE);
            }
            session.setSessionData(WaypointTriggerPrompt.CREATED_TRIGGER_KEY, null);
        }
        session.setSessionData("said", false);
        session.setSessionData("previous", this);
        Messaging.sendTr(player.createCommandSourceStack(), Messages.WAYPOINT_TRIGGER_EDITOR_PROMPT);
        if (editor.getCurrentWaypoint() != null) {
            editor.getCurrentWaypoint().describeTriggers(player.createCommandSourceStack());
        }
        return "";
    }

    private static Integer parseIndex(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Opens the trigger editor for a player, ending on {@code exit}, {@code triggers} or another {@code /npc path}. */
    public static void start(ServerPlayer player, WaypointEditor editor) {
        ChatPromptSession session = ChatPrompts.begin(player, new TriggerEditPrompt(editor));
        if (session != null) {
            session.withEscapeSequences("exit", "triggers", "/npc path").onAbandon(() -> Messaging
                    .sendTr(player.createCommandSourceStack(), Messages.WAYPOINT_TRIGGER_EDITOR_EXIT));
        }
    }
}
