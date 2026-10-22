package art.arcane.volmlib.util.noise;

import art.arcane.volmlib.util.VolmLog;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class NativeNoiseLibrary {
    public static final String POLICY_PROPERTY = "volmlib.native";
    public static final String LIBRARY_PROPERTY = "volmlib.native.library";
    private static final Resolution SHARED = open();

    private NativeNoiseLibrary() {
    }

    public static Resolution shared() {
        return SHARED;
    }

    public static Policy policy() {
        return switch (System.getProperty(POLICY_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT)) {
            case "on" -> Policy.ON;
            case "off" -> Policy.OFF;
            default -> Policy.AUTO;
        };
    }

    public static void report(Policy policy, String status) {
        if (policy == Policy.ON) {
            VolmLog.warning("Noise", "Native noise kernels unavailable (" + status + "); using Java");
        }
    }

    private static Resolution open() {
        Policy policy = policy();
        if (policy == Policy.OFF) {
            return new Resolution(null, "off", policy);
        }
        String operatingSystem = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String platform = operatingSystem.contains("mac") ? "macos" : operatingSystem.contains("linux") ? "linux"
                : operatingSystem.contains("win") ? "windows" : "unsupported";
        String architecture = switch (System.getProperty("os.arch")) {
            case "aarch64", "arm64" -> "aarch64";
            case "amd64", "x86_64" -> "x86_64";
            default -> "unsupported";
        };
        if (platform.equals("unsupported") || architecture.equals("unsupported")) {
            return failed(policy, "unsupported-platform");
        }
        Path libraryPath;
        try {
            String configured = System.getProperty(LIBRARY_PROPERTY);
            if (configured != null) {
                libraryPath = Path.of(configured);
                if (!libraryPath.isAbsolute()) {
                    return failed(policy, "library-path-not-absolute");
                }
                if (!Files.isRegularFile(libraryPath)) {
                    return failed(policy, "library-missing");
                }
            } else {
                libraryPath = extract(platform, architecture);
                if (libraryPath == null) {
                    return failed(policy, "library-not-packaged");
                }
            }
        } catch (IOException | RuntimeException failure) {
            VolmLog.fine("Noise", "Native noise library extraction failed", failure);
            return failed(policy, "library-extraction-failed");
        }
        boolean accessEnabled = NativeNoiseLibrary.class.getModule().isNativeAccessEnabled();
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, Arena.global());
            return new Resolution(lookup, accessEnabled ? "native" : "native-unflagged", policy);
        } catch (IllegalCallerException denied) {
            return failed(policy, "native-access-denied");
        } catch (IllegalArgumentException | LinkageError failure) {
            VolmLog.fine("Noise", "Native noise library could not be opened", failure);
            return failed(policy, "library-load-failed");
        }
    }

    private static Path extract(String platform, String architecture) throws IOException {
        String name = switch (platform) {
            case "macos" -> "libvolmlib_noise.dylib";
            case "windows" -> "volmlib_noise.dll";
            default -> "libvolmlib_noise.so";
        };
        String resource = "/art/arcane/volmlib/native/" + platform + "-" + architecture + "/" + name;
        try (InputStream stream = NativeNoiseLibrary.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            Path target = Files.createTempFile("volmlib-noise-", name.substring(name.lastIndexOf('.')));
            target.toFile().deleteOnExit();
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }

    private static Resolution failed(Policy policy, String status) {
        report(policy, status);
        return new Resolution(null, status, policy);
    }

    public enum Policy {
        AUTO,
        ON,
        OFF
    }

    public record Resolution(SymbolLookup lookup, String status, Policy policy) {
        public boolean available() {
            return lookup != null;
        }

        public boolean unflagged() {
            return status.equals("native-unflagged");
        }
    }
}
