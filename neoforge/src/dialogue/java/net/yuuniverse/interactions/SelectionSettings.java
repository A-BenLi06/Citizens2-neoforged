package net.yuuniverse.interactions;

/** Legacy selection controls; the repeat delay is defined by the original scheduler. */
public record SelectionSettings(boolean enabled, Mode mode, boolean wrap) {
    public enum Mode { MOVE, SCROLL }
    public static final SelectionSettings DEFAULT = new SelectionSettings(true, Mode.MOVE, true);
    public static final long REPEAT_DELAY_MILLIS = 200;
}
