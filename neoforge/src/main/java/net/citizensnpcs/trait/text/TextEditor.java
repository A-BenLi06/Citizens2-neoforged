package net.citizensnpcs.trait.text;

import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.editor.Editor;
import net.citizensnpcs.util.Messages;
import net.minecraft.server.level.ServerPlayer;

/** A text editor bound to one trait and player identity, through chat and command buttons. */
public final class TextEditor extends Editor {
    public static final String PERMISSION = "citizens.npc.edit.text";
    private final ServerPlayer initialPlayer;
    private final Text text;
    private final TextBasePrompt prompt;
    private ChatPromptSession session;
    private boolean editing;

    public TextEditor(ServerPlayer player, Text text) {
        this.initialPlayer = player;
        this.text = text;
        this.prompt = new TextBasePrompt(text);
    }

    @Override
    public void begin() {
        editing = true;
        text.editorStarted(this);
        Messaging.sendTr(initialPlayer.createCommandSourceStack(), Messages.TEXT_EDITOR_BEGIN);
        ChatPrompts.begin(initialPlayer, prompt, null, created -> {
            session = created;
            created.withEscapeSequences("exit", "/npc text")
                    .onAbandon(() -> { Editor.leave(created.getPlayer(), this); end(); });
        });
    }

    @Override
    public void end() {
        if (!editing) return;
        editing = false;
        text.editorStopped(this);
        ChatPrompts.abandon(session);
        ServerPlayer player = session == null ? initialPlayer : session.getPlayer();
        Messaging.sendTr(player.createCommandSourceStack(), Messages.TEXT_EDITOR_END);
    }

    public void close() {
        ServerPlayer player = session == null ? initialPlayer : session.getPlayer();
        Editor.leave(player, this);
        end();
    }

    public NPC getNPC() { return text.getNPC(); }

    public void executeCommand(String input) throws CommandException {
        if (!editing || !ChatPrompts.isActive(session))
            throw new CommandException("citizens.editors.text.not-active");
        if (input.trim().equalsIgnoreCase("exit")) { close(); return; }
        try {
            prompt.applyInput(session, input);
        } finally {
            ChatPrompts.refresh(session);
        }
    }
}
