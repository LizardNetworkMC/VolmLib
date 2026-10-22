package art.arcane.volmlib.util.storage;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class DocumentNames {
    private static final Pattern COLLECTION = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");
    private static final Pattern NUMBERED_DEVICE = Pattern.compile("(?:com|lpt)[0-9]");
    private static final Set<String> DEVICES = Set.of("con", "prn", "aux", "nul");

    private DocumentNames() {
    }

    public static String requireCollection(String collection) {
        String required = Objects.requireNonNull(collection, "collection");
        if (!COLLECTION.matcher(required).matches()) {
            throw new IllegalArgumentException("Collection names use 1 to 64 lowercase letters, digits, '_' or '-': " + required);
        }
        if (reserved(required)) {
            throw new IllegalArgumentException("Collection names cannot be a Windows device name such as con, nul, com1 or lpt1: " + required);
        }
        return required;
    }

    public static String requireId(String id) {
        String required = Objects.requireNonNull(id, "id");
        if (!ID.matcher(required).matches() || required.contains("..")) {
            throw new IllegalArgumentException("Document ids use 1 to 128 lowercase letters, digits, '_', '-' or '.', start with a letter or digit, and contain no '..': " + required);
        }
        if (reserved(required)) {
            throw new IllegalArgumentException("Document ids cannot be a Windows device name such as con, nul, com1 or lpt1: " + required);
        }
        return required;
    }

    private static boolean reserved(String name) {
        int dot = name.indexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        return DEVICES.contains(stem) || NUMBERED_DEVICE.matcher(stem).matches();
    }
}
