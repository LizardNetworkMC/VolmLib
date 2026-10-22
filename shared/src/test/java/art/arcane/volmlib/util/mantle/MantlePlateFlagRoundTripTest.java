package art.arcane.volmlib.util.mantle;

import art.arcane.volmlib.util.function.Consumer4;
import art.arcane.volmlib.util.io.CountingDataInputStream;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import art.arcane.volmlib.util.mantle.flag.ReservedFlag;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.mantle.runtime.MantleDataAdapter;
import art.arcane.volmlib.util.mantle.runtime.MantleHooks;
import art.arcane.volmlib.util.mantle.runtime.TectonicPlate;
import art.arcane.volmlib.util.matter.IrisMatter;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterSlice;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MantlePlateFlagRoundTripTest {
    private static final int SECTIONS = 6;
    private static final String PLATE_DIGEST = "6ec73e0f27802de2ff0f91e6d505e8a0982bc99837ed822219c1d551290b483a";
    private static final MantleFlag[] FLAGS = allFlags();

    @Test
    public void flaggedPlateBytesMatchThePinnedEncodingAndRoundTrip() throws Exception {
        TectonicPlate<Matter> plate = buildPlate();

        byte[] encoded = encode(plate);
        TectonicPlate<Matter> restored = TectonicPlate.read(SECTIONS * 16,
                CountingDataInputStream.readAhead(new ByteArrayInputStream(encoded)), true, ADAPTER, MantleHooks.NONE);
        byte[] again = encode(restored);

        assertArrayEquals(encoded, again);
        assertEquals(PLATE_DIGEST, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)));
        for (int index = 0; index < 1024; index++) {
            MantleChunk<Matter> original = plate.get(index & 31, index >> 5);
            MantleChunk<Matter> copy = restored.get(index & 31, index >> 5);
            if (original == null) {
                continue;
            }
            for (MantleFlag flag : FLAGS) {
                assertEquals(original.isFlagged(flag), copy.isFlagged(flag));
            }
        }
    }

    private static TectonicPlate<Matter> buildPlate() throws Exception {
        TectonicPlate<Matter> plate = new TectonicPlate<>(SECTIONS * 16, 2, 7, ADAPTER, MantleHooks.NONE);
        Random random = new Random(0x1A5EL);
        for (int index = 0; index < 96; index++) {
            MantleChunk<Matter> chunk = plate.getOrCreate(index & 31, index >> 5);
            chunk.use();
            try {
                for (MantleFlag flag : FLAGS) {
                    if (random.nextInt(4) != 0) {
                        continue;
                    }
                    switch (random.nextInt(3)) {
                        case 0 -> chunk.raiseFlagSuspend(flag, () -> writeCell(chunk, random));
                        case 1 -> chunk.raiseFlagUnchecked(flag, () -> writeCell(chunk, random));
                        default -> chunk.flag(flag, true);
                    }
                }
                if (random.nextInt(5) == 0) {
                    chunk.flag(ReservedFlag.CARVED, false);
                }
            } finally {
                chunk.release();
            }
            if (random.nextBoolean()) {
                assertTrue(chunk.tryLockUses());
                chunk.unlockUses(false);
            }
        }
        MantleChunk<Matter> source = plate.get(3, 0);
        MantleChunk<Matter> target = plate.getOrCreate(31, 31);
        target.copyFrom(source);
        assertTrue(plate.trySeal());
        plate.reopen();
        return plate;
    }

    private static void writeCell(MantleChunk<Matter> chunk, Random random) {
        Matter matter = chunk.getOrCreate(random.nextInt(SECTIONS));
        matter.getHeader().setCreatedAt(1_700_000_000_000L);
        MatterSlice<Integer> slice = matter.slice(Integer.class);
        slice.set(random.nextInt(16), random.nextInt(16), random.nextInt(16), random.nextInt(9) - 4);
    }

    private static MantleFlag[] allFlags() {
        ReservedFlag[] reserved = ReservedFlag.values();
        MantleFlag[] flags = new MantleFlag[reserved.length + MantleFlag.MAX_ORDINAL - MantleFlag.MIN_ORDINAL + 1];
        System.arraycopy(reserved, 0, flags, 0, reserved.length);
        for (int ordinal = MantleFlag.MIN_ORDINAL; ordinal <= MantleFlag.MAX_ORDINAL; ordinal++) {
            flags[reserved.length + ordinal - MantleFlag.MIN_ORDINAL] = MantleFlag.of(ordinal);
        }
        return flags;
    }

    private static byte[] encode(TectonicPlate<Matter> plate) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        plate.write(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static final MantleDataAdapter<Matter> ADAPTER = new MantleDataAdapter<>() {
        @Override
        public Matter createSection() {
            return new IrisMatter(16, 16, 16);
        }

        @Override
        public Matter readSection(CountingDataInputStream din) throws IOException {
            return Matter.readDin(din);
        }

        @Override
        public void writeSection(Matter section, DataOutputStream dos) throws IOException {
            section.writeTrimmedDos(dos);
        }

        @Override
        public void trimSection(Matter section) {
            section.trimSlices();
        }

        @Override
        public boolean isSectionEmpty(Matter section) {
            return section.getSliceMap().isEmpty();
        }

        @Override
        public Class<?> classifyValue(Object value) {
            return value.getClass();
        }

        @Override
        public <T> void set(Matter section, int x, int y, int z, Class<?> type, T value) {
            section.<T>slice(type).set(x, y, z, value);
        }

        @Override
        public <T> void remove(Matter section, int x, int y, int z, Class<T> type) {
            MatterSlice<T> slice = section.getSlice(type);
            if (slice != null) {
                slice.set(x, y, z, null);
            }
        }

        @Override
        public <T> T get(Matter section, int x, int y, int z, Class<T> type) {
            MatterSlice<T> slice = section.getSlice(type);
            return slice == null ? null : slice.get(x, y, z);
        }

        @Override
        public <T> void iterate(Matter section, Class<T> type, Consumer4<Integer, Integer, Integer, T> iterator) {
            MatterSlice<T> slice = section.getSlice(type);
            if (slice != null) {
                slice.iterateSync(iterator);
            }
        }

        @Override
        public boolean hasSlice(Matter section, Class<?> type) {
            return section.hasSlice(type);
        }

        @Override
        public void deleteSlice(Matter section, Class<?> type) {
            section.deleteSlice(type);
        }
    };
}
