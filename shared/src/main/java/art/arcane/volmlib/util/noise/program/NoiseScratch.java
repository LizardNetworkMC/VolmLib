package art.arcane.volmlib.util.noise.program;

import java.util.Arrays;

public final class NoiseScratch {
    static final int BLOCK = 256;

    final double[][] coordinates = new double[3][BLOCK];
    final int[][] projections = new int[3][BLOCK];
    final double[][] axes = new double[3][];
    final double[][] pairLow = new double[3][];
    final double[][] pairHigh = new double[3][];
    double[][] values;
    double[] scaledU = new double[BLOCK];
    double[] scaledV = new double[BLOCK];
    double[] scaledW = new double[BLOCK];
    double[] scalarValues;
    final double[] scalarCoordinates = new double[3];
    boolean scalarActive;

    public NoiseScratch() {
        this(0);
    }

    NoiseScratch(int terms) {
        values = new double[terms][];
        scalarValues = new double[terms];
    }

    double[] termBuffer(int term, int length) {
        double[] buffer = values[term];
        if (buffer == null || buffer.length < length) {
            buffer = new double[Math.max(length, BLOCK)];
            values[term] = buffer;
        }
        return buffer;
    }

    void prepareTerms(int terms) {
        if (values.length < terms) {
            values = Arrays.copyOf(values, terms);
        }
    }

    void prepareScaled(int length) {
        if (scaledU.length < length) {
            scaledU = new double[length];
            scaledV = new double[length];
            scaledW = new double[length];
        }
    }

    double[] axis(int axis, int length) {
        double[] buffer = axes[axis];
        if (buffer == null || buffer.length < length) {
            buffer = new double[Math.max(length, 16)];
            axes[axis] = buffer;
        }
        return buffer;
    }

    void preparePair(int pair, int length) {
        double[] low = pairLow[pair];
        if (low == null || low.length < length) {
            pairLow[pair] = new double[Math.max(length, 16)];
            pairHigh[pair] = new double[Math.max(length, 16)];
        }
    }

    double[] scalarValues(int terms) {
        if (scalarValues.length < terms) {
            scalarValues = new double[terms];
        }
        return scalarValues;
    }
}
