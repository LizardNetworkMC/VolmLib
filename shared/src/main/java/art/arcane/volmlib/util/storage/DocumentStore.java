package art.arcane.volmlib.util.storage;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DocumentStore implements AutoCloseable {
    public static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10L);

    private final DocumentBackend backend;
    private final Logger logger;
    private final ExecutorService io;
    private final Map<String, DocumentRepository<?>> repositories = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private FlushReport closeReport;

    public DocumentStore(DocumentBackend backend, String threadName, Logger logger) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.logger = Objects.requireNonNull(logger, "logger");
        String requiredThreadName = Objects.requireNonNull(threadName, "threadName");
        io = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, requiredThreadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    public String backendName() {
        return backend.name();
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> DocumentRepository<T> repository(String collection, Class<T> type) {
        requireOpen();
        String requiredCollection = DocumentNames.requireCollection(collection);
        Class<T> requiredType = Objects.requireNonNull(type, "type");
        DocumentRepository<?> existing = repositories.get(requiredCollection);
        if (existing != null) {
            if (existing.type() != requiredType) {
                throw new IllegalStateException("Collection " + requiredCollection + " is already bound to "
                        + existing.type().getName());
            }
            return (DocumentRepository<T>) existing;
        }
        DocumentRepository<T> repository = new DocumentRepository<>(this, requiredCollection, requiredType);
        repositories.put(requiredCollection, repository);
        return repository;
    }

    public List<String> collections() {
        List<String> names = new ArrayList<>(repositories.keySet());
        Collections.sort(names);
        return List.copyOf(names);
    }

    public int dirtyCount() {
        int dirty = 0;
        for (DocumentRepository<?> repository : repositories.values()) {
            dirty += repository.dirtyCount();
        }
        return dirty;
    }

    public boolean closed() {
        return closed;
    }

    public CompletableFuture<FlushReport> flush() {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Document store is closed"));
        }
        return submit(this::flushAll);
    }

    @Override
    public void close() {
        close(DEFAULT_CLOSE_TIMEOUT);
    }

    public synchronized FlushReport close(Duration timeout) {
        if (closeReport != null) {
            return closeReport;
        }
        Duration requiredTimeout = Objects.requireNonNull(timeout, "timeout");
        closed = true;
        FlushReport report = FlushReport.EMPTY;
        CompletableFuture<FlushReport> finalFlush = CompletableFuture.supplyAsync(this::flushAll, io);
        io.shutdown();
        try {
            report = finalFlush.get(requiredTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!io.awaitTermination(requiredTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                io.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            io.shutdownNow();
        } catch (ExecutionException | TimeoutException exception) {
            logger.log(Level.SEVERE, "The final " + backend.name() + " storage flush did not complete", exception);
            io.shutdownNow();
        }
        List<String> pending = pendingIds();
        if (!pending.isEmpty()) {
            logger.severe("Storage closed with " + pending.size() + " unsaved document(s): " + String.join(", ", pending));
        }
        try {
            backend.close();
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Unable to close the " + backend.name() + " storage backend", exception);
        }
        closeReport = new FlushReport(report.written(), report.deleted(), report.failures(), pending.size());
        return closeReport;
    }

    <T> CompletableFuture<T> submit(IoTask<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    result.complete(task.run());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(new IllegalStateException("Document store is closed", exception));
        }
        return result;
    }

    void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Document store is closed");
        }
    }

    DocumentBackend backend() {
        return backend;
    }

    Logger logger() {
        return logger;
    }

    private FlushReport flushAll() {
        FlushReport report = FlushReport.EMPTY;
        for (String collection : collections()) {
            report = report.plus(repositories.get(collection).flushPending());
        }
        return report;
    }

    private List<String> pendingIds() {
        List<String> pending = new ArrayList<>();
        for (String collection : collections()) {
            for (String id : repositories.get(collection).dirtyIds()) {
                pending.add(collection + "/" + id);
            }
        }
        return List.copyOf(pending);
    }

    @FunctionalInterface
    interface IoTask<T> {
        T run() throws Exception;
    }
}
