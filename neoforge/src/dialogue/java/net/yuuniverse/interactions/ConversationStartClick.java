package net.yuuniverse.interactions;

/** Legacy right-click entry modes. Proximity entry does not use a click mode. */
public enum ConversationStartClick {
    RIGHT_CLICK, SHIFT_RIGHT_CLICK, ALL_RIGHT_CLICK;

    public boolean permits(boolean sneaking) {
        return switch (this) {
            case RIGHT_CLICK -> !sneaking;
            case SHIFT_RIGHT_CLICK -> sneaking;
            case ALL_RIGHT_CLICK -> true;
        };
    }
}
