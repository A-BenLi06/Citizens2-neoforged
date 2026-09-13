package net.citizensnpcs.api.command;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.joml.Quaternionfc;
import org.joml.Vector3fc;

import com.google.common.base.Joiner;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.Arg.CompletionsProvider;
import net.citizensnpcs.api.command.Arg.FlagValidator;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.command.exception.CommandUsageException;
import net.citizensnpcs.api.command.exception.NoPermissionsException;
import net.citizensnpcs.api.command.exception.ServerCommandException;
import net.citizensnpcs.api.command.exception.UnhandledCommandException;
import net.citizensnpcs.api.command.exception.WrappedCommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Durations;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Paginator;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.Placeholders;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Dispatches Citizens' annotated command methods.
 * <p>
 * This is upstream's dispatcher, kept deliberately: {@code NPCCommands} alone is several thousand lines of
 * {@link Command}-annotated methods carrying the whole {@code /npc create Bob --at 1,2,3 --type zombie -bstu} flag
 * grammar, and reproducing that on Brigadier argument nodes would be a rewrite rather than a port. Brigadier registers one
 * greedy-string node per root command and hands the raw text here; see {@code CommandRegistry}.
 * <p>
 * Differences from upstream, all from the platform:
 * <ul>
 * <li>The sender is a {@link CommandSourceStack} rather than a Bukkit {@code CommandSender}, and the root command is a
 * plain name rather than a Bukkit command object.
 * <li>"Console command" now means "does not require a player". A method declares {@link ServerPlayer} as its second
 * parameter to require one, in the same spirit as upstream declaring Bukkit's {@code Player}; the source is unwrapped for
 * it at call time.
 * <li>Argument coercion covers the vanilla types: registry objects are looked up by id (so values added by other mods
 * work), and Bukkit-only types — {@code Color}, {@code Material}, {@code NamespacedKey} — become an integer, an
 * {@link Item} and a {@link ResourceLocation}.
 * <li>Tab completion is exposed as a plain method for the Brigadier bridge to call instead of implementing Bukkit's
 * {@code TabCompleter}.
 * </ul>
 */
public class CommandManager {
    private final Map<Class<? extends Annotation>, CommandAnnotationProcessor> annotationProcessors = new HashMap<>();
    private final Map<String, CommandInfo> commands = new HashMap<>();
    private final Map<String, String> helpPermissions = new HashMap<>();
    private TimeUnit defaultDurationUnits;
    private Injector injector;
    private Function<Command, String> translationPrefixProvider;

    public CommandManager() {
        registerAnnotationProcessor(new RequirementsProcessor());
    }

    /**
     * Runs a command.
     *
     * @param rootCommand
     *            the top-level command name, without a slash
     * @param args
     *            everything after it, already split on spaces
     * @param sender
     *            who ran it
     * @param methodArgs
     *            extra arguments the command methods expect after the context and sender — for Citizens, the selected NPC
     */
    public void execute(String rootCommand, String[] args, CommandSourceStack sender, Object... methodArgs)
            throws CommandException {
        String[] newArgs = new String[args.length + 1];
        System.arraycopy(args, 0, newArgs, 1, args.length);
        newArgs[0] = rootCommand.toLowerCase(Locale.ROOT);

        Object[] newMethodArgs = new Object[methodArgs.length + 1];
        System.arraycopy(methodArgs, 0, newMethodArgs, 1, methodArgs.length);
        executeCommand(newArgs, sender, newMethodArgs);
    }

    private void executeCommand(String[] args, CommandSourceStack sender, Object[] methodArgs) throws CommandException {
        String cmdName = args[0].toLowerCase(Locale.ROOT);
        String modifier = args.length > 1 ? args[1] : "";
        boolean help = modifier.equalsIgnoreCase("help");

        CommandInfo info = getCommand(cmdName, modifier);
        if (info == null || info.handler == null) {
            if (help) {
                executeHelp(args, sender);
                return;
            }
            info = commands.get(cmdName + " *");
        }
        if (info == null && args.length > 2) {
            info = getCommand(cmdName, args[1], args[2]);
        }
        if (info == null)
            throw new UnhandledCommandException();

        if (!info.serverCommand && sender.getPlayer() == null)
            throw new ServerCommandException();

        if (!hasPermission(info, sender))
            throw new NoPermissionsException();

        Command cmd = info.commandAnnotation;
        if (cmd.parsePlaceholders()) {
            NPC npc = methodArgs.length > 2 && methodArgs[2] instanceof NPC ? (NPC) methodArgs[2] : null;
            for (int i = 1; i < args.length; i++) {
                args[i] = Placeholders.replace(args[i], sender, npc);
            }
        }
        CommandContext context = new CommandContext(sender, args);

        if (cmd.requiresFlags() && !context.hasAnyFlags())
            throw new CommandUsageException("", getUsage(args, cmd));

        if (context.argsLength() < cmd.min())
            throw new CommandUsageException(CommandMessages.TOO_FEW_ARGUMENTS, getUsage(args, cmd));

        if (cmd.max() != -1 && context.argsLength() > cmd.max())
            throw new CommandUsageException(CommandMessages.TOO_MANY_ARGUMENTS, getUsage(args, cmd));

        if (!cmd.flags().contains("*")) {
            for (char flag : context.getFlags()) {
                if (cmd.flags().indexOf(String.valueOf(flag)) == -1)
                    throw new CommandUsageException("Unknown flag: " + flag, getUsage(args, cmd));
            }
        }
        methodArgs[0] = context;

        if (cmd.strictArguments()) {
            for (String flag : context.getValueFlags().keySet()) {
                if (!info.valueFlags().contains(flag) && !flag.equals("id") && !flag.equals("uuid"))
                    throw new CommandException(CommandMessages.UNKNOWN_FLAG, "--" + flag);
            }
        }

        for (InjectedCommandArgument argument : info.methodArguments.values()) {
            if (argument.permission != null && Arrays.stream(argument.names).anyMatch(context::hasValueFlag)
                    && !PermissionUtil.hasPermission(sender, argument.permission)) throw new NoPermissionsException();
        }

        for (Annotation annotation : info.annotations) {
            CommandAnnotationProcessor processor = annotationProcessors.get(annotation.annotationType());
            processor.process(sender, context, annotation, methodArgs);
        }
        if (!info.methodArguments.isEmpty()) {
            methodArgs = Arrays.copyOf(methodArgs, methodArgs.length + info.methodArguments.size());
            NPC npc = methodArgs.length > 2 && methodArgs[2] instanceof NPC ? (NPC) methodArgs[2] : null;
            for (Entry<Integer, InjectedCommandArgument> entry : info.methodArguments.entrySet()) {
                InjectedCommandArgument argument = entry.getValue();
                Object val = argument.getInput(context);
                if (val != null) {
                    String raw = val.toString();
                    try {
                        if (cmd.strictArguments() && (argument.paramType == Boolean.class || argument.paramType == boolean.class)
                                && !raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false"))
                            throw new IllegalArgumentException("Expected a boolean");
                        val = argument.validator != null
                                ? argument.validator.validate(context, sender, npc, raw)
                                : coerce(argument.paramType, raw, context, sender);
                        if (cmd.strictArguments() && (val == null || val instanceof Double number && !Double.isFinite(number)
                                || val instanceof Float number && !Float.isFinite(number)))
                            throw new IllegalArgumentException("Invalid typed argument");
                    } catch (IllegalArgumentException failure) {
                        if (!cmd.strictArguments()) throw failure;
                        String name = argument.names.length == 0 ? Integer.toString(argument.index) : "--" + argument.names[0];
                        throw new CommandException(CommandMessages.INVALID_VALUE, name, raw);
                    }
                }
                methodArgs[entry.getKey()] = val;
            }
        }
        // a method that wants a player gets one; the check above guarantees it exists
        if (info.wantsPlayer) {
            methodArgs[1] = sender.getPlayer();
        }
        try {
            info.handler.handle(info.instance, methodArgs);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            Messaging.severe("Failed to execute command", cmdName, modifier, e.getMessage());
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof CommandException) {
                if (e.getCause() instanceof CommandUsageException usage && usage.getUsage() == null) {
                    usage.setUsage(getUsage(args, cmd));
                }
                throw (CommandException) e.getCause();
            }
            throw new WrappedCommandException(e.getCause());
        }
    }

    /**
     * Turns a flag or argument string into the type the method parameter wants.
     * <p>
     * Anything not handled here is left as a String, which is what a parameter declaring String expects and what a custom
     * validator would have taken anyway.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Object coerce(Class<?> desiredType, String raw, CommandContext context, CommandSourceStack sender)
            throws CommandException {
        if (desiredType == String.class)
            return raw;
        if (desiredType == ServerPlayer.class) {
            if (sender.getServer() == null)
                return null;
            try {
                return sender.getServer().getPlayerList().getPlayer(UUID.fromString(raw));
            } catch (IllegalArgumentException ex) {
                return sender.getServer().getPlayerList().getPlayerByName(raw);
            }
        }
        if (desiredType == double.class || desiredType == Double.class)
            return Double.parseDouble(raw);
        if (desiredType == int.class || desiredType == Integer.class)
            return Integer.parseInt(raw);
        if (desiredType == boolean.class || desiredType == Boolean.class)
            return Boolean.parseBoolean(raw);
        if (desiredType == float.class || desiredType == Float.class)
            return Float.parseFloat(raw);
        if (Enum.class.isAssignableFrom(desiredType))
            return matchEnum((Enum[]) desiredType.getEnumConstants(), raw.toUpperCase(Locale.ROOT));
        if (desiredType == ResourceLocation.class)
            return ResourceLocation.tryParse(raw.contains(":") ? raw : "minecraft:" + raw);
        if (desiredType == Location.class)
            return context.parseLocation(raw);
        if (desiredType == UUID.class)
            return UUID.fromString(raw);
        if (desiredType == Duration.class)
            return Durations.parse(raw, defaultDurationUnits);
        if (desiredType == Vec3.class)
            return CommandContext.parseVector(raw);
        if (Quaternionfc.class.isAssignableFrom(desiredType))
            return CommandContext.parseQuaternion(raw);
        if (Vector3fc.class.isAssignableFrom(desiredType))
            return CommandContext.parseVector3f(raw);
        Registry<?> registry = registryFor(desiredType);
        if (registry != null) {
            ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT).contains(":")
                    ? raw.toLowerCase(Locale.ROOT)
                    : "minecraft:" + raw.toLowerCase(Locale.ROOT));
            return id == null ? null : registry.get(id);
        }
        return raw;
    }

    /**
     * @return the built-in registry holding this type, or null when it is not a registry type
     */
    private static Registry<?> registryFor(Class<?> type) {
        if (type == EntityType.class)
            return BuiltInRegistries.ENTITY_TYPE;
        if (type == Item.class)
            return BuiltInRegistries.ITEM;
        if (type == Block.class)
            return BuiltInRegistries.BLOCK;
        return null;
    }

    private void executeHelp(String[] args, CommandSourceStack sender) throws CommandException {
        String permission = helpPermissions.getOrDefault(args[0], "citizens." + args[0] + ".help");
        if (!PermissionUtil.hasPermission(sender, permission))
            throw new NoPermissionsException();
        int page = 1;
        try {
            page = args.length == 3 ? Integer.parseInt(args[2]) : page;
        } catch (NumberFormatException e) {
            sendSpecificHelp(sender, args[0], args[2]);
            return;
        }
        sendHelp(sender, args[0], page);
    }

    /**
     * {@link #execute} with every failure reported to the sender instead of thrown.
     *
     * @return false when no command matched, so the caller can fall back to its own handling
     */
    public boolean executeSafe(String rootCommand, String[] args, CommandSourceStack sender, Object... methodArgs) {
        try {
            try {
                execute(rootCommand, args, sender, methodArgs);
            } catch (ServerCommandException ex) {
                Messaging.sendTr(sender, CommandMessages.MUST_BE_INGAME);
            } catch (CommandUsageException ex) {
                if (ex.getMessage() != null && !ex.getMessage().isEmpty()) {
                    Messaging.sendError(sender, ex.getMessage());
                }
                Messaging.sendError(sender, ex.getUsage());
            } catch (UnhandledCommandException ex) {
                Messaging.sendErrorTr(sender, CommandMessages.UNKNOWN_COMMAND);
                return false;
            } catch (WrappedCommandException ex) {
                if (ex.getCause() instanceof NumberFormatException) {
                    if (Messaging.isDebugging()) {
                        ex.printStackTrace();
                    }
                    Messaging.sendErrorTr(sender, CommandMessages.INVALID_NUMBER);
                } else
                    throw ex.getCause();
            } catch (CommandException ex) {
                Messaging.sendError(sender, ex.getMessage());
            }
        } catch (Throwable ex) {
            ex.printStackTrace();
            if (sender.getPlayer() != null) {
                Messaging.sendErrorTr(sender, CommandMessages.REPORT_ERROR);
                Messaging.sendError(sender, ex.getClass().getName() + ": " + ex.getMessage());
            }
        }
        return true;
    }

    private String format(Command command, String alias) {
        String description = command.desc();
        if (translationPrefixProvider != null && description.isEmpty()) {
            description = translationPrefixProvider.apply(command) + ".description";
        }
        return String.format(COMMAND_FORMAT, alias, command.usage().isEmpty() ? "" : " " + command.usage(),
                Messaging.tryTranslate(description));
    }

    /**
     * @return the registered sub-command closest to what was typed, by edit distance, or empty
     */
    public String getClosestCommandModifier(String command, String modifier) {
        int minDist = Integer.MAX_VALUE;
        command = command.toLowerCase(Locale.ROOT);
        String closest = "";
        for (String cmd : commands.keySet()) {
            String[] split = cmd.split(" ");
            if (split.length <= 1 || !split[0].equals(command)) {
                continue;
            }
            int distance = getLevenshteinDistance(modifier, split[1]);
            if (minDist > distance) {
                minDist = distance;
                closest = split[1];
            }
        }
        return closest;
    }

    public CommandInfo getCommand(String... commandParts) {
        return commands.get(Joiner.on(' ').join(commandParts).toLowerCase(Locale.ROOT));
    }

    /**
     * @return every command registered under this root, so {@code getCommands("npc")} finds {@code /npc look} and
     *         {@code /npc jump} alike
     */
    public List<CommandInfo> getCommands(String topLevelCommand) {
        topLevelCommand = topLevelCommand.toLowerCase(Locale.ROOT);
        List<CommandInfo> cmds = new ArrayList<>();
        for (Entry<String, CommandInfo> entry : commands.entrySet()) {
            if (!entry.getKey().startsWith(topLevelCommand) || entry.getValue() == null)
                continue;

            cmds.add(entry.getValue());
        }
        return cmds;
    }

    /** @return the root command names that have at least one sub-command registered */
    public Set<String> getRootCommands() {
        Set<String> roots = new HashSet<>();
        for (String key : commands.keySet()) {
            roots.add(key.split(" ")[0]);
        }
        return roots;
    }

    private List<String> getLines(CommandSourceStack sender, String baseCommand) {
        // a command with several modifiers is registered under each, so it must only be listed once
        Set<CommandInfo> processed = new HashSet<>();
        List<String> lines = new ArrayList<>();
        for (CommandInfo info : getCommands(baseCommand)) {
            Command command = info.getCommandAnnotation();
            if (processed.contains(info) || !PermissionUtil.hasPermission(sender, "citizens.admin")
                    && !PermissionUtil.hasPermission(sender, command.permission()))
                continue;

            lines.add(format(command, baseCommand));
            if (command.modifiers().length > 0) {
                processed.add(info);
            }
        }
        Collections.sort(lines);
        return lines;
    }

    private String getUsage(String[] args, Command cmd) {
        return "/" + args[0] + " " + cmd.usage();
    }

    public boolean hasCommand(String... parts) {
        if (parts == null || parts.length == 0)
            throw new IllegalArgumentException("parts must not be empty");
        return commands.containsKey(Joiner.on(' ').join(parts)) || commands.containsKey(parts[0] + " *");
    }

    private boolean hasPermission(CommandInfo method, CommandSourceStack sender) {
        Command cmd = method.commandAnnotation;
        return cmd.permission().isEmpty() || PermissionUtil.hasPermission(sender, cmd.permission())
                || PermissionUtil.hasPermission(sender, "citizens.admin");
    }

    /**
     * Completions for a partially typed command.
     *
     * @param rootCommand
     *            the top-level command name
     * @param args
     *            what has been typed after it; the last element may be a partial word
     */
    public List<String> onTabComplete(CommandSourceStack sender, String rootCommand, String[] args) {
        List<String> results = new ArrayList<>();
        if (args.length <= 2 && args.length > 0 && args[0].equalsIgnoreCase("help"))
            return getCommands(rootCommand.toLowerCase(Locale.ROOT)).stream()
                    .map(info -> info.commandAnnotation.modifiers().length > 0 ? info.commandAnnotation.modifiers()[0]
                            : null)
                    .filter(Objects::nonNull).collect(Collectors.toList());

        if (args.length <= 1) {
            String search = args.length == 1 ? args[0] : "";
            for (String base : commands.keySet()) {
                String[] parts = base.split(" ");
                if (!parts[0].equalsIgnoreCase(rootCommand) || parts.length < 2) {
                    continue;
                }
                if (parts[1].startsWith(search)) {
                    results.add(parts[1]);
                }
            }
            return results;
        }
        CommandInfo cmd = getCommand(rootCommand, args[0]);
        if (cmd == null && args.length > 1) {
            cmd = getCommand(rootCommand, args[0], args[1]);
        }
        if (cmd == null)
            return results;

        // parsed without clearing flags, so a half-typed flag is still visible
        String[] newArgs = new String[args.length + 1];
        System.arraycopy(args, 0, newArgs, 1, args.length);
        newArgs[0] = rootCommand.toLowerCase(Locale.ROOT);
        CommandContext context = new CommandContext(false, sender, newArgs);

        results.addAll(cmd.getArgTabCompletions(context, sender, args.length - 1));

        String lastArg = (newArgs.length >= 2 ? newArgs[newArgs.length - 2] : newArgs[newArgs.length - 1])
                .toLowerCase(Locale.ROOT);
        String hyphenStrippedArg = lastArg.replaceFirst("--", "");

        if (lastArg.startsWith("--") && cmd.valueFlags().contains(hyphenStrippedArg)) {
            results.addAll(cmd.getFlagTabCompletions(context, sender, hyphenStrippedArg));
        } else {
            lastArg = newArgs[newArgs.length - 1];
            hyphenStrippedArg = lastArg.replaceFirst("--", "");
            boolean isEmpty = lastArg.isEmpty() || Set.of("-", "--").contains(lastArg);
            for (String valueFlag : cmd.valueFlags()) {
                if (lastArg.startsWith("--") && valueFlag.startsWith(hyphenStrippedArg)
                        || isEmpty && !context.hasValueFlag(valueFlag)) {
                    results.add("--" + valueFlag);
                }
            }
            String flags = cmd.commandAnnotation.flags();
            for (int i = 0; i < flags.length(); i++) {
                char c = flags.charAt(i);
                if (lastArg.isEmpty() && !context.hasFlag(c)) {
                    results.add("-" + c);
                }
            }
        }
        return results;
    }

    /**
     * Scans a class for {@link Command}-annotated methods. Without an {@link Injector} only static methods are picked up.
     */
    public void register(Class<?> clazz) {
        registerMethods(clazz, null);
    }

    public void registerAnnotationProcessor(CommandAnnotationProcessor processor) {
        annotationProcessors.put(processor.getAnnotationClass(), processor);
    }

    private void registerMethods(Class<?> clazz, Method parent) {
        Object obj = injector != null ? injector.getInstance(clazz) : null;
        registerMethods(clazz, parent, obj);
    }

    private void registerMethods(Class<?> clazz, Method parent, Object obj) {
        for (Method method : clazz.getMethods()) {
            if (!method.isAnnotationPresent(Command.class)
                    || !Modifier.isStatic(method.getModifiers()) && obj == null) {
                continue;
            }
            Command cmd = method.getAnnotation(Command.class);
            if (!cmd.permission().isEmpty()) PermissionUtil.register(cmd.permission());
            CommandInfo info = new CommandInfo(cmd, (instance, args) -> method.invoke(instance, args));

            info.instance = obj;

            List<Annotation> annotations = new ArrayList<>();
            for (Annotation annotation : method.getDeclaringClass().getAnnotations()) {
                if (annotationProcessors.containsKey(annotation.annotationType())) {
                    annotations.add(annotation);
                }
            }
            for (Annotation annotation : method.getAnnotations()) {
                Class<? extends Annotation> annotationClass = annotation.annotationType();
                if (!annotationProcessors.containsKey(annotationClass))
                    continue;

                // a method annotation replaces the same annotation inherited from the class
                Iterator<Annotation> itr = annotations.iterator();
                while (itr.hasNext()) {
                    if (itr.next().annotationType() == annotationClass) {
                        itr.remove();
                    }
                }
                annotations.add(annotation);
            }
            if (!annotations.isEmpty()) {
                info.annotations = annotations;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            info.wantsPlayer = parameterTypes.length > 1 && parameterTypes[1] == ServerPlayer.class;
            info.serverCommand = !info.wantsPlayer;
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                for (Annotation ann : parameters[i].getAnnotations()) {
                    if (ann instanceof Flag) {
                        Flag flag = (Flag) ann;
                        if (!flag.permission().isEmpty()) PermissionUtil.register(flag.permission());
                        info.addFlagAnnotation(i, parameterTypes[i], flag);
                    } else if (ann instanceof Arg) {
                        info.addArgAnnotation(i, parameterTypes[i], (Arg) ann);
                    }
                }
            }
            for (String alias : cmd.aliases()) {
                // Bukkit dispatches aliases under the primary command name. The native Brigadier roots must
                // share that permission too, so /wp and /waypoints do not require separate help grants.
                String helpPermission = "citizens." + cmd.aliases()[0].toLowerCase(Locale.ROOT) + ".help";
                helpPermissions.putIfAbsent(alias.toLowerCase(Locale.ROOT), helpPermission);
                PermissionUtil.register(helpPermission);
                for (String modifier : cmd.modifiers()) {
                    commands.put(alias + " " + modifier, info);
                }
                if (!commands.containsKey(alias + " help")) {
                    commands.put(alias + " help", null);
                }
            }
        }
    }

    private void sendHelp(CommandSourceStack sender, String name, int page) throws CommandException {
        if (name.equalsIgnoreCase("npc")) {
            name = "NPC";
        }
        Paginator paginator = new Paginator()
                .header(capitalize(name) + " " + Messaging.tr(CommandMessages.COMMAND_HELP_HEADER))
                .console(sender.getPlayer() == null);
        for (String line : getLines(sender, name.toLowerCase(Locale.ROOT))) {
            paginator.addLine(line);
        }
        if (!paginator.sendPage(sender, page))
            throw new CommandException(CommandMessages.COMMAND_PAGE_MISSING, page);
    }

    private void sendSpecificHelp(CommandSourceStack sender, String rootCommand, String modifier)
            throws CommandException {
        CommandInfo info = getCommand(rootCommand, modifier);
        if (info == null)
            throw new CommandException(CommandMessages.COMMAND_MISSING, rootCommand + " " + modifier);
        Messaging.send(sender, format(info.getCommandAnnotation(), rootCommand));
        String help = Messaging.tryTranslate(info.getCommandAnnotation().help());
        if (translationPrefixProvider != null) {
            String helpKey = translationPrefixProvider.apply(info.getCommandAnnotation()) + ".help";
            String attemptedTranslation = Messaging.tryTranslate(helpKey);
            if (!helpKey.equals(attemptedTranslation) && !attemptedTranslation.isEmpty()) {
                help = attemptedTranslation;
            }
        }
        if (help.isEmpty())
            return;
        Messaging.send(sender, "<aqua>" + help);
    }

    public void setDefaultDurationUnits(TimeUnit unit) {
        this.defaultDurationUnits = unit;
    }

    public void setInjector(Injector injector) {
        this.injector = injector;
    }

    public void setTranslationPrefixProvider(Function<Command, String> provider) {
        this.translationPrefixProvider = provider;
    }

    @FunctionalInterface
    public static interface CommandHandler {
        void handle(Object instance, Object[] args)
                throws CommandException, InvocationTargetException, IllegalAccessException;
    }

    /** One registered command method, with everything needed to check, complete and invoke it. */
    public class CommandInfo {
        private List<Annotation> annotations = new ArrayList<>();
        private final Command commandAnnotation;
        private final CommandHandler handler;
        public Object instance;
        private final Map<Integer, InjectedCommandArgument> methodArguments = new HashMap<>();
        public boolean serverCommand;
        /** Whether the second parameter is a player, meaning the command cannot come from the console. */
        public boolean wantsPlayer;
        private Collection<String> valueFlags;

        public CommandInfo(Command commandAnnotation, CommandHandler handler) {
            this.commandAnnotation = commandAnnotation;
            this.handler = handler;
        }

        public void addArgAnnotation(int idx, Class<?> paramType, Arg arg) {
            methodArguments.put(idx, new InjectedCommandArgument(injector, paramType, arg));
        }

        public void addFlagAnnotation(int idx, Class<?> paramType, Flag flag) {
            methodArguments.put(idx, new InjectedCommandArgument(injector, paramType, flag));
        }

        private Collection<String> calculateValueFlags() {
            valueFlags = new HashSet<>();
            for (InjectedCommandArgument instance : methodArguments.values()) {
                valueFlags.addAll(Arrays.asList(instance.names));
            }
            valueFlags.addAll(Arrays.asList(commandAnnotation.valueFlags()));
            return valueFlags;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            return Objects.equals(commandAnnotation, ((CommandInfo) obj).commandAnnotation);
        }

        public Collection<? extends String> getArgTabCompletions(CommandContext args, CommandSourceStack sender,
                int index) {
            List<String> completions = new ArrayList<>();
            for (InjectedCommandArgument instance : methodArguments.values()) {
                if (instance.matches(index)) {
                    String needle = index < args.argsLength() ? args.getString(index) : "";
                    instance.getTabCompletions(args, sender).stream()
                            .filter(s -> needle.isEmpty() || s.toLowerCase().startsWith(needle.toLowerCase()))
                            .forEach(completions::add);
                }
            }
            return completions;
        }

        public Command getCommandAnnotation() {
            return commandAnnotation;
        }

        public Collection<String> getFlagTabCompletions(CommandContext args, CommandSourceStack sender, String flag) {
            List<String> completions = new ArrayList<>();
            for (InjectedCommandArgument instance : methodArguments.values()) {
                if (instance.matches(flag)) {
                    String needle = args.getFlag(flag, "");
                    instance.getTabCompletions(args, sender).stream()
                            .filter(s -> needle.isEmpty() || s.toLowerCase().startsWith(needle.toLowerCase()))
                            .forEach(completions::add);
                }
            }
            return completions;
        }

        @Override
        public int hashCode() {
            return 31 + (commandAnnotation == null ? 0 : commandAnnotation.hashCode());
        }

        public Collection<String> valueFlags() {
            return valueFlags == null ? calculateValueFlags() : valueFlags;
        }
    }

    private static class InjectedCommandArgument {
        private final String[] completions;
        private CompletionsProvider completionsProvider;
        private final String defaultValue;
        private int index = -1;
        private final String[] names;
        private final Class<?> paramType;
        private String permission;
        private FlagValidator<?> validator;

        InjectedCommandArgument(Injector injector, Class<?> paramType, Arg arg) {
            this.paramType = paramType;
            this.names = new String[] {};
            this.index = arg.value();
            this.completions = arg.completions();
            this.defaultValue = arg.defValue().isEmpty() ? null : arg.defValue();
            if (arg.validator() != FlagValidator.Identity.class) {
                validator = (FlagValidator<?>) instantiate(injector, arg.validator());
            }
            if (arg.completionsProvider() != CompletionsProvider.Identity.class) {
                completionsProvider = (CompletionsProvider) instantiate(injector, arg.completionsProvider());
            }
        }

        InjectedCommandArgument(Injector injector, Class<?> paramType, Flag flag) {
            this.paramType = paramType;
            this.names = flag.value();
            for (int i = 0; i < names.length; i++) {
                names[i] = names[i].toLowerCase(Locale.ROOT);
            }
            this.permission = flag.permission().isEmpty() ? null : flag.permission();
            this.completions = flag.completions();
            this.defaultValue = flag.defValue().isEmpty() ? null : flag.defValue();
            if (flag.validator() != FlagValidator.Identity.class) {
                validator = (FlagValidator<?>) instantiate(injector, flag.validator());
            }
            if (flag.completionsProvider() != CompletionsProvider.Identity.class) {
                completionsProvider = (CompletionsProvider) instantiate(injector, flag.completionsProvider());
            }
        }

        /**
         * Validators and completion providers are plain helpers, so they are built without an injector when there is none
         * — upstream requires one and throws otherwise.
         */
        private static Object instantiate(Injector injector, Class<?> clazz) {
            if (injector != null)
                return injector.getInstance(clazz);
            try {
                java.lang.reflect.Constructor<?> ctr = clazz.getDeclaredConstructor();
                ctr.setAccessible(true);
                return ctr.newInstance();
            } catch (Exception ex) {
                Messaging.severe("Could not create", clazz.getName(), "for a command parameter:", ex.getMessage());
                return null;
            }
        }

        Object getInput(CommandContext context) {
            if (names.length > 0) {
                Object val = context.getFlag(names[0], defaultValue);
                if (val == null && names.length > 1 && !names[1].isEmpty()) {
                    val = context.getFlag(names[1], defaultValue);
                }
                return val;
            }
            return context.getString(index, defaultValue);
        }

        @SuppressWarnings("rawtypes")
        Collection<String> getTabCompletions(CommandContext args, CommandSourceStack sender) {
            if (permission != null && !PermissionUtil.hasPermission(sender, permission))
                return Collections.emptyList();

            if (completionsProvider != null)
                return completionsProvider.getCompletions(args, sender,
                        CitizensAPI.getDefaultNPCSelector().getSelected(sender));

            if (completions.length > 0)
                return Arrays.asList(completions);
            Registry<?> registry = registryFor(paramType);
            if (registry != null)
                return registry.keySet().stream().map(ResourceLocation::getPath).collect(Collectors.toList());
            if (Enum.class.isAssignableFrom(paramType))
                return Arrays.stream((Enum[]) paramType.getEnumConstants()).map(Enum::name)
                        .collect(Collectors.toList());
            if (paramType == boolean.class || paramType == Boolean.class)
                return Arrays.asList("true", "false");
            return Collections.emptyList();
        }

        Optional<String> getValueFlag() {
            return names.length == 0 ? Optional.empty() : Optional.of(names[0]);
        }

        boolean matches(int index) {
            return this.index == index;
        }

        boolean matches(String flag) {
            return names.length > 0
                    && (names[0].equalsIgnoreCase(flag) || names.length > 1 && names[1].equalsIgnoreCase(flag));
        }
    }

    private static String capitalize(Object string) {
        String capitalize = string.toString();
        return capitalize.isEmpty() ? "" : Character.toUpperCase(capitalize.charAt(0)) + capitalize.substring(1);
    }

    private static int getLevenshteinDistance(String s, String t) {
        if (s == null || t == null)
            throw new IllegalArgumentException("Strings must not be null");
        int n = s.length();
        int m = t.length();
        if (n == 0)
            return m;
        if (m == 0)
            return n;
        int[] p = new int[n + 1];
        int[] d = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            p[i] = i;
        }
        for (int j = 1; j <= m; j++) {
            char tj = t.charAt(j - 1);
            d[0] = j;
            for (int i = 1; i <= n; i++) {
                int cost = s.charAt(i - 1) == tj ? 0 : 1;
                d[i] = Math.min(Math.min(d[i - 1] + 1, p[i] + 1), p[i - 1] + cost);
            }
            int[] swap = p;
            p = d;
            d = swap;
        }
        return p[n];
    }

    /**
     * Matches an enum constant loosely: exact name first, then ignoring separators, then by prefix. Upstream also maps
     * {@code item} onto Bukkit's {@code DROPPED_ITEM}, which has no counterpart here.
     */
    private static <T extends Enum<?>> T matchEnum(T[] values, String toMatch) {
        toMatch = toMatch.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (T check : values) {
            if (toMatch.equals(check.name().toLowerCase(Locale.ROOT)))
                return check;
        }
        for (T check : values) {
            String name = check.name().toLowerCase(Locale.ROOT);
            if (name.replace("_", "").equals(toMatch) || name.startsWith(toMatch))
                return check;
        }
        return null;
    }

    private static final String COMMAND_FORMAT = "<green>/{{%s%s <green>- [[%s";
}
