package art.arcane.volmlib.util.storage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ChangeRetries {
    public static final int MAXIMUM_ENTRIES = 256;
    public static final int MAXIMUM_ATTEMPTS = 6;
    static final long FIRST_DELAY_MILLIS = 5_000L;
    static final long MAXIMUM_DELAY_MILLIS = 300_000L;

    private final Map<Key, Pending> pending = new LinkedHashMap<>();
    private long dropped;

    public synchronized Outcome failed(String collection, String id, long now) {
        Key key = new Key(collection, id);
        Pending current = pending.remove(key);
        int attempts = current == null ? 1 : current.attempts() + 1;
        if (attempts >= MAXIMUM_ATTEMPTS) {
            return Outcome.GAVE_UP;
        }
        pending.put(key, new Pending(attempts, now + delay(attempts)));
        while (pending.size() > MAXIMUM_ENTRIES) {
            Iterator<Key> oldest = pending.keySet().iterator();
            oldest.next();
            oldest.remove();
            dropped++;
        }
        return current == null ? Outcome.FIRST : Outcome.RETRYING;
    }

    public synchronized void succeeded(String collection, String id) {
        pending.remove(new Key(collection, id));
    }

    public synchronized List<Key> due(long now) {
        List<Key> ready = new ArrayList<>();
        for (Map.Entry<Key, Pending> entry : pending.entrySet()) {
            if (entry.getValue().nextAttempt() <= now) {
                ready.add(entry.getKey());
            }
        }
        return ready;
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized long dropped() {
        return dropped;
    }

    private static long delay(int attempts) {
        return Math.min(MAXIMUM_DELAY_MILLIS, FIRST_DELAY_MILLIS << Math.min(16, attempts - 1));
    }

    public enum Outcome {
        FIRST,
        RETRYING,
        GAVE_UP
    }

    public record Key(String collection, String id) {
        public Key {
            Objects.requireNonNull(collection, "collection");
            Objects.requireNonNull(id, "id");
        }
    }

    private record Pending(int attempts, long nextAttempt) {
    }
}
