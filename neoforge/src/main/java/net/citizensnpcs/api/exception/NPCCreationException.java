package net.citizensnpcs.api.exception;

/** Creation was vetoed or its candidate was removed/replaced before initialization completed. */
public class NPCCreationException extends RuntimeException {
    public NPCCreationException(String message) { super(message); }
}
