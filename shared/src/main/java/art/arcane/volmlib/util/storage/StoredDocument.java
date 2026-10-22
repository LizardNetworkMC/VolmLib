package art.arcane.volmlib.util.storage;

import java.util.Objects;

public record StoredDocument(String id, long revision, String json) {
    public StoredDocument {
        DocumentNames.requireId(id);
        if (revision < 1L) {
            throw new IllegalArgumentException("Document revision must be positive: " + revision);
        }
        Objects.requireNonNull(json, "json");
    }
}
