package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.noise.CellHeightNoise;
import art.arcane.volmlib.util.noise.CellularNoise;
import art.arcane.volmlib.util.noise.CubicNoise;
import art.arcane.volmlib.util.noise.FastNoiseDouble;
import art.arcane.volmlib.util.noise.FlatNoise;
import art.arcane.volmlib.util.noise.FractalBillowPerlinNoise;
import art.arcane.volmlib.util.noise.FractalBillowSimplexNoise;
import art.arcane.volmlib.util.noise.FractalCubicNoise;
import art.arcane.volmlib.util.noise.FractalFBMSimplexNoise;
import art.arcane.volmlib.util.noise.FractalRigidMultiSimplexNoise;
import art.arcane.volmlib.util.noise.GlobNoise;
import art.arcane.volmlib.util.noise.InterpolatedNoise;
import art.arcane.volmlib.util.noise.NoiseGenerator;
import art.arcane.volmlib.util.noise.OffsetNoiseGenerator;
import art.arcane.volmlib.util.noise.PerlinNoise;
import art.arcane.volmlib.util.noise.SimplexNoise;
import art.arcane.volmlib.util.noise.VascularNoise;
import art.arcane.volmlib.util.noise.WhiteNoise;

public abstract class PrimitiveKernel {
    final double ox;
    final double oz;
    private final PrimitiveKind kind;
    private final FastNoiseDouble fastNoise;
    private final long signature;

    PrimitiveKernel(PrimitiveKind kind, FastNoiseDouble fastNoise, double ox, double oz, long signature) {
        this.kind = kind;
        this.fastNoise = fastNoise;
        this.ox = ox;
        this.oz = oz;
        this.signature = signature;
    }

    public static PrimitiveKernel of(NoiseGenerator generator) {
        if (generator == null || generator.getClass() != OffsetNoiseGenerator.class) {
            return new GenericKernel(generator);
        }
        OffsetNoiseGenerator offset = (OffsetNoiseGenerator) generator;
        NoiseGenerator base = offset.getBase();
        double ox = offset.getOffsetX();
        double oz = offset.getOffsetZ();
        Class<?> type = base.getClass();
        if (type == SimplexNoise.class) {
            return new SimplexKernel((SimplexNoise) base, ox, oz);
        }
        if (type == PerlinNoise.class) {
            return new PerlinKernel((PerlinNoise) base, ox, oz);
        }
        if (type == FractalBillowSimplexNoise.class) {
            return new BillowSimplexKernel((FractalBillowSimplexNoise) base, ox, oz);
        }
        if (type == FractalFBMSimplexNoise.class) {
            return new FbmSimplexKernel((FractalFBMSimplexNoise) base, ox, oz);
        }
        if (type == CellularNoise.class) {
            return new CellularKernel((CellularNoise) base, ox, oz);
        }
        if (type == VascularNoise.class) {
            return new VascularKernel((VascularNoise) base, ox, oz);
        }
        if (type == GlobNoise.class) {
            return new GlobKernel((GlobNoise) base, ox, oz);
        }
        if (type == WhiteNoise.class) {
            return new WhiteKernel((WhiteNoise) base, ox, oz);
        }
        if (type == FractalRigidMultiSimplexNoise.class) {
            return offsetCall(PrimitiveKind.RIGID_MULTI_SIMPLEX, base, ((FractalRigidMultiSimplexNoise) base).getFastNoise(), ox, oz);
        }
        if (type == FractalBillowPerlinNoise.class) {
            return offsetCall(PrimitiveKind.BILLOW_PERLIN, base, ((FractalBillowPerlinNoise) base).getFastNoise(), ox, oz);
        }
        if (type == CubicNoise.class) {
            return offsetCall(PrimitiveKind.CUBIC, base, ((CubicNoise) base).getFastNoise(), ox, oz);
        }
        if (type == FractalCubicNoise.class) {
            return offsetCall(PrimitiveKind.FRACTAL_CUBIC, base, ((FractalCubicNoise) base).getFastNoise(), ox, oz);
        }
        if (type == CellHeightNoise.class) {
            return offsetCall(PrimitiveKind.CELL_HEIGHT, base, ((CellHeightNoise) base).getFastNoise(), ox, oz);
        }
        if (type == FlatNoise.class) {
            return new OffsetCallKernel(PrimitiveKind.FLAT, base, null, ox, oz,
                    new SignatureHasher().add(PrimitiveKind.FLAT.ordinal()).add(ox).add(oz).value());
        }
        if (type == InterpolatedNoise.class) {
            InterpolatedNoise interpolated = (InterpolatedNoise) base;
            long signature = new SignatureHasher().add(PrimitiveKind.INTERPOLATED.ordinal())
                    .add(interpolated.getMethod().ordinal())
                    .add(interpolated.getCoordinateScale())
                    .add(of(interpolated.getGenerator()).signature())
                    .add(ox)
                    .add(oz)
                    .value();
            return new OffsetCallKernel(PrimitiveKind.INTERPOLATED, base, null, ox, oz, signature);
        }
        return new GenericKernel(generator);
    }

    public PrimitiveKind kind() {
        return kind;
    }

    public FastNoiseDouble fastNoise() {
        return fastNoise;
    }

    public double offsetX() {
        return ox;
    }

    public double offsetZ() {
        return oz;
    }

    public long signature() {
        return signature;
    }

    public abstract NoiseGenerator source();

    abstract double noise2(double u, double v);

    abstract double signed2(double u, double v);

    abstract double noise3(double u, double v, double w);

    abstract double signed3(double u, double v, double w);

    abstract void noise2(double[] u, double[] v, double[] out, int count);

    abstract void signed2(double[] u, double[] v, double[] out, int count);

    abstract void noise3(double[] u, double[] v, double[] w, double[] out, int count);

    abstract void signed3(double[] u, double[] v, double[] w, double[] out, int count);

    private static PrimitiveKernel offsetCall(PrimitiveKind kind, NoiseGenerator base, FastNoiseDouble noise, double ox, double oz) {
        return new OffsetCallKernel(kind, base, noise, ox, oz, fastSignature(kind, noise, ox, oz, 1, 1D));
    }

    static long fastSignature(PrimitiveKind kind, FastNoiseDouble noise, double ox, double oz, int octaves, double octaveBounding) {
        if (noise.getCellularNoiseLookup() != null) {
            return SignatureHasher.unique();
        }
        return new SignatureHasher().add(kind.ordinal())
                .add(noise.getSeed())
                .add(noise.getFrequency())
                .add(noise.getNoiseType().ordinal())
                .add(noise.getLongerp().ordinal())
                .add(noise.getFractalOctaves())
                .add(noise.getFractalLacunarity())
                .add(noise.getFractalGain())
                .add(noise.getFractalType().ordinal())
                .add(noise.getFractalBounding())
                .add(noise.getCellularDistanceFunction().ordinal())
                .add(noise.getCellularReturnType().ordinal())
                .add(noise.getGradientPerturbAmp())
                .add(ox)
                .add(oz)
                .add(octaves)
                .add(octaveBounding)
                .value();
    }

    static final class SimplexKernel extends PrimitiveKernel {
        private final SimplexNoise generator;

        SimplexKernel(SimplexNoise generator, double ox, double oz) {
            super(PrimitiveKind.SIMPLEX, generator.getFastNoise(), ox, oz,
                    fastSignature(PrimitiveKind.SIMPLEX, generator.getFastNoise(), ox, oz, generator.getOctaves(), generator.getOctaveBounding()));
            this.generator = generator;
        }

        @Override
        public NoiseGenerator source() {
            return generator;
        }

        @Override
        double noise2(double u, double v) {
            return generator.noise(u + ox, v + oz);
        }

        @Override
        double signed2(double u, double v) {
            return generator.noiseSigned(u + ox, v + oz);
        }

        @Override
        double noise3(double u, double v, double w) {
            return generator.noise(u + ox, v, w + oz);
        }

        @Override
        double signed3(double u, double v, double w) {
            return generator.noiseSigned(u + ox, v, w + oz);
        }

        @Override
        void noise2(double[] u, double[] v, double[] out, int count) {
            SimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void signed2(double[] u, double[] v, double[] out, int count) {
            SimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void noise3(double[] u, double[] v, double[] w, double[] out, int count) {
            SimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }

        @Override
        void signed3(double[] u, double[] v, double[] w, double[] out, int count) {
            SimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }
    }

    static final class PerlinKernel extends PrimitiveKernel {
        private final PerlinNoise generator;

        PerlinKernel(PerlinNoise generator, double ox, double oz) {
            super(PrimitiveKind.PERLIN, generator.getFastNoise(), ox, oz,
                    fastSignature(PrimitiveKind.PERLIN, generator.getFastNoise(), ox, oz, generator.getOctaves(), generator.getOctaveBounding()));
            this.generator = generator;
        }

        @Override
        public NoiseGenerator source() {
            return generator;
        }

        @Override
        double noise2(double u, double v) {
            return generator.noise(u + ox, v + oz);
        }

        @Override
        double signed2(double u, double v) {
            return generator.noiseSigned(u + ox, v + oz);
        }

        @Override
        double noise3(double u, double v, double w) {
            return generator.noise(u + ox, v, w + oz);
        }

        @Override
        double signed3(double u, double v, double w) {
            return generator.noiseSigned(u + ox, v, w + oz);
        }

        @Override
        void noise2(double[] u, double[] v, double[] out, int count) {
            PerlinNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void signed2(double[] u, double[] v, double[] out, int count) {
            PerlinNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void noise3(double[] u, double[] v, double[] w, double[] out, int count) {
            PerlinNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }

        @Override
        void signed3(double[] u, double[] v, double[] w, double[] out, int count) {
            PerlinNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }
    }

    static final class BillowSimplexKernel extends PrimitiveKernel {
        private final FractalBillowSimplexNoise generator;

        BillowSimplexKernel(FractalBillowSimplexNoise generator, double ox, double oz) {
            super(PrimitiveKind.BILLOW_SIMPLEX, generator.getFastNoise(), ox, oz,
                    fastSignature(PrimitiveKind.BILLOW_SIMPLEX, generator.getFastNoise(), ox, oz, 1, 1D));
            this.generator = generator;
        }

        @Override
        public NoiseGenerator source() {
            return generator;
        }

        @Override
        double noise2(double u, double v) {
            return generator.noise(u + ox, v + oz);
        }

        @Override
        double signed2(double u, double v) {
            return generator.noiseSigned(u + ox, v + oz);
        }

        @Override
        double noise3(double u, double v, double w) {
            return generator.noise(u + ox, v, w + oz);
        }

        @Override
        double signed3(double u, double v, double w) {
            return generator.noiseSigned(u + ox, v, w + oz);
        }

        @Override
        void noise2(double[] u, double[] v, double[] out, int count) {
            FractalBillowSimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void signed2(double[] u, double[] v, double[] out, int count) {
            FractalBillowSimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void noise3(double[] u, double[] v, double[] w, double[] out, int count) {
            FractalBillowSimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }

        @Override
        void signed3(double[] u, double[] v, double[] w, double[] out, int count) {
            FractalBillowSimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }
    }

    static final class FbmSimplexKernel extends PrimitiveKernel {
        private final FractalFBMSimplexNoise generator;

        FbmSimplexKernel(FractalFBMSimplexNoise generator, double ox, double oz) {
            super(PrimitiveKind.FBM_SIMPLEX, generator.getFastNoise(), ox, oz,
                    fastSignature(PrimitiveKind.FBM_SIMPLEX, generator.getFastNoise(), ox, oz, 1, 1D));
            this.generator = generator;
        }

        @Override
        public NoiseGenerator source() {
            return generator;
        }

        @Override
        double noise2(double u, double v) {
            return generator.noise(u + ox, v + oz);
        }

        @Override
        double signed2(double u, double v) {
            return generator.noiseSigned(u + ox, v + oz);
        }

        @Override
        double noise3(double u, double v, double w) {
            return generator.noise(u + ox, v, w + oz);
        }

        @Override
        double signed3(double u, double v, double w) {
            return generator.noiseSigned(u + ox, v, w + oz);
        }

        @Override
        void noise2(double[] u, double[] v, double[] out, int count) {
            FractalFBMSimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void signed2(double[] u, double[] v, double[] out, int count) {
            FractalFBMSimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void noise3(double[] u, double[] v, double[] w, double[] out, int count) {
            FractalFBMSimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }

        @Override
        void signed3(double[] u, double[] v, double[] w, double[] out, int count) {
            FractalFBMSimplexNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }
    }

    static final class CellularKernel extends PrimitiveKernel {
        private final CellularNoise generator;

        CellularKernel(CellularNoise generator, double ox, double oz) {
            super(PrimitiveKind.CELLULAR, generator.getFastNoise(), ox, oz,
                    fastSignature(PrimitiveKind.CELLULAR, generator.getFastNoise(), ox, oz, 1, 1D));
            this.generator = generator;
        }

        @Override
        public NoiseGenerator source() {
            return generator;
        }

        @Override
        double noise2(double u, double v) {
            return generator.noise(u + ox, v + oz);
        }

        @Override
        double signed2(double u, double v) {
            return generator.noiseSigned(u + ox, v + oz);
        }

        @Override
        double noise3(double u, double v, double w) {
            return generator.noise(u + ox, v, w + oz);
        }

        @Override
        double signed3(double u, double v, double w) {
            return generator.noiseSigned(u + ox, v, w + oz);
        }

        @Override
        void noise2(double[] u, double[] v, double[] out, int count) {
            CellularNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void signed2(double[] u, double[] v, double[] out, int count) {
            CellularNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void noise3(double[] u, double[] v, double[] w, double[] out, int count) {
            CellularNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }

        @Override
        void signed3(double[] u, double[] v, double[] w, double[] out, int count) {
            CellularNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }
    }

    static final class VascularKernel extends PrimitiveKernel {
        private final VascularNoise generator;

        VascularKernel(VascularNoise generator, double ox, double oz) {
            super(PrimitiveKind.VASCULAR, generator.getFastNoise(), ox, oz,
                    fastSignature(PrimitiveKind.VASCULAR, generator.getFastNoise(), ox, oz, 1, 1D));
            this.generator = generator;
        }

        @Override
        public NoiseGenerator source() {
            return generator;
        }

        @Override
        double noise2(double u, double v) {
            return generator.noise(u + ox, v + oz);
        }

        @Override
        double signed2(double u, double v) {
            return generator.noiseSigned(u + ox, v + oz);
        }

        @Override
        double noise3(double u, double v, double w) {
            return generator.noise(u + ox, v, w + oz);
        }

        @Override
        double signed3(double u, double v, double w) {
            return generator.noiseSigned(u + ox, v, w + oz);
        }

        @Override
        void noise2(double[] u, double[] v, double[] out, int count) {
            VascularNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void signed2(double[] u, double[] v, double[] out, int count) {
            VascularNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void noise3(double[] u, double[] v, double[] w, double[] out, int count) {
            VascularNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }

        @Override
        void signed3(double[] u, double[] v, double[] w, double[] out, int count) {
            VascularNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }
    }

    static final class GlobKernel extends PrimitiveKernel {
        private final GlobNoise generator;

        GlobKernel(GlobNoise generator, double ox, double oz) {
            super(PrimitiveKind.GLOB, generator.getFastNoise(), ox, oz,
                    fastSignature(PrimitiveKind.GLOB, generator.getFastNoise(), ox, oz, 1, 1D));
            this.generator = generator;
        }

        @Override
        public NoiseGenerator source() {
            return generator;
        }

        @Override
        double noise2(double u, double v) {
            return generator.noise(u + ox, v + oz);
        }

        @Override
        double signed2(double u, double v) {
            return generator.noiseSigned(u + ox, v + oz);
        }

        @Override
        double noise3(double u, double v, double w) {
            return generator.noise(u + ox, v, w + oz);
        }

        @Override
        double signed3(double u, double v, double w) {
            return generator.noiseSigned(u + ox, v, w + oz);
        }

        @Override
        void noise2(double[] u, double[] v, double[] out, int count) {
            GlobNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void signed2(double[] u, double[] v, double[] out, int count) {
            GlobNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void noise3(double[] u, double[] v, double[] w, double[] out, int count) {
            GlobNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }

        @Override
        void signed3(double[] u, double[] v, double[] w, double[] out, int count) {
            GlobNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }
    }

    static final class WhiteKernel extends PrimitiveKernel {
        private final WhiteNoise generator;

        WhiteKernel(WhiteNoise generator, double ox, double oz) {
            super(PrimitiveKind.WHITE, generator.getFastNoise(), ox, oz,
                    fastSignature(PrimitiveKind.WHITE, generator.getFastNoise(), ox, oz, 1, 1D));
            this.generator = generator;
        }

        @Override
        public NoiseGenerator source() {
            return generator;
        }

        @Override
        double noise2(double u, double v) {
            return generator.noise(u + ox, v + oz);
        }

        @Override
        double signed2(double u, double v) {
            return generator.noiseSigned(u + ox, v + oz);
        }

        @Override
        double noise3(double u, double v, double w) {
            return generator.noise(u + ox, v, w + oz);
        }

        @Override
        double signed3(double u, double v, double w) {
            return generator.noiseSigned(u + ox, v, w + oz);
        }

        @Override
        void noise2(double[] u, double[] v, double[] out, int count) {
            WhiteNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void signed2(double[] u, double[] v, double[] out, int count) {
            WhiteNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void noise3(double[] u, double[] v, double[] w, double[] out, int count) {
            WhiteNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }

        @Override
        void signed3(double[] u, double[] v, double[] w, double[] out, int count) {
            WhiteNoise source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }
    }

    static final class OffsetCallKernel extends PrimitiveKernel {
        private final NoiseGenerator generator;

        OffsetCallKernel(PrimitiveKind kind, NoiseGenerator generator, FastNoiseDouble noise, double ox, double oz, long signature) {
            super(kind, noise, ox, oz, signature);
            this.generator = generator;
        }

        @Override
        public NoiseGenerator source() {
            return generator;
        }

        @Override
        double noise2(double u, double v) {
            return generator.noise(u + ox, v + oz);
        }

        @Override
        double signed2(double u, double v) {
            return generator.noiseSigned(u + ox, v + oz);
        }

        @Override
        double noise3(double u, double v, double w) {
            return generator.noise(u + ox, v, w + oz);
        }

        @Override
        double signed3(double u, double v, double w) {
            return generator.noiseSigned(u + ox, v, w + oz);
        }

        @Override
        void noise2(double[] u, double[] v, double[] out, int count) {
            NoiseGenerator source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void signed2(double[] u, double[] v, double[] out, int count) {
            NoiseGenerator source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index] + offsetZ);
            }
        }

        @Override
        void noise3(double[] u, double[] v, double[] w, double[] out, int count) {
            NoiseGenerator source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }

        @Override
        void signed3(double[] u, double[] v, double[] w, double[] out, int count) {
            NoiseGenerator source = generator;
            double offsetX = ox;
            double offsetZ = oz;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index] + offsetX, v[index], w[index] + offsetZ);
            }
        }
    }

    static final class GenericKernel extends PrimitiveKernel {
        private final NoiseGenerator generator;

        GenericKernel(NoiseGenerator generator) {
            super(PrimitiveKind.GENERIC, null, 0D, 0D, SignatureHasher.unique());
            this.generator = generator;
        }

        @Override
        public NoiseGenerator source() {
            return generator;
        }

        @Override
        double noise2(double u, double v) {
            return generator.noise(u, v);
        }

        @Override
        double signed2(double u, double v) {
            return generator.noiseSigned(u, v);
        }

        @Override
        double noise3(double u, double v, double w) {
            return generator.noise(u, v, w);
        }

        @Override
        double signed3(double u, double v, double w) {
            return generator.noiseSigned(u, v, w);
        }

        @Override
        void noise2(double[] u, double[] v, double[] out, int count) {
            NoiseGenerator source = generator;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index], v[index]);
            }
        }

        @Override
        void signed2(double[] u, double[] v, double[] out, int count) {
            NoiseGenerator source = generator;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index], v[index]);
            }
        }

        @Override
        void noise3(double[] u, double[] v, double[] w, double[] out, int count) {
            NoiseGenerator source = generator;
            for (int index = 0; index < count; index++) {
                out[index] = source.noise(u[index], v[index], w[index]);
            }
        }

        @Override
        void signed3(double[] u, double[] v, double[] w, double[] out, int count) {
            NoiseGenerator source = generator;
            for (int index = 0; index < count; index++) {
                out[index] = source.noiseSigned(u[index], v[index], w[index]);
            }
        }
    }
}
