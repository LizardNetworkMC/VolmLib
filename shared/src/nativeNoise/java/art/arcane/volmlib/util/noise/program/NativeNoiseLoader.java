package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.VolmLog;
import art.arcane.volmlib.util.noise.NativeNoiseLibrary;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public final class NativeNoiseLoader {
    private static volatile String status = "not-loaded";

    private NativeNoiseLoader() {
    }

    public static NoiseBackend load() {
        NativeNoiseLibrary.Resolution library = NativeNoiseLibrary.shared();
        if (!library.available()) {
            status = library.status();
            return null;
        }
        try {
            MethodHandle abi = Linker.nativeLinker().downcallHandle(library.lookup().find("volmlib_noise_abi").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            if ((int) abi.invokeExact() != NativeProgramEncoder.VERSION) {
                return fail(library, "abi-mismatch");
            }
            NativeNoiseBackend backend = new NativeNoiseBackend(library.lookup());
            String mismatch = NoiseBackendSelfTest.run(backend);
            if (mismatch != null) {
                VolmLog.warning("Noise", "Native noise kernels disabled: self-test mismatch in " + mismatch);
                status = "self-test-failed";
                return null;
            }
            if (library.unflagged()) {
                VolmLog.info("Noise", "Native noise kernels enabled; pass --enable-native-access=ALL-UNNAMED to silence the JDK warning");
            }
            status = "native";
            return backend;
        } catch (Throwable failure) {
            VolmLog.warning("Noise", "Native noise kernels failed to initialize; using Java", failure);
            status = "initialization-failed";
            return null;
        }
    }

    public static String status() {
        return status;
    }

    private static NoiseBackend fail(NativeNoiseLibrary.Resolution library, String reason) {
        NativeNoiseLibrary.report(library.policy(), reason);
        status = reason;
        return null;
    }
}
