package art.arcane.volmlib.util.noise.program;

abstract class ScalarEvaluator {
    private static final ThreadLocal<NoiseScratch> SCRATCH = ThreadLocal.withInitial(NoiseScratch::new);

    static ScalarEvaluator of(NoiseProgram program) {
        int[] chain = chain(program);
        if (chain != null && program.volumetric && SignedChain.accepts(program, chain)) {
            return new SignedChain(program, chain);
        }
        if (chain != null && !program.volumetric && PlanarChain.accepts(program, chain)) {
            return new PlanarChain(program, chain);
        }
        return new Interpreted(program);
    }

    abstract double sample(double x, double y, double z);

    private static int[] chain(NoiseProgram program) {
        int root = program.termNode[program.rootTerm];
        int length = 0;
        for (int node = root; node >= 0; node = program.nodeFracture[node]) {
            if (program.nodeOpaque[node] || program.nodeChildren[node] != null || length > program.nodeCount) {
                return null;
            }
            length++;
        }
        int[] chain = new int[length];
        int level = 0;
        for (int node = root; node >= 0; node = program.nodeFracture[node]) {
            chain[level++] = node;
        }
        return chain;
    }

    private static int level(int[] chain, int node) {
        for (int level = 0; level < chain.length; level++) {
            if (chain[level] == node) {
                return level;
            }
        }
        return -1;
    }

    private static final class Interpreted extends ScalarEvaluator {
        private final NoiseProgram program;

        private Interpreted(NoiseProgram program) {
            this.program = program;
        }

        @Override
        double sample(double x, double y, double z) {
            NoiseScratch scratch = SCRATCH.get();
            if (scratch.scalarActive) {
                return JavaScalarBackend.evaluate(program, new double[program.termCount], new double[]{x, y, z});
            }
            scratch.scalarActive = true;
            try {
                double[] coordinates = scratch.scalarCoordinates;
                coordinates[0] = x;
                coordinates[1] = y;
                coordinates[2] = z;
                return JavaScalarBackend.evaluate(program, scratch.scalarValues(program.termCount), coordinates);
            } finally {
                scratch.scalarActive = false;
            }
        }
    }

    private static final class PlanarChain extends ScalarEvaluator {
        private final int leaf;
        private final PrimitiveKernel[] kernels;
        private final double[] scale;
        private final double[] fractureScale;
        private final double[] opacity;
        private final boolean[] post;
        private final double[] power;
        private final double[] down;
        private final double[] up;
        private final double[] patch;

        private PlanarChain(NoiseProgram program, int[] chain) {
            int levels = chain.length;
            leaf = levels - 1;
            kernels = new PrimitiveKernel[levels];
            scale = new double[levels];
            fractureScale = new double[levels];
            opacity = new double[levels];
            post = new boolean[levels];
            power = new double[levels];
            down = new double[levels];
            up = new double[levels];
            patch = new double[levels];
            for (int level = 0; level < levels; level++) {
                int node = chain[level];
                kernels[level] = program.nodeKernels[node];
                scale[level] = program.nodeScale[node];
                fractureScale[level] = program.nodeFractureScale[node];
                opacity[level] = program.nodeOpacity[node];
                power[level] = program.nodePower[node];
                down[level] = program.nodeDown[node];
                up[level] = program.nodeUp[node];
                patch[level] = program.nodePatch[node];
            }
            for (int term = 0; term < program.termCount; term++) {
                post[level(chain, program.termNode[term])] = program.termApplyPost[term];
            }
        }

        private static boolean accepts(NoiseProgram program, int[] chain) {
            for (int term = 0; term < program.termCount; term++) {
                if (program.termMode[term] != NoiseProgram.UNSIGNED_2D || level(chain, program.termNode[term]) < 0) {
                    return false;
                }
            }
            return true;
        }

        @Override
        double sample(double x, double z, double ignored) {
            int last = leaf;
            PrimitiveKernel leafKernel = kernels[last];
            double leafScale = scale[last];
            double forward = finish(last, leafKernel.noise2(x * leafScale, z * leafScale) * opacity[last]);
            if (last == 0) {
                return forward;
            }
            double reverse = finish(last, leafKernel.noise2(z * leafScale, x * leafScale) * opacity[last]);
            for (int level = last - 1; level > 0; level--) {
                PrimitiveKernel kernel = kernels[level];
                double levelScale = scale[level];
                double displacement = fractureScale[level];
                double nextForward = finish(level, kernel.noise2((x + ((forward - 0.5D) * displacement)) * levelScale,
                        (z + ((reverse - 0.5D) * displacement)) * levelScale) * opacity[level]);
                double nextReverse = finish(level, kernel.noise2((z + ((reverse - 0.5D) * displacement)) * levelScale,
                        (x + ((forward - 0.5D) * displacement)) * levelScale) * opacity[level]);
                forward = nextForward;
                reverse = nextReverse;
            }
            double rootScale = scale[0];
            double displacement = fractureScale[0];
            return finish(0, kernels[0].noise2((x + ((forward - 0.5D) * displacement)) * rootScale,
                    (z + ((reverse - 0.5D) * displacement)) * rootScale) * opacity[0]);
        }

        private double finish(int level, double raw) {
            if (!post[level]) {
                return raw;
            }
            double exponent = power[level];
            double n = exponent != 1D ? (raw < 0 ? -Math.pow(Math.abs(raw), exponent) : Math.pow(raw, exponent)) : raw;
            return ((n / 1D) - down[level] + up[level]) * patch[level];
        }
    }

    private static final class SignedChain extends ScalarEvaluator {
        private static final int P0 = 1;
        private static final int P1 = 1 << 1;
        private static final int P2 = 1 << 2;
        private static final int XY = 1 << 3;
        private static final int YX = 1 << 4;
        private static final int XZ = 1 << 5;
        private static final int ZX = 1 << 6;
        private static final int YZ = 1 << 7;
        private static final int ZY = 1 << 8;

        private final int leaf;
        private final PrimitiveKernel[] kernels;
        private final double[] scale;
        private final double[] fractureScale;
        private final double[] opacity;
        private final boolean[] unity;
        private final int[] masks;

        private SignedChain(NoiseProgram program, int[] chain) {
            int levels = chain.length;
            leaf = levels - 1;
            kernels = new PrimitiveKernel[levels];
            scale = new double[levels];
            fractureScale = new double[levels];
            opacity = new double[levels];
            unity = new boolean[levels];
            masks = new int[levels];
            for (int level = 0; level < levels; level++) {
                int node = chain[level];
                kernels[level] = program.nodeKernels[node];
                scale[level] = program.nodeScale[node];
                fractureScale[level] = program.nodeSignedFractureScale[node];
                opacity[level] = program.nodeOpacity[node];
                unity[level] = program.nodeUnityOpacity[node];
            }
            for (int term = 0; term < program.termCount; term++) {
                masks[level(chain, program.termNode[term])] |= bit(program, term);
            }
        }

        private static boolean accepts(NoiseProgram program, int[] chain) {
            for (int node : chain) {
                if (!program.nodeIdentitySigned[node]) {
                    return false;
                }
            }
            for (int term = 0; term < program.termCount; term++) {
                int mode = program.termMode[term];
                if ((mode != NoiseProgram.SIGNED_3D && mode != NoiseProgram.SIGNED_2D) || level(chain, program.termNode[term]) < 0) {
                    return false;
                }
            }
            return true;
        }

        private static int bit(NoiseProgram program, int term) {
            int a0 = program.termAxis0[term];
            int a1 = program.termAxis1[term];
            if (program.termMode[term] == NoiseProgram.SIGNED_3D) {
                return a0 == 0 ? P0 : a0 == 2 ? P1 : P2;
            }
            if (a0 == 0) {
                return a1 == 1 ? XY : XZ;
            }
            if (a0 == 1) {
                return a1 == 0 ? YX : YZ;
            }
            return a1 == 0 ? ZX : ZY;
        }

        @Override
        double sample(double x, double y, double z) {
            int last = leaf;
            PrimitiveKernel kernel = kernels[last];
            double s = scale[last];
            int mask = masks[last];
            double p0 = 0D;
            double p1 = 0D;
            double p2 = 0D;
            double xy = 0D;
            double yx = 0D;
            double xz = 0D;
            double zx = 0D;
            double yz = 0D;
            double zy = 0D;
            if ((mask & P0) != 0) {
                p0 = signed(last, kernel.signed3(x * s, y * s, z * s));
            }
            if ((mask & P1) != 0) {
                p1 = signed(last, kernel.signed3(z * s, x * s, y * s));
            }
            if ((mask & P2) != 0) {
                p2 = signed(last, kernel.signed3(y * s, z * s, x * s));
            }
            if ((mask & XY) != 0) {
                xy = signed(last, kernel.signed2(x * s, y * s));
            }
            if ((mask & YX) != 0) {
                yx = signed(last, kernel.signed2(y * s, x * s));
            }
            if ((mask & XZ) != 0) {
                xz = signed(last, kernel.signed2(x * s, z * s));
            }
            if ((mask & ZX) != 0) {
                zx = signed(last, kernel.signed2(z * s, x * s));
            }
            if ((mask & YZ) != 0) {
                yz = signed(last, kernel.signed2(y * s, z * s));
            }
            if ((mask & ZY) != 0) {
                zy = signed(last, kernel.signed2(z * s, y * s));
            }
            for (int level = last - 1; level >= 0; level--) {
                kernel = kernels[level];
                s = scale[level];
                double f = fractureScale[level];
                mask = masks[level];
                double n0 = p0;
                double n1 = p1;
                double n2 = p2;
                double nxy = xy;
                double nyx = yx;
                double nxz = xz;
                double nzx = zx;
                double nyz = yz;
                double nzy = zy;
                if ((mask & P0) != 0) {
                    n0 = signed(level, kernel.signed3((x + (p0 * f)) * s, (y + (yx * f)) * s, (z + (p1 * f)) * s));
                }
                if ((mask & P1) != 0) {
                    n1 = signed(level, kernel.signed3((z + (p1 * f)) * s, (x + (xz * f)) * s, (y + (p2 * f)) * s));
                }
                if ((mask & P2) != 0) {
                    n2 = signed(level, kernel.signed3((y + (p2 * f)) * s, (z + (zy * f)) * s, (x + (p0 * f)) * s));
                }
                if ((mask & XY) != 0) {
                    nxy = signed(level, kernel.signed2((x + (xy * f)) * s, (y + (yx * f)) * s));
                }
                if ((mask & YX) != 0) {
                    nyx = signed(level, kernel.signed2((y + (yx * f)) * s, (x + (xy * f)) * s));
                }
                if ((mask & XZ) != 0) {
                    nxz = signed(level, kernel.signed2((x + (xz * f)) * s, (z + (zx * f)) * s));
                }
                if ((mask & ZX) != 0) {
                    nzx = signed(level, kernel.signed2((z + (zx * f)) * s, (x + (xz * f)) * s));
                }
                if ((mask & YZ) != 0) {
                    nyz = signed(level, kernel.signed2((y + (yz * f)) * s, (z + (zy * f)) * s));
                }
                if ((mask & ZY) != 0) {
                    nzy = signed(level, kernel.signed2((z + (zy * f)) * s, (y + (yz * f)) * s));
                }
                p0 = n0;
                p1 = n1;
                p2 = n2;
                xy = nxy;
                yx = nyx;
                xz = nxz;
                zx = nzx;
                yz = nyz;
                zy = nzy;
            }
            return p0;
        }

        private double signed(int level, double value) {
            return unity[level] ? value : ((value + 1D) * opacity[level]) - 1D;
        }
    }
}
