package art.arcane.volmlib.util.storage;

import java.io.IOException;

public final class RevisionConflictException extends IOException {
    private final String collection;
    private final String id;
    private final long expectedRevision;
    private final long actualRevision;

    public RevisionConflictException(String collection, String id, long expectedRevision, long actualRevision) {
        super("Revision conflict for " + collection + "/" + id + ": expected " + expectedRevision
                + ", found " + actualRevision);
        this.collection = collection;
        this.id = id;
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    public String collection() {
        return collection;
    }

    public String id() {
        return id;
    }

    public long expectedRevision() {
        return expectedRevision;
    }

    public long actualRevision() {
        return actualRevision;
    }
}
