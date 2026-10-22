package art.arcane.volmlib.util.storage;

import art.arcane.volmlib.util.config.ConfigJson;
import art.arcane.volmlib.util.io.AtomicFileIO;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JsonDocumentBackend implements DocumentBackend {
    public static final String NAME = "json";
    public static final String DOCUMENT_EXTENSION = ".json";
    public static final String LAST_GOOD_SUFFIX = ".last-good";
    static final int MAXIMUM_DOCUMENT_BYTES = 16 * 1024 * 1024;
    private static final TypeAdapter<JsonElement> STRICT_ELEMENT = new Gson().getAdapter(JsonElement.class);

    private final Path root;
    private final Logger logger;
    private final Set<Path> skippedFiles = ConcurrentHashMap.newKeySet();

    public JsonDocumentBackend(Path root, Logger logger) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String name() {
        return NAME;
    }

    public Path root() {
        return root;
    }

    @Override
    public synchronized Optional<StoredDocument> read(String collection, String id) throws IOException {
        Path primary = documentFile(collection, id);
        if (!Files.exists(primary, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(readRecovering(collection, id, primary));
    }

    @Override
    public synchronized List<StoredDocument> readAll(String collection) throws IOException {
        Path directory = root.resolve(DocumentNames.requireCollection(collection));
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<StoredDocument> documents = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*" + DOCUMENT_EXTENSION)) {
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                if (fileName.startsWith(".") || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String id = fileName.substring(0, fileName.length() - DOCUMENT_EXTENSION.length());
                if (!validId(id)) {
                    skip(collection, entry);
                    continue;
                }
                documents.add(readRecovering(collection, id, entry));
            }
        }
        documents.sort((left, right) -> left.id().compareTo(right.id()));
        return List.copyOf(documents);
    }

    @Override
    public synchronized StoredDocument write(String collection, String id, String json, long expectedRevision)
            throws IOException {
        Path primary = documentFile(collection, id);
        JsonElement document = parseStrict(Objects.requireNonNull(json, "json"), primary);
        String currentContent = null;
        long actualRevision = 0L;
        if (Files.exists(primary, LinkOption.NOFOLLOW_LINKS)) {
            try {
                currentContent = readBounded(primary);
                actualRevision = decode(collection, id, currentContent, primary).revision();
            } catch (IOException failure) {
                currentContent = null;
                actualRevision = readRecovering(collection, id, primary).revision();
            }
        }
        if (actualRevision != expectedRevision) {
            throw new RevisionConflictException(collection, id, expectedRevision, actualRevision);
        }
        long nextRevision = expectedRevision + 1L;
        JsonObject envelope = new JsonObject();
        envelope.add("id", new JsonPrimitive(id));
        envelope.add("revision", new JsonPrimitive(nextRevision));
        envelope.add("document", document);
        if (currentContent != null) {
            AtomicFileIO.writeString(lastGoodFile(primary), currentContent);
        }
        AtomicFileIO.writeString(primary, ConfigJson.PRETTY.toJson(envelope) + "\n");
        return new StoredDocument(id, nextRevision, ConfigJson.PRETTY.toJson(document));
    }

    @Override
    public synchronized boolean delete(String collection, String id, long expectedRevision) throws IOException {
        Path primary = documentFile(collection, id);
        if (!Files.exists(primary, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        long actualRevision = readRecovering(collection, id, primary).revision();
        if (actualRevision != expectedRevision) {
            throw new RevisionConflictException(collection, id, expectedRevision, actualRevision);
        }
        Files.deleteIfExists(lastGoodFile(primary));
        Files.deleteIfExists(primary);
        return true;
    }

    @Override
    public void close() {
    }

    Path documentFile(String collection, String id) {
        Path directory = root.resolve(DocumentNames.requireCollection(collection));
        Path file = directory.resolve(DocumentNames.requireId(id) + DOCUMENT_EXTENSION).normalize();
        if (!file.getParent().equals(directory)) {
            throw new IllegalArgumentException("Document path escapes its collection: " + id);
        }
        return file;
    }

    private static boolean validId(String id) {
        try {
            DocumentNames.requireId(id);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void skip(String collection, Path file) {
        if (skippedFiles.add(file)) {
            logger.warning("Skipped " + file + " while reading " + collection + ": the file name is not a valid document id;"
                    + " rename or remove it");
        }
    }

    static Path lastGoodFile(Path primary) {
        return primary.resolveSibling(primary.getFileName() + LAST_GOOD_SUFFIX);
    }

    private StoredDocument readRecovering(String collection, String id, Path primary) throws IOException {
        try {
            return decode(collection, id, readBounded(primary), primary);
        } catch (IOException primaryFailure) {
            Path lastGood = lastGoodFile(primary);
            if (!Files.isRegularFile(lastGood, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unreadable document " + collection + "/" + id
                        + " and no last-good copy exists", primaryFailure);
            }
            try {
                StoredDocument recovered = decode(collection, id, readBounded(lastGood), lastGood);
                logger.log(Level.WARNING, "Document " + collection + "/" + id + " is unreadable; using its last-good copy at revision "
                        + recovered.revision(), primaryFailure);
                return recovered;
            } catch (IOException lastGoodFailure) {
                lastGoodFailure.addSuppressed(primaryFailure);
                throw new IOException("Document " + collection + "/" + id + " and its last-good copy are both unreadable",
                        lastGoodFailure);
            }
        }
    }

    private static StoredDocument decode(String collection, String id, String content, Path source) throws IOException {
        JsonElement parsed = parseStrict(content, source);
        if (!parsed.isJsonObject()) {
            throw new IOException("Document file is not a JSON object: " + source);
        }
        JsonObject envelope = parsed.getAsJsonObject();
        JsonElement storedId = envelope.get("id");
        JsonElement revision = envelope.get("revision");
        JsonElement document = envelope.get("document");
        if (storedId == null || !storedId.isJsonPrimitive() || !id.equals(storedId.getAsString())) {
            throw new IOException("Document file " + source + " does not belong to " + collection + "/" + id);
        }
        if (revision == null || !revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isNumber()
                || document == null) {
            throw new IOException("Document file is missing its revision or document: " + source);
        }
        long revisionValue = revision.getAsLong();
        if (revisionValue < 1L) {
            throw new IOException("Document file has a non-positive revision: " + source);
        }
        return new StoredDocument(id, revisionValue, ConfigJson.PRETTY.toJson(document));
    }

    private static JsonElement parseStrict(String content, Path source) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(content))) {
            reader.setLenient(false);
            JsonElement element = STRICT_ELEMENT.read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Trailing content after JSON value in " + source);
            }
            return element;
        } catch (JsonParseException | IllegalStateException | NumberFormatException failure) {
            throw new IOException("Malformed JSON in " + source, failure);
        }
    }

    private static String readBounded(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Document path is not a regular file: " + file);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAXIMUM_DOCUMENT_BYTES + 1);
            if (bytes.length > MAXIMUM_DOCUMENT_BYTES) {
                throw new IOException("Document exceeds " + MAXIMUM_DOCUMENT_BYTES + " bytes: " + file);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
