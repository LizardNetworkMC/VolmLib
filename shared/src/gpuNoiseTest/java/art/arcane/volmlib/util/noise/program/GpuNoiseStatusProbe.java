package art.arcane.volmlib.util.noise.program;

public final class GpuNoiseStatusProbe {
    private GpuNoiseStatusProbe() {
    }

    public static void main(String[] arguments) {
        System.out.println("status=" + GpuNoise.status() + " available=" + GpuNoise.available());
    }
}
