package net.citizensnpcs.api.command.exception;

/** A precondition declared by the command was not met - no NPC selected, wrong mob type, missing trait. */
public class RequirementMissingException extends CommandException {
    public RequirementMissingException(String message) {
        super(message);
    }

    private static final long serialVersionUID = -4299721983654504028L;
}
