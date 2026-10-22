package art.arcane.volmlib.util.storage;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public final class SqlDocumentBackend implements DocumentBackend {
    public static final String CHANGES = "changes";
    private static final Pattern TABLE_PREFIX = Pattern.compile("[a-z0-9_]{0,32}");
    private static final int MAXIMUM_DOCUMENT_BYTES = 16 * 1024 * 1024 - 1;

    private final Options options;
    private final SqlChangeLog changeLog;
    private final Set<String> createdTables = ConcurrentHashMap.newKeySet();

    public SqlDocumentBackend(Options options) {
        this.options = Objects.requireNonNull(options, "options");
        this.changeLog = new SqlChangeLog(options.dataSource(), options.dialect(), options.tablePrefix() + CHANGES);
    }

    @Override
    public String name() {
        return options.name();
    }

    public SqlDialect dialect() {
        return options.dialect();
    }

    public SqlChangeLog changeLog() {
        return changeLog;
    }

    @Override
    public Optional<StoredDocument> read(String collection, String id) throws IOException {
        String table = table(collection);
        try (Connection connection = options.dataSource().getConnection()) {
            ensure(connection, table, collection);
            return select(connection, table, DocumentNames.requireId(id));
        } catch (SQLException exception) {
            throw failure("read", collection, id, exception);
        }
    }

    @Override
    public List<StoredDocument> readAll(String collection) throws IOException {
        String table = table(collection);
        try (Connection connection = options.dataSource().getConnection()) {
            ensure(connection, table, collection);
            try (PreparedStatement statement = connection.prepareStatement("SELECT id, revision, document FROM "
                    + options.dialect().quote(table) + " ORDER BY id");
                 ResultSet rows = statement.executeQuery()) {
                List<StoredDocument> documents = new ArrayList<>();
                while (rows.next()) {
                    documents.add(new StoredDocument(rows.getString(1), rows.getLong(2), rows.getString(3)));
                }
                return List.copyOf(documents);
            }
        } catch (SQLException exception) {
            throw failure("read every document of", collection, "*", exception);
        }
    }

    @Override
    public StoredDocument write(String collection, String id, String json, long expectedRevision) throws IOException {
        String table = table(collection);
        String requiredId = DocumentNames.requireId(id);
        Objects.requireNonNull(json, "json");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_DOCUMENT_BYTES) {
            throw new IOException("Document " + collection + "/" + id + " is larger than 16 MiB");
        }
        long revision = expectedRevision + 1L;
        try (Connection connection = options.dataSource().getConnection()) {
            ensure(connection, table, collection);
            return transaction(connection, () -> {
                if (expectedRevision == 0L) {
                    insert(connection, table, collection, requiredId, json);
                } else {
                    update(connection, table, collection, requiredId, json, expectedRevision);
                }
                appendChange(connection, collection, requiredId);
                return new StoredDocument(requiredId, revision, json);
            });
        } catch (SQLException exception) {
            throw failure("write", collection, id, exception);
        }
    }

    @Override
    public boolean delete(String collection, String id, long expectedRevision) throws IOException {
        String table = table(collection);
        String requiredId = DocumentNames.requireId(id);
        try (Connection connection = options.dataSource().getConnection()) {
            ensure(connection, table, collection);
            return transaction(connection, () -> {
                int removed;
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + options.dialect().quote(table)
                        + " WHERE id = ? AND revision = ?")) {
                    statement.setString(1, requiredId);
                    statement.setLong(2, expectedRevision);
                    removed = statement.executeUpdate();
                }
                if (removed == 0) {
                    Optional<StoredDocument> actual = select(connection, table, requiredId);
                    if (actual.isEmpty()) {
                        return false;
                    }
                    throw new RevisionConflictException(collection, requiredId, expectedRevision, actual.get().revision());
                }
                appendChange(connection, collection, requiredId);
                return true;
            });
        } catch (SQLException exception) {
            throw failure("delete", collection, id, exception);
        }
    }

    @Override
    public void close() throws IOException {
        if (options.dataSource() instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                throw new IOException("Unable to close the " + options.name() + " connection pool", exception);
            }
        }
    }

    private void insert(Connection connection, String table, String collection, String id, String json) throws SQLException,
            RevisionConflictException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + options.dialect().quote(table)
                + " (id, revision, document) VALUES (?, 1, ?)")) {
            statement.setString(1, id);
            statement.setString(2, json);
            statement.executeUpdate();
        } catch (SQLException exception) {
            Optional<StoredDocument> actual = select(connection, table, id);
            if (actual.isPresent()) {
                throw new RevisionConflictException(collection, id, 0L, actual.get().revision());
            }
            throw exception;
        }
    }

    private void update(Connection connection, String table, String collection, String id, String json, long expected)
            throws SQLException, RevisionConflictException {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + options.dialect().quote(table)
                + " SET revision = ?, document = ? WHERE id = ? AND revision = ?")) {
            statement.setLong(1, expected + 1L);
            statement.setString(2, json);
            statement.setString(3, id);
            statement.setLong(4, expected);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            long actual = select(connection, table, id).map(StoredDocument::revision).orElse(0L);
            throw new RevisionConflictException(collection, id, expected, actual);
        }
    }

    private void appendChange(Connection connection, String collection, String id) throws SQLException {
        if (options.changeOrigin().isEmpty()) {
            return;
        }
        String table = options.tablePrefix() + CHANGES;
        ensureChanges(connection, table);
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + options.dialect().quote(table)
                + " (collection, id, server, time) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, collection);
            statement.setString(2, id);
            statement.setString(3, options.changeOrigin().get());
            statement.setLong(4, options.clock().getAsLong());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    changeLog.recordOwn(keys.getLong(1));
                }
            }
        }
    }

    private Optional<StoredDocument> select(Connection connection, String table, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT revision, document FROM " + options.dialect().quote(table)
                + " WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new StoredDocument(id, rows.getLong(1), rows.getString(2))) : Optional.empty();
            }
        }
    }

    private void ensure(Connection connection, String table, String collection) throws SQLException {
        if (options.changeOrigin().isPresent() && collection.equals(CHANGES)) {
            throw new IllegalArgumentException("The collection name " + CHANGES + " is reserved for the change log");
        }
        if (createdTables.contains(table)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(options.dialect().createDocuments(table));
        }
        createdTables.add(table);
    }

    private void ensureChanges(Connection connection, String table) throws SQLException {
        if (createdTables.contains(table)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(options.dialect().createChanges(table));
        }
        createdTables.add(table);
    }

    private <T> T transaction(Connection connection, Work<T> work) throws SQLException, IOException {
        boolean automatic = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | IOException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(automatic);
        }
    }

    private String table(String collection) {
        return options.tablePrefix() + DocumentNames.requireCollection(collection);
    }

    private IOException failure(String action, String collection, String id, SQLException exception) {
        return new IOException("Unable to " + action + " " + collection + "/" + id + " in the " + options.name() + " backend: "
                + exception.getMessage(), exception);
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws SQLException, IOException;
    }

    public record Options(String name, DataSource dataSource, SqlDialect dialect, String tablePrefix, Optional<String> changeOrigin,
                          LongSupplier clock) {
        public Options {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(dataSource, "dataSource");
            Objects.requireNonNull(dialect, "dialect");
            Objects.requireNonNull(tablePrefix, "tablePrefix");
            if (!TABLE_PREFIX.matcher(tablePrefix).matches()) {
                throw new IllegalArgumentException("Table prefixes use up to 32 lowercase letters, digits, or '_': " + tablePrefix);
            }
            Objects.requireNonNull(changeOrigin, "changeOrigin");
            Objects.requireNonNull(clock, "clock");
        }
    }
}
