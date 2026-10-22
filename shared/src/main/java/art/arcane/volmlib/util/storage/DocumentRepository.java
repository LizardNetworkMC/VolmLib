package art.arcane.volmlib.util.storage;

import art.arcane.volmlib.util.config.ConfigJson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class DocumentRepository<T> {
    private final DocumentStore store;
    private final String collection;
    private final Class<T> type;
    private final Map<String, Entry<T>> entries = new HashMap<>();

    DocumentRepository(DocumentStore store, String collection, Class<T> type) {
        this.store = store;
        this.collection = DocumentNames.requireCollection(collection);
        this.type = Objects.requireNonNull(type, "type");
    }

    public String collection() {
        return collection;
    }

    public Class<T> type() {
        return type;
    }

    public CompletableFuture<Optional<T>> load(String id) {
        String requiredId = DocumentNames.requireId(id);
        synchronized (this) {
            Entry<T> entry = entries.get(requiredId);
            if (entry != null && entry.loaded) {
                return CompletableFuture.completedFuture(Optional.ofNullable(entry.value));
            }
        }
        return store.submit(() -> loadFromBackend(requiredId));
    }

    public synchronized Optional<T> cached(String id) {
        Entry<T> entry = entries.get(DocumentNames.requireId(id));
        return entry == null || !entry.loaded ? Optional.empty() : Optional.ofNullable(entry.value);
    }

    public void save(String id, T document) {
        String requiredId = DocumentNames.requireId(id);
        T requiredDocument = Objects.requireNonNull(document, "document");
        store.requireOpen();
        synchronized (this) {
            Entry<T> entry = entries.computeIfAbsent(requiredId, ignored -> new Entry<>());
            entry.value = requiredDocument;
            entry.loaded = true;
            entry.version++;
        }
    }

    public void delete(String id) {
        String requiredId = DocumentNames.requireId(id);
        store.requireOpen();
        synchronized (this) {
            Entry<T> entry = entries.computeIfAbsent(requiredId, ignored -> new Entry<>());
            entry.value = null;
            entry.loaded = true;
            entry.version++;
        }
    }

    public CompletableFuture<Optional<T>> reload(String id) {
        String requiredId = DocumentNames.requireId(id);
        return store.submit(() -> reloadFromBackend(requiredId));
    }

    public CompletableFuture<Void> writeNow(String id, T document) {
        String requiredId = DocumentNames.requireId(id);
        store.requireOpen();
        return store.submit(() -> {
            writeThrough(requiredId, document);
            return null;
        });
    }

    public CompletableFuture<Map<String, T>> all() {
        return store.submit(this::loadAllFromBackend);
    }

    public synchronized boolean evict(String id) {
        String requiredId = DocumentNames.requireId(id);
        Entry<T> entry = entries.get(requiredId);
        if (entry == null) {
            return true;
        }
        if (entry.dirty()) {
            return false;
        }
        entries.remove(requiredId);
        return true;
    }

    public synchronized int dirtyCount() {
        int dirty = 0;
        for (Entry<T> entry : entries.values()) {
            if (entry.dirty()) {
                dirty++;
            }
        }
        return dirty;
    }

    public synchronized List<String> dirtyIds() {
        List<String> dirty = new ArrayList<>();
        for (Map.Entry<String, Entry<T>> entry : entries.entrySet()) {
            if (entry.getValue().dirty()) {
                dirty.add(entry.getKey());
            }
        }
        Collections.sort(dirty);
        return List.copyOf(dirty);
    }

    public synchronized int cachedCount() {
        return entries.size();
    }

    FlushReport flushPending() {
        List<Pending<T>> batch = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<String, Entry<T>> entry : entries.entrySet()) {
                Entry<T> state = entry.getValue();
                if (state.dirty()) {
                    batch.add(new Pending<>(entry.getKey(), state.value, state.version, state.revision, state.revisionKnown));
                }
            }
        }
        int written = 0;
        int deleted = 0;
        List<String> failures = new ArrayList<>();
        for (Pending<T> pending : batch) {
            try {
                long expected = pending.revisionKnown() ? pending.revision() : backendRevision(pending.id());
                long revision;
                if (pending.value() == null) {
                    if (expected > 0L && store.backend().delete(collection, pending.id(), expected)) {
                        deleted++;
                    }
                    revision = 0L;
                } else {
                    String json = ConfigJson.PRETTY.toJson(pending.value(), type);
                    revision = store.backend().write(collection, pending.id(), json, expected).revision();
                    written++;
                }
                markFlushed(pending, revision);
            } catch (IOException | RuntimeException failure) {
                failures.add(collection + "/" + pending.id() + ": " + failure.getMessage());
                store.logger().log(Level.SEVERE, "Unable to store " + collection + "/" + pending.id()
                        + " in the " + store.backendName() + " backend; it stays queued for the next flush", failure);
            }
        }
        return new FlushReport(written, deleted, failures, dirtyCount());
    }

    private synchronized void markFlushed(Pending<T> pending, long revision) {
        Entry<T> entry = entries.get(pending.id());
        if (entry == null) {
            return;
        }
        entry.revision = revision;
        entry.revisionKnown = true;
        entry.flushedVersion = Math.max(entry.flushedVersion, pending.version());
    }

    private long backendRevision(String id) throws IOException {
        Optional<StoredDocument> stored = store.backend().read(collection, id);
        return stored.map(StoredDocument::revision).orElse(0L);
    }

    private Optional<T> loadFromBackend(String id) throws IOException {
        Optional<StoredDocument> stored = store.backend().read(collection, id);
        T decoded = stored.isPresent() ? decode(stored.get()) : null;
        synchronized (this) {
            Entry<T> entry = entries.computeIfAbsent(id, ignored -> new Entry<>());
            adopt(entry, stored.orElse(null), decoded);
            return Optional.ofNullable(entry.value);
        }
    }

    private Optional<T> reloadFromBackend(String id) throws IOException {
        Optional<StoredDocument> stored = store.backend().read(collection, id);
        T decoded = stored.isPresent() ? decode(stored.get()) : null;
        synchronized (this) {
            Entry<T> entry = entries.computeIfAbsent(id, ignored -> new Entry<>());
            if (!entry.dirty()) {
                entry.value = decoded;
                entry.loaded = true;
                entry.revision = stored.map(StoredDocument::revision).orElse(0L);
                entry.revisionKnown = true;
            }
            return Optional.ofNullable(entry.value);
        }
    }

    private void writeThrough(String id, T document) throws IOException {
        long expected;
        synchronized (this) {
            Entry<T> entry = entries.get(id);
            expected = entry != null && entry.revisionKnown ? entry.revision : -1L;
        }
        if (expected < 0L) {
            expected = backendRevision(id);
        }
        long revision = 0L;
        if (document == null) {
            if (expected > 0L) {
                store.backend().delete(collection, id, expected);
            }
        } else {
            revision = store.backend().write(collection, id, ConfigJson.PRETTY.toJson(document, type), expected).revision();
        }
        synchronized (this) {
            Entry<T> entry = entries.computeIfAbsent(id, ignored -> new Entry<>());
            entry.value = document;
            entry.loaded = true;
            entry.revision = revision;
            entry.revisionKnown = true;
            entry.version++;
            entry.flushedVersion = entry.version;
        }
    }

    private Map<String, T> loadAllFromBackend() throws IOException {
        List<StoredDocument> stored = store.backend().readAll(collection);
        Map<String, T> decoded = new HashMap<>(Math.max(16, stored.size() * 2));
        for (StoredDocument document : stored) {
            decoded.put(document.id(), decode(document));
        }
        synchronized (this) {
            for (StoredDocument document : stored) {
                Entry<T> entry = entries.computeIfAbsent(document.id(), ignored -> new Entry<>());
                adopt(entry, document, decoded.get(document.id()));
            }
            TreeMap<String, T> result = new TreeMap<>();
            for (Map.Entry<String, Entry<T>> entry : entries.entrySet()) {
                if (entry.getValue().loaded && entry.getValue().value != null) {
                    result.put(entry.getKey(), entry.getValue().value);
                }
            }
            return Collections.unmodifiableMap(result);
        }
    }

    private void adopt(Entry<T> entry, StoredDocument stored, T decoded) {
        if (!entry.loaded) {
            entry.value = decoded;
            entry.loaded = true;
        }
        if (!entry.revisionKnown) {
            entry.revision = stored == null ? 0L : stored.revision();
            entry.revisionKnown = true;
        }
    }

    private T decode(StoredDocument stored) throws IOException {
        try {
            T value = ConfigJson.NORMAL.fromJson(stored.json(), type);
            if (value == null) {
                throw new IOException("Document " + collection + "/" + stored.id() + " is empty");
            }
            return value;
        } catch (JsonParseException failure) {
            throw new IOException("Document " + collection + "/" + stored.id() + " does not match "
                    + type.getSimpleName(), failure);
        }
    }

    private static final class Entry<T> {
        private T value;
        private boolean loaded;
        private long revision;
        private boolean revisionKnown;
        private long version;
        private long flushedVersion;

        private boolean dirty() {
            return version != flushedVersion;
        }
    }

    private record Pending<T>(String id, T value, long version, long revision, boolean revisionKnown) {
    }
}
