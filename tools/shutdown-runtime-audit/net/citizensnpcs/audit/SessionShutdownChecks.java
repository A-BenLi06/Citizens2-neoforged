package net.citizensnpcs.audit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.editor.Editor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;

/** Isolated, connection-free actors: no prompt text is sent and no player is added to the world. */
public final class SessionShutdownChecks {
    private static final List<String> checks = new ArrayList<>();
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<ChatPromptSession> sessions = new ArrayList<>();
    private static int editorEnds;
    private static int promptEnds;
    private static int editorRejected;
    private static int promptRejected;
    private static final ChatPrompt PROMPT = new ChatPrompt() {
        @Override public String getPromptText(ChatPromptSession session) { return ""; }
        @Override public ChatPrompt acceptInput(ChatPromptSession session, String input) { return this; }
    };

    public static void prepare(MinecraftServer server) {
        checks.clear();
        players.clear();
        sessions.clear();
        for (int i = 0; i < 3; i++) {
            players.add(new ServerPlayer(server, server.overworld(),
                    new GameProfile(UUID.randomUUID(), "Shutdown" + i), ClientInformation.createDefault()));
        }
        Object editorDispatcher = field(Editor.class, "dispatcher");
        Object promptDispatcher = field(ChatPrompts.class, "dispatcher");
        Editor.registerListeners();
        ChatPrompts.registerListeners();
        check(editorDispatcher != null && field(Editor.class, "dispatcher") == editorDispatcher,
                "editor repeated registration preserves one dispatcher");
        check(promptDispatcher != null && field(ChatPrompts.class, "dispatcher") == promptDispatcher,
                "prompt repeated registration preserves one dispatcher");

        // Ordinary reload cleanup must finish all callbacks and still permit future sessions.
        install(false);
        expectFailure(Editor::leaveAll, "ordinary editor cleanup reports failure");
        expectFailure(ChatPrompts::abandonAll, "ordinary prompt cleanup reports failure");
        check(editorEnds == 3 && promptEnds == 3, "ordinary cleanup continues after throwing callbacks");
        check(players.stream().noneMatch(Editor::hasEditor) && players.stream().noneMatch(ChatPrompts::isActive),
                "ordinary cleanup releases every session");

        Editor.shutdown();
        ChatPrompts.shutdown();
        Editor.shutdown();
        ChatPrompts.shutdown();
        check(field(Editor.class, "dispatcher") == null && field(ChatPrompts.class, "dispatcher") == null,
                "empty repeated shutdown releases both dispatchers");
        Editor.registerListeners();
        ChatPrompts.registerListeners();
        check(field(Editor.class, "dispatcher") != editorDispatcher
                && field(ChatPrompts.class, "dispatcher") != promptDispatcher,
                "next startup registers fresh dispatchers");
        install(true);
        check(players.stream().allMatch(Editor::hasEditor) && players.stream().allMatch(ChatPrompts::isActive),
                "next startup accepts editors and prompts");
    }

    private static void install(boolean shutdown) {
        editorEnds = promptEnds = editorRejected = promptRejected = 0;
        sessions.clear();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            boolean throwing = i == 0;
            Editor.enterOrLeave(player, new Editor() {
                @Override public void begin() { }
                @Override public void end() {
                    editorEnds++;
                    if (shutdown) {
                        try { Editor.enterOrLeave(player, this); }
                        catch (IllegalStateException expected) { editorRejected++; }
                        try { Editor.registerListeners(); }
                        catch (IllegalStateException expected) { editorRejected++; }
                    }
                    if (throwing) throw new IllegalStateException("expected shutdown editor failure");
                }
            });
            sessions.add(ChatPrompts.begin(player, PROMPT, null, session -> session.onAbandon(() -> {
                promptEnds++;
                if (shutdown) {
                    try { ChatPrompts.begin(player, PROMPT); }
                    catch (IllegalStateException expected) { promptRejected++; }
                    try { ChatPrompts.registerListeners(); }
                    catch (IllegalStateException expected) { promptRejected++; }
                    try { Editor.registerListeners(); }
                    catch (IllegalStateException expected) { promptRejected++; }
                }
                if (throwing) throw new IllegalStateException("expected shutdown prompt failure");
            })));
        }
    }

    public static List<String> verify() {
        check(editorEnds == 3, "server shutdown ends all editors after callback failure");
        check(promptEnds == 3, "server shutdown abandons all prompts after callback failure");
        check(editorRejected == 6, "editor shutdown callbacks cannot reopen or register");
        check(promptRejected == 9, "prompt shutdown callbacks cannot reopen prompts or editors");
        check(players.stream().noneMatch(Editor::hasEditor), "server shutdown clears editor ownership");
        check(players.stream().noneMatch(ChatPrompts::isActive), "server shutdown clears prompt ownership");
        check(sessions.stream().allMatch(ChatPromptSession::isEnded), "server shutdown marks every prompt ended");
        check(field(Editor.class, "dispatcher") == null, "server shutdown releases editor dispatcher");
        check(field(ChatPrompts.class, "dispatcher") == null, "server shutdown releases prompt dispatcher");
        Editor.shutdown();
        ChatPrompts.shutdown();
        check(editorEnds == 3 && promptEnds == 3, "repeated shutdown does not repeat callbacks");
        return List.copyOf(checks);
    }

    private static Object field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private static void expectFailure(Runnable action, String description) {
        boolean failed = false;
        try { action.run(); }
        catch (IllegalStateException expected) { failed = true; }
        check(failed, description);
    }

    private static void check(boolean success, String description) {
        if (!success) throw new AssertionError(description);
        checks.add(description);
    }
}
