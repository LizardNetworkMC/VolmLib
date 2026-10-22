package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.noise.CNG;
import art.arcane.volmlib.util.noise.NoiseType;
import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GpuNoiseProgramTest {
    private static final double[][] ORIGINS = {
            {-1_337.25D, -64D, 2_048.5D},
            {29_999_488D, 17D, -29_999_872D},
            {-12_582_917.75D, 211.5D, 7_340_033.125D}
    };

    @Test
    public void certifiedBoundsContainExactValuesOnEveryAcceptedShape() throws Exception {
        Assume.assumeTrue(GpuNoise.available());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        List<Future<String>> results = new ArrayList<>();
        long seed = 4242L;
        for (ProgramShapes.Shape shape : ProgramShapes.all()) {
            seed += 7919L;
            long shapeSeed = seed;
            results.add(pool.submit(() -> check(shape, shapeSeed, true)));
            results.add(pool.submit(() -> check(shape, shapeSeed, false)));
        }
        List<String> failures = new ArrayList<>();
        int accepted = 0;
        for (Future<String> result : results) {
            String outcome = result.get();
            if (outcome.equals("accepted")) {
                accepted++;
            } else if (!outcome.equals("declined")) {
                failures.add(outcome);
            }
        }
        pool.shutdown();
        assertTrue(String.join("\n", failures), failures.isEmpty());
        assertTrue("accepted programs " + accepted, accepted >= 150);
    }

    @Test
    public void unsupportedProgramsAreDeclined() {
        Assume.assumeTrue(GpuNoise.available());
        CNG white = ProgramShapes.style(new CNG(new RNG(5L), NoiseType.WHITE, 1D, 1), NoiseType.WHITE, 1D);
        assertNull(GpuNoise.compile(NoiseProgram.compileSigned3D(white)));
        CNG whiteFracture = new CNG(new RNG(6L), NoiseType.SIMPLEX, 1D, 1).fractureWith(new CNG(new RNG(7L), NoiseType.WHITE, 1D, 1), 12);
        assertNull(GpuNoise.compile(NoiseProgram.compileSigned3D(whiteFracture)));
        CNG modulo = new CNG(new RNG(8L), NoiseType.SIMPLEX, 1D, 1);
        modulo.child(new CNG(new RNG(9L), NoiseType.PERLIN, 1D, 1));
        modulo.injectWith(CNG.SRC_MOD);
        assertNull(GpuNoise.compile(NoiseProgram.compile2D(modulo)));
        CNG clover = ProgramShapes.style(new CNG(new RNG(10L), NoiseType.CLOVER, 1D, 1), NoiseType.CLOVER, 1D);
        assertNull(GpuNoise.compile(NoiseProgram.compileSigned3D(clover)));
    }

    @Test
    public void oversizedAndClosedDispatchesDecline() {
        Assume.assumeTrue(GpuNoise.available());
        NoiseProgram program = NoiseProgram.compileSigned3D(ProgramShapes.style(CNG.signature(new RNG(11L), NoiseType.PERLIN), NoiseType.PERLIN, 0.7D));
        GpuNoiseProgram gpu = GpuNoise.compile(program);
        assertTrue(gpu != null);
        double[] lo = new double[66 * 2 * 66];
        double[] hi = new double[lo.length];
        assertTrue(gpu.fillLatticeBounds(0D, 0D, 0D, 16D, 1D, 16D, 65, 2, 65, lo, hi));
        assertFalse(gpu.fillLatticeBounds(0D, 0D, 0D, 16D, 1D, 16D, 66, 2, 66, lo, hi));
        assertFalse(gpu.fillLatticeBounds(0D, 0D, 0D, 1D, 1D, 1D, 0, 2, 2, lo, hi));
        assertFalse(gpu.fillGridBounds(0D, 0D, 1D, 4, 4, lo, hi));
        long[] timing = gpu.lastTiming();
        assertTrue(timing[0] >= 0L && timing[1] > 0L && timing[2] >= 0L);
        gpu.close();
        gpu.close();
        assertFalse(gpu.fillLatticeBounds(0D, 0D, 0D, 1D, 1D, 1D, 2, 2, 2, lo, hi));
    }

    @Test
    public void uncalibratedProgramsCertifyNothing() {
        Assume.assumeTrue(GpuNoise.available());
        NoiseProgram program = NoiseProgram.compile2D(ProgramShapes.style(CNG.signature(new RNG(12L)), NoiseType.SIMPLEX, 0.8D));
        GpuNoiseProgram gpu = GpuNoise.compile(program, 0);
        assertTrue(gpu != null);
        double[] lo = new double[64];
        double[] hi = new double[64];
        assertTrue(gpu.fillGridBounds(100D, -100D, 2D, 8, 8, lo, hi));
        for (int index = 0; index < 64; index++) {
            assertEquals(Double.NEGATIVE_INFINITY, lo[index], 0D);
            assertEquals(Double.POSITIVE_INFINITY, hi[index], 0D);
        }
        gpu.close();
    }

    @Test
    public void policyOffDisablesTheGpu() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Dvolmlib.gpu=off");
        command.add("-cp");
        command.add(System.getProperty("volmlib.test.runtimeClasspath", System.getProperty("java.class.path")));
        command.add(GpuNoiseStatusProbe.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue("Probe timed out", process.waitFor(120L, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(output, output.contains("status=off available=false"));
    }

    @Test
    public void packagedLibraryExposesTheGpu() {
        Assume.assumeTrue(Boolean.getBoolean("volmlib.test.gpuNoise"));
        assertTrue(GpuNoise.status(), GpuNoise.status().equals("gpu") || GpuNoise.status().equals("no-adapter"));
    }

    private static String check(ProgramShapes.Shape shape, long seed, boolean volumetric) {
        CNG cng = shape.create(seed);
        NoiseProgram program = volumetric ? NoiseProgram.compileSigned3D(cng) : NoiseProgram.compile2D(cng);
        GpuNoiseProgram gpu = GpuNoise.compile(program);
        if (gpu == null) {
            return "declined";
        }
        try (gpu) {
            NoiseScratch scratch = program.newScratch();
            int nx = volumetric ? 17 : 129;
            int ny = volumetric ? 120 : 1;
            int nz = nx;
            int count = nx * ny * nz;
            double[] exact = new double[count];
            double[] lo = new double[count];
            double[] hi = new double[count];
            for (double[] origin : ORIGINS) {
                boolean filled;
                if (volumetric) {
                    program.fillLattice3D(scratch, origin[0], origin[1], origin[2], 8D, 1D, 8D, nx, ny, nz, exact);
                    filled = gpu.fillLatticeBounds(origin[0], origin[1], origin[2], 8D, 1D, 8D, nx, ny, nz, lo, hi);
                } else {
                    program.fillGrid2D(scratch, origin[0], origin[2], 4D, nx, nz, exact);
                    filled = gpu.fillGridBounds(origin[0], origin[2], 4D, nx, nz, lo, hi);
                }
                if (!filled) {
                    return shape.name() + (volumetric ? " 3D" : " 2D") + ": dispatch declined";
                }
                for (int index = 0; index < count; index++) {
                    if (!(lo[index] <= exact[index] && exact[index] <= hi[index])) {
                        return shape.name() + (volumetric ? " 3D" : " 2D") + ": " + exact[index] + " outside [" + lo[index] + ", " + hi[index] + "] at "
                                + index + " origin " + origin[0] + "," + origin[1] + "," + origin[2];
                    }
                }
            }
            return "accepted";
        }
    }
}
