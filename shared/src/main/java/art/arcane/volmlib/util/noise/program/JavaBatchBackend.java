package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.noise.CNG;

final class JavaBatchBackend implements NoiseBackend {
    private static final int BLOCK = NoiseScratch.BLOCK;

    @Override
    public String id() {
        return "java";
    }

    @Override
    public boolean supports(NoiseProgram program) {
        return true;
    }

    @Override
    public void fill2D(NoiseProgram program, NoiseScratch scratch, double[] xs, double[] zs, int count, double[] out) {
        scratch.prepareTerms(program.termCount);
        double[] coordinateX = scratch.coordinates[0];
        double[] coordinateZ = scratch.coordinates[1];
        for (int start = 0; start < count; start += BLOCK) {
            int length = Math.min(BLOCK, count - start);
            System.arraycopy(xs, start, coordinateX, 0, length);
            System.arraycopy(zs, start, coordinateZ, 0, length);
            evaluatePlanar(program, scratch, length);
            System.arraycopy(scratch.values[program.rootTerm], 0, out, start, length);
        }
    }

    @Override
    public void fillGrid2D(NoiseProgram program, NoiseScratch scratch, double x0, double z0, double step, int width, int depth, double[] out) {
        scratch.prepareTerms(program.termCount);
        double[] coordinateX = scratch.coordinates[0];
        double[] coordinateZ = scratch.coordinates[1];
        int total = width * depth;
        int x = 0;
        int z = 0;
        for (int start = 0; start < total; start += BLOCK) {
            int length = Math.min(BLOCK, total - start);
            for (int index = 0; index < length; index++) {
                coordinateX[index] = x0 + x * step;
                coordinateZ[index] = z0 + z * step;
                if (++x == width) {
                    x = 0;
                    z++;
                }
            }
            evaluatePlanar(program, scratch, length);
            System.arraycopy(scratch.values[program.rootTerm], 0, out, start, length);
        }
    }

    @Override
    public void fillColumn3D(NoiseProgram program, NoiseScratch scratch, double x, double z, double y0, double yStep, int count, double[] out) {
        scratch.axis(0, 1)[0] = x;
        scratch.axis(2, 1)[0] = z;
        double[] axisY = scratch.axis(1, count);
        for (int index = 0; index < count; index++) {
            axisY[index] = y0 + index * yStep;
        }
        evaluateVolume(program, scratch, 1, count, 1, out);
    }

    @Override
    public void fillLattice3D(NoiseProgram program, NoiseScratch scratch, double x0, double y0, double z0, double stepX, double stepY, double stepZ, int nx, int ny, int nz, double[] out) {
        double[] axisX = scratch.axis(0, nx);
        double[] axisY = scratch.axis(1, ny);
        double[] axisZ = scratch.axis(2, nz);
        for (int index = 0; index < nx; index++) {
            axisX[index] = x0 + index * stepX;
        }
        for (int index = 0; index < ny; index++) {
            axisY[index] = y0 + index * stepY;
        }
        for (int index = 0; index < nz; index++) {
            axisZ[index] = z0 + index * stepZ;
        }
        evaluateVolume(program, scratch, nx, ny, nz, out);
    }

    private static void evaluatePlanar(NoiseProgram program, NoiseScratch scratch, int length) {
        double[][] coordinates = scratch.coordinates;
        int count = program.termCount;
        for (int term = 0; term < count; term++) {
            pairTerm(program, scratch, term, coordinates[program.termAxis0[term]], coordinates[program.termAxis1[term]], length);
        }
    }

    private static void evaluateVolume(NoiseProgram program, NoiseScratch scratch, int nx, int ny, int nz, double[] out) {
        scratch.prepareTerms(program.termCount);
        int largestPair = 0;
        for (int pair = 0; pair < 3; pair++) {
            if (program.pairUsed[pair]) {
                largestPair = Math.max(largestPair, buildPair(scratch, pair, nx, ny, nz));
            }
        }
        scratch.prepareScaled(largestPair);
        for (int term : program.pairTerms) {
            int pair = program.termPair[term];
            boolean lowFirst = program.termAxis0[term] == lowAxis(pair);
            double[] low = scratch.pairLow[pair];
            double[] high = scratch.pairHigh[pair];
            pairTerm(program, scratch, term, lowFirst ? low : high, lowFirst ? high : low, pairSize(pair, nx, ny, nz));
        }
        double[] axisX = scratch.axes[0];
        double[] axisY = scratch.axes[1];
        double[] axisZ = scratch.axes[2];
        double[][] coordinates = scratch.coordinates;
        double[] coordinateX = coordinates[0];
        double[] coordinateY = coordinates[1];
        double[] coordinateZ = coordinates[2];
        int[][] projections = scratch.projections;
        int[] projectionXY = projections[0];
        int[] projectionXZ = projections[1];
        int[] projectionYZ = projections[2];
        int[] volumeTerms = program.volumeTerms;
        boolean projected = program.pairTerms.length > 0;
        int total = nx * ny * nz;
        int x = 0;
        int y = 0;
        int z = 0;
        for (int start = 0; start < total; start += BLOCK) {
            int length = Math.min(BLOCK, total - start);
            for (int index = 0; index < length; index++) {
                coordinateX[index] = axisX[x];
                coordinateY[index] = axisY[y];
                coordinateZ[index] = axisZ[z];
                if (projected) {
                    projectionXY[index] = y * nx + x;
                    projectionXZ[index] = z * nx + x;
                    projectionYZ[index] = z * ny + y;
                }
                if (++x == nx) {
                    x = 0;
                    if (++z == nz) {
                        z = 0;
                        y++;
                    }
                }
            }
            for (int term : volumeTerms) {
                volumeTerm(program, scratch, term, coordinates[program.termAxis0[term]], coordinates[program.termAxis1[term]],
                        coordinates[program.termAxis2[term]], projections[program.termPair[term]], length);
            }
            System.arraycopy(scratch.values[program.rootTerm], 0, out, start, length);
        }
    }

    private static int buildPair(NoiseScratch scratch, int pair, int nx, int ny, int nz) {
        int lowAxis = lowAxis(pair);
        int highAxis = highAxis(pair);
        int lowCount = axisSize(lowAxis, nx, ny, nz);
        int highCount = axisSize(highAxis, nx, ny, nz);
        int size = lowCount * highCount;
        scratch.preparePair(pair, size);
        double[] low = scratch.pairLow[pair];
        double[] high = scratch.pairHigh[pair];
        double[] lowValues = scratch.axes[lowAxis];
        double[] highValues = scratch.axes[highAxis];
        int index = 0;
        for (int h = 0; h < highCount; h++) {
            double highValue = highValues[h];
            for (int l = 0; l < lowCount; l++) {
                low[index] = lowValues[l];
                high[index] = highValue;
                index++;
            }
        }
        return size;
    }

    private static int lowAxis(int pair) {
        return pair == 2 ? 1 : 0;
    }

    private static int highAxis(int pair) {
        return pair == 0 ? 1 : 2;
    }

    private static int axisSize(int axis, int nx, int ny, int nz) {
        return axis == 0 ? nx : axis == 1 ? ny : nz;
    }

    private static int pairSize(int pair, int nx, int ny, int nz) {
        return axisSize(lowAxis(pair), nx, ny, nz) * axisSize(highAxis(pair), nx, ny, nz);
    }

    private static void pairTerm(NoiseProgram program, NoiseScratch scratch, int term, double[] first, double[] second, int length) {
        int node = program.termNode[term];
        int mode = program.termMode[term];
        double[] out = scratch.termBuffer(term, length);
        if (program.nodeOpaque[node]) {
            CNG source = program.nodeSources[node];
            for (int index = 0; index < length; index++) {
                out[index] = JavaScalarBackend.opaque(source, mode, first[index], second[index], 0D);
            }
            return;
        }
        double[][] values = scratch.values;
        int dep0 = program.termDep0[term];
        if (mode == NoiseProgram.SIGNED_2D && !program.nodeIdentitySigned[node]) {
            double[] unsigned = values[dep0];
            for (int index = 0; index < length; index++) {
                out[index] = (unsigned[index] * 2D) - 1D;
            }
            return;
        }
        PrimitiveKernel kernel = program.nodeKernels[node];
        double scale = program.nodeScale[node];
        double[] scaledU = scratch.scaledU;
        double[] scaledV = scratch.scaledV;
        boolean signed = mode == NoiseProgram.SIGNED_2D;
        if (dep0 < 0) {
            for (int index = 0; index < length; index++) {
                scaledU[index] = first[index] * scale;
                scaledV[index] = second[index] * scale;
            }
        } else if (signed) {
            double fractureScale = program.nodeSignedFractureScale[node];
            double[] fractureU = values[dep0];
            double[] fractureV = values[program.termDep1[term]];
            for (int index = 0; index < length; index++) {
                scaledU[index] = (first[index] + (fractureU[index] * fractureScale)) * scale;
                scaledV[index] = (second[index] + (fractureV[index] * fractureScale)) * scale;
            }
        } else {
            double fractureScale = program.nodeFractureScale[node];
            double[] fractureU = values[dep0];
            double[] fractureV = values[program.termDep1[term]];
            for (int index = 0; index < length; index++) {
                scaledU[index] = (first[index] + ((fractureU[index] - 0.5D) * fractureScale)) * scale;
                scaledV[index] = (second[index] + ((fractureV[index] - 0.5D) * fractureScale)) * scale;
            }
        }
        if (signed) {
            kernel.signed2(scaledU, scaledV, out, length);
            applySignedOpacity(program, node, out, length);
            return;
        }
        kernel.noise2(scaledU, scaledV, out, length);
        applyOpacity(program, node, out, length);
        if (program.termApplyPost[term]) {
            post(program, scratch, term, node, out, length);
        }
    }

    private static void volumeTerm(NoiseProgram program, NoiseScratch scratch, int term, double[] first, double[] second, double[] third,
                                   int[] projection, int length) {
        int node = program.termNode[term];
        int mode = program.termMode[term];
        double[] out = scratch.termBuffer(term, length);
        if (program.nodeOpaque[node]) {
            CNG source = program.nodeSources[node];
            for (int index = 0; index < length; index++) {
                out[index] = JavaScalarBackend.opaque(source, mode, first[index], second[index], third[index]);
            }
            return;
        }
        double[][] values = scratch.values;
        int dep0 = program.termDep0[term];
        if (mode == NoiseProgram.SIGNED_3D && !program.nodeIdentitySigned[node]) {
            double[] unsigned = values[dep0];
            for (int index = 0; index < length; index++) {
                out[index] = (unsigned[index] * 2D) - 1D;
            }
            return;
        }
        PrimitiveKernel kernel = program.nodeKernels[node];
        double scale = program.nodeScale[node];
        double[] scaledU = scratch.scaledU;
        double[] scaledV = scratch.scaledV;
        double[] scaledW = scratch.scaledW;
        boolean signed = mode == NoiseProgram.SIGNED_3D;
        if (dep0 < 0) {
            for (int index = 0; index < length; index++) {
                scaledU[index] = first[index] * scale;
                scaledV[index] = second[index] * scale;
                scaledW[index] = third[index] * scale;
            }
        } else if (signed) {
            double fractureScale = program.nodeSignedFractureScale[node];
            double[] fractureU = values[dep0];
            double[] fractureV = values[program.termDep1[term]];
            double[] fractureW = values[program.termDep2[term]];
            for (int index = 0; index < length; index++) {
                scaledU[index] = (first[index] + (fractureU[index] * fractureScale)) * scale;
                scaledV[index] = (second[index] + (fractureV[projection[index]] * fractureScale)) * scale;
                scaledW[index] = (third[index] + (fractureW[index] * fractureScale)) * scale;
            }
        } else {
            double fractureScale = program.nodeFractureScale[node];
            double[] fractureU = values[dep0];
            double[] fractureV = values[program.termDep1[term]];
            double[] fractureW = values[program.termDep2[term]];
            for (int index = 0; index < length; index++) {
                scaledU[index] = (first[index] + ((fractureU[index] - 0.5D) * fractureScale)) * scale;
                scaledV[index] = (second[index] + ((fractureV[projection[index]] - 0.5D) * fractureScale)) * scale;
                scaledW[index] = (third[index] + ((fractureW[index] - 0.5D) * fractureScale)) * scale;
            }
        }
        if (signed) {
            kernel.signed3(scaledU, scaledV, scaledW, out, length);
            applySignedOpacity(program, node, out, length);
            return;
        }
        kernel.noise3(scaledU, scaledV, scaledW, out, length);
        applyOpacity(program, node, out, length);
        if (program.termApplyPost[term]) {
            post(program, scratch, term, node, out, length);
        }
    }

    private static void applyOpacity(NoiseProgram program, int node, double[] out, int length) {
        double opacity = program.nodeOpacity[node];
        for (int index = 0; index < length; index++) {
            out[index] = out[index] * opacity;
        }
    }

    private static void applySignedOpacity(NoiseProgram program, int node, double[] out, int length) {
        if (program.nodeUnityOpacity[node]) {
            return;
        }
        double opacity = program.nodeOpacity[node];
        for (int index = 0; index < length; index++) {
            out[index] = ((out[index] + 1D) * opacity) - 1D;
        }
    }

    private static void post(NoiseProgram program, NoiseScratch scratch, int term, int node, double[] out, int length) {
        double power = program.nodePower[node];
        double down = program.nodeDown[node];
        double up = program.nodeUp[node];
        double patch = program.nodePatch[node];
        int[] children = program.termChildren[term];
        ChildInjector injector = program.nodeInjector[node];
        double[][] values = scratch.values;
        for (int index = 0; index < length; index++) {
            double raw = out[index];
            double n = power != 1D ? (raw < 0 ? -Math.pow(Math.abs(raw), power) : Math.pow(raw, power)) : raw;
            double m = 1;
            if (children != null) {
                for (int child : children) {
                    n = JavaScalarBackend.combine(injector, n, values[child][index]);
                    m += JavaScalarBackend.weight(injector);
                }
            }
            out[index] = ((n / m) - down + up) * patch;
        }
    }
}
