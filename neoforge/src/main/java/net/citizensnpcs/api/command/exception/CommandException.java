package net.citizensnpcs.api.command.exception;

import net.citizensnpcs.api.util.Messaging;

/**
 * A command failed for a reason the sender should be told about.
 * <p>
 * The message is run through the translator, so a translation key can be thrown directly. The stack trace is suppressed
 * because these are expected control flow, not faults.
 */
public class CommandException extends Exception {
    public CommandException() {
    }

    public CommandException(String message) {
        super(Messaging.tryTranslate(message));
    }

    public CommandException(String key, Object... replacements) {
        super(Messaging.tr(key, replacements));
    }

    public CommandException(Throwable t) {
        super(t);
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    private static final long serialVersionUID = 870638193072101739L;
}
