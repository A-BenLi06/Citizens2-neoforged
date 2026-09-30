package net.yuuniverse.interactions;

/** Legacy player-input entry modes. Proximity entry does not use a click mode. */
public enum ConversationStartClick {
    RIGHT_CLICK, SHIFT_RIGHT_CLICK, ALL_RIGHT_CLICK, SWAP_HAND_CLICK;

    public boolean permits(boolean sneaking) {
        return switch (this) {
            case RIGHT_CLICK -> !sneaking;
            case SHIFT_RIGHT_CLICK -> sneaking;
            case ALL_RIGHT_CLICK -> true;
            case SWAP_HAND_CLICK -> false;
        };
    }
}
