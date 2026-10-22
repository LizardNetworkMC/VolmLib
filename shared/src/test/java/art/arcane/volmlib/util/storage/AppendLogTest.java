package art.arcane.volmlib.util.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AppendLogTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void appendsOneLinePerEntryIntoUtcDailyFiles() throws Exception {
        AppendLog log = log();

        log.append(Instant.parse("2026-10-16T23:59:59Z"), "{\"n\":1}");
        log.append(Instant.parse("2026-10-17T00:00:01Z"), "{\"n\":2}");
        log.append(Instant.parse("2026-10-17T05:00:00Z"), "{\"n\":3}");

        assertEquals(List.of(LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-17")), log.days());
        assertEquals("{\"n\":2}\n{\"n\":3}\n", Files.readString(root().resolve("2026-10-17.jsonl"), StandardCharsets.UTF_8));
    }

    @Test
    public void readsAnInclusiveDayRangeWithAFilter() throws Exception {
        AppendLog log = log();
        log.append(Instant.parse("2026-10-14T10:00:00Z"), "{\"target\":\"a\"}");
        log.append(Instant.parse("2026-10-15T10:00:00Z"), "{\"target\":\"b\"}");
        log.append(Instant.parse("2026-10-16T10:00:00Z"), "{\"target\":\"a\"}");

        List<String> lines = log.read(LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-16"), line -> line.contains("\"a\""));

        assertEquals(List.of("{\"target\":\"a\"}"), lines);
        assertEquals(3, log.read(LocalDate.MIN, LocalDate.MAX, line -> true).size());
    }

    @Test
    public void prunesWholeDaysBeforeTheBoundaryAndIgnoresStrayFiles() throws Exception {
        AppendLog log = log();
        log.append(Instant.parse("2026-07-01T00:00:00Z"), "{}");
        log.append(Instant.parse("2026-10-16T00:00:00Z"), "{}");
        Files.writeString(root().resolve("notes.jsonl"), "{}", StandardCharsets.UTF_8);

        assertEquals(1, log.prune(LocalDate.parse("2026-10-01")));

        assertEquals(List.of(LocalDate.parse("2026-10-16")), log.days());
        assertTrue(Files.exists(root().resolve("notes.jsonl")));
    }

    @Test
    public void rejectsMultiLineEntries() {
        assertThrows(IllegalArgumentException.class, () -> log().append(Instant.EPOCH, "{\n}"));
    }

    private AppendLog log() {
        return new AppendLog(root(), Logger.getLogger("AppendLogTest"));
    }

    private Path root() {
        return temporaryFolder.getRoot().toPath().resolve("audit");
    }
}
