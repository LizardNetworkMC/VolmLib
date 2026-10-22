package art.arcane.volmlib.util.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record MysqlEndpoint(String host, int port, String database, String username, SslMode sslMode) {
    private static final Pattern HOST_NAME = Pattern.compile("(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?");
    private static final Pattern BRACKETED_IPV6 = Pattern.compile("\\[[0-9A-Fa-f:.]{2,45}]");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_$-]{1,64}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.@$-]{0,80}");

    public MysqlEndpoint {
        host = requireHost("host", host);
        port = requirePort("port", port);
        database = requireDatabase("database", database);
        username = requireUsername("username", username);
        Objects.requireNonNull(sslMode, "sslMode");
    }

    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }

    public Map<String, String> driverProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("sslMode", sslMode.name());
        properties.put("allowLoadLocalInfile", "false");
        properties.put("allowUrlInLocalInfile", "false");
        properties.put("autoDeserialize", "false");
        properties.put("allowPublicKeyRetrieval", "false");
        properties.put("characterEncoding", "UTF-8");
        if (sslMode.verifies()) {
            properties.put("fallbackToSystemTrustStore", "true");
        }
        return Collections.unmodifiableMap(properties);
    }

    @Override
    public String toString() {
        return "MysqlEndpoint[host=(redacted), port=" + port + ", database=(redacted), username="
                + (username.isEmpty() ? "(empty)" : "(redacted)") + ", sslMode=" + sslMode.configName + "]";
    }

    public static String requireHost(String path, String value) {
        String host = Objects.requireNonNullElse(value, "").trim();
        if (!HOST_NAME.matcher(host).matches() && !BRACKETED_IPV6.matcher(host).matches()) {
            throw new IllegalArgumentException(path + " must be a host name, an IPv4 address, or an IPv6 address in brackets");
        }
        return host;
    }

    public static int requirePort(String path, long value) {
        if (value < 1L || value > 65_535L) {
            throw new IllegalArgumentException(path + " must be between 1 and 65535");
        }
        return (int) value;
    }

    public static String requireDatabase(String path, String value) {
        String database = Objects.requireNonNullElse(value, "").trim();
        if (!DATABASE.matcher(database).matches()) {
            throw new IllegalArgumentException(path + " must use 1 to 64 letters, digits, '_', '$' or '-'");
        }
        return database;
    }

    public static String requireUsername(String path, String value) {
        String username = Objects.requireNonNullElse(value, "").trim();
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(path + " must use up to 80 letters, digits, '_', '.', '@', '$' or '-'");
        }
        return username;
    }

    public enum SslMode {
        DISABLED("disabled"),
        REQUIRED("required"),
        VERIFY_CA("verify-ca"),
        VERIFY_IDENTITY("verify-identity");

        private final String configName;

        SslMode(String configName) {
            this.configName = configName;
        }

        public String configName() {
            return configName;
        }

        public boolean verifies() {
            return this == VERIFY_CA || this == VERIFY_IDENTITY;
        }

        public static List<String> configNames() {
            return List.of(DISABLED.configName, REQUIRED.configName, VERIFY_CA.configName, VERIFY_IDENTITY.configName);
        }

        public static SslMode parse(String path, String value) {
            String requested = Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT);
            for (SslMode mode : values()) {
                if (mode.configName.equals(requested)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(path + " must be one of " + String.join(", ", configNames()));
        }
    }
}
