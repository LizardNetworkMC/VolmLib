package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.VolmLog;

public final class NoiseBackends {
    private static final NoiseBackend JAVA = new JavaBatchBackend();
    private static final NoiseBackend SCALAR = new JavaScalarBackend();
    private static final String LOADER = "art.arcane.volmlib.util.noise.program.NativeNoiseLoader";

    private NoiseBackends() {
    }

    public static NoiseBackend selected() {
        NoiseBackend selected = Selection.SELECTED;
        return selected == null ? JAVA : selected;
    }

    public static NoiseBackend java() {
        return JAVA;
    }

    public static NoiseBackend scalar() {
        return SCALAR;
    }

    public static String nativeStatus() {
        return Selection.NATIVE_STATUS;
    }

    private static final class Selection {
        private static final NoiseBackend NATIVE;
        private static final String NATIVE_STATUS;
        private static final NoiseBackend SELECTED;

        static {
            String id = System.getProperty("volmlib.noise.backend", "auto");
            boolean wantsNative = id.equals("auto") || id.equals("native");
            NoiseBackend loaded = null;
            String status = "not-requested";
            if (wantsNative) {
                if (Runtime.version().feature() < 25) {
                    status = "unsupported-runtime";
                } else {
                    try {
                        Class<?> loader = Class.forName(LOADER);
                        loaded = (NoiseBackend) loader.getMethod("load").invoke(null);
                        status = (String) loader.getMethod("status").invoke(null);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        VolmLog.fine("Noise", "Native noise backend is not available", failure);
                        status = "loader-unavailable";
                    }
                }
            }
            NATIVE = loaded;
            NATIVE_STATUS = status;
            SELECTED = resolve(id);
        }

        private static NoiseBackend resolve(String id) {
            if (id.equals(JAVA.id())) {
                return JAVA;
            }
            if (id.equals(SCALAR.id())) {
                return SCALAR;
            }
            if (id.equals("auto")) {
                return NATIVE == null ? JAVA : NATIVE;
            }
            if (id.equals("native")) {
                if (NATIVE == null) {
                    VolmLog.warning("Noise", "Native noise backend unavailable (" + NATIVE_STATUS + "); using " + JAVA.id());
                    return JAVA;
                }
                return NATIVE;
            }
            VolmLog.warning("Noise", "Unknown noise backend '" + id + "'; using " + JAVA.id());
            return JAVA;
        }
    }
}
