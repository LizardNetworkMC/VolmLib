package art.arcane.volmlib.util.storage;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ChangeCursorTest {
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final VisibleLog log = new VisibleLog();

    @Test
    public void aLowerRevisionCommittedLaterIsStillDelivered() throws Exception {
        ChangeCursor cursor = ChangeCursor.start(log, 0L, clock::get);
        log.commit(1L, "groups", "admin");
        log.commit(3L, "users", "a");

        assertEquals(List.of(1L, 3L), drain(cursor));
        assertEquals(3L, cursor.cursor());
        assertEquals(1, cursor.unresolved());

        log.commit(2L, "users", "revoked");
        log.commit(4L, "users", "b");

        assertEquals(List.of(2L, 4L), drain(cursor));
        assertEquals(0, cursor.unresolved());
        assertEquals(List.of(), drain(cursor));
    }

    @Test
    public void everyRevisionIsDeliveredOnceAcrossRepeatedPolls() throws Exception {
        ChangeCursor cursor = ChangeCursor.start(log, 0L, clock::get);
        log.commit(1L, "users", "a");
        log.commit(5L, "users", "b");
        List<Long> delivered = new ArrayList<>(drain(cursor));
        log.commit(3L, "users", "c");
        delivered.addAll(drain(cursor));
        delivered.addAll(drain(cursor));
        log.commit(2L, "users", "d");
        log.commit(4L, "users", "e");
        delivered.addAll(drain(cursor));
        delivered.addAll(drain(cursor));

        assertEquals(List.of(1L, 5L, 3L, 2L, 4L), delivered);
    }

    @Test
    public void unresolvedRevisionsStopBeingReadAfterTheWait() throws Exception {
        ChangeCursor cursor = ChangeCursor.start(log, 0L, clock::get);
        log.commit(1L, "users", "a");
        log.commit(3L, "users", "b");
        drain(cursor);
        log.reads.clear();
        drain(cursor);
        assertEquals(List.of(3L), log.reads);

        clock.addAndGet(ChangeCursor.GAP_WAIT_MILLIS + 1L);
        log.reads.clear();
        drain(cursor);

        assertEquals(0, cursor.unresolved());
        assertEquals(List.of(), log.reads);
    }

    @Test
    public void startMarksVisibleRowsAppliedAndWaitsForMissingOnes() throws Exception {
        log.commit(1L, "users", "a");
        log.commit(2L, "users", "b");
        log.commit(4L, "users", "c");

        ChangeCursor cursor = ChangeCursor.start(log, 4L, clock::get);

        assertEquals(4L, cursor.cursor());
        assertEquals(1, cursor.unresolved());
        assertEquals(List.of(), drain(cursor));
        log.commit(3L, "users", "late");
        assertEquals(List.of(3L), drain(cursor));
    }

    @Test
    public void aFailedPageIsDeliveredAgain() throws Exception {
        ChangeCursor cursor = ChangeCursor.start(log, 0L, clock::get);
        log.commit(1L, "users", "a");

        assertThrows(IOException.class, () -> cursor.drain(changes -> {
            throw new IOException("refresh failed");
        }));

        assertEquals(List.of(1L), drain(cursor));
    }

    @Test
    public void readsEveryPageOfALargeBacklog() throws Exception {
        ChangeCursor cursor = ChangeCursor.start(log, 0L, clock::get);
        for (long revision = 1L; revision <= ChangeCursor.BATCH * 2L + 7L; revision++) {
            log.commit(revision, "users", "u" + revision);
        }

        List<Long> delivered = drain(cursor);

        assertEquals(ChangeCursor.BATCH * 2 + 7, delivered.size());
        assertEquals(ChangeCursor.BATCH * 2L + 7L, cursor.cursor());
        assertTrue(drain(cursor).isEmpty());
    }

    private static List<Long> drain(ChangeCursor cursor) throws IOException {
        List<Long> revisions = new ArrayList<>();
        cursor.drain(changes -> {
            for (SqlChangeLog.Change change : changes) {
                revisions.add(change.revision());
            }
        });
        return revisions;
    }

    private static final class VisibleLog implements ChangeCursor.Reader {
        private final TreeMap<Long, SqlChangeLog.Change> rows = new TreeMap<>();
        private final List<Long> reads = new ArrayList<>();

        void commit(long revision, String collection, String id) {
            rows.put(revision, new SqlChangeLog.Change(revision, collection, id, "hub", revision));
        }

        @Override
        public List<SqlChangeLog.Change> after(long revision, int limit) {
            List<SqlChangeLog.Change> page = new ArrayList<>();
            for (SqlChangeLog.Change change : rows.tailMap(revision, false).values()) {
                if (page.size() == limit) {
                    break;
                }
                page.add(change);
                reads.add(change.revision());
            }
            return page;
        }
    }
}
