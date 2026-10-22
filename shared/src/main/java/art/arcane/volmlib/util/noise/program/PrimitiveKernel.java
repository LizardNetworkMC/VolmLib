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

    PrimitiveKernel(KernelSpec spec) {
        kind = spec.kind();
        fastNoise = spec.fastNoise();
        ox = spec.ox();
        oz = spec.oz();
        signature = spec.signature();
    }

    public static PrimitiveKernel of(NoiseGenerator generator) {
        if (generator == null || generator.getClass() != OffsetNoiseGenerator.class) {
            return new GenericKernel(generator);
        }
        OffsetNoiseGenerator offset = (OffsetNoiseGenerator) generator;
        NoiseGenerator base = offset.getBase();
        Class<?> type = base.getClass();
        if (type == SimplexNoise.class) {
            SimplexNoise simplex = (SimplexNoise) base;
            return new SimplexKernel(spec(PrimitiveKind.SIMPLEX, simplex.getFastNoise(), offset, simplex.getOctaves(), simplex.getOctaveBounding()), simplex);
        }
        if (type == PerlinNoise.class) {
            PerlinNoise perlin = (PerlinNoise) base;
            return new PerlinKernel(spec(PrimitiveKind.PERLIN, perlin.getFastNoise(), offset, perlin.getOctaves(), perlin.getOctaveBounding()), perlin);
        }
        if (type == FractalBillowSimplexNoise.class) {
            FractalBillowSimplexNoise billow = (FractalBillowSimplexNoise) base;
            return new BillowSimplexKernel(spec(PrimitiveKind.BILLOW_SIMPLEX, billow.getFastNoise(), offset, 1, 1D), billow);
        }
        if (type == FractalFBMSimplexNoise.class) {
            FractalFBMSimplexNoise fbm = (FractalFBMSimplexNoise) base;
            return new FbmSimplexKernel(spec(PrimitiveKind.FBM_SIMPLEX, fbm.getFastNoise(), offset, 1, 1D), fbm);
        }
        if (type == CellularNoise.class) {
            CellularNoise cellular = (CellularNoise) base;
            return new CellularKernel(spec(PrimitiveKind.CELLULAR, cellular.getFastNoise(), offset, 1, 1D), cellular);
        }
        if (type == VascularNoise.class) {
            VascularNoise vascular = (VascularNoise) base;
            return new VascularKernel(spec(PrimitiveKind.VASCULAR, vascular.getFastNoise(), offset, 1, 1D), vascular);
        }
        if (type == GlobNoise.class) {
            GlobNoise glob = (GlobNoise) base;
            return new GlobKernel(spec(PrimitiveKind.GLOB, glob.getFastNoise(), offset, 1, 1D), glob);
        }
        if (type == WhiteNoise.class) {
            WhiteNoise white = (WhiteNoise) base;
            return new WhiteKernel(spec(PrimitiveKind.WHITE, white.getFastNoise(), offset, 1, 1D), white);
        }
        if (type == FractalRigidMultiSimplexNoise.class) {
            return new OffsetCallKernel(spec(PrimitiveKind.RIGID_MULTI_SIMPLEX, ((FractalRigidMultiSimplexNoise) base).getFastNoise(), offset, 1, 1D), base);
        }
        if (type == FractalBillowPerlinNoise.class) {
            return new OffsetCallKernel(spec(PrimitiveKind.BILLOW_PERLIN, ((FractalBillowPerlinNoise) base).getFastNoise(), offset, 1, 1D), base);
        }
        if (type == CubicNoise.class) {
            return new OffsetCallKernel(spec(PrimitiveKind.CUBIC, ((CubicNoise) base).getFastNoise(), offset, 1, 1D), base);
        }
        if (type == FractalCubicNoise.class) {
            return new OffsetCallKernel(spec(PrimitiveKind.FRACTAL_CUBIC, ((FractalCubicNoise) base).getFastNoise(), offset, 1, 1D), base);
        }
        if (type == CellHeightNoise.class) {
            return new OffsetCallKernel(spec(PrimitiveKind.CELL_HEIGHT, ((CellHeightNoise) base).getFastNoise(), offset, 1, 1D), base);
        }
        if (type == FlatNoise.class) {
            long signature = new SignatureHasher().add(PrimitiveKind.FLAT.ordinal()).add(offset.getOffsetX()).add(offset.getOffsetZ()).value();
            return new OffsetCallKernel(new KernelSpec(PrimitiveKind.FLAT, null, offset.getOffsetX(), offset.getOffsetZ(), signature), base);
        }
        if (type == InterpolatedNoise.class) {
            InterpolatedNoise interpolated = (InterpolatedNoise) base;
            long signature = new SignatureHasher().add(PrimitiveKind.INTERPOLATED.ordinal())
                    .add(interpolated.getMethod().ordinal())
                    .add(interpolated.getCoordinateScale())
                    .add(of(interpolated.getGenerator()).signature())
                    .add(offset.getOffsetX())
                    .add(offset.getOffsetZ())
                    .value();
            return new OffsetCallKernel(new KernelSpec(PrimitiveKind.INTERPOLATED, null, offset.getOffsetX(), offset.getOffsetZ(), signature), base);
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

    private static KernelSpec spec(PrimitiveKind kind, FastNoiseDouble noise, OffsetNoiseGenerator offset, int octaves, double octaveBounding) {
        double ox = offset.getOffsetX();
        double oz = offset.getOffsetZ();
        if (noise.getCellularNoiseLookup() != null) {
            return new KernelSpec(kind, noise, ox, oz, SignatureHasher.unique());
        }
        long signature = new SignatureHasher().add(kind.ordinal())
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
        return new KernelSpec(kind, noise, ox, oz, signature);
    }

    record KernelSpec(PrimitiveKind kind, FastNoiseDouble fastNoise, double ox, double oz, long signature) {
    }

    static final class SimplexKernel extends PrimitiveKernel {
        private final SimplexNoise generator;

        SimplexKernel(KernelSpec spec, SimplexNoise generator) {
            super(spec);
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

        PerlinKernel(KernelSpec spec, PerlinNoise generator) {
            super(spec);
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

        BillowSimplexKernel(KernelSpec spec, FractalBillowSimplexNoise generator) {
            super(spec);
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

        FbmSimplexKernel(KernelSpec spec, FractalFBMSimplexNoise generator) {
            super(spec);
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

        CellularKernel(KernelSpec spec, CellularNoise generator) {
            super(spec);
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

        VascularKernel(KernelSpec spec, VascularNoise generator) {
            super(spec);
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

        GlobKernel(KernelSpec spec, GlobNoise generator) {
            super(spec);
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

        WhiteKernel(KernelSpec spec, WhiteNoise generator) {
            super(spec);
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

        OffsetCallKernel(KernelSpec spec, NoiseGenerator generator) {
            super(spec);
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
            super(new KernelSpec(PrimitiveKind.GENERIC, null, 0D, 0D, SignatureHasher.unique()));
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
