package net.citizensnpcs.api.trait.trait;

import java.util.UUID;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.PermissionUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Represents the owner of an NPC.
 * <p>
 * Only the sender type changes from upstream: Bukkit's {@code CommandSender}/{@code OfflinePlayer}/{@code Player}
 * trio collapses to {@link CommandSourceStack}, from which a {@link ServerPlayer} is extracted when there is one. A
 * source with no player — console, command block, rcon — owns nothing, which is what upstream's
 * {@code ConsoleCommandSender} branch amounts to.
 */
@TraitName("owner")
public class Owner extends Trait {
    @Persist
    private UUID uuid;

    public Owner() {
        super("owner");
    }

    /**
     * Gets the owner.
     *
     * @return "SERVER" or the UUID string
     */
    @Deprecated
    public String getOwner() {
        return uuid == null ? "SERVER" : uuid.toString();
    }

    /**
     * @return The owner's UUID, or <code>null</code> if the owner is the server or a UUID has not been collected for
     *         the owner.
     */
    public UUID getOwnerId() {
        return uuid;
    }

    /**
     * Gets if the given command source is the owner of an NPC.
     *
     * @param sender
     *            Sender to check
     * @return Whether the sender is the owner of an NPC
     */
    public boolean isOwnedBy(CommandSourceStack sender) {
        if (sender == null)
            return false;

        ServerPlayer player = playerOf(sender);
        if (uuid == null && player == null)
            // server-owned NPC, and the source is the console or a command block
            return true;

        return PermissionUtil.hasPermission(sender, "citizens.ignore-owner")
                || PermissionUtil.hasPermission(sender, "citizens.admin")
                || uuid != null && player != null && player.getUUID().equals(uuid);
    }

    public boolean isOwnedBy(String name) {
        return uuid == null ? "SERVER".equals(name) : uuid.toString().equalsIgnoreCase(name);
    }

    public boolean isOwnedBy(UUID other) {
        return uuid == null ? other == null : uuid.equals(other);
    }

    private static ServerPlayer playerOf(CommandSourceStack sender) {
        return sender.getEntity() instanceof ServerPlayer ? (ServerPlayer) sender.getEntity() : null;
    }

    public void setOwner(CommandSourceStack sender) {
        ServerPlayer player = sender == null ? null : playerOf(sender);
        this.uuid = player == null ? null : player.getUUID();
    }

    /**
     * Sets the owner of an NPC.
     *
     * @param owner
     *            Name of the player to set as owner of an NPC
     */
    @Deprecated
    public void setOwner(String owner) {
        setOwner(owner, null);
    }

    /**
     * Sets the owner of an NPC.
     *
     * @param owner
     *            Name of the owner
     * @param uuid
     *            UUID of the owner
     */
    @Deprecated
    public void setOwner(String owner, UUID uuid) {
        this.uuid = uuid;
    }

    public void setOwner(UUID uuid) {
        this.uuid = uuid;
    }

    @Override
    public String toString() {
        return "Owner{" + uuid + "}";
    }
}
