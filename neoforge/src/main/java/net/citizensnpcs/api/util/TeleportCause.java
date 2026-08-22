package net.citizensnpcs.api.util;

/**
 * Why an NPC teleport happened.
 * <p>
 * Vanilla Minecraft has no equivalent of Bukkit's {@code PlayerTeleportEvent.TeleportCause}, so the enum is declared
 * here. The constant names match Bukkit's exactly, keeping any persisted value readable across the port.
 */
public enum TeleportCause {
    CHORUS_FRUIT,
    COMMAND,
    DISMOUNT,
    END_GATEWAY,
    END_PORTAL,
    ENDER_PEARL,
    EXIT_BED,
    NETHER_PORTAL,
    PLUGIN,
    SPECTATE,
    UNKNOWN;
}
