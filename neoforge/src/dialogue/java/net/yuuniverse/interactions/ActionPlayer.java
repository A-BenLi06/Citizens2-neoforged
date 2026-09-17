package net.yuuniverse.interactions;

import net.minecraft.server.level.ServerPlayer;

/** Follow respawn on the same connection; never deliver an old batch to a later login with the same UUID. */
record ActionPlayer(ServerPlayer original, net.minecraft.server.network.ServerGamePacketListenerImpl connection) {
    ActionPlayer(ServerPlayer player) { this(player, player.connection); }

    ServerPlayer resolve() {
        var server = original.getServer();
        if (server == null) return null;
        var current = server.getPlayerList().getPlayer(original.getUUID());
        return current != null && !current.isRemoved() && current.connection == connection ? current : null;
    }

    boolean matches(ServerPlayer player) {
        return player.getUUID().equals(original.getUUID()) && player.connection == connection;
    }
}
