package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.noise.CNG;
import art.arcane.volmlib.util.noise.NoiseType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public final class NoiseProgramBenchmark {
    private static final int CHUNKS_2D = 300;
    private static final int BOXES_3D = 6;
    private static final int HEIGHT = 64;
    private static final int ROUNDS = 7;
    private static double sink;

    private NoiseProgramBenchmark() {
    }

    public static void main(String[] args) {
        String filter = args.length > 0 ? args[0] : "";
        List<Case> cases = new ArrayList<>();
        for (Case candidate : cases()) {
            if (candidate.name.contains(filter)) {
                cases.add(candidate);
            }
        }
        for (int warm = 0; warm < 2; warm++) {
            for (Case benchmark : cases) {
                measure2D(benchmark, warm, 1);
                measure3D(benchmark, warm, 1);
            }
        }
        System.out.printf("%-26s | %8s %8s %8s %6s %6s | %8s %8s %8s %8s %6s %6s %6s%n", "style",
                "2D cng", "scalar", "grid", "x-sc", "x-grd",
                "3D cng", "scalar", "lattice", "column", "x-sc", "x-lat", "x-col");
        for (Case benchmark : cases) {
            double[] planar = measure2D(benchmark, 100, ROUNDS);
            double[] volume = measure3D(benchmark, 100, ROUNDS);
            System.out.printf("%-26s | %8.1f %8.1f %8.1f %6.2f %6.2f | %8.1f %8.1f %8.1f %8.1f %6.2f %6.2f %6.2f%n", benchmark.name,
                    planar[0], planar[1], planar[2], planar[0] / planar[1], planar[0] / planar[2],
                    volume[0], volume[1], volume[2], volume[3], volume[0] / volume[1], volume[0] / volume[2], volume[0] / volume[3]);
        }
        System.out.println("ns per sample, median of " + ROUNDS + " rounds; sink=" + sink);
    }

    private static double[] measure2D(Case benchmark, int salt, int rounds) {
        CNG cng = benchmark.factory.apply(9001L);
        NoiseProgram program = NoiseProgram.compile2D(cng);
        NoiseScratch scratch = program.newScratch();
        double[] out = new double[256];
        double[][] samples = new double[3][rounds];
        double count = CHUNKS_2D * 256D;
        for (int round = 0; round < rounds; round++) {
            double base = (salt * 31 + round) * 65536D;
            double acc = 0D;
            long t0 = System.nanoTime();
            for (int chunk = 0; chunk < CHUNKS_2D; chunk++) {
                double cx = base + (chunk % 30) * 16;
                double cz = -base + (chunk / 30) * 16;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        acc += cng.noiseFast2D(cx + x, cz + z);
                    }
                }
            }
            long t1 = System.nanoTime();
            for (int chunk = 0; chunk < CHUNKS_2D; chunk++) {
                double cx = base + 4096D + (chunk % 30) * 16;
                double cz = -base + (chunk / 30) * 16;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        acc += program.sample2D(cx + x, cz + z);
                    }
                }
            }
            long t2 = System.nanoTime();
            for (int chunk = 0; chunk < CHUNKS_2D; chunk++) {
                double cx = base + 8192D + (chunk % 30) * 16;
                double cz = -base + (chunk / 30) * 16;
                program.fillGrid2D(scratch, cx, cz, 1D, 16, 16, out);
                acc += out[chunk & 255];
            }
            long t3 = System.nanoTime();
            sink += acc;
            samples[0][round] = (t1 - t0) / count;
            samples[1][round] = (t2 - t1) / count;
            samples[2][round] = (t3 - t2) / count;
        }
        return new double[]{median(samples[0]), median(samples[1]), median(samples[2])};
    }

    private static double[] measure3D(Case benchmark, int salt, int rounds) {
        CNG cng = benchmark.factory.apply(9001L);
        NoiseProgram program = NoiseProgram.compileSigned3D(cng);
        NoiseScratch scratch = program.newScratch();
        double[] out = new double[16 * HEIGHT * 16];
        double[][] samples = new double[4][rounds];
        double count = BOXES_3D * 256D * HEIGHT;
        for (int round = 0; round < rounds; round++) {
            double base = (salt * 31 + round) * 65536D;
            double acc = 0D;
            long t0 = System.nanoTime();
            for (int box = 0; box < BOXES_3D; box++) {
                double cx = base + box * 16;
                double cz = -base;
                for (int y = 0; y < HEIGHT; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            acc += cng.noiseFastSigned3D(cx + x, y - 32D, cz + z);
                        }
                    }
                }
            }
            long t1 = System.nanoTime();
            for (int box = 0; box < BOXES_3D; box++) {
                double cx = base + 4096D + box * 16;
                double cz = -base;
                for (int y = 0; y < HEIGHT; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            acc += program.sampleSigned3D(cx + x, y - 32D, cz + z);
                        }
                    }
                }
            }
            long t2 = System.nanoTime();
            for (int box = 0; box < BOXES_3D; box++) {
                double cx = base + 8192D + box * 16;
                double cz = -base;
                program.fillLattice3D(scratch, cx, -32D, cz, 1D, 1D, 1D, 16, HEIGHT, 16, out);
                acc += out[box];
            }
            long t3 = System.nanoTime();
            for (int box = 0; box < BOXES_3D; box++) {
                double cx = base + 12288D + box * 16;
                double cz = -base;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        program.fillColumn3D(scratch, cx + x, cz + z, -32D, 1D, HEIGHT, out);
                        acc += out[x];
                    }
                }
            }
            long t4 = System.nanoTime();
            sink += acc;
            samples[0][round] = (t1 - t0) / count;
            samples[1][round] = (t2 - t1) / count;
            samples[2][round] = (t3 - t2) / count;
            samples[3][round] = (t4 - t3) / count;
        }
        return new double[]{median(samples[0]), median(samples[1]), median(samples[2]), median(samples[3])};
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("SIMPLEX", seed -> ProgramShapes.style(new CNG(new RNG(seed), 1D, 1), NoiseType.SIMPLEX, 0.9D)));
        cases.add(new Case("PERLIN", seed -> ProgramShapes.style(new CNG(new RNG(seed), NoiseType.PERLIN, 1D, 1), NoiseType.PERLIN, 0.9D)));
        cases.add(new Case("CELLULAR", seed -> ProgramShapes.style(new CNG(new RNG(seed), NoiseType.CELLULAR, 1D, 1), NoiseType.CELLULAR, 0.9D)));
        cases.add(new Case("STATIC", seed -> ProgramShapes.style(new CNG(new RNG(seed), NoiseType.WHITE, 1D, 1), NoiseType.WHITE, 1D)));
        cases.add(new Case("CLOVER", seed -> ProgramShapes.style(new CNG(new RNG(seed), NoiseType.CLOVER, 1D, 1).bake(), NoiseType.CLOVER, 0.9D)));
        cases.add(new Case("NONOCTAVE_BILLOW", seed -> ProgramShapes.style(new CNG(new RNG(seed), NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 9), NoiseType.FRACTAL_BILLOW_SIMPLEX, 0.9D)));
        cases.add(new Case("NOWHERE", seed -> ProgramShapes.style(CNG.signaturePerlin(new RNG(seed)).bake(), NoiseType.SIMPLEX, 0.9D)));
        cases.add(new Case("SIMPLEX_VASCULAR", seed -> ProgramShapes.style(new CNG(new RNG(seed).nextParallelRNG(1), NoiseType.SIMPLEX, 1D, 1).scale(1)
                .fractureWith(new CNG(new RNG(seed).nextParallelRNG(8), NoiseType.VASCULAR, 1D, 1).scale(1), 200), NoiseType.SIMPLEX, 0.9D)));
        cases.add(new Case("IRIS", seed -> ProgramShapes.style(CNG.signature(new RNG(seed)), NoiseType.SIMPLEX, 0.9D)));
        cases.add(new Case("IRIS_HALF", seed -> ProgramShapes.style(CNG.signatureHalf(new RNG(seed)), NoiseType.SIMPLEX, 0.9D)));
        cases.add(new Case("IRIS_DOUBLE", seed -> ProgramShapes.style(CNG.signatureDouble(new RNG(seed)), NoiseType.SIMPLEX, 0.9D)));
        cases.add(new Case("PERLIN_IRIS", seed -> ProgramShapes.style(CNG.signature(new RNG(seed), NoiseType.PERLIN), NoiseType.PERLIN, 0.9D)));
        cases.add(new Case("GLOB_IRIS_THICK", seed -> ProgramShapes.style(CNG.signatureThick(new RNG(seed), NoiseType.GLOB), NoiseType.GLOB, 0.9D)));
        cases.add(new Case("CELLULAR_IRIS_DOUBLE", seed -> ProgramShapes.style(CNG.signatureDouble(new RNG(seed), NoiseType.CELLULAR), NoiseType.CELLULAR, 0.9D)));
        cases.add(new Case("FRACTAL_SMOKE", seed -> ProgramShapes.style(new CNG(new RNG(seed), 1D, 1)
                .fractureWith(new CNG(new RNG(seed).nextParallelRNG(1), NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 8).scale(0.2), 1000), NoiseType.FRACTAL_BILLOW_SIMPLEX, 0.9D)));
        cases.add(new Case("FRACTAL_WATER", seed -> ProgramShapes.style(new CNG(new RNG(seed), 1D, 1)
                .fractureWith(new CNG(new RNG(seed).nextParallelRNG(1), NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 9).scale(0.03), 9900), NoiseType.FRACTAL_FBM_SIMPLEX, 0.9D)));
        cases.add(new Case("IRIS_DOUBLE<-NOWHERE", seed -> {
            CNG layer = ProgramShapes.style(CNG.signatureDouble(new RNG(seed)), NoiseType.SIMPLEX, 1D);
            layer.fractureWith(ProgramShapes.style(CNG.signaturePerlin(new RNG(seed).nextParallelRNG(2934)).bake(), NoiseType.SIMPLEX, 1D), 18);
            layer.zoom(0.9D);
            return layer;
        }));
        return cases;
    }

    record Case(String name, Function<Long, CNG> factory) {
    }
}
