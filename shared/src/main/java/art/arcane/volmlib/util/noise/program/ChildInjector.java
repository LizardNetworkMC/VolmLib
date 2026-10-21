package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.function.NoiseInjector;
import art.arcane.volmlib.util.noise.CNG;

public enum ChildInjector {
    ADD,
    SRC_SUBTRACT,
    DST_SUBTRACT,
    MULTIPLY,
    MAX,
    MIN,
    SRC_MOD,
    SRC_POW,
    DST_MOD,
    DST_POW;

    public static ChildInjector of(NoiseInjector injector) {
        if (injector == CNG.ADD) {
            return ADD;
        }
        if (injector == CNG.SRC_SUBTRACT) {
            return SRC_SUBTRACT;
        }
        if (injector == CNG.DST_SUBTRACT) {
            return DST_SUBTRACT;
        }
        if (injector == CNG.MULTIPLY) {
            return MULTIPLY;
        }
        if (injector == CNG.MAX) {
            return MAX;
        }
        if (injector == CNG.MIN) {
            return MIN;
        }
        if (injector == CNG.SRC_MOD) {
            return SRC_MOD;
        }
        if (injector == CNG.SRC_POW) {
            return SRC_POW;
        }
        if (injector == CNG.DST_MOD) {
            return DST_MOD;
        }
        if (injector == CNG.DST_POW) {
            return DST_POW;
        }
        return null;
    }

    public boolean usesJavaMath() {
        return this == SRC_POW || this == DST_POW;
    }
}
