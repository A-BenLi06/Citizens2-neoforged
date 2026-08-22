package net.citizensnpcs.api.util;

/**
 * One step of a chat-driven configuration conversation.
 * <p>
 * Replaces Bukkit's Conversation API, which has no NeoForge counterpart. The shape is deliberately the same as Bukkit's
 * {@code StringPrompt} so that Citizens' existing prompts port across with the types swapped: send the question in
 * {@link #getPromptText}, read the answer in {@link #acceptInput}, and return the prompt to run next.
 */
public interface ChatPrompt {
    /**
     * Asks the question. A prompt that sends its own message through {@link Messaging} — as all of Citizens' do — should
     * return an empty string, which is not sent.
     */
    String getPromptText(ChatPromptSession session);

    /**
     * @return the next prompt, or null to end the conversation
     */
    ChatPrompt acceptInput(ChatPromptSession session, String input);
}
