package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.VolmLog;

public final class NoiseBackends {
    private static final NoiseBackend JAVA = new JavaBatchBackend();
    private static final NoiseBackend SCALAR = new JavaScalarBackend();
    private static final NoiseBackend SELECTED = resolve(System.getProperty("volmlib.noise.backend", "java"));

    private NoiseBackends() {
    }

    public static NoiseBackend selected() {
        return SELECTED;
    }

    public static NoiseBackend java() {
        return JAVA;
    }

    public static NoiseBackend scalar() {
        return SCALAR;
    }

    private static NoiseBackend resolve(String id) {
        if (id.equals(JAVA.id())) {
            return JAVA;
        }
        if (id.equals(SCALAR.id())) {
            return SCALAR;
        }
        VolmLog.warning("Noise", "Unknown noise backend '" + id + "'; using " + JAVA.id());
        return JAVA;
    }
}
