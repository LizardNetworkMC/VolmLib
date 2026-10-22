package art.arcane.volmlib.util.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DocumentStoreTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void coalescesRepeatedSavesIntoOneWritePerFlush() throws Exception {
        CountingBackend backend = new CountingBackend(new JsonDocumentBackend(root(), logger()));
        try (DocumentStore store = new DocumentStore(backend, "DocumentStoreTest-io", logger())) {
            DocumentRepository<Sample> samples = store.repository("samples", Sample.class);
            samples.save("alpha", new Sample("first", 1));
            samples.save("alpha", new Sample("second", 2));
            samples.save("alpha", new Sample("third", 3));
            assertEquals(1, store.dirtyCount());

            FlushReport report = store.flush().get(5L, TimeUnit.SECONDS);

            assertEquals(1, report.written());
            assertEquals(0, report.pending());
            assertEquals(1, backend.writes);
            assertEquals(0, store.dirtyCount());
            assertEquals(FlushReport.EMPTY.written(), store.flush().get(5L, TimeUnit.SECONDS).written());
        }
        DocumentStore reopened = new DocumentStore(new JsonDocumentBackend(root(), logger()), "DocumentStoreTest-io", logger());
        assertEquals(Optional.of(new Sample("third", 3)),
                reopened.repository("samples", Sample.class).load("alpha").get(5L, TimeUnit.SECONDS));
        reopened.close();
    }

    @Test
    public void servesCachedReadsAndReadsTheBackendOnFirstAccess() throws Exception {
        JsonDocumentBackend json = new JsonDocumentBackend(root(), logger());
        json.write("samples", "beta", "{\"name\":\"stored\",\"weight\":7}", 0L);
        CountingBackend backend = new CountingBackend(json);
        try (DocumentStore store = new DocumentStore(backend, "DocumentStoreTest-io", logger())) {
            DocumentRepository<Sample> samples = store.repository("samples", Sample.class);
            assertEquals(Optional.empty(), samples.cached("beta"));

            assertEquals(Optional.of(new Sample("stored", 7)), samples.load("beta").get(5L, TimeUnit.SECONDS));
            assertEquals(Optional.of(new Sample("stored", 7)), samples.load("beta").get(5L, TimeUnit.SECONDS));
            assertEquals(Optional.empty(), samples.load("missing").get(5L, TimeUnit.SECONDS));

            assertEquals(2, backend.reads);
            assertEquals(Optional.of(new Sample("stored", 7)), samples.cached("beta"));
        }
    }

    @Test
    public void updatesExistingDocumentsWithTheirStoredRevision() throws Exception {
        JsonDocumentBackend json = new JsonDocumentBackend(root(), logger());
        json.write("samples", "gamma", "{\"name\":\"old\",\"weight\":1}", 0L);
        json.write("samples", "gamma", "{\"name\":\"older\",\"weight\":2}", 1L);
        try (DocumentStore store = new DocumentStore(json, "DocumentStoreTest-io", logger())) {
            DocumentRepository<Sample> samples = store.repository("samples", Sample.class);
            samples.save("gamma", new Sample("blind", 3));
            store.flush().get(5L, TimeUnit.SECONDS);
            samples.save("gamma", new Sample("again", 4));

            FlushReport report = store.flush().get(5L, TimeUnit.SECONDS);

            assertTrue(report.successful());
            assertEquals(4L, json.read("samples", "gamma").orElseThrow().revision());
        }
    }

    @Test
    public void deletesAndListsWithPendingChangesApplied() throws Exception {
        JsonDocumentBackend json = new JsonDocumentBackend(root(), logger());
        json.write("samples", "one", "{\"name\":\"one\",\"weight\":1}", 0L);
        json.write("samples", "two", "{\"name\":\"two\",\"weight\":2}", 0L);
        try (DocumentStore store = new DocumentStore(json, "DocumentStoreTest-io", logger())) {
            DocumentRepository<Sample> samples = store.repository("samples", Sample.class);
            samples.delete("one");
            samples.save("three", new Sample("three", 3));

            Map<String, Sample> all = samples.all().get(5L, TimeUnit.SECONDS);
            assertEquals(List.of("three", "two"), new ArrayList<>(all.keySet()));

            FlushReport report = store.flush().get(5L, TimeUnit.SECONDS);
            assertEquals(1, report.deleted());
            assertEquals(1, report.written());
            assertEquals(Optional.empty(), json.read("samples", "one"));
        }
    }

    @Test
    public void keepsFailedWritesQueuedAndReportsThem() throws Exception {
        JsonDocumentBackend json = new JsonDocumentBackend(root(), logger());
        try (DocumentStore store = new DocumentStore(json, "DocumentStoreTest-io", logger())) {
            DocumentRepository<Sample> samples = store.repository("samples", Sample.class);
            samples.save("delta", new Sample("first", 1));
            store.flush().get(5L, TimeUnit.SECONDS);
            json.write("samples", "delta", "{\"name\":\"elsewhere\",\"weight\":9}", 1L);
            samples.save("delta", new Sample("mine", 2));

            FlushReport report = store.flush().get(5L, TimeUnit.SECONDS);

            assertFalse(report.successful());
            assertEquals(1, report.pending());
            assertEquals(List.of("delta"), samples.dirtyIds());
            assertFalse(samples.evict("delta"));
        }
    }

    @Test
    public void closeFlushesPendingWritesAndRejectsLaterSaves() throws Exception {
        DocumentStore store = new DocumentStore(new JsonDocumentBackend(root(), logger()), "DocumentStoreTest-io", logger());
        DocumentRepository<Sample> samples = store.repository("samples", Sample.class);
        samples.save("epsilon", new Sample("final", 5));

        FlushReport report = store.close(Duration.ofSeconds(5L));

        assertEquals(1, report.written());
        assertEquals(0, report.pending());
        assertSame(report, store.close(Duration.ofSeconds(5L)));
        assertTrue(Files.readString(root().resolve("samples/epsilon.json"), StandardCharsets.UTF_8).contains("\"final\""));
        assertThrows(IllegalStateException.class, () -> samples.save("epsilon", new Sample("late", 6)));
        assertTrue(store.flush().isCompletedExceptionally());
    }

    @Test
    public void bindsEachCollectionToOneType() {
        try (DocumentStore store = new DocumentStore(new JsonDocumentBackend(root(), logger()), "DocumentStoreTest-io", logger())) {
            DocumentRepository<Sample> samples = store.repository("samples", Sample.class);
            assertSame(samples, store.repository("samples", Sample.class));
            assertThrows(IllegalStateException.class, () -> store.repository("samples", String.class));
            assertEquals(List.of("samples"), store.collections());
            assertEquals("json", store.backendName());
        }
    }

    private Path root() {
        return temporaryFolder.getRoot().toPath().resolve("storage");
    }

    private static Logger logger() {
        return Logger.getLogger("DocumentStoreTest");
    }

    public record Sample(String name, int weight) {
    }

    private static final class CountingBackend implements DocumentBackend {
        private final DocumentBackend delegate;
        private int reads;
        private int writes;

        private CountingBackend(DocumentBackend delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public Optional<StoredDocument> read(String collection, String id) throws IOException {
            reads++;
            return delegate.read(collection, id);
        }

        @Override
        public List<StoredDocument> readAll(String collection) throws IOException {
            return delegate.readAll(collection);
        }

        @Override
        public StoredDocument write(String collection, String id, String json, long expectedRevision) throws IOException {
            writes++;
            return delegate.write(collection, id, json, expectedRevision);
        }

        @Override
        public boolean delete(String collection, String id, long expectedRevision) throws IOException {
            return delegate.delete(collection, id, expectedRevision);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
