package art.arcane.volmlib.util.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.LongSupplier;

public final class ChangeCursor {
    public static final int BATCH = 500;
    public static final long GAP_WAIT_MILLIS = 120_000L;
    static final int MAXIMUM_GAPS = 4096;
    static final int SEED_WINDOW = 1024;

    private final Reader reader;
    private final LongSupplier clock;
    private final TreeMap<Long, Long> gaps = new TreeMap<>();
    private long cursor;

    private ChangeCursor(Reader reader, LongSupplier clock, long cursor) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cursor = cursor;
    }

    public static ChangeCursor start(SqlChangeLog log, LongSupplier clock) throws IOException {
        SqlChangeLog source = Objects.requireNonNull(log, "log");
        return start(source::after, source.latest(), clock);
    }

    static ChangeCursor start(Reader reader, long latest, LongSupplier clock) throws IOException {
        ChangeCursor started = new ChangeCursor(reader, clock, Math.max(0L, latest - SEED_WINDOW));
        started.seed(latest);
        return started;
    }

    public synchronized long cursor() {
        return cursor;
    }

    public synchronized int unresolved() {
        return gaps.size();
    }

    public synchronized int drain(PageHandler handler) throws IOException {
        Objects.requireNonNull(handler, "handler");
        long now = clock.getAsLong();
        expire(now);
        long from = gaps.isEmpty() ? cursor : gaps.firstKey() - 1L;
        int delivered = 0;
        while (true) {
            List<SqlChangeLog.Change> rows = reader.after(from, BATCH);
            List<SqlChangeLog.Change> fresh = new ArrayList<>(rows.size());
            for (SqlChangeLog.Change row : rows) {
                if (row.revision() > cursor || gaps.containsKey(row.revision())) {
                    fresh.add(row);
                }
            }
            if (!fresh.isEmpty()) {
                handler.accept(List.copyOf(fresh));
            }
            for (SqlChangeLog.Change row : rows) {
                observe(row.revision(), now);
            }
            delivered += fresh.size();
            if (rows.size() < BATCH) {
                return delivered;
            }
            from = rows.get(rows.size() - 1).revision();
        }
    }

    private void seed(long latest) throws IOException {
        long now = clock.getAsLong();
        long from = cursor;
        while (from < latest) {
            List<SqlChangeLog.Change> rows = reader.after(from, BATCH);
            for (SqlChangeLog.Change row : rows) {
                if (row.revision() > latest) {
                    break;
                }
                observe(row.revision(), now);
            }
            if (rows.size() < BATCH || rows.get(rows.size() - 1).revision() >= latest) {
                break;
            }
            from = rows.get(rows.size() - 1).revision();
        }
        observe(latest, now);
    }

    private void observe(long revision, long now) {
        if (revision <= cursor) {
            gaps.remove(revision);
            return;
        }
        for (long missing = Math.max(cursor + 1L, revision - MAXIMUM_GAPS); missing < revision; missing++) {
            gaps.put(missing, now);
        }
        cursor = revision;
        while (gaps.size() > MAXIMUM_GAPS) {
            gaps.pollFirstEntry();
        }
    }

    private void expire(long now) {
        long cutoff = now - GAP_WAIT_MILLIS;
        while (!gaps.isEmpty()) {
            Map.Entry<Long, Long> oldest = gaps.firstEntry();
            if (oldest.getValue() > cutoff) {
                return;
            }
            gaps.pollFirstEntry();
        }
    }

    @FunctionalInterface
    public interface Reader {
        List<SqlChangeLog.Change> after(long revision, int limit) throws IOException;
    }

    @FunctionalInterface
    public interface PageHandler {
        void accept(List<SqlChangeLog.Change> changes) throws IOException;
    }
}
