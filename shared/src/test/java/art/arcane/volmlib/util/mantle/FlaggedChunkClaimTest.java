package art.arcane.volmlib.util.mantle;

import art.arcane.volmlib.util.io.CountingDataInputStream;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import org.junit.Test;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FlaggedChunkClaimTest {
    @Test
    public void claimIsExclusiveUntilCompletedAndThenStaysRaised() {
        TestChunk chunk = new TestChunk();

        assertTrue(chunk.tryClaim(MantleFlag.CARVED));
        assertTrue(chunk.isClaimed(MantleFlag.CARVED));
        assertFalse(chunk.tryClaim(MantleFlag.CARVED));
        assertTrue(chunk.tryClaim(MantleFlag.OBJECT));
        chunk.completeClaim(MantleFlag.CARVED);

        assertTrue(chunk.isFlagged(MantleFlag.CARVED));
        assertFalse(chunk.isClaimed(MantleFlag.CARVED));
        assertFalse(chunk.tryClaim(MantleFlag.CARVED));
        assertTrue(chunk.isClaimed(MantleFlag.OBJECT));
        chunk.abandonClaim(MantleFlag.OBJECT);
        assertFalse(chunk.isFlagged(MantleFlag.OBJECT));
        assertTrue(chunk.tryClaim(MantleFlag.OBJECT));
    }

    @Test
    public void completingAFlagRaisedDuringTheTaskFailsAndReleasesTheClaim() {
        TestChunk chunk = new TestChunk();
        assertTrue(chunk.tryClaim(MantleFlag.CARVED));
        chunk.flag(MantleFlag.CARVED, true);

        assertThrows(IllegalStateException.class, () -> chunk.completeClaim(MantleFlag.CARVED));
        assertFalse(chunk.isClaimed(MantleFlag.CARVED));
        assertThrows(IllegalStateException.class,
                () -> chunk.raiseFlagSuspendAfterFlag(MantleFlag.of(70)));
        assertFalse(chunk.isClaimed(MantleFlag.of(70)));
    }

    @Test
    public void closedChunkRejectsClaims() {
        TestChunk chunk = new TestChunk();
        chunk.close();

        assertThrows(MantleClosedException.class, () -> chunk.tryClaim(MantleFlag.CARVED));
        assertThrows(MantleClosedException.class, () -> chunk.raiseFlagSuspend(MantleFlag.CARVED, () -> {
        }));
    }

    @Test(timeout = 10_000L)
    public void suspendedRaiseWaitsForTheHolderAndSkipsCompletedWork() throws Exception {
        TestChunk chunk = new TestChunk();
        assertTrue(chunk.tryClaim(MantleFlag.CARVED));
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiter = executor.submit(() -> {
                started.countDown();
                chunk.raiseFlagSuspend(MantleFlag.CARVED, runs::incrementAndGet);
            });
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            Thread.sleep(50L);
            assertFalse(waiter.isDone());
            chunk.completeClaim(MantleFlag.CARVED);
            waiter.get(2L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, runs.get());
        assertTrue(chunk.isFlagged(MantleFlag.CARVED));
    }

    @Test(timeout = 10_000L)
    public void suspendedRaiseRunsTheTaskWhenTheHolderAbandons() throws Exception {
        TestChunk chunk = new TestChunk();
        assertTrue(chunk.tryClaim(MantleFlag.CARVED));
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiter = executor.submit(() -> {
                started.countDown();
                chunk.raiseFlagSuspend(MantleFlag.CARVED, runs::incrementAndGet);
            });
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            Thread.sleep(50L);
            chunk.abandonClaim(MantleFlag.CARVED);
            waiter.get(2L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, runs.get());
        assertTrue(chunk.isFlagged(MantleFlag.CARVED));
        assertFalse(chunk.isClaimed(MantleFlag.CARVED));
    }

    @Test(timeout = 10_000L)
    public void failedTaskLeavesTheFlagLoweredAndUnclaimed() {
        TestChunk chunk = new TestChunk();

        assertThrows(IllegalArgumentException.class, () -> chunk.raiseFlagSuspend(MantleFlag.CARVED, () -> {
            throw new IllegalArgumentException("boom");
        }));

        assertFalse(chunk.isFlagged(MantleFlag.CARVED));
        assertFalse(chunk.isClaimed(MantleFlag.CARVED));
        chunk.raiseFlagSuspend(MantleFlag.CARVED, () -> {
        });
        assertTrue(chunk.isFlagged(MantleFlag.CARVED));
    }

    @Test(timeout = 10_000L)
    public void copyWaitsForClaimsInFlight() throws Exception {
        TestChunk source = new TestChunk();
        source.flag(MantleFlag.REAL, true);
        TestChunk target = new TestChunk();
        assertTrue(target.tryClaim(MantleFlag.of(200)));
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> copy = executor.submit(() -> {
                started.countDown();
                target.copyFrom(source);
            });
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            Thread.sleep(50L);
            assertFalse(copy.isDone());
            target.abandonClaim(MantleFlag.of(200));
            copy.get(2L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(target.isFlagged(MantleFlag.REAL));
        assertTrue(target.tryClaim(MantleFlag.of(200)));
    }

    @Test(timeout = 30_000L)
    public void concurrentRaisesRunEachFlagExactlyOnce() throws Exception {
        int threads = 8;
        MantleFlag[] flags = {MantleFlag.CARVED, MantleFlag.OBJECT, MantleFlag.of(64), MantleFlag.of(127),
                MantleFlag.of(128), MantleFlag.of(255)};
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 200; round++) {
                TestChunk chunk = new TestChunk();
                AtomicIntegerArray runs = new AtomicIntegerArray(flags.length);
                CyclicBarrier barrier = new CyclicBarrier(threads);
                List<Future<?>> futures = new ArrayList<>(threads);
                for (int thread = 0; thread < threads; thread++) {
                    int offset = thread;
                    futures.add(executor.submit(() -> {
                        barrier.await(5L, TimeUnit.SECONDS);
                        for (int index = 0; index < flags.length; index++) {
                            int flagIndex = (index + offset) % flags.length;
                            if ((offset & 1) == 0) {
                                chunk.raiseFlagSuspend(flags[flagIndex], () -> {
                                    runs.incrementAndGet(flagIndex);
                                    Thread.onSpinWait();
                                });
                            } else if (chunk.tryClaim(flags[flagIndex])) {
                                runs.incrementAndGet(flagIndex);
                                chunk.completeClaim(flags[flagIndex]);
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(10L, TimeUnit.SECONDS);
                }
                for (int index = 0; index < flags.length; index++) {
                    assertEquals(1, runs.get(index));
                    assertTrue(chunk.isFlagged(flags[index]));
                    assertFalse(chunk.isClaimed(flags[index]));
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class TestChunk extends MantleChunkSupport<int[]> {
        private TestChunk() {
            super(1, 0, 0);
        }

        private void copyFrom(TestChunk other) {
            super.copyFrom(other);
        }

        private void raiseFlagSuspendAfterFlag(MantleFlag flag) {
            raiseFlagSuspend(flag, () -> flag(flag, true));
        }

        @Override
        protected int[] createSection() {
            return new int[1];
        }

        @Override
        protected int[] readSection(CountingDataInputStream input) {
            return new int[1];
        }

        @Override
        protected void writeSection(int[] section, DataOutputStream output) {
        }

        @Override
        protected void trimSection(int[] section) {
        }

        @Override
        protected boolean isSectionEmpty(int[] section) {
            return true;
        }
    }
}
