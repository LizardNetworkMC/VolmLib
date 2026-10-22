package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.noise.CNG;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

public final class GpuCertificateCampaign {
    private static final double[] STEPS = {1D, 2D, 4D, 8D, 0.5D};

    private GpuCertificateCampaign() {
    }

    public static void main(String[] arguments) throws Exception {
        long pointsPerProgram = arguments.length > 0 ? Long.parseLong(arguments[0]) : 10_000_000L;
        String filter = arguments.length > 1 ? arguments[1] : "";
        int threads = arguments.length > 2 ? Integer.parseInt(arguments[2]) : Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        System.out.println("gpu status=" + GpuNoise.status() + " adapter=" + GpuNoise.adapter() + " exact backend=" + NoiseBackends.selected().id());
        if (!GpuNoise.available()) {
            return;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (NoiseProgramBenchmark.Case benchmark : NoiseProgramBenchmark.cases()) {
            candidates.add(new Candidate("style:" + benchmark.name(), benchmark.factory()));
        }
        for (ProgramShapes.Shape shape : ProgramShapes.all()) {
            candidates.add(new Candidate(shape.name(), shape::create));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> results = new ArrayList<>();
        long seed = 31337L;
        for (Candidate candidate : candidates) {
            if (!candidate.name().contains(filter)) {
                continue;
            }
            seed += 7919L;
            long programSeed = seed;
            for (boolean volumetric : new boolean[]{true, false}) {
                results.add(pool.submit(() -> run(candidate, programSeed, volumetric, pointsPerProgram)));
            }
        }
        System.out.printf("%-34s %-2s %5s | %8s %10s %10s | %11s %9s %10s %10s %7s %6s%n", "program", "d", "terms",
                "calib", "calibWorst", "margin", "points", "uncert%", "worst", "scale", "w/marg", "viol");
        long violations = 0L;
        double maxRatio = 0D;
        int accepted = 0;
        int declined = 0;
        for (Future<String> result : results) {
            String line = result.get();
            System.out.println(line);
            if (line.contains("declined")) {
                declined++;
                continue;
            }
            accepted++;
            String[] parts = line.trim().split("\\s+");
            violations += Long.parseLong(parts[parts.length - 1]);
            maxRatio = Math.max(maxRatio, Double.parseDouble(parts[parts.length - 2]));
        }
        pool.shutdown();
        System.out.println("accepted=" + accepted + " declined=" + declined + " violations=" + violations + " maxWorstOverMargin=" + maxRatio);
    }

    private static String run(Candidate candidate, long seed, boolean volumetric, long budget) {
        CNG cng = candidate.factory().apply(seed);
        NoiseProgram program = volumetric ? NoiseProgram.compileSigned3D(cng) : NoiseProgram.compile2D(cng);
        String dimension = volumetric ? "3" : "2";
        GpuNoiseProgram gpu = GpuNoise.compile(program);
        if (gpu == null) {
            return String.format("%-34s %-2s %5d | declined", candidate.name(), dimension, program.termCount);
        }
        try (gpu) {
            GpuNoiseProgram.Calibration calibration = gpu.calibration();
            SplittableRandom random = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + (volumetric ? 1L : 2L));
            NoiseScratch scratch = program.newScratch();
            long points = 0L;
            long uncertified = 0L;
            long violations = 0L;
            double worst = 0D;
            double scale = 0D;
            double margin = gpu.margin();
            int round = 0;
            while (points < budget) {
                int nx;
                int ny;
                int nz;
                double stepX;
                double stepY;
                int kind = round % 3;
                if (volumetric) {
                    nx = kind == 0 ? 33 : kind == 1 ? 65 : 48;
                    ny = kind == 0 ? 350 : kind == 1 ? 97 : 48;
                    nz = nx;
                    stepX = kind == 0 ? 8D : kind == 1 ? 16D : STEPS[random.nextInt(STEPS.length)];
                    stepY = kind == 0 ? 1D : kind == 1 ? 8D : STEPS[random.nextInt(STEPS.length)];
                } else {
                    nx = kind == 0 ? 129 : kind == 1 ? 257 : 384;
                    ny = 1;
                    nz = nx;
                    stepX = kind == 0 ? 8D : kind == 1 ? 4D : 2D;
                    stepY = stepX;
                }
                int count = nx * ny * nz;
                double[] origin = origin(random, round);
                double[] exact = new double[count];
                float[] approximate = new float[count];
                boolean filled;
                if (volumetric) {
                    program.fillLattice3D(scratch, origin[0], origin[1], origin[2], stepX, stepY, stepX, nx, ny, nz, exact);
                    filled = gpu.fillLattice3D(origin[0], origin[1], origin[2], stepX, stepY, stepX, nx, ny, nz, approximate);
                } else {
                    program.fillGrid2D(scratch, origin[0], origin[2], stepX, nx, nz, exact);
                    filled = gpu.fillGrid2D(origin[0], origin[2], stepX, nx, nz, approximate);
                }
                if (!filled) {
                    return String.format("%-34s %-2s %5d | dispatch-failed", candidate.name(), dimension, program.termCount);
                }
                for (int index = 0; index < count; index++) {
                    double expected = exact[index];
                    scale = Math.max(scale, Math.abs(expected));
                    if (!GpuNoiseProgram.certified(approximate[index])) {
                        uncertified++;
                        continue;
                    }
                    double delta = Math.abs(approximate[index] - expected);
                    worst = Math.max(worst, delta);
                    if (delta > margin) {
                        violations++;
                    }
                }
                points += count;
                round++;
            }
            return String.format("%-34s %-2s %5d | %8d %10.3e %10.3e | %11d %9.4f %10.3e %10.3e %7.3f %6d", candidate.name(), dimension,
                    program.termCount, calibration.points(), calibration.worstDelta(), margin, points, 100D * uncertified / points, worst, scale,
                    worst / margin, violations);
        }
    }

    private static double[] origin(SplittableRandom random, int round) {
        double[] origin = new double[3];
        int category = random.nextInt(5);
        for (int axis = 0; axis < 3; axis++) {
            boolean vertical = axis == 1;
            origin[axis] = switch (category) {
                case 0 -> Math.floor(random.nextDouble(-4096D, 4096D) / 16D) * 16D;
                case 1 -> vertical ? -64D : Math.floor(random.nextDouble(-30_000_000D, 30_000_000D) / 16D) * 16D;
                case 2 -> vertical ? random.nextDouble(-64D, 320D) : random.nextDouble(-30_000_000D, 30_000_000D);
                case 3 -> random.nextDouble(-1_048_576D, 1_048_576D);
                default -> vertical ? -64D : (round & 1) == 0 ? 29_999_488D : -29_999_872D;
            };
        }
        return origin;
    }

    private record Candidate(String name, Function<Long, CNG> factory) {
    }
}
