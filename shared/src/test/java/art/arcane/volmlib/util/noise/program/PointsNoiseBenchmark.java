package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.noise.CNG;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PointsNoiseBenchmark {
    private static final int ROUNDS = 9;
    private static final int TOTAL = 96 * 1024;
    private static final int[] BATCHES = {48, 256, 1024};
    private static double sink;

    private PointsNoiseBenchmark() {
    }

    public static void main(String[] args) {
        String filter = args.length > 0 ? args[0] : "";
        System.out.println("selected backend " + NoiseBackends.selected().id() + " (native status " + NoiseBackends.nativeStatus() + ")");
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
        StringBuilder header = new StringBuilder(String.format("%-22s %-7s | %7s", "style", "backend", "sample"));
        for (int batch : BATCHES) {
            header.append(String.format(" | %7s %7s %5s %5s", "j" + batch, "n" + batch, "x-j", "x-n"));
        }
        System.out.println(header);
        for (NoiseProgramBenchmark.Case benchmark : cases) {
            double[] result = measure(benchmark, 100, ROUNDS);
            StringBuilder line = new StringBuilder(String.format("%-22s %-7s | %7.1f", benchmark.name(), backendName(benchmark), result[0]));
            for (int batch = 0; batch < BATCHES.length; batch++) {
                double java = result[1 + batch * 2];
                double selected = result[2 + batch * 2];
                line.append(String.format(" | %7.1f %7.1f %5.2f %5.2f", java, selected, result[0] / java, result[0] / selected));
            }
            System.out.println(line);
        }
        System.out.println("ns per sample at warped points (sample = sampleSigned3D, j = Java batch fillPoints3D, n = selected backend fillPoints3D), median of "
                + ROUNDS + " rounds; sink=" + sink);
    }

    private static String backendName(NoiseProgramBenchmark.Case benchmark) {
        return NoiseProgram.compileSigned3D(benchmark.factory().apply(9001L)).backend().id();
    }

    private static double[] measure(NoiseProgramBenchmark.Case benchmark, int salt, int rounds) {
        CNG cng = benchmark.factory().apply(9001L);
        NoiseProgram volume = NoiseProgram.compileSigned3D(cng);
        NoiseBackend java = NoiseBackends.java();
        NoiseScratch scratch = new NoiseScratch();
        double[] xs = new double[TOTAL];
        double[] ys = new double[TOTAL];
        double[] zs = new double[TOTAL];
        double[] out = new double[TOTAL];
        int columns = 1 + BATCHES.length * 2;
        double[][] samples = new double[columns][rounds];
        for (int round = 0; round < rounds; round++) {
            warped(xs, ys, zs, (salt * 31 + round) * 4096D);
            double acc = 0D;
            long t0 = System.nanoTime();
            for (int index = 0; index < TOTAL; index++) {
                acc += volume.sampleSigned3D(xs[index], ys[index], zs[index]);
            }
            samples[0][round] = (System.nanoTime() - t0) / (double) TOTAL;
            for (int batch = 0; batch < BATCHES.length; batch++) {
                int size = BATCHES[batch];
                int chunks = TOTAL / size;
                double[][] bx = split(xs, size, chunks);
                double[][] by = split(ys, size, chunks);
                double[][] bz = split(zs, size, chunks);
                long t1 = System.nanoTime();
                for (int chunk = 0; chunk < chunks; chunk++) {
                    java.fillPoints3D(volume, scratch, bx[chunk], by[chunk], bz[chunk], size, out);
                    acc += out[size - 1];
                }
                long t2 = System.nanoTime();
                for (int chunk = 0; chunk < chunks; chunk++) {
                    volume.fillPoints3D(scratch, bx[chunk], by[chunk], bz[chunk], size, out);
                    acc += out[size - 1];
                }
                long t3 = System.nanoTime();
                double processed = chunks * (double) size;
                samples[1 + batch * 2][round] = (t2 - t1) / processed;
                samples[2 + batch * 2][round] = (t3 - t2) / processed;
            }
            sink += acc;
        }
        double[] medians = new double[columns];
        for (int index = 0; index < columns; index++) {
            medians[index] = median(samples[index]);
        }
        return medians;
    }

    private static double[][] split(double[] values, int size, int chunks) {
        double[][] split = new double[chunks][];
        for (int chunk = 0; chunk < chunks; chunk++) {
            split[chunk] = Arrays.copyOfRange(values, chunk * size, chunk * size + size);
        }
        return split;
    }

    private static void warped(double[] xs, double[] ys, double[] zs, double origin) {
        int index = 0;
        for (int column = 0; index < xs.length; column++) {
            double x = origin + (column % 9) * 8D;
            double z = -origin + (column / 9) * 8D;
            for (int y = -64; y < 192 && index < xs.length; y++) {
                double a = Math.sin(column * 0.37D + y * 0.11D);
                double b = Math.cos(column * 0.23D - y * 0.07D);
                xs[index] = x + (a * 14.5D);
                ys[index] = y + (b * 14.5D);
                zs[index] = z + ((a - b) * 0.5D * 14.5D);
                index++;
            }
        }
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
