package art.arcane.volmlib.util.noise.program;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NoiseBackendSelfTestTest {
    @Test
    public void referenceBackendsPassTheSelfTest() {
        assertNull(NoiseBackendSelfTest.run(NoiseBackends.java()));
        assertNull(NoiseBackendSelfTest.run(NoiseBackends.scalar()));
    }

    @Test
    public void singleBitDifferencesFailTheSelfTest() {
        for (String api : new String[]{"fill2D", "fillGrid2D", "fillColumn3D", "fillLattice3D"}) {
            String failure = NoiseBackendSelfTest.run(new PerturbedBackend(api));
            assertNotNull(api, failure);
            assertTrue(failure, failure.endsWith(api));
        }
    }

    @Test
    public void backendsThatDeclineFailTheSelfTest() {
        String failure = NoiseBackendSelfTest.run(new PerturbedBackend("") {
            @Override
            public boolean supports(NoiseProgram program) {
                return false;
            }
        });
        assertNotNull(failure);
        assertTrue(failure, failure.endsWith("is not supported"));
    }

    private static class PerturbedBackend implements NoiseBackend {
        private final String api;

        PerturbedBackend(String api) {
            this.api = api;
        }

        @Override
        public String id() {
            return "perturbed";
        }

        @Override
        public boolean supports(NoiseProgram program) {
            return true;
        }

        @Override
        public void fill2D(NoiseProgram program, NoiseScratch scratch, double[] xs, double[] zs, int count, double[] out) {
            NoiseBackends.java().fill2D(program, scratch, xs, zs, count, out);
            perturb("fill2D", out, count);
        }

        @Override
        public void fillGrid2D(NoiseProgram program, NoiseScratch scratch, double x0, double z0, double step, int width, int depth, double[] out) {
            NoiseBackends.java().fillGrid2D(program, scratch, x0, z0, step, width, depth, out);
            perturb("fillGrid2D", out, width * depth);
        }

        @Override
        public void fillColumn3D(NoiseProgram program, NoiseScratch scratch, double x, double z, double y0, double yStep, int count, double[] out) {
            NoiseBackends.java().fillColumn3D(program, scratch, x, z, y0, yStep, count, out);
            perturb("fillColumn3D", out, count);
        }

        @Override
        public void fillLattice3D(NoiseProgram program, NoiseScratch scratch, double x0, double y0, double z0, double stepX, double stepY, double stepZ,
                                  int nx, int ny, int nz, double[] out) {
            NoiseBackends.java().fillLattice3D(program, scratch, x0, y0, z0, stepX, stepY, stepZ, nx, ny, nz, out);
            perturb("fillLattice3D", out, nx * ny * nz);
        }

        private void perturb(String call, double[] out, int count) {
            if (call.equals(api)) {
                int index = count / 2;
                out[index] = Double.longBitsToDouble(Double.doubleToRawLongBits(out[index]) ^ 1L);
            }
        }
    }
}
