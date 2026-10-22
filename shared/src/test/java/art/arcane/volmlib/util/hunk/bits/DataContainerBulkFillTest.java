package art.arcane.volmlib.util.hunk.bits;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DataContainerBulkFillTest {
    private static final Writable<Integer> INTEGERS = new Writable<>() {
        @Override
        public Integer readNodeData(DataInputStream input) throws IOException {
            return input.readInt();
        }

        @Override
        public void writeNodeData(DataOutputStream output, Integer value) throws IOException {
            output.writeInt(value);
        }
    };

    @Test
    public void bitFillMatchesScalarWritesValueForValueAndByteForByte() throws Exception {
        Random random = new Random(0xB175L);
        int[] lengths = {4096, 4096, 4096, 512, 100};
        double[] densities = {0.0D, 0.001D, 0.05D, 0.5D, 1.0D};
        for (int length : lengths) {
            for (double density : densities) {
                for (int prefill = 0; prefill < 3; prefill++) {
                    DataContainer<Integer> scalar = new DataContainer<>(INTEGERS, length);
                    DataContainer<Integer> bulk = new DataContainer<>(INTEGERS, length);
                    prefill(random.nextLong(), prefill, length, scalar, bulk);
                    for (int fill = 0; fill < 4; fill++) {
                        boolean alignedOnly = random.nextBoolean();
                        long[] bits = bits(random, length, density, alignedOnly);
                        Integer value = fill == 3 ? null : random.nextInt(40) - 20;
                        for (int position = 0; position < length; position++) {
                            if ((bits[position >>> 6] & (1L << position)) != 0L) {
                                scalar.set(position, value);
                            }
                        }
                        bulk.fillFromBits(bits, value);
                        for (int position = 0; position < length; position++) {
                            assertEquals(scalar.get(position), bulk.get(position));
                        }
                    }
                    assertArrayEquals(scalar.write(), bulk.write());
                }
            }
        }
    }

    @Test
    public void bitFillRejectsMismatchedOrOverlongBitSets() {
        DataContainer<Integer> container = new DataContainer<>(INTEGERS, 100);

        assertThrows(IllegalArgumentException.class, () -> container.fillFromBits(new long[1], 7));
        assertThrows(IllegalArgumentException.class, () -> container.fillFromBits(new long[3], 7));
        long[] overlong = new long[2];
        overlong[1] = 1L << 40;
        assertThrows(IllegalArgumentException.class, () -> container.fillFromBits(overlong, 7));
    }

    private static void prefill(long seed, int mode, int length, DataContainer<Integer> first, DataContainer<Integer> second) {
        Random random = new Random(seed);
        for (int position = 0; position < length; position++) {
            boolean write = switch (mode) {
                case 0 -> false;
                case 1 -> (position & 0x333) == 0 && random.nextInt(3) == 0;
                default -> random.nextInt(4) == 0;
            };
            if (write) {
                Integer value = random.nextInt(70);
                first.set(position, value);
                second.set(position, value);
            }
        }
    }

    private static long[] bits(Random random, int length, double density, boolean alignedOnly) {
        long[] bits = new long[(length + 63) >>> 6];
        for (int position = 0; position < length; position++) {
            if (alignedOnly && (position & 0x333) != 0) {
                continue;
            }
            if (random.nextDouble() < density) {
                bits[position >>> 6] |= 1L << position;
            }
        }
        return bits;
    }
}
