package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.noise.CNG;

final class JavaScalarBackend implements NoiseBackend {
    @Override
    public String id() {
        return "scalar";
    }

    @Override
    public boolean supports(NoiseProgram program) {
        return true;
    }

    @Override
    public void fill2D(NoiseProgram program, NoiseScratch scratch, double[] xs, double[] zs, int count, double[] out) {
        double[] values = scratch.scalarValues(program.termCount);
        double[] coordinates = scratch.scalarCoordinates;
        for (int index = 0; index < count; index++) {
            coordinates[0] = xs[index];
            coordinates[1] = zs[index];
            out[index] = evaluate(program, values, coordinates);
        }
    }

    @Override
    public void fillGrid2D(NoiseProgram program, NoiseScratch scratch, double x0, double z0, double step, int width, int depth, double[] out) {
        double[] values = scratch.scalarValues(program.termCount);
        double[] coordinates = scratch.scalarCoordinates;
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                coordinates[0] = x0 + x * step;
                coordinates[1] = z0 + z * step;
                out[z * width + x] = evaluate(program, values, coordinates);
            }
        }
    }

    @Override
    public void fillColumn3D(NoiseProgram program, NoiseScratch scratch, double x, double z, double y0, double yStep, int count, double[] out) {
        double[] values = scratch.scalarValues(program.termCount);
        double[] coordinates = scratch.scalarCoordinates;
        coordinates[0] = x;
        coordinates[2] = z;
        for (int index = 0; index < count; index++) {
            coordinates[1] = y0 + index * yStep;
            out[index] = evaluate(program, values, coordinates);
        }
    }

    @Override
    public void fillLattice3D(NoiseProgram program, NoiseScratch scratch, double x0, double y0, double z0, double stepX, double stepY, double stepZ, int nx, int ny, int nz, double[] out) {
        double[] values = scratch.scalarValues(program.termCount);
        double[] coordinates = scratch.scalarCoordinates;
        for (int y = 0; y < ny; y++) {
            coordinates[1] = y0 + y * stepY;
            for (int z = 0; z < nz; z++) {
                coordinates[2] = z0 + z * stepZ;
                for (int x = 0; x < nx; x++) {
                    coordinates[0] = x0 + x * stepX;
                    out[(y * nz + z) * nx + x] = evaluate(program, values, coordinates);
                }
            }
        }
    }

    static double evaluate(NoiseProgram program, double[] values, double[] coordinates) {
        int count = program.termCount;
        for (int term = 0; term < count; term++) {
            values[term] = term(program, term, values, coordinates);
        }
        return values[count - 1];
    }

    static double opaque(CNG source, int mode, double a, double b, double c) {
        return switch (mode) {
            case NoiseProgram.UNSIGNED_2D -> source.noiseFast2D(a, b);
            case NoiseProgram.NOISE_2D -> source.noise(a, b);
            case NoiseProgram.SIGNED_2D -> source.noiseFastSigned2D(a, b);
            case NoiseProgram.UNSIGNED_3D -> source.noiseFast3D(a, b, c);
            case NoiseProgram.NOISE_3D -> source.noise(a, b, c);
            default -> source.noiseFastSigned3D(a, b, c);
        };
    }

    static double combine(ChildInjector injector, double source, double value) {
        return switch (injector) {
            case ADD -> source + value;
            case SRC_SUBTRACT -> source - value < 0D ? 0D : source - value;
            case DST_SUBTRACT -> value - source < 0D ? 0D : source - value;
            case MULTIPLY -> source * value;
            case MAX -> Math.max(source, value);
            case MIN -> Math.min(source, value);
            case SRC_MOD -> source % value;
            case SRC_POW -> Math.pow(source, value);
            case DST_MOD -> value % source;
            case DST_POW -> Math.pow(value, source);
        };
    }

    static double weight(ChildInjector injector) {
        return switch (injector) {
            case ADD -> 1D;
            case SRC_SUBTRACT, DST_SUBTRACT -> -1D;
            default -> 0D;
        };
    }

    private static double term(NoiseProgram program, int term, double[] values, double[] coordinates) {
        int node = program.termNode[term];
        int mode = program.termMode[term];
        double a = coordinates[program.termAxis0[term]];
        double b = coordinates[program.termAxis1[term]];
        if (program.nodeOpaque[node]) {
            return opaque(program.nodeSources[node], mode, a, b, coordinates[program.termAxis2[term]]);
        }
        PrimitiveKernel kernel = program.nodeKernels[node];
        double scale = program.nodeScale[node];
        int dep0 = program.termDep0[term];
        switch (mode) {
            case NoiseProgram.UNSIGNED_2D, NoiseProgram.NOISE_2D -> {
                double raw;
                if (dep0 < 0) {
                    raw = kernel.noise2(a * scale, b * scale) * program.nodeOpacity[node];
                } else {
                    double fractureScale = program.nodeFractureScale[node];
                    raw = kernel.noise2((a + ((values[dep0] - 0.5D) * fractureScale)) * scale,
                            (b + ((values[program.termDep1[term]] - 0.5D) * fractureScale)) * scale) * program.nodeOpacity[node];
                }
                return program.termApplyPost[term] ? post(program, term, node, raw, values) : raw;
            }
            case NoiseProgram.SIGNED_2D -> {
                if (!program.nodeIdentitySigned[node]) {
                    return (values[dep0] * 2D) - 1D;
                }
                double signed;
                if (dep0 < 0) {
                    signed = kernel.signed2(a * scale, b * scale);
                } else {
                    double fractureScale = program.nodeSignedFractureScale[node];
                    signed = kernel.signed2((a + (values[dep0] * fractureScale)) * scale,
                            (b + (values[program.termDep1[term]] * fractureScale)) * scale);
                }
                return program.nodeUnityOpacity[node] ? signed : ((signed + 1D) * program.nodeOpacity[node]) - 1D;
            }
            case NoiseProgram.UNSIGNED_3D, NoiseProgram.NOISE_3D -> {
                double c = coordinates[program.termAxis2[term]];
                double raw;
                if (dep0 < 0) {
                    raw = kernel.noise3(a * scale, b * scale, c * scale) * program.nodeOpacity[node];
                } else {
                    double fractureScale = program.nodeFractureScale[node];
                    raw = kernel.noise3((a + ((values[dep0] - 0.5D) * fractureScale)) * scale,
                            (b + ((values[program.termDep1[term]] - 0.5D) * fractureScale)) * scale,
                            (c + ((values[program.termDep2[term]] - 0.5D) * fractureScale)) * scale) * program.nodeOpacity[node];
                }
                return program.termApplyPost[term] ? post(program, term, node, raw, values) : raw;
            }
            default -> {
                if (!program.nodeIdentitySigned[node]) {
                    return (values[dep0] * 2D) - 1D;
                }
                double c = coordinates[program.termAxis2[term]];
                double signed;
                if (dep0 < 0) {
                    signed = kernel.signed3(a * scale, b * scale, c * scale);
                } else {
                    double fractureScale = program.nodeSignedFractureScale[node];
                    signed = kernel.signed3((a + (values[dep0] * fractureScale)) * scale,
                            (b + (values[program.termDep1[term]] * fractureScale)) * scale,
                            (c + (values[program.termDep2[term]] * fractureScale)) * scale);
                }
                return program.nodeUnityOpacity[node] ? signed : ((signed + 1D) * program.nodeOpacity[node]) - 1D;
            }
        }
    }

    private static double post(NoiseProgram program, int term, int node, double raw, double[] values) {
        double power = program.nodePower[node];
        double n = power != 1D ? (raw < 0 ? -Math.pow(Math.abs(raw), power) : Math.pow(raw, power)) : raw;
        double m = 1;
        int[] children = program.termChildren[term];
        if (children != null) {
            ChildInjector injector = program.nodeInjector[node];
            for (int child : children) {
                n = combine(injector, n, values[child]);
                m += weight(injector);
            }
        }
        return ((n / m) - program.nodeDown[node] + program.nodeUp[node]) * program.nodePatch[node];
    }
}
