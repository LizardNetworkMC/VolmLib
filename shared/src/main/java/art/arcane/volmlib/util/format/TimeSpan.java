package art.arcane.volmlib.util.format;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class TimeSpan {
    public static final TimeSpan PERMANENT = new TimeSpan(null);
    public static final Duration MAXIMUM = Duration.ofDays(36_500L);

    private static final char[] UNITS = {'w', 'd', 'h', 'm', 's'};
    private static final long[] UNIT_SECONDS = {604_800L, 86_400L, 3_600L, 60L, 1L};

    private final Duration duration;

    private TimeSpan(Duration duration) {
        this.duration = duration;
    }

    public static TimeSpan of(Duration duration) {
        Duration required = Objects.requireNonNull(duration, "duration");
        if (required.isNegative() || required.isZero()) {
            throw new IllegalArgumentException("A time span must be longer than zero seconds");
        }
        if (required.compareTo(MAXIMUM) > 0) {
            throw new IllegalArgumentException("A time span cannot exceed " + MAXIMUM.toDays() + " days");
        }
        if (required.getNano() != 0) {
            throw new IllegalArgumentException("A time span is measured in whole seconds");
        }
        return new TimeSpan(required);
    }

    public static TimeSpan parse(String text) {
        String input = Objects.requireNonNull(text, "text").trim().toLowerCase(Locale.ROOT);
        if (input.isEmpty()) {
            throw new IllegalArgumentException("Enter a duration such as 30d, 12h30m, or perm");
        }
        if (input.equals("perm") || input.equals("permanent")) {
            return PERMANENT;
        }
        long totalSeconds = 0L;
        int lastUnit = -1;
        int index = 0;
        while (index < input.length()) {
            char current = input.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            int digitsStart = index;
            while (index < input.length() && isAsciiDigit(input.charAt(index))) {
                index++;
            }
            if (index == digitsStart) {
                throw new IllegalArgumentException("Expected a number at position " + (digitsStart + 1) + " in " + text.trim());
            }
            if (index == input.length()) {
                throw new IllegalArgumentException("Add a unit (w, d, h, m, s) after " + input.substring(digitsStart));
            }
            int unit = unitIndex(input.charAt(index));
            if (unit < 0) {
                throw new IllegalArgumentException("Unknown duration unit '" + input.charAt(index) + "'; use w, d, h, m, or s");
            }
            if (unit <= lastUnit) {
                throw new IllegalArgumentException("Write duration units once each, largest first, as in 1d12h");
            }
            long amount = parseAmount(input.substring(digitsStart, index));
            try {
                totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(amount, UNIT_SECONDS[unit]));
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("A time span cannot exceed " + MAXIMUM.toDays() + " days", exception);
            }
            lastUnit = unit;
            index++;
        }
        return of(Duration.ofSeconds(totalSeconds));
    }

    public boolean permanent() {
        return duration == null;
    }

    public Optional<Duration> duration() {
        return Optional.ofNullable(duration);
    }

    public Optional<Instant> expiryFrom(Instant start) {
        Instant requiredStart = Objects.requireNonNull(start, "start");
        return duration == null ? Optional.empty() : Optional.of(requiredStart.plus(duration));
    }

    public String format() {
        return duration == null ? "perm" : format(duration);
    }

    public static String format(Duration duration) {
        long remaining = Objects.requireNonNull(duration, "duration").getSeconds();
        if (remaining <= 0L) {
            return "0s";
        }
        StringBuilder output = new StringBuilder(16);
        for (int unit = 0; unit < UNITS.length; unit++) {
            long amount = remaining / UNIT_SECONDS[unit];
            if (amount > 0L) {
                output.append(amount).append(UNITS[unit]);
                remaining -= amount * UNIT_SECONDS[unit];
            }
        }
        return output.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TimeSpan span && Objects.equals(duration, span.duration);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(duration);
    }

    @Override
    public String toString() {
        return format();
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static int unitIndex(char unit) {
        for (int index = 0; index < UNITS.length; index++) {
            if (UNITS[index] == unit) {
                return index;
            }
        }
        return -1;
    }

    private static long parseAmount(String digits) {
        if (digits.length() > 12) {
            throw new IllegalArgumentException("A time span cannot exceed " + MAXIMUM.toDays() + " days");
        }
        return Long.parseLong(digits);
    }
}
