package art.arcane.volmlib.util.storage;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DocumentNamesTest {
    @Test
    public void rejectsWindowsDeviceNamesWithOrWithoutAnExtension() {
        for (String name : List.of("con", "prn", "aux", "nul", "com1", "com9", "lpt1", "lpt9", "con.backup", "nul.json", "com3.x")) {
            assertThrows(IllegalArgumentException.class, () -> DocumentNames.requireId(name));
        }
        for (String name : List.of("con", "aux", "lpt2")) {
            assertThrows(IllegalArgumentException.class, () -> DocumentNames.requireCollection(name));
        }
    }

    @Test
    public void acceptsNamesThatOnlyStartLikeDevices() {
        for (String name : List.of("console", "control", "auxiliary", "null", "com10", "lpt", "com", "nul-1", "admin.con")) {
            assertEquals(name, DocumentNames.requireId(name));
        }
        assertEquals("connections", DocumentNames.requireCollection("connections"));
    }
}
