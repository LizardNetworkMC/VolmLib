package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.function.NoiseInjector;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.noise.CNG;
import art.arcane.volmlib.util.noise.FastNoiseDouble;
import art.arcane.volmlib.util.noise.NoiseType;
import art.arcane.volmlib.util.noise.PerlinNoise;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.LongFunction;

final class NoiseBackendSelfTest {
    private static final double[] ORIGINS = {0D, -0.5D, 12345.25D, -29_999_999.5D, 29_999_872D, -4097.75D};
    private static final double[] STEPS = {1D, 0.25D, 3.7D, 4D};
    private static final int POINTS = 384;

    private NoiseBackendSelfTest() {
    }

    static String run(NoiseBackend backend) {
        long seed = 0x6a09e667f3bcc909L;
        for (Case check : cases()) {
            seed += 0x9e3779b97f4a7c15L;
            CNG cng = check.factory().apply(seed);
            NoiseProgram planar = NoiseProgram.compile2D(cng);
            NoiseProgram volume = NoiseProgram.compileSigned3D(cng);
            if (!backend.supports(planar) || !backend.supports(volume)) {
                continue;
            }
            String failure = compare(backend, planar, volume, new SplittableRandom(seed));
            if (failure != null) {
                return check.name() + " " + failure;
            }
        }
        return null;
    }

    private static String compare(NoiseBackend backend, NoiseProgram planar, NoiseProgram volume, SplittableRandom random) {
        NoiseBackend java = NoiseBackends.java();
        NoiseScratch scratch = new NoiseScratch();
        double[] expected = new double[POINTS];
        double[] actual = new double[POINTS];
        double[] xs = new double[POINTS];
        double[] zs = new double[POINTS];
        for (int index = 0; index < POINTS; index++) {
            xs[index] = coordinate(random);
            zs[index] = (index & 3) == 0 ? xs[index] : coordinate(random);
        }
        java.fill2D(planar, scratch, xs, zs, POINTS, expected);
        backend.fill2D(planar, scratch, xs, zs, POINTS, actual);
        if (differs(expected, actual, POINTS)) {
            return "fill2D";
        }
        double[] ys = new double[POINTS];
        for (int index = 0; index < POINTS; index++) {
            ys[index] = (index & 1) == 0 ? random.nextDouble(-64D, 320D) : coordinate(random);
        }
        java.fillPoints3D(volume, scratch, xs, ys, zs, POINTS, expected);
        backend.fillPoints3D(volume, scratch, xs, ys, zs, POINTS, actual);
        if (differs(expected, actual, POINTS)) {
            return "fillPoints3D";
        }
        for (double origin : ORIGINS) {
            double step = STEPS[random.nextInt(STEPS.length)];
            double z0 = coordinate(random);
            java.fillGrid2D(planar, scratch, origin, z0, step, 16, 16, expected);
            backend.fillGrid2D(planar, scratch, origin, z0, step, 16, 16, actual);
            if (differs(expected, actual, 256)) {
                return "fillGrid2D";
            }
            double y0 = random.nextInt(-64, 320);
            java.fillColumn3D(volume, scratch, origin, z0, y0, step, 96, expected);
            backend.fillColumn3D(volume, scratch, origin, z0, y0, step, 96, actual);
            if (differs(expected, actual, 96)) {
                return "fillColumn3D";
            }
            java.fillLattice3D(volume, scratch, origin, y0, z0, step, 2D, 0.5D, 5, 7, 6, expected);
            backend.fillLattice3D(volume, scratch, origin, y0, z0, step, 2D, 0.5D, 5, 7, 6, actual);
            if (differs(expected, actual, 210)) {
                return "fillLattice3D";
            }
        }
        return null;
    }

    private static boolean differs(double[] expected, double[] actual, int count) {
        for (int index = 0; index < count; index++) {
            if (Double.doubleToRawLongBits(expected[index]) != Double.doubleToRawLongBits(actual[index])) {
                return true;
            }
        }
        return false;
    }

    private static double coordinate(SplittableRandom random) {
        return switch (random.nextInt(4)) {
            case 0 -> random.nextDouble(-4096D, 4096D);
            case 1 -> random.nextInt(-30_000_000, 30_000_001);
            case 2 -> random.nextDouble(-30_000_000D, 30_000_000D);
            default -> ORIGINS[random.nextInt(ORIGINS.length)];
        };
    }

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>(32);
        NoiseType[] primitives = {NoiseType.SIMPLEX, NoiseType.PERLIN, NoiseType.FRACTAL_BILLOW_SIMPLEX, NoiseType.FRACTAL_FBM_SIMPLEX,
                NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, NoiseType.CELLULAR, NoiseType.VASCULAR, NoiseType.GLOB, NoiseType.WHITE};
        for (NoiseType type : primitives) {
            cases.add(new Case(type.name(), seed -> style(new CNG(new RNG(seed), type, 1D, 1), type.getCoordinateScale(), 0.73D)));
            cases.add(new Case("signature:" + type.name(), seed -> style(CNG.signatureDouble(new RNG(seed), type), type.getCoordinateScale(), 0.9D)));
        }
        cases.add(new Case("octaves:SIMPLEX", seed -> style(new CNG(new RNG(seed), NoiseType.SIMPLEX, 1D, 3), 1.5D, 0.5D)));
        cases.add(new Case("octaves:PERLIN", seed -> style(new CNG(new RNG(seed), NoiseType.PERLIN, 1D, 5), 1.5D, 2.5D)));
        cases.add(new Case("octaves:BILLOW", seed -> style(new CNG(new RNG(seed), NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 9), 1.5D, 0.2D)));
        cases.add(new Case("octaves:FBM", seed -> style(new CNG(new RNG(seed), NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 8), 1.5D, 0.4D)));
        cases.add(new Case("octaves:RIGID", seed -> style(new CNG(new RNG(seed), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 4), 1.5D, 0.8D)));
        cases.add(new Case("longerp:HERMITE", seed -> perlin(seed, FastNoiseDouble.Longerp.Hermite)));
        cases.add(new Case("longerp:LINEAR", seed -> perlin(seed, FastNoiseDouble.Longerp.Linear)));
        cases.add(new Case("fracture:WATER", seed -> style(new CNG(new RNG(seed), 1D, 1)
                .fractureWith(new CNG(new RNG(seed).nextParallelRNG(1), NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 9).scale(0.03), 9900), 1.5D, 1.8D)));
        cases.add(new Case("fracture:WHITE", seed -> style(new CNG(new RNG(seed), NoiseType.SIMPLEX, 1D, 1)
                .fractureWith(new CNG(new RNG(seed + 2L), NoiseType.WHITE, 1D, 1), 12), 1.5D, 1.2D)));
        cases.add(new Case("opacity", seed -> style(new CNG(new RNG(seed), NoiseType.SIMPLEX, 0.7D, 1)
                .fractureWith(new CNG(new RNG(seed + 3L), NoiseType.PERLIN, 0.45D, 1), 60), 1.5D, 1.1D)));
        cases.add(new Case("post", seed -> style(new CNG(new RNG(seed), NoiseType.SIMPLEX, 1D, 1).up(0.25D).down(0.1D).patch(0.6D)
                .fractureWith(new CNG(new RNG(seed + 1L), 1D, 1).patch(1.4D), 33), 1.5D, 0.9D)));
        NoiseInjector[] injectors = {CNG.ADD, CNG.SRC_SUBTRACT, CNG.DST_SUBTRACT, CNG.MULTIPLY, CNG.MAX, CNG.MIN, CNG.SRC_MOD, CNG.DST_MOD};
        for (int index = 0; index < injectors.length; index++) {
            NoiseInjector injector = injectors[index];
            cases.add(new Case("children:" + index, seed -> children(seed, injector)));
        }
        return cases;
    }

    private static CNG perlin(long seed, FastNoiseDouble.Longerp longerp) {
        PerlinNoise perlin = new PerlinNoise(seed);
        perlin.getFastNoise().setLongerp(longerp);
        return style(new CNG(new RNG(seed), perlin.offset(seed + 1L), 1D, 1)
                .fractureWith(new CNG(new RNG(seed + 2L), NoiseType.PERLIN, 1D, 1), 40), 1.5D, 1.3D);
    }

    private static CNG children(long seed, NoiseInjector injector) {
        CNG root = new CNG(new RNG(seed), NoiseType.SIMPLEX, 1D, 1)
                .fractureWith(new CNG(new RNG(seed + 1L), NoiseType.PERLIN, 1D, 1), 20);
        root.child(new CNG(new RNG(seed + 2L), NoiseType.PERLIN, 1D, 1));
        root.child(new CNG(new RNG(seed + 3L), NoiseType.CELLULAR, 1D, 1).fractureWith(new CNG(new RNG(seed + 4L), 1D, 1), 15));
        root.injectWith(injector);
        return style(root, 1.5D, 0.85D);
    }

    private static CNG style(CNG cng, double coordinateScale, double zoom) {
        CNG styled = cng.bake().scale(coordinateScale).bake();
        styled.zoom(zoom);
        return styled;
    }

    private record Case(String name, LongFunction<CNG> factory) {
    }
}
