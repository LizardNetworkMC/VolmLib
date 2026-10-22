package art.arcane.volmlib.util.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AppendLog {
    public static final String EXTENSION = ".jsonl";
    private static final int MAXIMUM_LINE_CHARACTERS = 1024 * 1024;

    private final Path directory;
    private final Logger logger;

    public AppendLog(Path directory, Logger logger) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Path directory() {
        return directory;
    }

    public synchronized void append(Instant time, String json) throws IOException {
        Instant requiredTime = Objects.requireNonNull(time, "time");
        String line = Objects.requireNonNull(json, "json");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Append log entries must be single-line JSON");
        }
        Files.createDirectories(directory);
        Path file = file(LocalDate.ofInstant(requiredTime, ZoneOffset.UTC));
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                StandardOpenOption.WRITE, StandardOpenOption.SYNC);
    }

    public synchronized List<String> read(LocalDate from, LocalDate to, Predicate<String> filter) throws IOException {
        LocalDate first = Objects.requireNonNull(from, "from");
        LocalDate last = Objects.requireNonNull(to, "to");
        Predicate<String> accepted = Objects.requireNonNull(filter, "filter");
        List<String> lines = new ArrayList<>();
        for (LocalDate day : days()) {
            if (day.isBefore(first) || day.isAfter(last)) {
                continue;
            }
            readDay(day, accepted, lines);
        }
        return Collections.unmodifiableList(lines);
    }

    public synchronized int prune(LocalDate keepFrom) throws IOException {
        LocalDate boundary = Objects.requireNonNull(keepFrom, "keepFrom");
        int removed = 0;
        for (LocalDate day : days()) {
            if (day.isBefore(boundary) && Files.deleteIfExists(file(day))) {
                removed++;
            }
        }
        return removed;
    }

    public synchronized List<LocalDate> days() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<LocalDate> days = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                try {
                    days.add(LocalDate.parse(name.substring(0, name.length() - EXTENSION.length())));
                } catch (DateTimeParseException ignored) {
                    logger.fine("Skipping non-dated append log file " + name);
                }
            }
        }
        Collections.sort(days);
        return List.copyOf(days);
    }

    private void readDay(LocalDate day, Predicate<String> filter, List<String> lines) throws IOException {
        Path file = file(day);
        int lineNumber = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (line.length() > MAXIMUM_LINE_CHARACTERS) {
                    logger.log(Level.WARNING, "Skipping oversized line " + lineNumber + " in " + file.getFileName());
                    continue;
                }
                if (filter.test(line)) {
                    lines.add(line);
                }
            }
        }
    }

    private Path file(LocalDate day) {
        return directory.resolve(day + EXTENSION);
    }
}
