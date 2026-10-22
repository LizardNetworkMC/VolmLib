package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.VolmLog;
import art.arcane.volmlib.util.noise.NativeNoiseLibrary;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

public final class GpuNoise {
    public static final String POLICY_PROPERTY = "volmlib.gpu";
    private static final State STATE = open();

    private GpuNoise() {
    }

    public static boolean available() {
        return STATE.bindings() != null;
    }

    public static String status() {
        return STATE.status();
    }

    public static String adapter() {
        return STATE.adapter();
    }

    public static GpuNoiseProgram compile(NoiseProgram program) {
        return compile(program, GpuNoiseProgram.CALIBRATION_LATTICES);
    }

    public static GpuNoiseProgram compile(NoiseProgram program, int calibrationLattices) {
        Bindings bindings = STATE.bindings();
        if (bindings == null) {
            return null;
        }
        MemorySegment encoded = NativeProgramEncoder.encode(program);
        if (encoded == null) {
            return null;
        }
        long handle;
        try {
            handle = (long) bindings.compile().invokeExact(encoded, encoded.byteSize());
        } catch (Throwable failure) {
            VolmLog.warning("Noise", "GPU noise program compilation failed", failure);
            return null;
        }
        if (handle == 0L) {
            return null;
        }
        GpuNoiseProgram compiled = new GpuNoiseProgram(program, bindings, handle);
        if (calibrationLattices > 0 && !compiled.calibrate(program.structuralSignature(), calibrationLattices)) {
            compiled.close();
            return null;
        }
        return compiled;
    }

    private static State open() {
        String policy = System.getProperty(POLICY_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
        if (policy.equals("off")) {
            return new State("off", "", null);
        }
        NativeNoiseLibrary.Resolution library = NativeNoiseLibrary.shared();
        if (!library.available()) {
            return new State("native-" + library.status(), "", null);
        }
        SymbolLookup lookup = library.lookup();
        Optional<MemorySegment> probe = lookup.find("volmlib_gpu_open");
        if (probe.isEmpty()) {
            return new State("gpu-not-built", "", null);
        }
        try {
            Linker linker = Linker.nativeLinker();
            MethodHandle open = linker.downcallHandle(probe.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            if ((int) open.invokeExact() != 1) {
                return new State("no-adapter", "", null);
            }
            MethodHandle name = linker.downcallHandle(lookup.find("volmlib_gpu_adapter").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            String adapter;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(256);
                int length = (int) name.invokeExact(buffer, 256);
                adapter = new String(buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
            }
            Bindings bindings = new Bindings(
                    linker.downcallHandle(lookup.find("volmlib_gpu_compile").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)),
                    linker.downcallHandle(lookup.find("volmlib_gpu_release").orElseThrow(),
                            FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG)),
                    linker.downcallHandle(lookup.find("volmlib_gpu_lattice3d").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                                    ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)),
                    linker.downcallHandle(lookup.find("volmlib_gpu_grid2d").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                                    ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)),
                    linker.downcallHandle(lookup.find("volmlib_gpu_timing").orElseThrow(),
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), Linker.Option.critical(true)));
            return new State("gpu", adapter, bindings);
        } catch (Throwable failure) {
            VolmLog.warning("Noise", "GPU noise programs failed to initialize", failure);
            return new State("initialization-failed", "", null);
        }
    }

    record Bindings(MethodHandle compile, MethodHandle release, MethodHandle lattice3D, MethodHandle grid2D, MethodHandle timing) {
    }

    private record State(String status, String adapter, Bindings bindings) {
    }
}
