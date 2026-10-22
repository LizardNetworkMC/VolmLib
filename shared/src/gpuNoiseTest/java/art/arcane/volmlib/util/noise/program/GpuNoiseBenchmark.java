package art.arcane.volmlib.util.noise.program;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class GpuNoiseBenchmark {
    private static final String[] STYLES = {"SIMPLEX", "SIMPLEX_VASCULAR", "PERLIN_IRIS", "IRIS", "FRACTAL_WATER"};
    private static final int[] COLUMNS = {2, 3, 9, 17, 33, 65};
    private static final int ROWS = 350;
    private static double sink;

    private GpuNoiseBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        String filter = arguments.length > 0 ? arguments[0] : "";
        int threads = arguments.length > 1 ? Integer.parseInt(arguments[1]) : 16;
        System.out.println("gpu status=" + GpuNoise.status() + " adapter=" + GpuNoise.adapter() + " exact backend=" + NoiseBackends.selected().id());
        if (!GpuNoise.available()) {
            return;
        }
        List<Bench> benches = new ArrayList<>();
        for (NoiseProgramBenchmark.Case benchmark : NoiseProgramBenchmark.cases()) {
            if (Arrays.asList(STYLES).contains(benchmark.name()) && benchmark.name().contains(filter)) {
                NoiseProgram program = NoiseProgram.compileSigned3D(benchmark.factory().apply(9001L));
                benches.add(new Bench(benchmark.name(), program, GpuNoise.compile(program)));
            }
        }
        for (int warm = 0; warm < 2; warm++) {
            for (Bench bench : benches) {
                for (int columns : COLUMNS) {
                    single(bench, columns, 1);
                }
            }
        }
        System.out.printf("%-18s %8s %9s | %9s %9s %9s %9s | %9s %9s | %8s %8s %8s%n", "style", "columns", "points",
                "java", "native", "gpuVal", "gpuBnd", "gpuMs", "natMs", "prepUs", "execUs", "copyUs");
        for (Bench bench : benches) {
            for (int columns : COLUMNS) {
                double[] result = single(bench, columns, 7);
                int points = columns * ROWS * columns;
                System.out.printf("%-18s %8s %9d | %9.2f %9.2f %9.2f %9.2f | %9.3f %9.3f | %8.0f %8.0f %8.0f%n", bench.name, columns + "x" + ROWS + "x" + columns,
                        points, result[0], result[1], result[2], result[3], result[3] * points / 1.0E6, result[1] * points / 1.0E6,
                        result[4] / 1.0E3, result[5] / 1.0E3, result[6] / 1.0E3);
            }
        }
        System.out.println("ns per point (median of 7), gpuMs/natMs = one call end to end on one thread");
        System.out.printf("%-18s | %14s %14s %14s %14s%n", "throughput", "native x" + threads, "gpu x1", "gpu x2", "gpu x4");
        for (Bench bench : benches) {
            double nativeRate = throughput(bench, threads, false);
            double gpuOne = throughput(bench, 1, true);
            double gpuTwo = throughput(bench, 2, true);
            double gpuFour = throughput(bench, 4, true);
            System.out.printf("%-18s | %14.1f %14.1f %14.1f %14.1f%n", bench.name, nativeRate, gpuOne, gpuTwo, gpuFour);
        }
        System.out.println("Mpoints per second on 65x350x65 region lattices; sink=" + sink);
        for (Bench bench : benches) {
            bench.gpu.close();
        }
    }

    private static double[] single(Bench bench, int columns, int rounds) {
        int points = columns * ROWS * columns;
        double[] exact = new double[points];
        float[] values = new float[points];
        double[] lo = new double[points];
        double[] hi = new double[points];
        NoiseScratch scratch = bench.program.newScratch();
        NoiseBackend java = NoiseBackends.java();
        double[][] samples = new double[7][rounds];
        int repeats = Math.max(1, 200_000 / points);
        for (int round = 0; round < rounds; round++) {
            double base = (round + 1) * 65_536D;
            long t0 = System.nanoTime();
            for (int repeat = 0; repeat < repeats; repeat++) {
                java.fillLattice3D(bench.program, scratch, base + repeat * 1024D, -64D, -base, 8D, 1D, 8D, columns, ROWS, columns, exact);
            }
            long t1 = System.nanoTime();
            for (int repeat = 0; repeat < repeats; repeat++) {
                bench.program.fillLattice3D(scratch, base + repeat * 1024D, -64D, -base, 8D, 1D, 8D, columns, ROWS, columns, exact);
            }
            long t2 = System.nanoTime();
            for (int repeat = 0; repeat < repeats; repeat++) {
                bench.gpu.fillLattice3D(base + repeat * 1024D, -64D, -base, 8D, 1D, 8D, columns, ROWS, columns, values);
            }
            long t3 = System.nanoTime();
            long[] timing = new long[3];
            for (int repeat = 0; repeat < repeats; repeat++) {
                bench.gpu.fillLatticeBounds(base + repeat * 1024D, -64D, -base, 8D, 1D, 8D, columns, ROWS, columns, lo, hi);
                long[] last = bench.gpu.lastTiming();
                for (int index = 0; index < 3; index++) {
                    timing[index] += last[index];
                }
            }
            long t4 = System.nanoTime();
            sink += exact[round % points] + values[round % points] + lo[round % points];
            double count = (double) points * repeats;
            samples[0][round] = (t1 - t0) / count;
            samples[1][round] = (t2 - t1) / count;
            samples[2][round] = (t3 - t2) / count;
            samples[3][round] = (t4 - t3) / count;
            for (int index = 0; index < 3; index++) {
                samples[4 + index][round] = timing[index] / (double) repeats;
            }
        }
        double[] result = new double[7];
        for (int index = 0; index < 7; index++) {
            result[index] = median(samples[index]);
        }
        return result;
    }

    private static double throughput(Bench bench, int threads, boolean gpu) throws Exception {
        int columns = 65;
        int points = columns * ROWS * columns;
        int perThread = gpu ? 6 : 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Double>> results = new ArrayList<>();
            long start = System.nanoTime();
            for (int thread = 0; thread < threads; thread++) {
                int salt = thread;
                results.add(pool.submit(() -> {
                    double[] lo = new double[points];
                    double[] hi = new double[points];
                    double[] exact = new double[points];
                    NoiseScratch scratch = bench.program.newScratch();
                    double accumulator = 0D;
                    for (int repeat = 0; repeat < perThread; repeat++) {
                        double x = salt * 100_000D + repeat * 1024D;
                        if (gpu) {
                            bench.gpu.fillLatticeBounds(x, -64D, 4096D, 8D, 1D, 8D, columns, ROWS, columns, lo, hi);
                            accumulator += lo[repeat];
                        } else {
                            bench.program.fillLattice3D(scratch, x, -64D, 4096D, 8D, 1D, 8D, columns, ROWS, columns, exact);
                            accumulator += exact[repeat];
                        }
                    }
                    return accumulator;
                }));
            }
            for (Future<Double> result : results) {
                sink += result.get();
            }
            double seconds = (System.nanoTime() - start) / 1.0E9;
            return (double) points * perThread * threads / seconds / 1.0E6;
        } finally {
            pool.shutdown();
        }
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private record Bench(String name, NoiseProgram program, GpuNoiseProgram gpu) {
    }
}
