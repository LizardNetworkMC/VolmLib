package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.noise.CNG;
import art.arcane.volmlib.util.noise.NoiseType;

public final class NativeNoiseProbe {
    private NativeNoiseProbe() {
    }

    public static void main(String[] arguments) {
        String expectedBackend = arguments[0];
        String expectedStatus = arguments[1];
        String backend = NoiseBackends.selected().id();
        String status = NoiseBackends.nativeStatus();
        if (!backend.equals(expectedBackend) || !status.equals(expectedStatus)) {
            throw new AssertionError("Expected " + expectedBackend + "/" + expectedStatus + " got " + backend + "/" + status);
        }
        CNG cng = ProgramShapes.style(CNG.signatureDouble(new RNG(77L), NoiseType.PERLIN), NoiseType.PERLIN, 0.8D);
        ProgramParity.Result result = ProgramParity.check(cng, 77L, new ProgramParity.Budget(2000, 1000, 4, 4, 4));
        NoiseProgram program = NoiseProgram.compileSigned3D(cng);
        if (result.totalMismatches() != 0L) {
            throw new AssertionError(result.totalMismatches() + " mismatches, first " + result.firstMismatch);
        }
        if (!program.backend().id().equals(expectedBackend)) {
            throw new AssertionError("Program backend " + program.backend().id());
        }
        System.out.println("backend=" + backend + " status=" + status + " compared=" + result.totalCompared());
    }
}
