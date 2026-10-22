package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.noise.CNG;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class NativeNoiseBenchmark {
    private static final int CHUNKS_2D = 300;
    private static final int BOXES_3D = 6;
    private static final int HEIGHT = 64;
    private static final int ROUNDS = 9;
    private static double sink;

    private NativeNoiseBenchmark() {
    }

    public static void main(String[] args) {
        String filter = args.length > 0 ? args[0] : "";
        NoiseBackend selected = NoiseBackends.selected();
        System.out.println("selected backend " + selected.id() + " (native status " + NoiseBackends.nativeStatus() + ")");
        List<NoiseProgramBenchmark.Case> cases = new ArrayList<>();
        for (NoiseProgramBenchmark.Case candidate : NoiseProgramBenchmark.cases()) {
            if (candidate.name().contains(filter)) {
                cases.add(candidate);
            }
        }
        for (int warm = 0; warm < 3; warm++) {
            for (NoiseProgramBenchmark.Case benchmark : cases) {
                measure(benchmark, warm, 1);
            }
        }
        System.out.printf("%-22s %-7s | %7s %7s %5s | %7s %7s %5s | %7s %7s %5s | %7s %7s %5s%n", "style", "backend",
                "grid-j", "grid-n", "x", "fill-j", "fill-n", "x", "lat-j", "lat-n", "x", "col-j", "col-n", "x");
        for (NoiseProgramBenchmark.Case benchmark : cases) {
            double[] result = measure(benchmark, 100, ROUNDS);
            System.out.printf("%-22s %-7s | %7.1f %7.1f %5.2f | %7.1f %7.1f %5.2f | %7.1f %7.1f %5.2f | %7.1f %7.1f %5.2f%n", benchmark.name(),
                    backendName(benchmark),
                    result[0], result[1], result[0] / result[1], result[2], result[3], result[2] / result[3],
                    result[4], result[5], result[4] / result[5], result[6], result[7], result[6] / result[7]);
        }
        System.out.println("ns per sample (j = Java batch backend, n = selected backend), median of " + ROUNDS + " rounds; sink=" + sink);
    }

    private static String backendName(NoiseProgramBenchmark.Case benchmark) {
        CNG cng = benchmark.factory().apply(9001L);
        return NoiseProgram.compile2D(cng).backend().id() + "/" + NoiseProgram.compileSigned3D(cng).backend().id();
    }

    private static double[] measure(NoiseProgramBenchmark.Case benchmark, int salt, int rounds) {
        CNG cng = benchmark.factory().apply(9001L);
        NoiseProgram planar = NoiseProgram.compile2D(cng);
        NoiseProgram volume = NoiseProgram.compileSigned3D(cng);
        NoiseBackend java = NoiseBackends.java();
        NoiseScratch scratch = new NoiseScratch();
        double[] out = new double[16 * HEIGHT * 16];
        double[] xs = new double[256];
        double[] zs = new double[256];
        double[][] samples = new double[8][rounds];
        double count2D = CHUNKS_2D * 256D;
        double count3D = BOXES_3D * 256D * HEIGHT;
        for (int round = 0; round < rounds; round++) {
            double base = (salt * 31 + round) * 65536D;
            double acc = 0D;
            long t0 = System.nanoTime();
            for (int chunk = 0; chunk < CHUNKS_2D; chunk++) {
                java.fillGrid2D(planar, scratch, base + (chunk % 30) * 16, -base + (chunk / 30) * 16, 1D, 16, 16, out);
                acc += out[chunk & 255];
            }
            long t1 = System.nanoTime();
            for (int chunk = 0; chunk < CHUNKS_2D; chunk++) {
                planar.fillGrid2D(scratch, base + (chunk % 30) * 16, -base + (chunk / 30) * 16, 1D, 16, 16, out);
                acc += out[chunk & 255];
            }
            long t2 = System.nanoTime();
            for (int chunk = 0; chunk < CHUNKS_2D; chunk++) {
                scatter(xs, zs, base + chunk * 7.25D, -base - chunk * 3.5D);
                java.fill2D(planar, scratch, xs, zs, 256, out);
                acc += out[chunk & 255];
            }
            long t3 = System.nanoTime();
            for (int chunk = 0; chunk < CHUNKS_2D; chunk++) {
                scatter(xs, zs, base + chunk * 7.25D, -base - chunk * 3.5D);
                planar.fill2D(scratch, xs, zs, 256, out);
                acc += out[chunk & 255];
            }
            long t4 = System.nanoTime();
            for (int box = 0; box < BOXES_3D; box++) {
                java.fillLattice3D(volume, scratch, base + box * 16, -32D, -base, 1D, 1D, 1D, 16, HEIGHT, 16, out);
                acc += out[box];
            }
            long t5 = System.nanoTime();
            for (int box = 0; box < BOXES_3D; box++) {
                volume.fillLattice3D(scratch, base + box * 16, -32D, -base, 1D, 1D, 1D, 16, HEIGHT, 16, out);
                acc += out[box];
            }
            long t6 = System.nanoTime();
            for (int box = 0; box < BOXES_3D; box++) {
                for (int column = 0; column < 256; column++) {
                    java.fillColumn3D(volume, scratch, base + box * 16 + (column & 15), -base + (column >> 4), -32D, 1D, HEIGHT, out);
                    acc += out[column & 63];
                }
            }
            long t7 = System.nanoTime();
            for (int box = 0; box < BOXES_3D; box++) {
                for (int column = 0; column < 256; column++) {
                    volume.fillColumn3D(scratch, base + box * 16 + (column & 15), -base + (column >> 4), -32D, 1D, HEIGHT, out);
                    acc += out[column & 63];
                }
            }
            long t8 = System.nanoTime();
            sink += acc;
            samples[0][round] = (t1 - t0) / count2D;
            samples[1][round] = (t2 - t1) / count2D;
            samples[2][round] = (t3 - t2) / count2D;
            samples[3][round] = (t4 - t3) / count2D;
            samples[4][round] = (t5 - t4) / count3D;
            samples[5][round] = (t6 - t5) / count3D;
            samples[6][round] = (t7 - t6) / count3D;
            samples[7][round] = (t8 - t7) / count3D;
        }
        double[] medians = new double[8];
        for (int index = 0; index < 8; index++) {
            medians[index] = median(samples[index]);
        }
        return medians;
    }

    private static void scatter(double[] xs, double[] zs, double x, double z) {
        for (int index = 0; index < 256; index++) {
            xs[index] = x + (index * 37 % 256) * 1.5D;
            zs[index] = z + (index * 91 % 256) * 0.75D;
        }
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
