package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DurationParsingTest {
    @Test void signedNumbersHonorTheirDefaultUnits() {
        assertEquals(Duration.ofSeconds(-2), Durations.parse("-2", TimeUnit.SECONDS));
        assertEquals(Duration.ofMinutes(2), Durations.parse(" +2 ", TimeUnit.MINUTES));
        assertEquals(Duration.ofMillis(-100), Durations.parse("-2"));
        assertEquals(Duration.ofMillis(100), Durations.parse("+2"));
    }

    @Test void standardIsoDurationsKeepSignsAndCaseInsensitiveSyntax() {
        assertEquals(Duration.ofMinutes(62), Durations.parse("pt1h2m"));
        assertEquals(Duration.ofHours(-26), Durations.parse("-P1DT2H"));
        assertEquals(Duration.ofDays(1), Durations.parse("+P1D"));
        assertEquals(Duration.ofDays(1), Durations.parse("p1d"));
    }

    @Test void shorthandAndWhitespaceRemainSupported() {
        assertEquals(Duration.ofSeconds(3), Durations.parse(" 3s "));
        assertEquals(Duration.ofMinutes(3), Durations.parse("3min"));
        assertEquals(Duration.ofHours(2), Durations.parse("2hr"));
        assertEquals(Duration.ofDays(-1), Durations.parse("-1d"));
        assertEquals(Duration.ofHours(26), Durations.parse("1dT2h"));
        assertEquals(Duration.ofSeconds(2), Durations.parse("40t"));
    }

    @Test void emptyAndOverflowingNumbersFailAsInputErrors() {
        for (String input : new String[]{null, "", "  "})
            assertThrows(IllegalArgumentException.class, () -> Durations.parse(input));
        assertThrows(NumberFormatException.class, () -> Durations.parse("9223372036854775808", TimeUnit.SECONDS));
        assertThrows(NumberFormatException.class, () -> Durations.parse("2147483648"));
    }
}
