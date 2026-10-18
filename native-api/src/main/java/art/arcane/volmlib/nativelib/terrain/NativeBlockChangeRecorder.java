package art.arcane.volmlib.nativelib.terrain;

@FunctionalInterface
public interface NativeBlockChangeRecorder {
    void recordBlockChange(int x, int y, int z);
}
