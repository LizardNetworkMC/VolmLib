package art.arcane.volmlib.util.noise.program;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NativeNoiseBackendTest {
    @Test
    public void selectedNativeBackendIsBitExactOnEveryShape() {
        Assume.assumeTrue(NoiseBackends.nativeStatus().equals("native"));
        List<String> failures = new ArrayList<>();
        int nativePrograms = 0;
        long seed = 9001L;
        for (ProgramShapes.Shape shape : ProgramShapes.all()) {
            seed += 7919L;
            NoiseProgram program = NoiseProgram.compileSigned3D(shape.create(seed));
            if (program.backend().id().equals("native")) {
                nativePrograms++;
            }
            ProgramParity.Result result = ProgramParity.check(shape.create(seed), seed, new ProgramParity.Budget(2500, 1200, 5, 6, 6));
            if (result.totalMismatches() != 0L) {
                failures.add(shape.name() + ": " + result.totalMismatches() + " mismatches, first " + result.firstMismatch);
            }
        }
        assertTrue(String.join("\n", failures), failures.isEmpty());
        assertTrue("native programs " + nativePrograms, nativePrograms >= 75);
    }

    @Test
    public void loaderPolicyFallsBackToTheJavaBatchBackend() throws Exception {
        Assume.assumeTrue(supportedHost());
        probe("java", "off", List.of("-Dvolmlib.native=off"), false);
        probe("java", "not-requested", List.of("-Dvolmlib.noise.backend=java"), false);
        probe("java", "library-missing", List.of("-Dvolmlib.native.library=" + Path.of("build/missing-native-noise").toAbsolutePath()), false);
        probe("java", "library-path-not-absolute", List.of("-Dvolmlib.native.library=relative"), false);
        probe("java", "native-access-denied", List.of("--illegal-native-access=deny", "-Dvolmlib.native.library=" + Path.of("native/noise/Cargo.toml").toAbsolutePath()), false);
        probe("java", "library-not-packaged", List.of(), true);
        probe("java", "library-not-packaged", List.of("-Dvolmlib.noise.backend=native"), true);
    }

    @Test
    public void packagedNativeBackendLoadsWithAndWithoutTheAccessFlag() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("volmlib.test.nativeNoise") && supportedHost());
        probe("native", "native", List.of("--enable-native-access=ALL-UNNAMED"), false);
        probe("native", "native", List.of(), false);
        probe("native", "native", List.of("-Dvolmlib.native=on", "-Dvolmlib.noise.backend=native"), false);
        probe("java", "library-load-failed", List.of("-Dvolmlib.native.library=" + Path.of("native/noise/Cargo.toml").toAbsolutePath()), false);
    }

    @Test
    public void libraryWithoutTheNoiseSymbolsFallsBack() throws Exception {
        String library = System.getProperty("volmlib.test.missingSymbolLibrary");
        Assume.assumeTrue(library != null && supportedHost());
        probe("java", "initialization-failed", List.of("-Dvolmlib.native.library=" + library), false);
    }

    private static boolean supportedHost() {
        String operatingSystem = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return (operatingSystem.contains("mac") || operatingSystem.contains("linux") || operatingSystem.contains("win"))
                && List.of("aarch64", "arm64", "amd64", "x86_64").contains(System.getProperty("os.arch"));
    }

    private static void probe(String backend, String status, List<String> options, boolean withoutPackagedLibrary) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(options);
        command.add("-cp");
        String classpath = System.getProperty("volmlib.test.runtimeClasspath", System.getProperty("java.class.path"));
        if (withoutPackagedLibrary) {
            List<String> retained = new ArrayList<>();
            for (String entry : classpath.split(File.pathSeparator)) {
                if (!entry.endsWith("nativeNoiseResources")) {
                    retained.add(entry);
                }
            }
            classpath = String.join(File.pathSeparator, retained);
        }
        command.add(classpath);
        command.add(NativeNoiseProbe.class.getName());
        command.add(backend);
        command.add(status);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue("Probe timed out", process.waitFor(120L, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.exitValue());
    }
}
