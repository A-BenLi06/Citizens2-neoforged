package net.citizensnpcs.api.command;

import java.lang.annotation.Annotation;

import net.citizensnpcs.api.command.exception.CommandException;
import net.minecraft.commands.CommandSourceStack;

/** Checks one annotation on a command method before its body runs. */
public interface CommandAnnotationProcessor {
    /** @return the annotation class this processor handles */
    Class<? extends Annotation> getAnnotationClass();

    /**
     * @param sender
     *            who ran the command
     * @param context
     *            the parsed command line
     * @param instance
     *            the annotation instance found on the method
     * @param args
     *            the arguments about to be passed to the method, which a processor may rewrite
     * @throws CommandException
     *             to stop the command and report to the sender
     */
    void process(CommandSourceStack sender, CommandContext context, Annotation instance, Object[] args)
            throws CommandException;
}
