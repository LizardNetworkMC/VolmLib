package art.arcane.volmlib.util.noise.program;

public enum PrimitiveKind {
    SIMPLEX,
    PERLIN,
    BILLOW_SIMPLEX,
    BILLOW_PERLIN,
    FBM_SIMPLEX,
    RIGID_MULTI_SIMPLEX,
    CUBIC,
    FRACTAL_CUBIC,
    CELLULAR,
    GLOB,
    CELL_HEIGHT,
    VASCULAR,
    WHITE,
    FLAT,
    INTERPOLATED,
    GENERIC;

    public boolean isKernel() {
        return this != INTERPOLATED && this != GENERIC;
    }
}
