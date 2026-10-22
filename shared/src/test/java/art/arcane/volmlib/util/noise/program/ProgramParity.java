package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.noise.CNG;

import java.util.SplittableRandom;

final class ProgramParity {
    static final String[] APIS = {
            "sample2D", "fill2D", "fill2D-scalar", "fillGrid2D", "fillGrid2D-scalar",
            "sampleSigned3D", "fillColumn3D", "fillColumn3D-scalar", "fillLattice3D", "fillLattice3D-scalar",
            "fill2D-selected", "fillGrid2D-selected", "fillColumn3D-selected", "fillLattice3D-selected"
    };
    private static final double[] SPECIAL = {
            0D, -0D, 0.5D, -0.5D, 1.0E-9D, -1.0E-9D, 29_999_999.5D, -29_999_999.5D, 16D, -16D, 30_000_000D, -30_000_000D
    };
    private static final double[] STEPS = {1D, 0.25D, 4D, 3.7D, 2D, 0.5D};
    private static final int[][] GRID_SIZES = {{16, 16}, {7, 13}, {33, 5}, {1, 1}, {64, 3}};
    private static final int[][] LATTICE_SIZES = {{1, 1, 1}, {4, 9, 4}, {16, 24, 16}, {9, 40, 9}, {3, 1, 7}, {5, 300, 1}};

    private ProgramParity() {
    }

    static Result check(CNG cng, long seed, Budget budget) {
        Result result = new Result();
        NoiseProgram planar = NoiseProgram.compile2D(cng);
        NoiseProgram volume = NoiseProgram.compileSigned3D(cng);
        NoiseScratch scratch = new NoiseScratch();
        SplittableRandom random = new SplittableRandom(seed);
        checkPlanar(result, cng, planar, scratch, random, budget);
        checkVolume(result, cng, volume, scratch, random, budget);
        return result;
    }

    static double coordinate(SplittableRandom random) {
        int bucket = random.nextInt(10);
        if (bucket < 4) {
            return random.nextDouble(-4096D, 4096D);
        }
        if (bucket < 6) {
            return random.nextInt(-30_000_000, 30_000_001);
        }
        if (bucket < 8) {
            return random.nextDouble(-30_000_000D, 30_000_000D);
        }
        if (bucket < 9) {
            return random.nextInt(-64, 65);
        }
        return SPECIAL[random.nextInt(SPECIAL.length)];
    }

    private static double height(SplittableRandom random) {
        int bucket = random.nextInt(10);
        if (bucket < 7) {
            return random.nextDouble(-2048D, 2048D);
        }
        if (bucket < 9) {
            return random.nextInt(-64, 320);
        }
        return coordinate(random);
    }

    private static void checkPlanar(Result result, CNG cng, NoiseProgram program, NoiseScratch scratch, SplittableRandom random, Budget budget) {
        int points = budget.points2D();
        int grids = budget.grids();
        double[] xs = new double[points];
        double[] zs = new double[points];
        double[] expected = new double[points];
        for (int index = 0; index < points; index++) {
            xs[index] = coordinate(random);
            zs[index] = random.nextInt(4) == 0 ? xs[index] : coordinate(random);
            expected[index] = cng.noiseFast2D(xs[index], zs[index]);
        }
        double[] actual = new double[points];
        for (int index = 0; index < points; index++) {
            actual[index] = program.sample2D(xs[index], zs[index]);
        }
        result.compare(0, expected, actual, points, xs, zs, null);
        NoiseBackends.java().fill2D(program, scratch, xs, zs, points, actual);
        result.compare(1, expected, actual, points, xs, zs, null);
        NoiseBackends.scalar().fill2D(program, scratch, xs, zs, points, actual);
        result.compare(2, expected, actual, points, xs, zs, null);
        boolean selected = program.backend() != NoiseBackends.java();
        if (selected) {
            program.fill2D(scratch, xs, zs, points, actual);
            result.compare(10, expected, actual, points, xs, zs, null);
        }
        for (int grid = 0; grid < grids; grid++) {
            int[] size = GRID_SIZES[grid % GRID_SIZES.length];
            int width = size[0];
            int depth = size[1];
            double x0 = coordinate(random);
            double z0 = coordinate(random);
            double step = STEPS[random.nextInt(STEPS.length)];
            double[] gridExpected = new double[width * depth];
            double[] gridX = new double[width * depth];
            double[] gridZ = new double[width * depth];
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    int index = z * width + x;
                    gridX[index] = x0 + x * step;
                    gridZ[index] = z0 + z * step;
                    gridExpected[index] = cng.noiseFast2D(gridX[index], gridZ[index]);
                }
            }
            double[] gridActual = new double[width * depth];
            NoiseBackends.java().fillGrid2D(program, scratch, x0, z0, step, width, depth, gridActual);
            result.compare(3, gridExpected, gridActual, gridExpected.length, gridX, gridZ, null);
            NoiseBackends.scalar().fillGrid2D(program, scratch, x0, z0, step, width, depth, gridActual);
            result.compare(4, gridExpected, gridActual, gridExpected.length, gridX, gridZ, null);
            if (selected) {
                program.fillGrid2D(scratch, x0, z0, step, width, depth, gridActual);
                result.compare(11, gridExpected, gridActual, gridExpected.length, gridX, gridZ, null);
            }
        }
    }

    private static void checkVolume(Result result, CNG cng, NoiseProgram program, NoiseScratch scratch, SplittableRandom random, Budget budget) {
        int points = budget.points3D();
        int columns = budget.columns();
        int lattices = budget.lattices();
        double[] xs = new double[points];
        double[] ys = new double[points];
        double[] zs = new double[points];
        double[] expected = new double[points];
        double[] actual = new double[points];
        for (int index = 0; index < points; index++) {
            xs[index] = coordinate(random);
            ys[index] = height(random);
            zs[index] = random.nextInt(5) == 0 ? xs[index] : coordinate(random);
            expected[index] = cng.noiseFastSigned3D(xs[index], ys[index], zs[index]);
            actual[index] = program.sampleSigned3D(xs[index], ys[index], zs[index]);
        }
        result.compare(5, expected, actual, points, xs, zs, ys);
        boolean selected = program.backend() != NoiseBackends.java();
        for (int column = 0; column < columns; column++) {
            double x = coordinate(random);
            double z = coordinate(random);
            double y0 = height(random);
            double yStep = STEPS[random.nextInt(STEPS.length)];
            int count = 1 + random.nextInt(320);
            double[] columnExpected = new double[count];
            double[] columnX = new double[count];
            double[] columnY = new double[count];
            double[] columnZ = new double[count];
            for (int index = 0; index < count; index++) {
                columnX[index] = x;
                columnY[index] = y0 + index * yStep;
                columnZ[index] = z;
                columnExpected[index] = cng.noiseFastSigned3D(x, columnY[index], z);
            }
            double[] columnActual = new double[count];
            NoiseBackends.java().fillColumn3D(program, scratch, x, z, y0, yStep, count, columnActual);
            result.compare(6, columnExpected, columnActual, count, columnX, columnZ, columnY);
            NoiseBackends.scalar().fillColumn3D(program, scratch, x, z, y0, yStep, count, columnActual);
            result.compare(7, columnExpected, columnActual, count, columnX, columnZ, columnY);
            if (selected) {
                program.fillColumn3D(scratch, x, z, y0, yStep, count, columnActual);
                result.compare(12, columnExpected, columnActual, count, columnX, columnZ, columnY);
            }
        }
        for (int lattice = 0; lattice < lattices; lattice++) {
            int[] size = LATTICE_SIZES[lattice % LATTICE_SIZES.length];
            int nx = size[0];
            int ny = size[1];
            int nz = size[2];
            double x0 = coordinate(random);
            double y0 = height(random);
            double z0 = coordinate(random);
            double stepX = STEPS[random.nextInt(STEPS.length)];
            double stepY = STEPS[random.nextInt(STEPS.length)];
            double stepZ = STEPS[random.nextInt(STEPS.length)];
            int total = nx * ny * nz;
            double[] latticeExpected = new double[total];
            double[] latticeX = new double[total];
            double[] latticeY = new double[total];
            double[] latticeZ = new double[total];
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    for (int x = 0; x < nx; x++) {
                        int index = (y * nz + z) * nx + x;
                        latticeX[index] = x0 + x * stepX;
                        latticeY[index] = y0 + y * stepY;
                        latticeZ[index] = z0 + z * stepZ;
                        latticeExpected[index] = cng.noiseFastSigned3D(latticeX[index], latticeY[index], latticeZ[index]);
                    }
                }
            }
            double[] latticeActual = new double[total];
            NoiseBackends.java().fillLattice3D(program, scratch, x0, y0, z0, stepX, stepY, stepZ, nx, ny, nz, latticeActual);
            result.compare(8, latticeExpected, latticeActual, total, latticeX, latticeZ, latticeY);
            NoiseBackends.scalar().fillLattice3D(program, scratch, x0, y0, z0, stepX, stepY, stepZ, nx, ny, nz, latticeActual);
            result.compare(9, latticeExpected, latticeActual, total, latticeX, latticeZ, latticeY);
            if (selected) {
                program.fillLattice3D(scratch, x0, y0, z0, stepX, stepY, stepZ, nx, ny, nz, latticeActual);
                result.compare(13, latticeExpected, latticeActual, total, latticeX, latticeZ, latticeY);
            }
        }
    }

    record Budget(int points2D, int points3D, int grids, int columns, int lattices) {
    }

    static final class Result {
        final long[] compared = new long[APIS.length];
        final long[] mismatches = new long[APIS.length];
        String firstMismatch;

        void compare(int api, double[] expected, double[] actual, int count, double[] xs, double[] zs, double[] ys) {
            compared[api] += count;
            for (int index = 0; index < count; index++) {
                if (Double.doubleToRawLongBits(expected[index]) != Double.doubleToRawLongBits(actual[index])) {
                    mismatches[api]++;
                    if (firstMismatch == null) {
                        firstMismatch = APIS[api] + " at x=" + xs[index] + (ys == null ? "" : " y=" + ys[index]) + " z=" + zs[index]
                                + " expected=" + expected[index] + " actual=" + actual[index];
                    }
                }
            }
        }

        long totalMismatches() {
            long total = 0L;
            for (long count : mismatches) {
                total += count;
            }
            return total;
        }

        long totalCompared() {
            long total = 0L;
            for (long count : compared) {
                total += count;
            }
            return total;
        }
    }
}
