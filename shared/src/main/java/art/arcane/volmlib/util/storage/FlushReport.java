package art.arcane.volmlib.util.storage;

import java.util.ArrayList;
import java.util.List;

public record FlushReport(int written, int deleted, List<String> failures, int pending) {
    public static final FlushReport EMPTY = new FlushReport(0, 0, List.of(), 0);

    public FlushReport {
        failures = List.copyOf(failures);
    }

    public boolean successful() {
        return failures.isEmpty();
    }

    FlushReport plus(FlushReport other) {
        List<String> combined = new ArrayList<>(failures.size() + other.failures.size());
        combined.addAll(failures);
        combined.addAll(other.failures);
        return new FlushReport(written + other.written, deleted + other.deleted, combined, pending + other.pending);
    }
}
