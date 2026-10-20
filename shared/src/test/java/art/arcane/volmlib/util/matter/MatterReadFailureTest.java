package art.arcane.volmlib.util.matter;

import art.arcane.volmlib.util.io.CountingDataInputStream;
import art.arcane.volmlib.util.matter.slices.IntMatter;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

public class MatterReadFailureTest {
    @Test
    public void sliceIOExceptionPropagatesWithoutPublishingTheSlice() throws Exception {
        assertSliceFailurePropagates(new IOException("Slice unavailable"));
    }

    @Test
    public void sliceRuntimeFailurePropagatesWithoutPublishingTheSlice() throws Exception {
        assertSliceFailurePropagates(new IllegalStateException("Slice unavailable"));
    }

    @Test
    public void sliceJvmErrorPropagatesWithoutPublishingTheSlice() throws Exception {
        assertSliceFailurePropagates(new OutOfMemoryError("Slice allocation failed"));
    }

    @Test
    public void missingCodecRejectsTheMatter() throws Exception {
        IrisMatter matter = spy(new IrisMatter(1, 1, 1));
        doReturn(null).when(matter).createSlice(Integer.class, matter);
        try (CountingDataInputStream input = inputWithSlices(integerPayload())) {
            assertThrows(IOException.class, () -> Matter.readDin(input, size -> matter));
            assertTrue(matter.getSliceMap().isEmpty());
        }
    }

    @Test
    public void negativeAndEmptySliceFramesRejectTheMatter() throws Exception {
        for (int invalidLength : new int[]{-1, 0}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeHeader(output, 1);
            output.writeInt(invalidLength);
            try (CountingDataInputStream input = CountingDataInputStream.wrap(new ByteArrayInputStream(bytes.toByteArray()))) {
                assertThrows(IOException.class, () -> Matter.readDin(input));
            }
        }
    }

    @Test
    public void underConsumedSliceRejectsTheMatterWithoutPublishingIt() throws Exception {
        IrisMatter matter = spy(new IrisMatter(1, 1, 1));
        IntMatter slice = mock(IntMatter.class);
        doReturn(slice).when(matter).createSlice(Integer.class, matter);
        try (CountingDataInputStream input = inputWithSlices(integerPayload())) {
            assertThrows(IOException.class, () -> Matter.readDin(input, size -> matter));
            assertTrue(matter.getSliceMap().isEmpty());
        }
    }

    @Test
    public void overConsumedSliceRejectsTheMatterWithoutPublishingIt() throws Exception {
        IrisMatter matter = spy(new IrisMatter(1, 1, 1));
        IntMatter slice = mock(IntMatter.class);
        doReturn(slice).when(matter).createSlice(Integer.class, matter);
        doAnswer(invocation -> {
            invocation.getArgument(0, CountingDataInputStream.class).readInt();
            return null;
        }).when(slice).read(any());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        writeHeader(output, 1);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        new DataOutputStream(payload).writeUTF("java.lang.Integer");
        output.writeInt(payload.size());
        payload.writeTo(output);
        output.writeInt(73);
        try (CountingDataInputStream input = CountingDataInputStream.wrap(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThrows(IOException.class, () -> Matter.readDin(input, size -> matter));
            assertTrue(matter.getSliceMap().isEmpty());
        }
    }

    @Test
    public void duplicateSlicesRejectTheMatter() throws Exception {
        try (CountingDataInputStream input = inputWithSlices(integerPayload(), integerPayload())) {
            assertThrows(IOException.class, () -> Matter.readDin(input));
        }
    }

    @Test
    public void truncatedKnownSliceRejectsTheMatter() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        new DataOutputStream(payload).writeUTF("java.lang.Integer");
        try (CountingDataInputStream input = inputWithSlices(payload.toByteArray())) {
            assertThrows(IOException.class, () -> Matter.readDin(input));
        }
    }

    private static void assertSliceFailurePropagates(Throwable failure) throws Exception {
        IrisMatter matter = spy(new IrisMatter(1, 1, 1));
        IntMatter slice = mock(IntMatter.class);
        doReturn(slice).when(matter).createSlice(Integer.class, matter);
        doThrow(failure).when(slice).read(any());
        try (CountingDataInputStream input = inputWithSlices(integerPayload())) {
            assertSame(failure, assertThrows(Throwable.class, () -> Matter.readDin(input, size -> matter)));
            assertTrue(matter.getSliceMap().isEmpty());
        }
    }

    private static CountingDataInputStream inputWithSlices(byte[]... payloads) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        writeHeader(output, payloads.length);
        for (byte[] payload : payloads) {
            output.writeInt(payload.length);
            output.write(payload);
        }
        return CountingDataInputStream.wrap(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static void writeHeader(DataOutputStream output, int slices) throws IOException {
        output.writeInt(1);
        output.writeInt(1);
        output.writeInt(1);
        output.writeByte(slices);
        new MatterHeader().write(output);
    }

    private static byte[] integerPayload() throws IOException {
        IntMatter integers = new IntMatter(1, 1, 1);
        integers.set(0, 0, 0, 42);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        integers.write(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }
}
