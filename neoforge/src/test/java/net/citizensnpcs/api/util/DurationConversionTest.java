package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationConversionTest {
    @Test void tickConversionChecksBothIntegerBoundaries() {
        long maximumMillis = (long) Integer.MAX_VALUE * 50;
        long minimumMillis = (long) Integer.MIN_VALUE * 50;
        assertEquals(Integer.MAX_VALUE, Durations.toTicks(Duration.ofMillis(maximumMillis + 49)));
        assertEquals(Integer.MIN_VALUE, Durations.toTicks(Duration.ofMillis(minimumMillis - 49)));
        assertThrows(ArithmeticException.class, () -> Durations.toTicks(Duration.ofMillis(maximumMillis + 50)));
        assertThrows(ArithmeticException.class, () -> Durations.toTicks(Duration.ofMillis(minimumMillis - 50)));
        assertThrows(ArithmeticException.class, () -> Durations.toTicks(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test void secondConversionCannotWrapCooldownValues() {
        assertEquals(Integer.MAX_VALUE, Durations.toSeconds(Duration.ofSeconds(Integer.MAX_VALUE, 999999999)));
        assertEquals(Integer.MIN_VALUE, Durations.toSeconds(Duration.ofSeconds(Integer.MIN_VALUE)));
        assertThrows(ArithmeticException.class, () -> Durations.toSeconds(Duration.ofSeconds((long) Integer.MAX_VALUE + 1)));
        assertThrows(ArithmeticException.class, () -> Durations.toSeconds(Duration.ofSeconds((long) Integer.MIN_VALUE - 1)));
    }

    @Test void ordinaryValuesAndTruncationRemainCompatible() {
        assertEquals(60, Durations.toTicks(Durations.parse("3s")));
        assertEquals(40, Durations.toTicks(Durations.parse("40t")));
        assertEquals(1, Durations.toTicks(Duration.ofMillis(99)));
        assertEquals(-1, Durations.toTicks(Duration.ofMillis(-99)));
        assertEquals(0, Durations.toTicks(Duration.ofMillis(-49)));
        assertEquals(-1, Durations.toSeconds(Duration.ofMillis(-1)));
    }
}
