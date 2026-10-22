package art.arcane.volmlib.util.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sqlite.SQLiteDataSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SqlDocumentBackendTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final AtomicLong clock = new AtomicLong(1_000L);

    @Test
    public void writesReadsAndChecksRevisions() throws Exception {
        try (SqlDocumentBackend backend = backend(Optional.empty())) {
            StoredDocument first = backend.write("samples", "alpha", "{\"name\":\"a\"}", 0L);
            StoredDocument second = backend.write("samples", "alpha", "{\"name\":\"b\"}", 1L);

            assertEquals(1L, first.revision());
            assertEquals(2L, second.revision());
            assertEquals("{\"name\":\"b\"}", backend.read("samples", "alpha").orElseThrow().json());
            RevisionConflictException stale = assertThrows(RevisionConflictException.class,
                    () -> backend.write("samples", "alpha", "{}", 1L));
            assertEquals(2L, stale.actualRevision());
            assertThrows(RevisionConflictException.class, () -> backend.write("samples", "alpha", "{}", 0L));
            assertThrows(RevisionConflictException.class, () -> backend.delete("samples", "alpha", 1L));
            assertTrue(backend.delete("samples", "alpha", 2L));
            assertFalse(backend.delete("samples", "alpha", 2L));
            assertTrue(backend.read("samples", "alpha").isEmpty());
        }
    }

    @Test
    public void readAllReturnsEveryDocumentInIdOrder() throws Exception {
        try (SqlDocumentBackend backend = backend(Optional.empty())) {
            backend.write("samples", "beta", "{}", 0L);
            backend.write("samples", "alpha", "{}", 0L);

            List<StoredDocument> all = backend.readAll("samples");

            assertEquals(List.of("alpha", "beta"), all.stream().map(StoredDocument::id).toList());
            assertTrue(backend.readAll("empty-collection").isEmpty());
        }
    }

    @Test
    public void writesAppendChangeRowsInTheSameTransaction() throws Exception {
        try (SqlDocumentBackend backend = backend(Optional.of("lobby"))) {
            SqlChangeLog log = backend.changeLog();
            assertEquals(0L, log.latest());
            backend.write("users", "alpha", "{}", 0L);
            clock.set(5_000L);
            backend.write("groups", "vip", "{}", 0L);
            assertThrows(RevisionConflictException.class, () -> backend.write("groups", "vip", "{}", 0L));
            backend.delete("users", "alpha", 1L);

            List<SqlChangeLog.Change> changes = log.after(0L, 100);

            assertEquals(3, changes.size());
            assertEquals("users", changes.get(0).collection());
            assertEquals("lobby", changes.get(0).server());
            assertEquals("vip", changes.get(1).id());
            assertEquals(changes.get(2).revision(), log.latest());
            assertEquals(List.of(changes.get(2)), log.after(changes.get(1).revision(), 100));
            assertEquals(1, log.prune(2_000L));
            assertEquals(2, log.after(0L, 100).size());
            assertThrows(IllegalArgumentException.class, () -> backend.read("changes", "x"));
        }
    }

    @Test
    public void changeLogKnowsWhichRowsThisBackendWrote() throws Exception {
        try (SqlDocumentBackend mine = backend(Optional.of("lobby"));
             SqlDocumentBackend other = backend(Optional.of("lobby"))) {
            mine.write("groups", "vip", "{}", 0L);
            other.write("groups", "staff", "{}", 0L);

            List<SqlChangeLog.Change> changes = mine.changeLog().after(0L, 100);

            assertEquals(2, changes.size());
            assertTrue(mine.changeLog().claimOwn(changes.get(0).revision()));
            assertFalse(mine.changeLog().claimOwn(changes.get(0).revision()));
            assertFalse(mine.changeLog().claimOwn(changes.get(1).revision()));
            assertTrue(other.changeLog().claimOwn(changes.get(1).revision()));
        }
    }

    @Test
    public void repositoryWriteThroughDetectsAnotherWriterAndRecoversAfterReload() throws Exception {
        try (DocumentStore first = new DocumentStore(backend(Optional.of("a")), "SqlTest-a", logger());
             DocumentStore second = new DocumentStore(backend(Optional.of("b")), "SqlTest-b", logger())) {
            DocumentRepository<Sample> left = first.repository("samples", Sample.class);
            DocumentRepository<Sample> right = second.repository("samples", Sample.class);
            left.writeNow("alpha", new Sample("left", 1)).get(5L, TimeUnit.SECONDS);
            right.reload("alpha").get(5L, TimeUnit.SECONDS);
            left.writeNow("alpha", new Sample("left", 2)).get(5L, TimeUnit.SECONDS);

            ExecutionException conflict = assertThrows(ExecutionException.class,
                    () -> right.writeNow("alpha", new Sample("right", 3)).get(5L, TimeUnit.SECONDS));
            assertTrue(conflict.getCause() instanceof RevisionConflictException);
            assertEquals(new Sample("left", 2), right.reload("alpha").get(5L, TimeUnit.SECONDS).orElseThrow());
            right.writeNow("alpha", new Sample("right", 3)).get(5L, TimeUnit.SECONDS);

            Map<String, Sample> all = left.all().get(5L, TimeUnit.SECONDS);
            assertEquals(new Sample("left", 2), all.get("alpha"));
            assertEquals(new Sample("right", 3), left.reload("alpha").get(5L, TimeUnit.SECONDS).orElseThrow());
            assertEquals(0, first.dirtyCount());
            right.writeNow("alpha", null).get(5L, TimeUnit.SECONDS);
            assertTrue(left.reload("alpha").get(5L, TimeUnit.SECONDS).isEmpty());
        }
    }

    private SqlDocumentBackend backend(Optional<String> origin) {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temporaryFolder.getRoot().toPath().resolve("store.db"));
        return new SqlDocumentBackend(new SqlDocumentBackend.Options("sqlite", source, SqlDialect.SQLITE, "test_", origin, clock::get));
    }

    private static Logger logger() {
        return Logger.getLogger("SqlDocumentBackendTest");
    }

    public record Sample(String name, int count) {
    }
}
