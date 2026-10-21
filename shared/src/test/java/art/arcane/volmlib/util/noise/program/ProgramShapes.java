package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.cache.FloatCache;
import art.arcane.volmlib.util.function.NoiseInjector;
import art.arcane.volmlib.util.math.RNG;
import art.arcane.volmlib.util.noise.CNG;
import art.arcane.volmlib.util.noise.NoiseGenerator;
import art.arcane.volmlib.util.noise.NoiseType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class ProgramShapes {
    private static final NoiseType[] SIGNATURE_TYPES = {
            NoiseType.SIMPLEX, NoiseType.PERLIN, NoiseType.CELLULAR, NoiseType.GLOB, NoiseType.VASCULAR,
            NoiseType.FRACTAL_BILLOW_SIMPLEX, NoiseType.FRACTAL_FBM_SIMPLEX, NoiseType.CELLULAR_HEIGHT, NoiseType.CUBIC,
            NoiseType.CLOVER, NoiseType.HEXAGON, NoiseType.CELLULAR_BILINEAR, NoiseType.WHITE
    };

    private ProgramShapes() {
    }

    static List<Shape> all() {
        List<Shape> shapes = new ArrayList<>(256);
        for (NoiseType type : NoiseType.values()) {
            shapes.add(new Shape("type:" + type.name(), seed -> style(new CNG(new RNG(seed), type, 1D, 1), type, 0.73D)));
        }
        for (NoiseType type : SIGNATURE_TYPES) {
            shapes.add(new Shape("signature:" + type.name(), seed -> style(CNG.signature(new RNG(seed), type), type, 0.81D)));
            shapes.add(new Shape("signatureHalf:" + type.name(), seed -> style(CNG.signatureHalf(new RNG(seed), type), type, 1.17D)));
            shapes.add(new Shape("signatureThick:" + type.name(), seed -> style(CNG.signatureThick(new RNG(seed), type), type, 0.42D)));
            shapes.add(new Shape("signatureDouble:" + type.name(), seed -> style(CNG.signatureDouble(new RNG(seed), type), type, 0.9D)));
            shapes.add(new Shape("signaturePerlin:" + type.name(), seed -> style(CNG.signaturePerlin(new RNG(seed), type).bake(), type, 2.25D)));
            shapes.add(new Shape("signatureFast:" + type.name(), seed -> style(CNG.signatureFast(new RNG(seed), type, NoiseType.PERLIN), type, 0.66D)));
            shapes.add(new Shape("signatureDoubleFast:" + type.name(), seed -> style(CNG.signatureDoubleFast(new RNG(seed), type, NoiseType.SIMPLEX), type, 1.3D)));
        }
        shapes.add(new Shape("fractalSmoke", seed -> style(new CNG(new RNG(seed), 1D, 1)
                .fractureWith(new CNG(new RNG(seed).nextParallelRNG(1), NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 8).scale(0.2), 1000), NoiseType.FRACTAL_BILLOW_SIMPLEX, 0.37D)));
        shapes.add(new Shape("fractalWater", seed -> style(new CNG(new RNG(seed), 1D, 1)
                .fractureWith(new CNG(new RNG(seed).nextParallelRNG(1), NoiseType.FRACTAL_FBM_SIMPLEX, 1D, 9).scale(0.03), 9900), NoiseType.FRACTAL_FBM_SIMPLEX, 1.8D)));
        shapes.add(new Shape("simplexVascular", seed -> style(new CNG(new RNG(seed).nextParallelRNG(1), NoiseType.SIMPLEX, 1D, 1).scale(1)
                .fractureWith(new CNG(new RNG(seed).nextParallelRNG(8), NoiseType.VASCULAR, 1D, 1).scale(1), 200), NoiseType.SIMPLEX, 0.55D)));
        shapes.add(new Shape("simplexCells", seed -> style(new CNG(new RNG(seed).nextParallelRNG(1), NoiseType.SIMPLEX, 1D, 1).scale(1)
                .fractureWith(new CNG(new RNG(seed).nextParallelRNG(8), NoiseType.CELLULAR, 1D, 1).scale(1), 200), NoiseType.SIMPLEX, 3.1D)));
        shapes.add(new Shape("vascularThin", seed -> style(new CNG(new RNG(seed).nextParallelRNG(1), NoiseType.VASCULAR, 1D, 1).scale(1).pow(1D / 0.65D), NoiseType.VASCULAR, 1D)));
        shapes.add(new Shape("trioctaveSimplex", seed -> style(new CNG(new RNG(seed), 1D, 3), NoiseType.SIMPLEX, 0.5D)));
        shapes.add(new Shape("septoctavePerlin", seed -> style(new CNG(new RNG(seed), NoiseType.PERLIN, 1D, 7), NoiseType.PERLIN, 4.5D)));
        shapes.add(new Shape("nonoctaveBillow", seed -> style(new CNG(new RNG(seed), NoiseType.FRACTAL_BILLOW_SIMPLEX, 1D, 9), NoiseType.FRACTAL_BILLOW_SIMPLEX, 0.2D)));
        shapes.add(new Shape("octaveRigid", seed -> style(new CNG(new RNG(seed), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 1D, 4), NoiseType.FRACTAL_RIGID_MULTI_SIMPLEX, 0.8D)));
        shapes.add(new Shape("opacity", seed -> style(new CNG(new RNG(seed), NoiseType.SIMPLEX, 0.7D, 1)
                .fractureWith(new CNG(new RNG(seed + 3L), NoiseType.PERLIN, 0.45D, 1), 60), NoiseType.SIMPLEX, 1.1D)));
        shapes.add(new Shape("power", seed -> style(CNG.signature(new RNG(seed)).pow(2.5D), NoiseType.SIMPLEX, 0.7D)));
        shapes.add(new Shape("powerLeaf", seed -> style(new CNG(new RNG(seed), NoiseType.PERLIN, 1D, 1).pow(0.35D), NoiseType.PERLIN, 1.4D)));
        shapes.add(new Shape("upDownPatch", seed -> style(new CNG(new RNG(seed), NoiseType.SIMPLEX, 1D, 1).up(0.25D).down(0.1D).patch(0.6D)
                .fractureWith(new CNG(new RNG(seed + 1L), 1D, 1).patch(1.4D), 33), NoiseType.SIMPLEX, 0.9D)));
        for (ChildInjector injector : ChildInjector.values()) {
            shapes.add(new Shape("children:" + injector.name(), seed -> children(seed, injector)));
        }
        shapes.add(new Shape("customInjector", seed -> {
            CNG root = new CNG(new RNG(seed), NoiseType.SIMPLEX, 1D, 1);
            root.child(new CNG(new RNG(seed + 5L), NoiseType.PERLIN, 1D, 1));
            root.injectWith((source, value) -> new double[]{source * 0.5D + value * 0.25D, 0.5D});
            return style(root, NoiseType.SIMPLEX, 0.8D);
        }));
        shapes.add(new Shape("staticFracture", seed -> style(new CNG(new RNG(seed), NoiseType.SIMPLEX, 1D, 1)
                .fractureWith(new CNG(new RNG(seed + 2L), NoiseType.WHITE, 1D, 1), 12), NoiseType.SIMPLEX, 1.2D)));
        shapes.add(new Shape("noscaleRootWithFracture", seed -> style(new CNG(new RNG(seed), NoiseType.WHITE, 1D, 1)
                .fractureWith(new CNG(new RNG(seed + 2L), NoiseType.SIMPLEX, 1D, 1), 50), NoiseType.WHITE, 1D)));
        shapes.add(new Shape("subclassFracture", seed -> style(new CNG(new RNG(seed), NoiseType.SIMPLEX, 1D, 1)
                .fractureWith(new ScaledCNG(new RNG(seed + 4L)), 80), NoiseType.SIMPLEX, 1D)));
        shapes.add(new Shape("cachedFracture", seed -> {
            CNG fracture = new CNG(new RNG(seed + 6L), NoiseType.PERLIN, 1D, 1);
            FloatCache cache = new FloatCache(32, 32);
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    cache.set(x, z, (float) ((x * 31 + z * 17) % 97) / 97F);
                }
            }
            fracture.setCache(cache);
            return new CNG(new RNG(seed), NoiseType.SIMPLEX, 1D, 1).fractureWith(fracture, 25);
        }));
        shapes.add(new Shape("sharedNodes", seed -> {
            CNG leaf = new CNG(new RNG(seed + 9L), NoiseType.PERLIN, 1D, 1).scale(0.4D);
            CNG middle = new CNG(new RNG(seed + 8L), NoiseType.SIMPLEX, 1D, 1).fractureWith(leaf, 70);
            CNG root = new CNG(new RNG(seed), NoiseType.SIMPLEX, 1D, 1).fractureWith(middle, 45);
            root.child(middle);
            root.child(leaf);
            return style(root, NoiseType.SIMPLEX, 0.6D);
        }));
        shapes.add(new Shape("deepChain", seed -> {
            CNG node = new CNG(new RNG(seed + 20L), NoiseType.SIMPLEX, 1D, 1).scale(0.5D);
            for (int depth = 0; depth < 6; depth++) {
                node = new CNG(new RNG(seed + depth), depth % 2 == 0 ? NoiseType.PERLIN : NoiseType.SIMPLEX, 1D, 1).scale(0.8D).fractureWith(node, 30 + depth * 20);
            }
            return style(node, NoiseType.SIMPLEX, 0.75D);
        }));
        shapes.add(new Shape("styledLayer", seed -> {
            CNG layer = style(CNG.signatureDouble(new RNG(seed)), NoiseType.SIMPLEX, 1D).oct(2).pow(1D);
            layer.fractureWith(style(CNG.signaturePerlin(new RNG(seed).nextParallelRNG(2934)).bake(), NoiseType.SIMPLEX, 1D), 18);
            layer.zoom(0.67D);
            return layer;
        }));
        shapes.add(new Shape("cellularized", seed -> style(CNG.signature(new RNG(seed)), NoiseType.SIMPLEX, 1D)
                .cellularize(new RNG(seed).nextParallelRNG(884466), 2.5D).scale(1D / 1.5D).bake()));
        shapes.add(new Shape("customGenerator", seed -> new CNG(new RNG(seed), new WaveGenerator(), 1D, 1).bake()
                .fractureWith(new CNG(new RNG(seed + 1L), NoiseType.SIMPLEX, 1D, 1), 40)));
        return shapes;
    }

    static CNG style(CNG cng, NoiseType type, double zoom) {
        CNG styled = cng.bake().scale(type.getCoordinateScale()).bake();
        styled.zoom(zoom);
        return styled;
    }

    private static CNG children(long seed, ChildInjector injector) {
        CNG root = new CNG(new RNG(seed), NoiseType.SIMPLEX, 1D, 1)
                .fractureWith(new CNG(new RNG(seed + 1L), NoiseType.PERLIN, 1D, 1), 20);
        root.child(new CNG(new RNG(seed + 2L), NoiseType.PERLIN, 1D, 1));
        root.child(new CNG(new RNG(seed + 3L), NoiseType.CELLULAR, 1D, 1).fractureWith(new CNG(new RNG(seed + 4L), 1D, 1), 15));
        root.injectWith(injectorFunction(injector));
        return style(root, NoiseType.SIMPLEX, 0.85D);
    }

    private static NoiseInjector injectorFunction(ChildInjector injector) {
        return switch (injector) {
            case ADD -> CNG.ADD;
            case SRC_SUBTRACT -> CNG.SRC_SUBTRACT;
            case DST_SUBTRACT -> CNG.DST_SUBTRACT;
            case MULTIPLY -> CNG.MULTIPLY;
            case MAX -> CNG.MAX;
            case MIN -> CNG.MIN;
            case SRC_MOD -> CNG.SRC_MOD;
            case SRC_POW -> CNG.SRC_POW;
            case DST_MOD -> CNG.DST_MOD;
            case DST_POW -> CNG.DST_POW;
        };
    }

    record Shape(String name, Function<Long, CNG> factory) {
        CNG create(long seed) {
            return factory.apply(seed);
        }
    }

    static final class ScaledCNG extends CNG {
        ScaledCNG(RNG random) {
            super(random, NoiseType.SIMPLEX, 1D, 1);
        }

        @Override
        public double noiseFast2D(double x, double z) {
            return super.noiseFast2D(x * 1.5D, z * 0.5D);
        }

        @Override
        public double noiseFast3D(double x, double y, double z) {
            return super.noiseFast3D(x * 1.5D, y, z * 0.5D);
        }

        @Override
        public double noiseFastSigned3D(double x, double y, double z) {
            return super.noiseFastSigned3D(x * 1.5D, y, z * 0.5D);
        }
    }

    static final class WaveGenerator implements NoiseGenerator {
        @Override
        public double noise(double x) {
            return noise(x, 0D);
        }

        @Override
        public double noise(double x, double z) {
            return 0.5D + 0.5D * Math.sin(x * 0.013D) * Math.cos(z * 0.021D);
        }

        @Override
        public double noise(double x, double y, double z) {
            return 0.5D + 0.5D * Math.sin(x * 0.013D + y * 0.007D) * Math.cos(z * 0.021D);
        }
    }
}
