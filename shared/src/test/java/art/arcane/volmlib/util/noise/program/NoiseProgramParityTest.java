package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.noise.CNG;
import art.arcane.volmlib.util.noise.NoiseType;
import com.sun.management.ThreadMXBean;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NoiseProgramParityTest {
    @Test
    public void everyShapeIsBitExactOnEveryEntryPoint() {
        List<String> failures = new ArrayList<>();
        long seed = 1337L;
        for (ProgramShapes.Shape shape : ProgramShapes.all()) {
            seed += 7919L;
            ProgramParity.Result result = ProgramParity.check(shape.create(seed), seed, 2500, 1200, 5, 6, 6);
            if (result.totalMismatches() != 0L) {
                failures.add(shape.name() + ": " + result.totalMismatches() + " mismatches, first " + result.firstMismatch);
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void identicalGraphsShareSignaturesAndDistinctSeedsDoNot() {
        CNG first = ProgramShapes.style(CNG.signature(new RNG(42L), NoiseType.PERLIN), NoiseType.PERLIN, 0.8D);
        CNG same = ProgramShapes.style(CNG.signature(new RNG(42L), NoiseType.PERLIN), NoiseType.PERLIN, 0.8D);
        CNG other = ProgramShapes.style(CNG.signature(new RNG(43L), NoiseType.PERLIN), NoiseType.PERLIN, 0.8D);
        CNG zoomed = ProgramShapes.style(CNG.signature(new RNG(42L), NoiseType.PERLIN), NoiseType.PERLIN, 0.81D);
        assertEquals(NoiseProgram.compileSigned3D(first).structuralSignature(), NoiseProgram.compileSigned3D(same).structuralSignature());
        assertEquals(NoiseProgram.compile2D(first).structuralSignature(), NoiseProgram.compile2D(same).structuralSignature());
        assertNotEquals(NoiseProgram.compileSigned3D(first).structuralSignature(), NoiseProgram.compileSigned3D(other).structuralSignature());
        assertNotEquals(NoiseProgram.compileSigned3D(first).structuralSignature(), NoiseProgram.compileSigned3D(zoomed).structuralSignature());
        assertNotEquals(NoiseProgram.compile2D(first).structuralSignature(), NoiseProgram.compileSigned3D(first).structuralSignature());
    }

    @Test
    public void callbackGraphsNeverShareSignatures() {
        CNG first = new CNG(new RNG(5L), NoiseType.CLOVER, 1D, 1);
        CNG same = new CNG(new RNG(5L), NoiseType.CLOVER, 1D, 1);
        assertNotEquals(NoiseProgram.compile2D(first).structuralSignature(), NoiseProgram.compile2D(same).structuralSignature());
        NoiseProgram program = NoiseProgram.compile2D(first);
        assertEquals(program.structuralSignature(), program.structuralSignature());
    }

    @Test
    public void representabilityTracksCallbacksAndJavaMath() {
        assertTrue(NoiseProgram.compile2D(new CNG(new RNG(1L), 1D, 1)).isExactlyRepresentable());
        assertTrue(NoiseProgram.compileSigned3D(CNG.signatureDouble(new RNG(2L), NoiseType.CELLULAR)).isExactlyRepresentable());
        assertTrue(NoiseProgram.compileSigned3D(new CNG(new RNG(3L), 1D, 1)
                .fractureWith(new CNG(new RNG(4L), NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 9).scale(0.03), 9900)).isExactlyRepresentable());
        assertFalse(NoiseProgram.compile2D(new CNG(new RNG(5L), NoiseType.CLOVER, 1D, 1)).isExactlyRepresentable());
        assertFalse(NoiseProgram.compile2D(new CNG(new RNG(6L), NoiseType.CELLULAR_BILINEAR, 1D, 1)).isExactlyRepresentable());
        assertFalse(NoiseProgram.compile2D(CNG.signature(new RNG(7L)).pow(1.5D)).isExactlyRepresentable());
        CNG custom = new CNG(new RNG(8L), 1D, 1);
        custom.child(new CNG(new RNG(9L), 1D, 1));
        custom.injectWith((source, value) -> new double[]{source + value, 1D});
        assertFalse(NoiseProgram.compile2D(custom).isExactlyRepresentable());
    }

    @Test
    public void entryPointsRejectTheOtherDimensionality() {
        NoiseProgram planar = NoiseProgram.compile2D(new CNG(new RNG(1L), 1D, 1));
        NoiseProgram volume = NoiseProgram.compileSigned3D(new CNG(new RNG(1L), 1D, 1));
        assertThrows(IllegalStateException.class, () -> planar.sampleSigned3D(0D, 0D, 0D));
        assertThrows(IllegalStateException.class, () -> volume.sample2D(0D, 0D));
        assertThrows(IllegalStateException.class, () -> volume.fill2D(volume.newScratch(), new double[1], new double[1], 1, new double[1]));
        assertThrows(IllegalStateException.class, () -> planar.fillColumn3D(planar.newScratch(), 0D, 0D, 0D, 1D, 1, new double[1]));
    }

    @Test
    public void cyclicGraphsAreRejected() {
        CNG first = new CNG(new RNG(1L), 1D, 1);
        CNG second = new CNG(new RNG(2L), 1D, 1);
        first.fractureWith(second, 10);
        second.fractureWith(first, 10);
        assertThrows(IllegalArgumentException.class, () -> NoiseProgram.compile2D(first));
    }

    @Test
    public void batchFillsDoNotAllocateOnceWarm() {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        CNG cng = ProgramShapes.style(CNG.signature(new RNG(77L), NoiseType.PERLIN), NoiseType.PERLIN, 0.7D);
        NoiseProgram planar = NoiseProgram.compile2D(cng);
        NoiseProgram volume = NoiseProgram.compileSigned3D(cng);
        NoiseScratch scratch = planar.newScratch();
        double[] xs = new double[1000];
        double[] zs = new double[1000];
        for (int index = 0; index < xs.length; index++) {
            xs[index] = index * 1.7D;
            zs[index] = index * -0.3D;
        }
        double[] out = new double[16 * 64 * 16];
        for (int round = 0; round < 3; round++) {
            runFills(planar, volume, scratch, xs, zs, out);
        }
        long thread = Thread.currentThread().getId();
        long before = threads.getThreadAllocatedBytes(thread);
        runFills(planar, volume, scratch, xs, zs, out);
        long allocated = threads.getThreadAllocatedBytes(thread) - before;
        assertTrue("allocated " + allocated + " bytes", allocated < 1024L);
    }

    private static void runFills(NoiseProgram planar, NoiseProgram volume, NoiseScratch scratch, double[] xs, double[] zs, double[] out) {
        planar.fill2D(scratch, xs, zs, xs.length, out);
        planar.fillGrid2D(scratch, 100D, -200D, 1D, 16, 16, out);
        volume.fillColumn3D(scratch, 12D, 34D, -64D, 2D, 192, out);
        volume.fillLattice3D(scratch, 0D, -64D, 0D, 2D, 2D, 2D, 16, 64, 16, out);
    }
}
