package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.noise.FastNoiseDouble;
import art.arcane.volmlib.util.noise.PerlinNoise;
import art.arcane.volmlib.util.noise.SimplexNoise;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class NativeProgramEncoder {
    static final int MAGIC = 0x564e5052;
    static final int VERSION = 1;
    static final int HEADER_BYTES = 48;
    static final int NODE_BYTES = 160;
    static final int TERM_BYTES = 48;
    private static final int KIND_SIMPLEX = 0;
    private static final int KIND_PERLIN = 1;
    private static final int KIND_BILLOW_SIMPLEX = 2;
    private static final int KIND_FBM_SIMPLEX = 3;
    private static final int KIND_RIGID_MULTI_SIMPLEX = 4;
    private static final int KIND_CELLULAR = 5;
    private static final int KIND_VASCULAR = 6;
    private static final int KIND_GLOB = 7;
    private static final int KIND_WHITE = 8;

    private NativeProgramEncoder() {
    }

    static MemorySegment encode(NoiseProgram program) {
        if (!program.isExactlyRepresentable()) {
            return null;
        }
        int nodeCount = program.nodeCount;
        int[] kinds = new int[nodeCount];
        for (int node = 0; node < nodeCount; node++) {
            kinds[node] = nativeKind(program.nodeKernels[node]);
            if (kinds[node] < 0) {
                return null;
            }
        }
        int termCount = program.termCount;
        int childCount = 0;
        for (int term = 0; term < termCount; term++) {
            if (program.termApplyPost[term] && program.termChildren[term] != null) {
                childCount += program.termChildren[term].length;
            }
        }
        long size = HEADER_BYTES + (long) nodeCount * NODE_BYTES + (long) termCount * TERM_BYTES
                + 4L * (childCount + program.pairTerms.length + program.volumeTerms.length);
        MemorySegment segment = Arena.ofAuto().allocate(size, 8);
        segment.set(ValueLayout.JAVA_INT, 0, MAGIC);
        segment.set(ValueLayout.JAVA_INT, 4, VERSION);
        segment.set(ValueLayout.JAVA_INT, 8, program.volumetric ? 1 : 0);
        segment.set(ValueLayout.JAVA_INT, 12, nodeCount);
        segment.set(ValueLayout.JAVA_INT, 16, termCount);
        segment.set(ValueLayout.JAVA_INT, 20, childCount);
        segment.set(ValueLayout.JAVA_INT, 24, program.pairTerms.length);
        segment.set(ValueLayout.JAVA_INT, 28, program.volumeTerms.length);
        for (int pair = 0; pair < 3; pair++) {
            segment.set(ValueLayout.JAVA_INT, 32 + pair * 4L, program.pairUsed[pair] ? 1 : 0);
        }
        long offset = HEADER_BYTES;
        for (int node = 0; node < nodeCount; node++) {
            writeNode(program, node, kinds[node], segment, offset);
            offset += NODE_BYTES;
        }
        long childOffset = offset + (long) termCount * TERM_BYTES;
        int childCursor = 0;
        for (int term = 0; term < termCount; term++) {
            int[] children = program.termApplyPost[term] ? program.termChildren[term] : null;
            int count = children == null ? 0 : children.length;
            segment.set(ValueLayout.JAVA_INT, offset, program.termMode[term]);
            segment.set(ValueLayout.JAVA_INT, offset + 4, program.termNode[term]);
            segment.set(ValueLayout.JAVA_INT, offset + 8, program.termAxis0[term]);
            segment.set(ValueLayout.JAVA_INT, offset + 12, program.termAxis1[term]);
            segment.set(ValueLayout.JAVA_INT, offset + 16, program.termAxis2[term]);
            segment.set(ValueLayout.JAVA_INT, offset + 20, program.termDep0[term]);
            segment.set(ValueLayout.JAVA_INT, offset + 24, program.termDep1[term]);
            segment.set(ValueLayout.JAVA_INT, offset + 28, program.termDep2[term]);
            segment.set(ValueLayout.JAVA_INT, offset + 32, program.termPair[term]);
            segment.set(ValueLayout.JAVA_INT, offset + 36, program.termApplyPost[term] ? 1 : 0);
            segment.set(ValueLayout.JAVA_INT, offset + 40, childCursor);
            segment.set(ValueLayout.JAVA_INT, offset + 44, count);
            for (int child = 0; child < count; child++) {
                segment.set(ValueLayout.JAVA_INT, childOffset + 4L * (childCursor + child), children[child]);
            }
            childCursor += count;
            offset += TERM_BYTES;
        }
        offset = childOffset + 4L * childCount;
        for (int term : program.pairTerms) {
            segment.set(ValueLayout.JAVA_INT, offset, term);
            offset += 4;
        }
        for (int term : program.volumeTerms) {
            segment.set(ValueLayout.JAVA_INT, offset, term);
            offset += 4;
        }
        return segment;
    }

    private static int nativeKind(PrimitiveKernel kernel) {
        FastNoiseDouble noise = kernel.fastNoise();
        return switch (kernel.kind()) {
            case SIMPLEX -> KIND_SIMPLEX;
            case PERLIN -> KIND_PERLIN;
            case BILLOW_SIMPLEX -> KIND_BILLOW_SIMPLEX;
            case FBM_SIMPLEX -> KIND_FBM_SIMPLEX;
            case RIGID_MULTI_SIMPLEX -> KIND_RIGID_MULTI_SIMPLEX;
            case CELLULAR -> cellularKind(noise, KIND_CELLULAR);
            case VASCULAR -> cellularKind(noise, KIND_VASCULAR);
            case GLOB -> cellularKind(noise, KIND_GLOB);
            case WHITE -> KIND_WHITE;
            default -> -1;
        };
    }

    private static int cellularKind(FastNoiseDouble noise, int kind) {
        if (noise.getCellularNoiseLookup() != null || noise.getCellularReturnType() == FastNoiseDouble.CellularReturnType.NoiseLookup) {
            return -1;
        }
        return kind;
    }

    private static void writeNode(NoiseProgram program, int node, int kind, MemorySegment segment, long offset) {
        PrimitiveKernel kernel = program.nodeKernels[node];
        FastNoiseDouble noise = kernel.fastNoise();
        int octaves = 1;
        double octaveBounding = 1D;
        if (kind == KIND_SIMPLEX) {
            SimplexNoise simplex = (SimplexNoise) kernel.source();
            octaves = simplex.getOctaves();
            octaveBounding = simplex.getOctaveBounding();
        } else if (kind == KIND_PERLIN) {
            PerlinNoise perlin = (PerlinNoise) kernel.source();
            octaves = perlin.getOctaves();
            octaveBounding = perlin.getOctaveBounding();
        } else if (kind == KIND_BILLOW_SIMPLEX || kind == KIND_FBM_SIMPLEX || kind == KIND_RIGID_MULTI_SIMPLEX) {
            octaves = (int) noise.getFractalOctaves();
        }
        ChildInjector injector = program.nodeInjector[node];
        segment.set(ValueLayout.JAVA_INT, offset, kind);
        segment.set(ValueLayout.JAVA_INT, offset + 4, octaves);
        segment.set(ValueLayout.JAVA_INT, offset + 8, noise.getLongerp().ordinal());
        segment.set(ValueLayout.JAVA_INT, offset + 12, noise.getFractalType().ordinal());
        segment.set(ValueLayout.JAVA_INT, offset + 16, noise.getCellularDistanceFunction().ordinal());
        segment.set(ValueLayout.JAVA_INT, offset + 20, noise.getCellularReturnType().ordinal());
        segment.set(ValueLayout.JAVA_INT, offset + 24, injector == null ? -1 : injector.ordinal());
        segment.set(ValueLayout.JAVA_INT, offset + 28, program.nodeUnityOpacity[node] ? 1 : 0);
        segment.set(ValueLayout.JAVA_INT, offset + 32, program.nodeIdentitySigned[node] ? 1 : 0);
        segment.set(ValueLayout.JAVA_INT, offset + 36, 0);
        segment.set(ValueLayout.JAVA_LONG, offset + 40, noise.getSeed());
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 48, noise.getFrequency());
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 56, kernel.offsetX());
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 64, kernel.offsetZ());
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 72, octaveBounding);
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 80, noise.getFractalLacunarity());
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 88, noise.getFractalGain());
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 96, noise.getFractalBounding());
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 104, program.nodeScale[node]);
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 112, program.nodeFractureScale[node]);
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 120, program.nodeSignedFractureScale[node]);
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 128, program.nodeOpacity[node]);
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 136, program.nodeUp[node]);
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 144, program.nodeDown[node]);
        segment.set(ValueLayout.JAVA_DOUBLE, offset + 152, program.nodePatch[node]);
    }
}
