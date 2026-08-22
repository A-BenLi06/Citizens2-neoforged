package net.citizensnpcs.api.command.exception;

/** An unexpected throwable from a command body, wrapped so the dispatcher can report it once. */
public class WrappedCommandException extends CommandException {
    public WrappedCommandException(Throwable t) {
        super(t);
    }

    private static final long serialVersionUID = -4075721444847778918L;
}
