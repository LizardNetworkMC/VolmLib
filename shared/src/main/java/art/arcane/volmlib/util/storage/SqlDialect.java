package art.arcane.volmlib.util.storage;

public enum SqlDialect {
    SQLITE("\"", "TEXT", "TEXT", "INTEGER", "INTEGER PRIMARY KEY AUTOINCREMENT", ""),
    MYSQL("`", "VARCHAR(128)", "MEDIUMTEXT", "BIGINT", "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY",
            " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");

    private final String quote;
    private final String idType;
    private final String documentType;
    private final String numberType;
    private final String sequenceType;
    private final String tableOptions;

    SqlDialect(String quote, String idType, String documentType, String numberType, String sequenceType, String tableOptions) {
        this.quote = quote;
        this.idType = idType;
        this.documentType = documentType;
        this.numberType = numberType;
        this.sequenceType = sequenceType;
        this.tableOptions = tableOptions;
    }

    public String quote(String identifier) {
        return quote + identifier + quote;
    }

    String createDocuments(String table) {
        return "CREATE TABLE IF NOT EXISTS " + quote(table) + " (id " + idType + " NOT NULL PRIMARY KEY, revision " + numberType
                + " NOT NULL, document " + documentType + " NOT NULL)" + tableOptions;
    }

    String createChanges(String table) {
        String collectionType = this == SQLITE ? "TEXT" : "VARCHAR(64)";
        return "CREATE TABLE IF NOT EXISTS " + quote(table) + " (revision " + sequenceType + ", collection " + collectionType
                + " NOT NULL, id " + idType + " NOT NULL, server " + collectionType + " NOT NULL, time " + numberType + " NOT NULL)"
                + tableOptions;
    }
}
