package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.VolmLog;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GpuNoiseProgram implements AutoCloseable {
    public static final double SAFETY = 4D;
    public static final int CALIBRATION_LATTICES = 24;
    public static final double MAX_EXTENT = 1024D;
    private static final double RELATIVE_FLOOR = 1.0E-6D;
    private static final float UNCERTIFIED = 1.0E38F;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;
    private static final double[] CALIBRATION_STEPS = {1D, 2D, 4D, 8D, 0.5D};
    private final NoiseProgram program;
    private final GpuNoise.Bindings bindings;
    private final long handle;
    private final MemorySegment addressSpace;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile double margin = Double.POSITIVE_INFINITY;
    private volatile Calibration calibration;

    GpuNoiseProgram(NoiseProgram program, GpuNoise.Bindings bindings, long handle) {
        this.program = program;
        this.bindings = bindings;
        this.handle = handle;
        addressSpace = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);
    }

    public NoiseProgram program() {
        return program;
    }

    public double margin() {
        return margin;
    }

    public Calibration calibration() {
        return calibration;
    }

    public boolean fillLattice3D(double x0, double y0, double z0, double stepX, double stepY, double stepZ, int nx, int ny, int nz, float[] out) {
        long result = dispatchLattice(x0, y0, z0, stepX, stepY, stepZ, nx, ny, nz);
        if (result == 0L) {
            return false;
        }
        MemorySegment.copy(addressSpace, FLOAT, result, out, 0, nx * ny * nz);
        return true;
    }

    public boolean fillGrid2D(double x0, double z0, double step, int width, int depth, float[] out) {
        long result = dispatchGrid(x0, z0, step, width, depth);
        if (result == 0L) {
            return false;
        }
        MemorySegment.copy(addressSpace, FLOAT, result, out, 0, width * depth);
        return true;
    }

    public boolean fillLatticeBounds(double x0, double y0, double z0, double stepX, double stepY, double stepZ, int nx, int ny, int nz,
                                     double[] lo, double[] hi) {
        long result = dispatchLattice(x0, y0, z0, stepX, stepY, stepZ, nx, ny, nz);
        if (result == 0L) {
            return false;
        }
        bounds(result, nx * ny * nz, lo, hi);
        return true;
    }

    public boolean fillGridBounds(double x0, double z0, double step, int width, int depth, double[] lo, double[] hi) {
        long result = dispatchGrid(x0, z0, step, width, depth);
        if (result == 0L) {
            return false;
        }
        bounds(result, width * depth, lo, hi);
        return true;
    }

    public long[] lastTiming() {
        long[] timing = new long[3];
        try {
            bindings.timing().invokeExact(MemorySegment.ofArray(timing));
        } catch (Throwable failure) {
            VolmLog.fine("Noise", "GPU timing read failed", failure);
        }
        return timing;
    }

    public static boolean certified(float value) {
        return Math.abs(value) < UNCERTIFIED;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            bindings.release().invokeExact(handle);
        } catch (Throwable failure) {
            VolmLog.warning("Noise", "GPU noise program release failed", failure);
        }
    }

    boolean calibrate(long seed, int lattices) {
        SplittableRandom random = new SplittableRandom(seed ^ 0x6A09E667F3BCC909L);
        boolean volumetric = program.volumetric;
        double[] exact = new double[volumetric ? 36_000 : 23_104];
        float[] approximate = new float[exact.length];
        NoiseScratch scratch = program.newScratch();
        long points = 0L;
        long uncertified = 0L;
        double worst = 0D;
        double scale = 0D;
        for (int lattice = 0; lattice < lattices; lattice++) {
            double[] origin = calibrationOrigin(random, lattice);
            double fine = CALIBRATION_STEPS[(lattice / 3) % CALIBRATION_STEPS.length];
            int kind = lattice % 3;
            int nx;
            int ny;
            int nz;
            double step;
            double stepY;
            if (volumetric) {
                nx = kind == 0 ? 33 : kind == 1 ? 24 : 17;
                ny = kind == 0 ? 25 : kind == 1 ? 40 : 120;
                nz = nx;
                step = kind == 0 ? 32D : kind == 1 ? fine : 64D;
                stepY = kind == 0 ? 32D : kind == 1 ? CALIBRATION_STEPS[lattice % CALIBRATION_STEPS.length] : 6D;
            } else {
                nx = kind == 0 ? 129 : kind == 1 ? 152 : 65;
                ny = 1;
                nz = nx;
                step = kind == 0 ? 8D : kind == 1 ? Math.min(fine, 4D) : 16D;
                stepY = step;
            }
            int count = nx * ny * nz;
            boolean filled;
            if (volumetric) {
                program.fillLattice3D(scratch, origin[0], origin[1], origin[2], step, stepY, step, nx, ny, nz, exact);
                filled = fillLattice3D(origin[0], origin[1], origin[2], step, stepY, step, nx, ny, nz, approximate);
            } else {
                program.fillGrid2D(scratch, origin[0], origin[2], step, nx, nz, exact);
                filled = fillGrid2D(origin[0], origin[2], step, nx, nz, approximate);
            }
            if (!filled) {
                return false;
            }
            for (int index = 0; index < count; index++) {
                double expected = exact[index];
                if (!Double.isFinite(expected)) {
                    return false;
                }
                scale = Math.max(scale, Math.abs(expected));
                if (!certified(approximate[index])) {
                    uncertified++;
                    continue;
                }
                worst = Math.max(worst, Math.abs(approximate[index] - expected));
            }
            points += count;
        }
        double floor = Math.max(scale, 1.0E-9D) * RELATIVE_FLOOR;
        double resolved = SAFETY * worst + floor;
        calibration = new Calibration(points, uncertified, worst, scale, resolved);
        margin = resolved;
        return true;
    }

    private static double[] calibrationOrigin(SplittableRandom random, int lattice) {
        double[] origin = new double[3];
        int category = lattice % 4;
        for (int axis = 0; axis < 3; axis++) {
            boolean vertical = axis == 1;
            origin[axis] = switch (category) {
                case 0 -> random.nextDouble(-4096D, 4096D);
                case 1 -> vertical ? Math.floor(random.nextDouble(-64D, 320D)) : Math.floor(random.nextDouble(-30_000_000D, 30_000_000D));
                case 2 -> vertical ? random.nextDouble(-64D, 320D) : random.nextDouble(-30_000_000D, 30_000_000D);
                default -> random.nextDouble(-1_048_576D, 1_048_576D);
            };
        }
        return origin;
    }

    private long dispatchLattice(double x0, double y0, double z0, double stepX, double stepY, double stepZ, int nx, int ny, int nz) {
        if (closed.get() || nx <= 0 || ny <= 0 || nz <= 0) {
            return 0L;
        }
        try {
            return (long) bindings.lattice3D().invokeExact(handle, x0, y0, z0, stepX, stepY, stepZ, nx, ny, nz);
        } catch (Throwable failure) {
            VolmLog.warning("Noise", "GPU lattice dispatch failed", failure);
            return 0L;
        }
    }

    private long dispatchGrid(double x0, double z0, double step, int width, int depth) {
        if (closed.get() || width <= 0 || depth <= 0) {
            return 0L;
        }
        try {
            return (long) bindings.grid2D().invokeExact(handle, x0, z0, step, width, depth);
        } catch (Throwable failure) {
            VolmLog.warning("Noise", "GPU grid dispatch failed", failure);
            return 0L;
        }
    }

    private void bounds(long result, int count, double[] lo, double[] hi) {
        double width = margin;
        for (int index = 0; index < count; index++) {
            float value = addressSpace.get(FLOAT, result + 4L * index);
            if (!certified(value)) {
                lo[index] = Double.NEGATIVE_INFINITY;
                hi[index] = Double.POSITIVE_INFINITY;
                continue;
            }
            lo[index] = Math.nextDown(value - width);
            hi[index] = Math.nextUp(value + width);
        }
    }

    public record Calibration(long points, long uncertified, double worstDelta, double outputScale, double margin) {
    }
}
