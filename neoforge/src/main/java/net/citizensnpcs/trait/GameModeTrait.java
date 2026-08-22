package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Persists the game mode of a player-type NPC. Mostly visible through the tab list, where the mode decides whether the
 * entry is greyed out as a spectator.
 */
@TraitName("gamemodetrait")
public class GameModeTrait extends Trait {
    @Persist
    private GameType mode;

    public GameModeTrait() {
        super("gamemodetrait");
    }

    public GameType getGameMode() {
        return mode;
    }

    @Override
    public void run() {
        if (mode != null && npc.getEntity() instanceof ServerPlayer player && player.gameMode.getGameModeForPlayer() != mode) {
            player.setGameMode(mode);
        }
    }

    public void setGameMode(GameType mode) {
        this.mode = mode;
    }
}
