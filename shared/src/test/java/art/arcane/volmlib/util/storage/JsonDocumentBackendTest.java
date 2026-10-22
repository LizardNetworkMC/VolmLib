package art.arcane.volmlib.util.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JsonDocumentBackendTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesOneEnvelopePerDocumentAndIncrementsRevision() throws Exception {
        JsonDocumentBackend backend = backend();

        StoredDocument first = backend.write("groups", "admin", "{\"weight\":10}", 0L);
        StoredDocument second = backend.write("groups", "admin", "{\"weight\":20}", 1L);

        assertEquals(1L, first.revision());
        assertEquals(2L, second.revision());
        Path primary = root().resolve("groups/admin.json");
        JsonObject envelope = JsonParser.parseString(Files.readString(primary, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("admin", envelope.get("id").getAsString());
        assertEquals(2L, envelope.get("revision").getAsLong());
        assertEquals(20, envelope.getAsJsonObject("document").get("weight").getAsInt());
        assertEquals(20, JsonParser.parseString(backend.read("groups", "admin").orElseThrow().json())
                .getAsJsonObject().get("weight").getAsInt());
    }

    @Test
    public void keepsThePreviousSuccessfulWriteAsLastGood() throws Exception {
        JsonDocumentBackend backend = backend();
        backend.write("groups", "admin", "{\"weight\":10}", 0L);
        assertFalse(Files.exists(root().resolve("groups/admin.json.last-good")));

        backend.write("groups", "admin", "{\"weight\":20}", 1L);

        JsonObject lastGood = JsonParser.parseString(Files.readString(root().resolve("groups/admin.json.last-good"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1L, lastGood.get("revision").getAsLong());
        assertEquals(10, lastGood.getAsJsonObject("document").get("weight").getAsInt());
    }

    @Test
    public void recoversFromTheLastGoodCopyWhenThePrimaryIsCorrupt() throws Exception {
        JsonDocumentBackend backend = backend();
        backend.write("groups", "admin", "{\"weight\":10}", 0L);
        backend.write("groups", "admin", "{\"weight\":20}", 1L);
        Path primary = root().resolve("groups/admin.json");
        Files.writeString(primary, "{\"id\":\"admin\",\"revision\":3,\"document\":{\"weight\"", StandardCharsets.UTF_8);

        StoredDocument recovered = backend.read("groups", "admin").orElseThrow();

        assertEquals(1L, recovered.revision());
        assertEquals(10, JsonParser.parseString(recovered.json()).getAsJsonObject().get("weight").getAsInt());
        StoredDocument rewritten = backend.write("groups", "admin", "{\"weight\":30}", 1L);
        assertEquals(2L, rewritten.revision());
        JsonObject lastGood = JsonParser.parseString(Files.readString(root().resolve("groups/admin.json.last-good"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1L, lastGood.get("revision").getAsLong());
    }

    @Test
    public void reportsCorruptionWhenNoLastGoodCopyExists() throws Exception {
        JsonDocumentBackend backend = backend();
        backend.write("groups", "admin", "{\"weight\":10}", 0L);
        Files.writeString(root().resolve("groups/admin.json"), "not json", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> backend.read("groups", "admin"));
    }

    @Test
    public void rejectsStaleRevisions() throws Exception {
        JsonDocumentBackend backend = backend();
        backend.write("users", "player-one", "{}", 0L);

        RevisionConflictException conflict = assertThrows(RevisionConflictException.class,
                () -> backend.write("users", "player-one", "{}", 0L));

        assertEquals(0L, conflict.expectedRevision());
        assertEquals(1L, conflict.actualRevision());
        assertThrows(RevisionConflictException.class, () -> backend.delete("users", "player-one", 5L));
    }

    @Test
    public void readsWholeCollectionsSortedAndIgnoresSidecarFiles() throws Exception {
        JsonDocumentBackend backend = backend();
        backend.write("tracks", "staff", "{}", 0L);
        backend.write("tracks", "donor", "{}", 0L);
        backend.write("tracks", "donor", "{\"x\":1}", 1L);
        Files.writeString(root().resolve("tracks/.donor.json.123.tmp"), "partial", StandardCharsets.UTF_8);

        List<StoredDocument> documents = backend.readAll("tracks");

        assertEquals(List.of("donor", "staff"), documents.stream().map(StoredDocument::id).toList());
        assertEquals(List.of(), backend.readAll("missing"));
    }

    @Test
    public void deletesTheDocumentAndItsLastGoodCopy() throws Exception {
        JsonDocumentBackend backend = backend();
        backend.write("groups", "vip", "{}", 0L);
        backend.write("groups", "vip", "{}", 1L);

        assertTrue(backend.delete("groups", "vip", 2L));
        assertFalse(backend.delete("groups", "vip", 2L));

        assertEquals(Optional.empty(), backend.read("groups", "vip"));
        try (Stream<Path> remaining = Files.list(root().resolve("groups"))) {
            assertEquals(0L, remaining.count());
        }
    }

    @Test
    public void rejectsUnsafeNamesAndMalformedDocuments() {
        JsonDocumentBackend backend = backend();

        assertThrows(IllegalArgumentException.class, () -> backend.read("groups", "../config"));
        assertThrows(IllegalArgumentException.class, () -> backend.read("Groups", "admin"));
        assertThrows(IllegalArgumentException.class, () -> backend.read("groups", "Admin"));
        assertThrows(IOException.class, () -> backend.write("groups", "admin", "{\"open\":", 0L));
        assertThrows(IOException.class, () -> backend.write("groups", "admin", "{} {}", 0L));
    }

    @Test
    public void readAllSkipsStrayFilesAndWarnsOncePerFile() throws Exception {
        Logger logger = Logger.getLogger("JsonDocumentBackendTest.stray");
        logger.setUseParentHandlers(false);
        List<String> warnings = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            JsonDocumentBackend backend = new JsonDocumentBackend(root(), logger);
            backend.write("groups", "admin", "{\"weight\":10}", 0L);
            Files.writeString(root().resolve("groups/Foo.json"), "{}", StandardCharsets.UTF_8);
            Files.writeString(root().resolve("groups/a..b.json"), "{}", StandardCharsets.UTF_8);

            assertEquals(List.of("admin"), backend.readAll("groups").stream().map(StoredDocument::id).toList());
            assertEquals(List.of("admin"), backend.readAll("groups").stream().map(StoredDocument::id).toList());
            assertEquals(2, warnings.size());
            assertTrue(warnings.stream().anyMatch(message -> message.contains("Foo.json")));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    public void refusesWindowsDeviceNamesAsDocumentIds() {
        JsonDocumentBackend backend = backend();

        assertThrows(IllegalArgumentException.class, () -> backend.write("groups", "con", "{}", 0L));
        assertThrows(IllegalArgumentException.class, () -> backend.write("groups", "lpt1.old", "{}", 0L));
        assertFalse(Files.exists(root().resolve("groups")));
    }

    private JsonDocumentBackend backend() {
        return new JsonDocumentBackend(root(), Logger.getLogger("JsonDocumentBackendTest"));
    }

    private Path root() {
        return temporaryFolder.getRoot().toPath().resolve("storage");
    }
}
