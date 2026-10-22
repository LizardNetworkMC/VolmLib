package art.arcane.volmlib.nativelib.terrain;

public interface NativeSectionVolume extends NativeBlockVolume {
    int sectionCount();

    short[] section(int sectionIndex);

    NativeBlockState[] palette();
}
