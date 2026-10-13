package art.arcane.volmlib.util.interpolation;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class IrisInterpolationBoundsTest {
    private static final InterpolationMethod[] PAIRED_METHODS = {
            InterpolationMethod.BILINEAR,
            InterpolationMethod.BILINEAR_STARCAST_3,
            InterpolationMethod.BILINEAR_STARCAST_6,
            InterpolationMethod.BILINEAR_STARCAST_9,
            InterpolationMethod.BILINEAR_STARCAST_12
    };

    @Test
    public void everyMethodPreservesSeparatePassValuesAndSamplesEachCoordinateOnce() {
        Random random = new Random(19053);
        for (InterpolationMethod method : InterpolationMethod.values()) {
            for (double scale : new double[]{1D, 7D, 12D, 23D, 200D, 7.75D, 8192D}) {
                for (int index = 0; index < 24; index++) {
                    int x = random.nextInt();
                    int z = random.nextInt();
                    Map<Coordinate, NoiseBounds> expectedSamples = new HashMap<>();
                    NoiseBounds expected = separatePasses(method, x, z, scale,
                            IrisInterpolationBoundsTest::asymmetricBounds, expectedSamples);
                    Map<Coordinate, Integer> actualSamples = new HashMap<>();
                    NoiseBounds actual = IrisInterpolation.getNoiseBounds(method, x, z, scale, (sx, sz) -> {
                        actualSamples.merge(new Coordinate(sx, sz), 1, Integer::sum);
                        return asymmetricBounds(sx, sz);
                    });
                    assertBounds(expected, actual);
                    assertEquals(expectedSamples.keySet(), actualSamples.keySet());
                    for (int calls : actualSamples.values()) {
                        assertEquals(1, calls);
                    }
                }
            }
        }
    }

    @Test
    public void fractionalBoundsMatchSeparatePassesAndSampleEachCoordinateOnce() {
        for (InterpolationMethod method : InterpolationMethod.values()) {
            Map<Coordinate, NoiseBounds> expectedSamples = new HashMap<>();
            NoiseBounds expected = separatePasses(method, -31.75D, 29.25D, 32D,
                    IrisInterpolationBoundsTest::asymmetricBounds, expectedSamples);
            Map<Coordinate, Integer> actualSamples = new HashMap<>();
            NoiseBounds actual = IrisInterpolation.getNoiseBounds(method, -31.75D, 29.25D, 32D, (x, z) -> {
                actualSamples.merge(new Coordinate(x, z), 1, Integer::sum);
                return asymmetricBounds(x, z);
            });

            assertBounds(expected, actual);
            assertEquals(expectedSamples.keySet(), actualSamples.keySet());
            for (int calls : actualSamples.values()) {
                assertEquals(1, calls);
            }
        }
    }

    @Test
    public void pairedMethodsPreserveRawValuesAndCallbackOrderAtGridEdges() {
        for (InterpolationMethod method : PAIRED_METHODS) {
            for (double scale : new double[]{1D, 6D, 12D, 23D, 52D, 200D, 7.75D, 8192D}) {
                double[] points = {-30_000_000D, -scale, Math.nextDown(-scale), Math.nextUp(-scale),
                        -0.75D, -0D, 0D, Math.nextDown(scale), scale, Math.nextUp(scale), 30_000_000D};
                for (int index = 0; index < points.length; index++) {
                    double x = points[index];
                    double z = points[points.length - index - 1];
                    Map<Coordinate, NoiseBounds> expectedSamples = new LinkedHashMap<>();
                    AtomicInteger expectedCalls = new AtomicInteger();
                    NoiseBounds expected = separatePasses(method, x, z, scale,
                            (sx, sz) -> orderedBounds(sx, sz, expectedCalls.incrementAndGet()), expectedSamples);
                    List<Coordinate> actualSamples = new ArrayList<>();
                    AtomicInteger actualCalls = new AtomicInteger();
                    NoiseBounds actual = IrisInterpolation.getNoiseBounds(method, x, z, scale, (sx, sz) -> {
                        actualSamples.add(new Coordinate(sx, sz));
                        return orderedBounds(sx, sz, actualCalls.incrementAndGet());
                    });
                    assertBounds(expected, actual);
                    assertEquals(new ArrayList<>(expectedSamples.keySet()), actualSamples);
                }
            }
        }
    }

    @Test
    public void pairedMethodsPreserveSignedZeroAndNonfiniteArithmetic() {
        double[] values = {0D, -0D, Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MAX_VALUE, -Double.MAX_VALUE, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.longBitsToDouble(0x7ff8000000000042L)};
        for (InterpolationMethod method : PAIRED_METHODS) {
            for (int index = 0; index < values.length; index++) {
                double minimum = values[index];
                double maximum = values[values.length - index - 1];
                NoiseBoundsProvider provider = (x, z) -> new NoiseBounds(minimum, maximum);
                NoiseBounds expected = separatePasses(method, -7D, -0D, 7D, provider, new HashMap<>());
                assertBounds(expected, IrisInterpolation.getNoiseBounds(method, -7D, -0D, 7D, provider));
            }
        }
    }

    @Test
    public void nestedPairedProvidersKeepTheirOwnCacheAndReplay() {
        for (InterpolationMethod outer : PAIRED_METHODS) {
            for (InterpolationMethod inner : PAIRED_METHODS) {
                NoiseBoundsProvider expectedProvider = (x, z) -> separatePasses(inner,
                        x - 17D, z + 19D, 6D, IrisInterpolationBoundsTest::asymmetricBounds, new HashMap<>());
                NoiseBoundsProvider actualProvider = (x, z) -> IrisInterpolation.getNoiseBounds(inner,
                        x - 17D, z + 19D, 6D, IrisInterpolationBoundsTest::asymmetricBounds);
                NoiseBounds expected = separatePasses(outer, -93D, 201D, 23D,
                        expectedProvider, new HashMap<>());
                assertBounds(expected, IrisInterpolation.getNoiseBounds(outer, -93D, 201D, 23D, actualProvider));
            }
        }
    }

    @Test
    public void nestedProvidersKeepTheirOwnSamplingSequences() {
        for (InterpolationMethod method : InterpolationMethod.values()) {
            NoiseBoundsProvider expectedProvider = (x, z) -> separatePasses(
                    InterpolationMethod.HERMITE_STARCAST_12, (int) x + 103, (int) z - 79, 13.5D,
                    IrisInterpolationBoundsTest::asymmetricBounds, new HashMap<>());
            NoiseBoundsProvider actualProvider = (x, z) -> IrisInterpolation.getNoiseBounds(
                    InterpolationMethod.HERMITE_STARCAST_12, (int) x + 103, (int) z - 79, 13.5D,
                    IrisInterpolationBoundsTest::asymmetricBounds);
            NoiseBounds expected = separatePasses(method, -93, 201, 23D, expectedProvider, new HashMap<>());
            NoiseBounds actual = IrisInterpolation.getNoiseBounds(method, -93, 201, 23D, actualProvider);
            assertBounds(expected, actual);
        }
    }

    @Test
    public void nestedScalarProvidersPreserveTheirOriginalCallbackOrder() {
        for (InterpolationMethod method : InterpolationMethod.values()) {
            AtomicInteger expectedCalls = new AtomicInteger();
            AtomicInteger actualCalls = new AtomicInteger();
            NoiseBoundsProvider expectedProvider = (x, z) -> scalarBounds(x, z, expectedCalls);
            NoiseBoundsProvider actualProvider = (x, z) -> scalarBounds(x, z, actualCalls);
            NoiseBounds expected = separatePasses(method, -93, 201, 23D, expectedProvider, new HashMap<>());
            NoiseBounds actual = IrisInterpolation.getNoiseBounds(method, -93, 201, 23D, actualProvider);
            assertBounds(expected, actual);
            assertEquals(expectedCalls.get(), actualCalls.get());
        }
    }

    private static NoiseBounds scalarBounds(double x, double z, AtomicInteger calls) {
        double value = IrisInterpolation.getNoise(InterpolationMethod.HERMITE_STARCAST_12,
                (int) x + 103, (int) z - 79, 13.5D, (sx, sz) -> calls.incrementAndGet());
        return new NoiseBounds(value, value + 17D);
    }

    @Test
    public void failedProviderDoesNotLeavePartialSamplesForTheNextPass() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("provider failure");
        try {
            IrisInterpolation.getNoiseBounds(InterpolationMethod.HERMITE_STARCAST_12, 71, -209, 7D,
                    (x, z) -> {
                        if (calls.incrementAndGet() == 5) {
                            throw failure;
                        }
                        return asymmetricBounds(x, z);
                    });
            fail("Expected provider failure");
        } catch (IllegalStateException expected) {
            assertEquals(failure, expected);
        }
        NoiseBounds expected = separatePasses(InterpolationMethod.HERMITE_STARCAST_12, 71, -209, 7D,
                IrisInterpolationBoundsTest::asymmetricBounds, new HashMap<>());
        NoiseBounds actual = IrisInterpolation.getNoiseBounds(InterpolationMethod.HERMITE_STARCAST_12,
                71, -209, 7D, IrisInterpolationBoundsTest::asymmetricBounds);
        assertBounds(expected, actual);
    }

    private static NoiseBounds separatePasses(InterpolationMethod method, double x, double z, double scale,
                                              NoiseBoundsProvider provider, Map<Coordinate, NoiseBounds> samples) {
        double minimum = IrisInterpolation.getNoise(method, x, z, scale,
                (sx, sz) -> samples.computeIfAbsent(new Coordinate(sx, sz),
                        coordinate -> provider.noise(sx, sz)).min());
        double maximum = IrisInterpolation.getNoise(method, x, z, scale,
                (sx, sz) -> samples.computeIfAbsent(new Coordinate(sx, sz),
                        coordinate -> provider.noise(sx, sz)).max());
        return new NoiseBounds(minimum, maximum);
    }

    private static NoiseBounds asymmetricBounds(double x, double z) {
        double minimum = StrictMath.sin(x * 0.0031D) * 35D - StrictMath.cos(z * 0.0087D) * 7D;
        return new NoiseBounds(minimum, minimum + 17D + StrictMath.cos(x * 0.0123D - z * 0.019D) * 11D);
    }

    private static NoiseBounds orderedBounds(double x, double z, int call) {
        NoiseBounds bounds = asymmetricBounds(x, z);
        return new NoiseBounds(bounds.min() + call * 0.001D, bounds.max() - call * 0.003D);
    }

    private static void assertBounds(NoiseBounds expected, NoiseBounds actual) {
        assertEquals(Double.doubleToRawLongBits(expected.min()), Double.doubleToRawLongBits(actual.min()));
        assertEquals(Double.doubleToRawLongBits(expected.max()), Double.doubleToRawLongBits(actual.max()));
    }

    private record Coordinate(double x, double z) {
    }
}
