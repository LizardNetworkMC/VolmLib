package art.arcane.volmlib.util.storage;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SqlChangeLog {
    static final int OWN_REVISION_LIMIT = 4096;

    private final DataSource dataSource;
    private final SqlDialect dialect;
    private final String table;
    private final Set<Long> ownRevisions = new LinkedHashSet<>();
    private volatile boolean created;

    SqlChangeLog(DataSource dataSource, SqlDialect dialect, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.table = Objects.requireNonNull(table, "table");
    }

    public long latest() throws IOException {
        try (Connection connection = dataSource.getConnection()) {
            ensure(connection);
            try (PreparedStatement statement = connection.prepareStatement("SELECT MAX(revision) FROM " + dialect.quote(table));
                 ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException exception) {
            throw new IOException("Unable to read the newest change-log revision: " + exception.getMessage(), exception);
        }
    }

    public List<Change> after(long revision, int limit) throws IOException {
        try (Connection connection = dataSource.getConnection()) {
            ensure(connection);
            try (PreparedStatement statement = connection.prepareStatement("SELECT revision, collection, id, server, time FROM "
                    + dialect.quote(table) + " WHERE revision > ? ORDER BY revision LIMIT ?")) {
                statement.setLong(1, revision);
                statement.setInt(2, Math.max(1, limit));
                try (ResultSet rows = statement.executeQuery()) {
                    List<Change> changes = new ArrayList<>();
                    while (rows.next()) {
                        changes.add(new Change(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5)));
                    }
                    return List.copyOf(changes);
                }
            }
        } catch (SQLException exception) {
            throw new IOException("Unable to read the change log after revision " + revision + ": " + exception.getMessage(), exception);
        }
    }

    public int prune(long olderThanMillis) throws IOException {
        try (Connection connection = dataSource.getConnection()) {
            ensure(connection);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + dialect.quote(table) + " WHERE time < ?")) {
                statement.setLong(1, olderThanMillis);
                return statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IOException("Unable to prune the change log: " + exception.getMessage(), exception);
        }
    }

    public boolean claimOwn(long revision) {
        synchronized (ownRevisions) {
            return ownRevisions.remove(revision);
        }
    }

    void recordOwn(long revision) {
        synchronized (ownRevisions) {
            ownRevisions.add(revision);
            if (ownRevisions.size() > OWN_REVISION_LIMIT) {
                ownRevisions.remove(ownRevisions.iterator().next());
            }
        }
    }

    private void ensure(Connection connection) throws SQLException {
        if (created) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect.createChanges(table));
        }
        created = true;
    }

    public record Change(long revision, String collection, String id, String server, long time) {
        public Change {
            Objects.requireNonNull(collection, "collection");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(server, "server");
        }
    }
}
