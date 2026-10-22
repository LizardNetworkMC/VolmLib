package art.arcane.volmlib.util.storage;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface DocumentBackend extends AutoCloseable {
    String name();

    Optional<StoredDocument> read(String collection, String id) throws IOException;

    List<StoredDocument> readAll(String collection) throws IOException;

    StoredDocument write(String collection, String id, String json, long expectedRevision) throws IOException;

    boolean delete(String collection, String id, long expectedRevision) throws IOException;

    @Override
    void close() throws IOException;
}
