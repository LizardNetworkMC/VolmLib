package art.arcane.volmlib.util.noise.program;

public interface NoiseBackend {
    String id();

    boolean supports(NoiseProgram program);

    void fill2D(NoiseProgram program, NoiseScratch scratch, double[] xs, double[] zs, int count, double[] out);

    void fillGrid2D(NoiseProgram program, NoiseScratch scratch, double x0, double z0, double step, int width, int depth, double[] out);

    void fillColumn3D(NoiseProgram program, NoiseScratch scratch, double x, double z, double y0, double yStep, int count, double[] out);

    void fillLattice3D(NoiseProgram program, NoiseScratch scratch, double x0, double y0, double z0, double stepX, double stepY, double stepZ, int nx, int ny, int nz, double[] out);

    void fillPoints3D(NoiseProgram program, NoiseScratch scratch, double[] xs, double[] ys, double[] zs, int count, double[] out);
}
