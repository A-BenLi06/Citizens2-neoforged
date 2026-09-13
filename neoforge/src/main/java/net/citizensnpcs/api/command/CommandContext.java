package net.citizensnpcs.api.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;

import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.persistence.LocationPersister;
import net.citizensnpcs.api.util.Durations;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Location;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Rotations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Parsed form of a command invocation — positional arguments, {@code --flag value} pairs and {@code -abc} switches.
 * <p>
 * The parsing follows upstream: it is where {@code /npc create Bob --at 1,2,3 --type zombie -bstu}
 * gets its shape, including quoted arguments, brace-delimited JSON and multi-word values. Explicitly quoted empty values
 * remain distinct from tokens collapsed during parsing. The platform-specific pieces are:
 * <ul>
 * <li>The sender is a {@link CommandSourceStack}, which already unifies player, command block and console — so upstream's
 * three-way branch on sender type collapses into asking the source for its position and level.
 * <li>A location's world part is a dimension id resolved through {@link LocationPersister#resolve}, which also accepts
 * the legacy world names an existing save may hold.
 * <li>{@code parseEulerAngle} returns vanilla {@link Rotations} (degrees) rather than Bukkit's {@code EulerAngle}
 * (radians). The {@code d}/{@code r} suffixes still work, so the same input produces the same pose.
 * </ul>
 */
public class CommandContext {
    protected String[] args;
    protected final Set<Character> flags = new HashSet<>();
    private Location location = null;
    private final String[] rawArgs;
    private final CommandSourceStack sender;
    protected final Map<String, String> valueFlags = new HashMap<>();

    public CommandContext(boolean clearFlags, CommandSourceStack sender, String[] args) {
        this.sender = sender;
        this.rawArgs = new String[args.length];
        System.arraycopy(args, 0, rawArgs, 0, args.length);

        boolean[] isquoted = new boolean[args.length];
        int i = 1;
        for (; i < args.length; i++) {
            // initial pass for quotes
            args[i] = args[i].trim();
            if (args[i].length() == 0)
                continue;

            if (args[i].charAt(0) == '{') {
                String json = args[i];
                for (int inner = i + 1; inner < args.length; inner++) {
                    if (CharMatcher.is('{').countIn(json) - CharMatcher.is('}').countIn(json) == 0)
                        break;

                    json += " " + args[inner];
                    args[inner] = "";
                }
                args[i] = json;
            } else if (args[i].charAt(0) == '\'' || args[i].charAt(0) == '"' || args[i].charAt(0) == '`') {
                char quote = args[i].charAt(0);
                String quoted = args[i].substring(1);
                if (quoted.length() > 0 && quoted.charAt(quoted.length() - 1) == quote) {
                    args[i] = quoted.substring(0, quoted.length() - 1);
                    isquoted[i] = true;
                    continue;
                }
                for (int inner = i + 1; inner < args.length; inner++) {
                    if (args[inner].isEmpty())
                        continue;

                    String test = args[inner].trim();
                    quoted += " " + test;
                    if (test.charAt(test.length() - 1) == quote) {
                        args[i] = quoted.substring(0, quoted.length() - 1);
                        isquoted[i] = true;
                        // remove ending quote
                        for (int j = i + 1; j <= inner; ++j) {
                            args[j] = ""; // collapse previous
                        }
                        break;
                    }
                }
            }
        }
        for (i = 1; i < args.length; ++i) {
            // second pass for flags
            int length = args[i].length();
            if (length == 0 || isquoted[i])
                continue;

            if (i + 1 < args.length && length > 2 && VALUE_FLAG.matcher(args[i]).matches()) {
                int inner = i + 1;
                while (args[inner].length() == 0 && !isquoted[inner]) {
                    // later args may have been quoted
                    if (++inner >= args.length) {
                        inner = -1;
                        break;
                    }
                }
                if (inner != -1) {
                    valueFlags.put(args[i].toLowerCase(Locale.ROOT).substring(2), args[inner]);
                    if (clearFlags) {
                        args[i] = "";
                        args[inner] = "";
                        isquoted[inner] = false;
                    }
                }
            } else if (FLAG.matcher(args[i]).matches()) {
                for (int k = 1; k < args[i].length(); k++) {
                    flags.add(args[i].charAt(k));
                }
                args[i] = "";
            }
        }
        List<String> copied = new ArrayList<>();
        for (i = 0; i < args.length; i++) {
            String arg = isquoted[i] ? args[i] : args[i].trim();
            if (arg.isEmpty() && !isquoted[i])
                continue;

            copied.add(arg);
        }
        this.args = copied.toArray(new String[0]);
    }

    public CommandContext(CommandSourceStack sender, String[] args) {
        this(true, sender, args);
    }

    public CommandContext(String[] args) {
        this(null, args);
    }

    public int argsLength() {
        return args.length - 1;
    }

    public String getCommand() {
        return args[0];
    }

    public double getDouble(int index) throws NumberFormatException {
        return Double.parseDouble(args[index + 1]);
    }

    public double getDouble(int index, double def) throws NumberFormatException {
        return index + 1 < args.length ? Double.parseDouble(args[index + 1]) : def;
    }

    public String getFlag(String ch) {
        return valueFlags.get(ch);
    }

    public String getFlag(String ch, String def) {
        String value = valueFlags.get(ch);
        return value == null ? def : value;
    }

    public double getFlagDouble(String ch) throws NumberFormatException {
        return Double.parseDouble(valueFlags.get(ch));
    }

    public double getFlagDouble(String ch, double def) throws NumberFormatException {
        String value = valueFlags.get(ch);
        return value == null ? def : Double.parseDouble(value);
    }

    public int getFlagInteger(String ch) throws NumberFormatException {
        return Integer.parseInt(valueFlags.get(ch));
    }

    public int getFlagInteger(String ch, int def) throws NumberFormatException {
        String value = valueFlags.get(ch);
        return value == null ? def : Integer.parseInt(value);
    }

    public Set<Character> getFlags() {
        return flags;
    }

    public int getFlagTicks(String ch) throws NumberFormatException {
        return parseTicks(valueFlags.get(ch));
    }

    public int getFlagTicks(String ch, int def) throws NumberFormatException {
        String value = valueFlags.get(ch);
        return value == null ? def : parseTicks(value);
    }

    public int getInteger(int index) throws NumberFormatException {
        return Integer.parseInt(args[index + 1]);
    }

    public int getInteger(int index, int def) throws NumberFormatException {
        if (index + 1 < args.length) {
            try {
                return Integer.parseInt(args[index + 1]);
            } catch (NumberFormatException ex) {
            }
        }
        return def;
    }

    public String getJoinedStrings(int initialIndex) {
        return getJoinedStrings(initialIndex, ' ');
    }

    public String getJoinedStrings(int initialIndex, char delimiter) {
        initialIndex = initialIndex + 1;
        StringBuilder buffer = new StringBuilder(args[initialIndex]);
        for (int i = initialIndex + 1; i < args.length; i++) {
            buffer.append(delimiter).append(args[i]);
        }
        return buffer.toString().trim();
    }

    public String[] getPaddedSlice(int index, int padding) {
        String[] slice = new String[args.length - index + padding];
        System.arraycopy(args, index, slice, padding, args.length - index);
        return slice;
    }

    public String getRawCommand() {
        return Joiner.on(' ').join(rawArgs);
    }

    /**
     * Where the command was run from, or what {@code --location} / {@code --entitylocation} names instead.
     */
    public Location getSenderLocation() throws CommandException {
        if (location != null)
            return location;
        if (hasValueFlag("entitylocation")) {
            Entity entity = EntityUtil.getEntity(UUID.fromString(getFlag("entitylocation")));
            if (entity == null)
                throw new CommandException(CommandMessages.INVALID_LOCATION);
            return location = Location.of(entity);
        }
        if (hasValueFlag("location"))
            return location = parseLocation(getFlag("location"));
        return location = sourceLocation();
    }

    /** The block the sender is looking at, or its own position when it cannot look anywhere. */
    public Location getSenderTargetBlockLocation() throws CommandException {
        if (location != null)
            return location;
        if (hasValueFlag("location"))
            return location = parseLocation(getFlag("location"));
        Location targeted = targetBlockLocation();
        return location = targeted != null ? targeted : sourceLocation();
    }

    public String[] getSlice(int index) {
        String[] slice = new String[args.length - index - 1];
        System.arraycopy(args, index + 1, slice, 0, args.length - index - 1);
        return slice;
    }

    public String getString(int index) {
        return args[index + 1];
    }

    public String getString(int index, String def) {
        return index + 1 < args.length ? args[index + 1] : def;
    }

    public int getTicks(int index) throws NumberFormatException {
        return parseTicks(args[index + 1]);
    }

    public Map<String, String> getValueFlags() {
        return valueFlags;
    }

    public boolean hasAnyFlags() {
        return !valueFlags.isEmpty() || !flags.isEmpty();
    }

    public boolean hasAnyValueFlag(String... strings) {
        for (String s : strings) {
            if (hasValueFlag(s))
                return true;
        }
        return false;
    }

    public boolean hasFlag(char ch) {
        return flags.contains(ch);
    }

    public boolean hasValueFlag(String ch) {
        return valueFlags.containsKey(ch);
    }

    public int length() {
        return args.length;
    }

    public boolean matches(String command) {
        return args[0].equalsIgnoreCase(command);
    }

    /**
     * @return the sender, which may be a player, a command block or the console
     */
    public CommandSourceStack getSender() {
        return sender;
    }

    /**
     * Three angles for an armour stand pose. A {@code d} suffix means degrees and {@code r} radians, as upstream; the
     * result is in degrees because that is what vanilla {@link Rotations} holds.
     */
    public Rotations parseEulerAngle(String input) {
        Iterator<Double> itr = Splitter.on(',').splitToStream(input).map(s -> {
            if (s.endsWith("d"))
                return Double.parseDouble(s.substring(0, s.length() - 1));
            if (s.endsWith("r"))
                return Math.toDegrees(Double.parseDouble(s.substring(0, s.length() - 1)));
            // upstream's bare number is radians, since that is what Bukkit's EulerAngle takes
            return Math.toDegrees(Double.parseDouble(s));
        }).iterator();
        return new Rotations(itr.next().floatValue(), itr.next().floatValue(), itr.next().floatValue());
    }

    /**
     * Resolves a {@code --at}-style location: coordinates, {@code me}/{@code here}, {@code facing}, or a player name.
     */
    public Location parseLocation(String flag) throws CommandException {
        Location base = sourceLocation();
        if (LOCATION_PATTERN.asPredicate().test(flag))
            return parseLocation(base != null ? base.getWorld() : null, flag);
        if (flag.equals("me") || flag.equals("here"))
            return base;
        if (flag.equals("facing")) {
            Location targeted = targetBlockLocation();
            if (targeted == null)
                throw new CommandException(CommandMessages.MUST_BE_INGAME);
            return targeted;
        }
        ServerPlayer search = sender == null || sender.getServer() == null ? null
                : sender.getServer().getPlayerList().getPlayerByName(flag);
        if (search == null)
            throw new CommandException(CommandMessages.PLAYER_NOT_FOUND_FOR_SPAWN);
        return Location.of(search);
    }

    /**
     * Duration in ticks: a bare number, or one with a {@code s}/{@code m}/{@code h}/{@code d} suffix.
     */
    public int parseTicks(String dur) {
        return Durations.toTicks(Durations.parse(dur));
    }

    /**
     * @param baseWorld
     *            the dimension to use when the location does not name one
     */
    public static Location parseLocation(ServerLevel baseWorld, String flag) throws CommandException {
        // Denizen's l@ prefix is kept: an existing saves.yml or script may still use it
        boolean denizen = flag.startsWith("l@");
        String[] parts = Iterables.toArray(LOCATION_SPLITTER.split(flag.replaceFirst("l@", "")), String.class);
        String worldName = baseWorld != null ? baseWorld.dimension().location().toString() : "";
        double x = 0, y = 0, z = 0;
        float yaw = 0F, pitch = 0F;
        switch (parts.length) {
            case 6:
                if (denizen) {
                    worldName = parts[5].replaceFirst("w@", "");
                } else {
                    pitch = Float.parseFloat(parts[5]);
                }
            case 5:
                if (denizen) {
                    pitch = Float.parseFloat(parts[4]);
                } else {
                    yaw = Float.parseFloat(parts[4]);
                }
            case 4:
                if (denizen && parts.length > 4) {
                    yaw = Float.parseFloat(parts[3]);
                } else {
                    worldName = parts[3].replaceFirst("w@", "");
                }
            case 3:
                x = Double.parseDouble(parts[0]);
                y = Double.parseDouble(parts[1]);
                z = Double.parseDouble(parts[2]);
                break;
            default:
                throw new CommandException(CommandMessages.INVALID_LOCATION);
        }
        ServerLevel world = LocationPersister.resolve(worldName);
        if (world == null)
            throw new CommandException(CommandMessages.INVALID_LOCATION);
        return new Location(world, x, y, z, yaw, pitch);
    }

    public static Quaternionf parseQuaternion(String string) {
        String[] parts = string.split(",");
        return new Quaternionf(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]));
    }

    public static Vec3 parseVector(String string) {
        String[] parts = string.split(",");
        return new Vec3(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
    }

    public static Vector3f parseVector3f(String string) {
        String[] parts = string.split(",");
        return new Vector3f().set(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]));
    }

    /**
     * @return where the sender is, or null when there is no sender at all
     */
    private Location sourceLocation() {
        if (sender == null)
            return null;
        Vec3 pos = sender.getPosition();
        // getRotation is (pitch, yaw), in that order
        Vec2 rot = sender.getRotation();
        return new Location(sender.getLevel(), pos.x, pos.y, pos.z, rot.y, rot.x);
    }

    /**
     * @return the block the sender is looking at within 64 blocks, or null when the sender is not an entity or is not
     *         looking at anything
     */
    private Location targetBlockLocation() {
        if (sender == null || !(sender.getEntity() instanceof Entity entity))
            return null;
        Vec3 eye = entity.getEyePosition();
        Vec3 end = eye.add(entity.getViewVector(1.0F).scale(64));
        BlockHitResult hit = entity.level().clip(
                new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
        if (hit.getType() != HitResult.Type.BLOCK)
            return null;
        return Location.fromBlockPosCentred(sender.getLevel(), hit.getBlockPos());
    }

    private static final Pattern FLAG = Pattern.compile("^-[a-zA-Z]+$");
    private static final Pattern LOCATION_PATTERN = Pattern.compile("[,:]");
    private static final Splitter LOCATION_SPLITTER = Splitter.on(Pattern.compile("[,:]")).omitEmptyStrings();
    private static final Pattern VALUE_FLAG = Pattern.compile("^--[a-zA-Z0-9-_]+$");
}
