package art.arcane.volmlib.nativelib.minecraft26_2.terrain;

import java.util.OptionalInt;

public final class NativeJigsawBounds implements AutoCloseable {
    private static final ThreadLocal<Bounds> CURRENT = new ThreadLocal<>();
    private static volatile boolean placementBoundsApplied;

    private final Bounds previous;

    private NativeJigsawBounds(OptionalInt verticalDistance) {
        previous = CURRENT.get();
        if (verticalDistance.isPresent()) {
            CURRENT.set(new Bounds(verticalDistance.getAsInt()));
        } else {
            CURRENT.remove();
        }
    }

    public static NativeJigsawBounds open(OptionalInt verticalDistance) {
        return new NativeJigsawBounds(verticalDistance);
    }

    public static void heightLimits(int minimumY, int maximumY) {
        Bounds bounds = CURRENT.get();
        if (bounds != null) {
            bounds.minimumY = minimumY;
            bounds.maximumY = maximumY;
        }
    }

    public static void center(int centerY) {
        Bounds bounds = CURRENT.get();
        if (bounds != null) {
            bounds.centerY = centerY;
        }
    }

    public static boolean placementBoundsApplied() {
        return placementBoundsApplied;
    }

    public static double minimum(double vanillaMinimum) {
        placementBoundsApplied = true;
        Bounds bounds = CURRENT.get();
        return bounds == null ? vanillaMinimum : Math.max(bounds.minimumY, bounds.centerY - bounds.distance);
    }

    public static double maximum(double vanillaMaximum) {
        Bounds bounds = CURRENT.get();
        return bounds == null ? vanillaMaximum : Math.min(bounds.maximumY, bounds.centerY + bounds.distance + 1);
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private static final class Bounds {
        private final int distance;
        private int minimumY;
        private int maximumY;
        private int centerY;

        private Bounds(int distance) {
            if (distance < 0) {
                throw new IllegalArgumentException("Jigsaw vertical distance must not be negative");
            }
            this.distance = distance;
        }
    }
}
