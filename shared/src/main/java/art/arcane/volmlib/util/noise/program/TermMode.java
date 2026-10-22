package art.arcane.volmlib.util.noise.program;

public enum TermMode {
    UNSIGNED_2D(false),
    NOISE_2D(false),
    SIGNED_2D(false),
    UNSIGNED_3D(true),
    NOISE_3D(true),
    SIGNED_3D(true);

    private final boolean volumetric;

    TermMode(boolean volumetric) {
        this.volumetric = volumetric;
    }

    public boolean isVolumetric() {
        return volumetric;
    }
}
