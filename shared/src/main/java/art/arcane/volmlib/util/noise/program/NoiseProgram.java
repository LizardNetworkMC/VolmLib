package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.noise.CNG;

import java.util.List;

public final class NoiseProgram {
    static final int UNSIGNED_2D = 0;
    static final int NOISE_2D = 1;
    static final int SIGNED_2D = 2;
    static final int UNSIGNED_3D = 3;
    static final int NOISE_3D = 4;
    static final int SIGNED_3D = 5;
    private static final TermMode[] MODES = TermMode.values();

    final boolean volumetric;
    final int nodeCount;
    final CNG[] nodeSources;
    final PrimitiveKernel[] nodeKernels;
    final boolean[] nodeOpaque;
    final double[] nodeScale;
    final double[] nodeFractureScale;
    final double[] nodeSignedFractureScale;
    final double[] nodeOpacity;
    final boolean[] nodeUnityOpacity;
    final boolean[] nodeIdentitySigned;
    final double[] nodePower;
    final double[] nodeUp;
    final double[] nodeDown;
    final double[] nodePatch;
    final ChildInjector[] nodeInjector;
    final int[] nodeFracture;
    final int[][] nodeChildren;
    final int termCount;
    final int rootTerm;
    final int[] termMode;
    final int[] termNode;
    final int[] termAxis0;
    final int[] termAxis1;
    final int[] termAxis2;
    final int[] termDep0;
    final int[] termDep1;
    final int[] termDep2;
    final int[] termPair;
    final int[][] termChildren;
    final boolean[] termApplyPost;
    final int[] pairTerms;
    final int[] volumeTerms;
    final boolean[] pairUsed = new boolean[3];
    Object backendState;
    private final boolean representable;
    private final long signature;
    private final NoiseBackend backend;
    private final ScalarEvaluator scalar;
    private final PrimitiveKernel leafKernel;
    private final double leafScale;
    private final double leafOpacity;
    private final boolean leafUnityOpacity;

    NoiseProgram(NoiseProgramCompiler compiler) {
        volumetric = compiler.volumetric;
        List<NoiseProgramCompiler.NodeEntry> nodes = compiler.nodes;
        nodeCount = nodes.size();
        nodeSources = new CNG[nodeCount];
        nodeKernels = new PrimitiveKernel[nodeCount];
        nodeOpaque = new boolean[nodeCount];
        nodeScale = new double[nodeCount];
        nodeFractureScale = new double[nodeCount];
        nodeSignedFractureScale = new double[nodeCount];
        nodeOpacity = new double[nodeCount];
        nodeUnityOpacity = new boolean[nodeCount];
        nodeIdentitySigned = new boolean[nodeCount];
        nodePower = new double[nodeCount];
        nodeUp = new double[nodeCount];
        nodeDown = new double[nodeCount];
        nodePatch = new double[nodeCount];
        nodeInjector = new ChildInjector[nodeCount];
        nodeFracture = new int[nodeCount];
        nodeChildren = new int[nodeCount][];
        for (int node = 0; node < nodeCount; node++) {
            NoiseProgramCompiler.NodeEntry entry = nodes.get(node);
            nodeSources[node] = entry.source;
            nodeKernels[node] = entry.kernel;
            nodeOpaque[node] = entry.opaque;
            nodeScale[node] = entry.scale;
            nodeFractureScale[node] = entry.fractureScale;
            nodeSignedFractureScale[node] = entry.signedFractureScale;
            nodeOpacity[node] = entry.opacity;
            nodeUnityOpacity[node] = entry.unityOpacity;
            nodeIdentitySigned[node] = entry.identitySigned;
            nodePower[node] = entry.power;
            nodeUp[node] = entry.up;
            nodeDown[node] = entry.down;
            nodePatch[node] = entry.patch;
            nodeInjector[node] = entry.injector;
            nodeFracture[node] = entry.fracture;
            nodeChildren[node] = entry.children;
        }
        List<NoiseProgramCompiler.TermEntry> terms = compiler.terms;
        termCount = terms.size();
        rootTerm = termCount - 1;
        termMode = new int[termCount];
        termNode = new int[termCount];
        termAxis0 = new int[termCount];
        termAxis1 = new int[termCount];
        termAxis2 = new int[termCount];
        termDep0 = new int[termCount];
        termDep1 = new int[termCount];
        termDep2 = new int[termCount];
        termPair = new int[termCount];
        termChildren = new int[termCount][];
        termApplyPost = new boolean[termCount];
        int volumeCount = 0;
        for (int term = 0; term < termCount; term++) {
            NoiseProgramCompiler.TermEntry entry = terms.get(term);
            termMode[term] = entry.mode;
            termNode[term] = entry.node;
            termAxis0[term] = entry.axis0;
            termAxis1[term] = entry.axis1;
            termAxis2[term] = entry.axis2;
            termDep0[term] = entry.dep0;
            termDep1[term] = entry.dep1;
            termDep2[term] = entry.dep2;
            termPair[term] = NoiseProgramCompiler.pairId(entry.axis0, entry.axis1);
            termChildren[term] = entry.children;
            termApplyPost[term] = entry.applyPost;
            if (NoiseProgramCompiler.isVolumetricMode(entry.mode)) {
                volumeCount++;
            } else {
                pairUsed[termPair[term]] = true;
            }
        }
        pairTerms = new int[termCount - volumeCount];
        volumeTerms = new int[volumeCount];
        int pairCursor = 0;
        int volumeCursor = 0;
        for (int term = 0; term < termCount; term++) {
            if (NoiseProgramCompiler.isVolumetricMode(termMode[term])) {
                volumeTerms[volumeCursor++] = term;
            } else {
                pairTerms[pairCursor++] = term;
            }
        }
        representable = compiler.representable();
        signature = compiler.signature();
        NoiseBackend selected = NoiseBackends.selected();
        backend = selected.supports(this) ? selected : NoiseBackends.java();
        scalar = ScalarEvaluator.of(this);
        int rootNode = termNode[rootTerm];
        boolean leaf = termCount == 1 && !nodeOpaque[rootNode] && !termApplyPost[rootTerm] && termDep0[rootTerm] < 0;
        leafKernel = leaf ? nodeKernels[rootNode] : null;
        leafScale = nodeScale[rootNode];
        leafOpacity = nodeOpacity[rootNode];
        leafUnityOpacity = nodeUnityOpacity[rootNode];
    }

    public static NoiseProgram compile2D(CNG root) {
        return NoiseProgramCompiler.compile(root, false);
    }

    public static NoiseProgram compileSigned3D(CNG root) {
        return NoiseProgramCompiler.compile(root, true);
    }

    public boolean isExactlyRepresentable() {
        return representable;
    }

    public long structuralSignature() {
        return signature;
    }

    public NoiseScratch newScratch() {
        return new NoiseScratch(termCount);
    }

    public double sample2D(double x, double z) {
        requirePlanar();
        PrimitiveKernel kernel = leafKernel;
        if (kernel != null) {
            return kernel.noise2(x * leafScale, z * leafScale) * leafOpacity;
        }
        return scalar.sample(x, z, 0D);
    }

    public double sampleSigned3D(double x, double y, double z) {
        requireVolumetric();
        PrimitiveKernel kernel = leafKernel;
        if (kernel != null) {
            double signed = kernel.signed3(x * leafScale, y * leafScale, z * leafScale);
            return leafUnityOpacity ? signed : ((signed + 1D) * leafOpacity) - 1D;
        }
        return scalar.sample(x, y, z);
    }

    public void fill2D(NoiseScratch scratch, double[] xs, double[] zs, int count, double[] out) {
        requirePlanar();
        backend.fill2D(this, scratch, xs, zs, count, out);
    }

    public void fillGrid2D(NoiseScratch scratch, double x0, double z0, double step, int width, int depth, double[] out) {
        requirePlanar();
        backend.fillGrid2D(this, scratch, x0, z0, step, width, depth, out);
    }

    public void fillColumn3D(NoiseScratch scratch, double x, double z, double y0, double yStep, int count, double[] out) {
        requireVolumetric();
        backend.fillColumn3D(this, scratch, x, z, y0, yStep, count, out);
    }

    public void fillLattice3D(NoiseScratch scratch, double x0, double y0, double z0, double stepX, double stepY, double stepZ, int nx, int ny, int nz, double[] out) {
        requireVolumetric();
        backend.fillLattice3D(this, scratch, x0, y0, z0, stepX, stepY, stepZ, nx, ny, nz, out);
    }

    public NoiseBackend backend() {
        return backend;
    }

    public boolean isVolumetric() {
        return volumetric;
    }

    public int termCount() {
        return termCount;
    }

    public int rootTerm() {
        return rootTerm;
    }

    public TermMode termMode(int term) {
        return MODES[termMode[term]];
    }

    public int termNode(int term) {
        return termNode[term];
    }

    public int termAxis(int term, int slot) {
        return switch (slot) {
            case 0 -> termAxis0[term];
            case 1 -> termAxis1[term];
            case 2 -> termAxis2[term];
            default -> throw new IllegalArgumentException("Axis slot must be 0, 1 or 2");
        };
    }

    public int termDependency(int term, int slot) {
        return switch (slot) {
            case 0 -> termDep0[term];
            case 1 -> termDep1[term];
            case 2 -> termDep2[term];
            default -> throw new IllegalArgumentException("Dependency slot must be 0, 1 or 2");
        };
    }

    public int[] termChildren(int term) {
        int[] children = termChildren[term];
        return children == null ? new int[0] : children.clone();
    }

    public boolean termAppliesPost(int term) {
        return termApplyPost[term];
    }

    public int nodeCount() {
        return nodeCount;
    }

    public boolean nodeOpaque(int node) {
        return nodeOpaque[node];
    }

    public PrimitiveKernel nodeKernel(int node) {
        return nodeKernels[node];
    }

    public double nodeScale(int node) {
        return nodeScale[node];
    }

    public double nodeFractureScale(int node) {
        return nodeFractureScale[node];
    }

    public double nodeSignedFractureScale(int node) {
        return nodeSignedFractureScale[node];
    }

    public double nodeOpacity(int node) {
        return nodeOpacity[node];
    }

    public boolean nodeIdentitySigned(int node) {
        return nodeIdentitySigned[node];
    }

    public double nodePower(int node) {
        return nodePower[node];
    }

    public double nodeUp(int node) {
        return nodeUp[node];
    }

    public double nodeDown(int node) {
        return nodeDown[node];
    }

    public double nodePatch(int node) {
        return nodePatch[node];
    }

    public ChildInjector nodeInjector(int node) {
        return nodeInjector[node];
    }

    void requirePlanar() {
        if (volumetric) {
            throw new IllegalStateException("Program was compiled for signed 3D sampling");
        }
    }

    void requireVolumetric() {
        if (!volumetric) {
            throw new IllegalStateException("Program was compiled for 2D sampling");
        }
    }
}
