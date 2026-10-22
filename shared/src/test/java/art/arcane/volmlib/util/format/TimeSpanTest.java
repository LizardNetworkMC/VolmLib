package art.arcane.volmlib.util.format;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TimeSpanTest {
    @Test
    public void parsesSingleAndCompoundUnits() {
        assertEquals(Optional.of(Duration.ofDays(30L)), TimeSpan.parse("30d").duration());
        assertEquals(Optional.of(Duration.ofHours(12L).plusMinutes(30L)), TimeSpan.parse("12h30m").duration());
        assertEquals(Optional.of(Duration.ofDays(9L).plusSeconds(5L)), TimeSpan.parse("1w2d5s").duration());
        assertEquals(Optional.of(Duration.ofMinutes(90L)), TimeSpan.parse(" 1H 30M ").duration());
    }

    @Test
    public void recognizesPermanentSpellings() {
        assertSame(TimeSpan.PERMANENT, TimeSpan.parse("perm"));
        assertSame(TimeSpan.PERMANENT, TimeSpan.parse("Permanent"));
        assertTrue(TimeSpan.PERMANENT.permanent());
        assertEquals(Optional.empty(), TimeSpan.PERMANENT.expiryFrom(Instant.EPOCH));
        assertEquals("perm", TimeSpan.PERMANENT.format());
    }

    @Test
    public void computesExpiryFromStart() {
        Instant start = Instant.parse("2026-10-16T12:00:00Z");
        assertEquals(Optional.of(Instant.parse("2026-10-17T12:00:00Z")), TimeSpan.parse("1d").expiryFrom(start));
    }

    @Test
    public void formatsCanonicallyAndRoundTrips() {
        assertEquals("12h30m", TimeSpan.parse("750m").format());
        assertEquals("1w1d", TimeSpan.parse("8d").format());
        assertEquals("1d1s", TimeSpan.format(Duration.ofSeconds(86_401L)));
        assertEquals("0s", TimeSpan.format(Duration.ZERO));
        for (String input : new String[]{"1s", "59m", "3w6d23h59m59s", "36500d"}) {
            TimeSpan parsed = TimeSpan.parse(input);
            assertEquals(parsed, TimeSpan.parse(parsed.format()));
        }
    }

    @Test
    public void rejectsMalformedInput() {
        for (String input : new String[]{"", "   ", "d", "10", "5x", "1h1d", "1h1h", "-5m", "1.5h", "0s", "0d0h", "h5"}) {
            assertThrows(input, IllegalArgumentException.class, () -> TimeSpan.parse(input));
        }
    }

    @Test
    public void rejectsSpansBeyondTheMaximum() {
        assertThrows(IllegalArgumentException.class, () -> TimeSpan.parse("36501d"));
        assertThrows(IllegalArgumentException.class, () -> TimeSpan.parse("99999999999999999w"));
        assertThrows(IllegalArgumentException.class, () -> TimeSpan.of(Duration.ofMillis(1500L)));
        assertFalse(TimeSpan.parse("36500d").permanent());
    }
}
