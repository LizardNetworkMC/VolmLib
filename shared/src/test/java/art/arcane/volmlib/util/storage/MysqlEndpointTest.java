package art.arcane.volmlib.util.storage;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MysqlEndpointTest {
    @Test
    public void acceptsHostNamesAndAddresses() {
        for (String host : List.of("127.0.0.1", "localhost", "db.example.com", "mysql-1.internal", "[::1]", "[2001:db8::10]",
                "[::ffff:10.0.0.1]", " db.example.com ")) {
            assertEquals(host.trim(), MysqlEndpoint.requireHost("storage.mysql.host", host));
        }
    }

    @Test
    public void rejectsHostsThatCarryUrlSyntax() {
        for (String host : List.of("", "evil.example.com/echelon?allowLoadLocalInfile=true", "a:3306", "user@host", "host,other",
                "-leading", "trailing.", "::1", "[::1]:3306", "[evil.com]", "host name", "a".repeat(254), "address=(host=x)")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> MysqlEndpoint.requireHost("storage.mysql.host", host));
            assertTrue(failure.getMessage().startsWith("storage.mysql.host must be"));
        }
    }

    @Test
    public void acceptsAndRejectsDatabaseNames() {
        for (String database : List.of("echelon", "Echelon_Net", "perm$db", "net-1", "a".repeat(64))) {
            assertEquals(database, MysqlEndpoint.requireDatabase("storage.mysql.database", database));
        }
        for (String database : List.of("", "echelon?allowLoadLocalInfile=true&autoDeserialize=true", "echelon/other", "ech elon",
                "echelon;drop", "ech%65lon", "a".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> MysqlEndpoint.requireDatabase("storage.mysql.database", database));
        }
    }

    @Test
    public void acceptsAndRejectsUsernames() {
        for (String username : List.of("", "perms", "echelon.app", "svc@ops", "a".repeat(80))) {
            assertEquals(username, MysqlEndpoint.requireUsername("storage.mysql.username", username));
        }
        for (String username : List.of("perms&password=x", "per ms", "perms?", "a".repeat(81))) {
            assertThrows(IllegalArgumentException.class, () -> MysqlEndpoint.requireUsername("storage.mysql.username", username));
        }
    }

    @Test
    public void rejectsPortsOutOfRange() {
        assertEquals(3306, MysqlEndpoint.requirePort("storage.mysql.port", 3306L));
        assertThrows(IllegalArgumentException.class, () -> MysqlEndpoint.requirePort("storage.mysql.port", 0L));
        assertThrows(IllegalArgumentException.class, () -> MysqlEndpoint.requirePort("storage.mysql.port", 65_536L));
    }

    @Test
    public void buildsAUrlWithoutOptionsAndPinsDangerousDriverProperties() {
        MysqlEndpoint endpoint = new MysqlEndpoint("db.example.com", 3307, "echelon", "perms", MysqlEndpoint.SslMode.REQUIRED);

        assertEquals("jdbc:mysql://db.example.com:3307/echelon", endpoint.jdbcUrl());
        Map<String, String> properties = endpoint.driverProperties();
        assertEquals("REQUIRED", properties.get("sslMode"));
        assertEquals("false", properties.get("allowLoadLocalInfile"));
        assertEquals("false", properties.get("allowUrlInLocalInfile"));
        assertEquals("false", properties.get("autoDeserialize"));
        assertEquals("false", properties.get("allowPublicKeyRetrieval"));
        assertEquals("UTF-8", properties.get("characterEncoding"));
        assertFalse(properties.containsKey("allowLoadLocalInfileInPath"));
        assertFalse(properties.containsKey("fallbackToSystemTrustStore"));
        assertEquals("jdbc:mysql://[::1]:3306/echelon",
                new MysqlEndpoint("[::1]", 3306, "echelon", "", MysqlEndpoint.SslMode.DISABLED).jdbcUrl());
    }

    @Test
    public void mapsSslModesAndVerifiesWithTheSystemTrustStore() {
        assertEquals(MysqlEndpoint.SslMode.DISABLED, MysqlEndpoint.SslMode.parse("storage.mysql.sslMode", "disabled"));
        assertEquals(MysqlEndpoint.SslMode.REQUIRED, MysqlEndpoint.SslMode.parse("storage.mysql.sslMode", " Required "));
        assertEquals(MysqlEndpoint.SslMode.VERIFY_CA, MysqlEndpoint.SslMode.parse("storage.mysql.sslMode", "verify-ca"));
        MysqlEndpoint.SslMode identity = MysqlEndpoint.SslMode.parse("storage.mysql.sslMode", "verify-identity");
        assertEquals(MysqlEndpoint.SslMode.VERIFY_IDENTITY, identity);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> MysqlEndpoint.SslMode.parse("storage.mysql.sslMode", "true"));
        assertEquals("storage.mysql.sslMode must be one of disabled, required, verify-ca, verify-identity", failure.getMessage());

        Map<String, String> properties = new MysqlEndpoint("db.example.com", 3306, "echelon", "perms", identity).driverProperties();
        assertEquals("VERIFY_IDENTITY", properties.get("sslMode"));
        assertEquals("true", properties.get("fallbackToSystemTrustStore"));
    }

    @Test
    public void redactsTheHostDatabaseAndUsernameFromText() {
        String text = new MysqlEndpoint("db.example.com", 3306, "secretdb", "perms", MysqlEndpoint.SslMode.VERIFY_CA).toString();

        assertFalse(text.contains("db.example.com"));
        assertFalse(text.contains("secretdb"));
        assertFalse(text.contains("perms"));
        assertTrue(text.contains("port=3306"));
        assertTrue(text.contains("sslMode=verify-ca"));
    }
}
