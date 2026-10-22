package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.VolmLog;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicBoolean;

final class NativeNoiseBackend implements NoiseBackend {
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    private final MemorySegment addressSpace;
    private final MethodHandle validate;
    private final MethodHandle input;
    private final MethodHandle fill2D;
    private final MethodHandle grid2D;
    private final MethodHandle column3D;
    private final MethodHandle lattice3D;
    private final MethodHandle points3D;
    private final AtomicBoolean failureReported = new AtomicBoolean();

    NativeNoiseBackend(SymbolLookup library) {
        Linker linker = Linker.nativeLinker();
        addressSpace = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);
        validate = linker.downcallHandle(library.find("volmlib_noise_validate").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        input = linker.downcallHandle(library.find("volmlib_noise_input").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT), Linker.Option.critical(false));
        fill2D = linker.downcallHandle(library.find("volmlib_noise_fill2d").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        grid2D = linker.downcallHandle(library.find("volmlib_noise_grid2d").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        column3D = linker.downcallHandle(library.find("volmlib_noise_column3d").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_INT));
        lattice3D = linker.downcallHandle(library.find("volmlib_noise_lattice3d").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        points3D = linker.downcallHandle(library.find("volmlib_noise_points3d").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    }

    @Override
    public String id() {
        return "native";
    }

    @Override
    public boolean supports(NoiseProgram program) {
        MemorySegment encoded = NativeProgramEncoder.encode(program);
        if (encoded == null) {
            return false;
        }
        try {
            if ((int) validate.invokeExact(encoded, encoded.byteSize()) != 1) {
                return false;
            }
        } catch (Throwable failure) {
            reportFailure(failure);
            return false;
        }
        program.backendState = encoded;
        return true;
    }

    @Override
    public void fill2D(NoiseProgram program, NoiseScratch scratch, double[] xs, double[] zs, int count, double[] out) {
        if (!(program.backendState instanceof MemorySegment encoded) || count <= 0) {
            NoiseBackends.java().fill2D(program, scratch, xs, zs, count, out);
            return;
        }
        try {
            long buffer = (long) input.invokeExact(count * 2);
            long result = 0L;
            if (buffer != 0L) {
                MemorySegment.copy(xs, 0, addressSpace, DOUBLE, buffer, count);
                MemorySegment.copy(zs, 0, addressSpace, DOUBLE, buffer + count * 8L, count);
                result = (long) fill2D.invokeExact(encoded, count);
            }
            if (result != 0L) {
                MemorySegment.copy(addressSpace, DOUBLE, result, out, 0, count);
                return;
            }
            reportFailure(null);
        } catch (Throwable failure) {
            reportFailure(failure);
        }
        NoiseBackends.java().fill2D(program, scratch, xs, zs, count, out);
    }

    @Override
    public void fillGrid2D(NoiseProgram program, NoiseScratch scratch, double x0, double z0, double step, int width, int depth, double[] out) {
        if (!(program.backendState instanceof MemorySegment encoded) || width <= 0 || depth <= 0) {
            NoiseBackends.java().fillGrid2D(program, scratch, x0, z0, step, width, depth, out);
            return;
        }
        try {
            long result = (long) grid2D.invokeExact(encoded, x0, z0, step, width, depth);
            if (result != 0L) {
                MemorySegment.copy(addressSpace, DOUBLE, result, out, 0, width * depth);
                return;
            }
            reportFailure(null);
        } catch (Throwable failure) {
            reportFailure(failure);
        }
        NoiseBackends.java().fillGrid2D(program, scratch, x0, z0, step, width, depth, out);
    }

    @Override
    public void fillColumn3D(NoiseProgram program, NoiseScratch scratch, double x, double z, double y0, double yStep, int count, double[] out) {
        if (!(program.backendState instanceof MemorySegment encoded) || count <= 0) {
            NoiseBackends.java().fillColumn3D(program, scratch, x, z, y0, yStep, count, out);
            return;
        }
        try {
            long result = (long) column3D.invokeExact(encoded, x, z, y0, yStep, count);
            if (result != 0L) {
                MemorySegment.copy(addressSpace, DOUBLE, result, out, 0, count);
                return;
            }
            reportFailure(null);
        } catch (Throwable failure) {
            reportFailure(failure);
        }
        NoiseBackends.java().fillColumn3D(program, scratch, x, z, y0, yStep, count, out);
    }

    @Override
    public void fillLattice3D(NoiseProgram program, NoiseScratch scratch, double x0, double y0, double z0, double stepX, double stepY, double stepZ,
                              int nx, int ny, int nz, double[] out) {
        if (!(program.backendState instanceof MemorySegment encoded) || nx <= 0 || ny <= 0 || nz <= 0) {
            NoiseBackends.java().fillLattice3D(program, scratch, x0, y0, z0, stepX, stepY, stepZ, nx, ny, nz, out);
            return;
        }
        try {
            long result = (long) lattice3D.invokeExact(encoded, x0, y0, z0, stepX, stepY, stepZ, nx, ny, nz);
            if (result != 0L) {
                MemorySegment.copy(addressSpace, DOUBLE, result, out, 0, nx * ny * nz);
                return;
            }
            reportFailure(null);
        } catch (Throwable failure) {
            reportFailure(failure);
        }
        NoiseBackends.java().fillLattice3D(program, scratch, x0, y0, z0, stepX, stepY, stepZ, nx, ny, nz, out);
    }

    @Override
    public void fillPoints3D(NoiseProgram program, NoiseScratch scratch, double[] xs, double[] ys, double[] zs, int count, double[] out) {
        if (!(program.backendState instanceof MemorySegment encoded) || count <= 0 || count > Integer.MAX_VALUE / 3) {
            NoiseBackends.java().fillPoints3D(program, scratch, xs, ys, zs, count, out);
            return;
        }
        try {
            long buffer = (long) input.invokeExact(count * 3);
            long result = 0L;
            if (buffer != 0L) {
                MemorySegment.copy(xs, 0, addressSpace, DOUBLE, buffer, count);
                MemorySegment.copy(ys, 0, addressSpace, DOUBLE, buffer + count * 8L, count);
                MemorySegment.copy(zs, 0, addressSpace, DOUBLE, buffer + count * 16L, count);
                result = (long) points3D.invokeExact(encoded, count);
            }
            if (result != 0L) {
                MemorySegment.copy(addressSpace, DOUBLE, result, out, 0, count);
                return;
            }
            reportFailure(null);
        } catch (Throwable failure) {
            reportFailure(failure);
        }
        NoiseBackends.java().fillPoints3D(program, scratch, xs, ys, zs, count, out);
    }

    private void reportFailure(Throwable failure) {
        if (!failureReported.compareAndSet(false, true)) {
            return;
        }
        if (failure == null) {
            VolmLog.warning("Noise", "Native noise kernel call was declined; using the Java batch backend for that call");
            return;
        }
        VolmLog.warning("Noise", "Native noise kernel call failed; using the Java batch backend for that call", failure);
    }
}
