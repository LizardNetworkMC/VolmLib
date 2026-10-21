package art.arcane.volmlib.util.mantle;

import art.arcane.volmlib.util.io.CountingDataInputStream;
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MantleChunkUseTest {
    @Test
    public void usesCountUntilEveryReleaseAndBlockTheUseLock() {
        TestChunk chunk = new TestChunk();
        chunk.use();
        chunk.use();

        assertTrue(chunk.inUse());
        assertFalse(chunk.tryLockUses());
        chunk.release();
        assertTrue(chunk.inUse());
        chunk.release();
        assertFalse(chunk.inUse());
        assertTrue(chunk.tryLockUses());
        assertTrue(chunk.inUse());
        chunk.unlockUses(false);
        assertFalse(chunk.inUse());
        chunk.use();
        chunk.release();
    }

    @Test(timeout = 10_000L)
    public void useWaitsForTheUseLockAndFailsOnceTheChunkIsSealed() throws Exception {
        TestChunk chunk = new TestChunk();
        assertTrue(chunk.tryLockUses());
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> user = executor.submit(() -> {
                started.countDown();
                chunk.use();
            });
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            Thread.sleep(50L);
            assertFalse(user.isDone());
            chunk.unlockUses(true);
            Throwable failure = assertThrows(Exception.class, () -> user.get(2L, TimeUnit.SECONDS)).getCause();
            assertTrue(failure instanceof MantleClosedException);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(chunk.isClosed());
        assertFalse(chunk.inUse());
        chunk.reopen();
        chunk.use();
        chunk.release();
    }

    @Test(timeout = 10_000L)
    public void useWaitingOnAnUnsealedLockProceeds() throws Exception {
        TestChunk chunk = new TestChunk();
        assertTrue(chunk.tryLockUses());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> user = executor.submit(() -> {
                chunk.use();
                chunk.release();
            });
            Thread.sleep(20L);
            chunk.unlockUses(false);
            user.get(2L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertFalse(chunk.inUse());
    }

    @Test(timeout = 10_000L)
    public void closeWaitsForUsersAndRejectsNewUses() throws Exception {
        TestChunk chunk = new TestChunk();
        chunk.use();
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> closer = executor.submit(() -> {
                started.countDown();
                chunk.close();
            });
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            Thread.sleep(50L);
            assertFalse(closer.isDone());
            assertThrows(MantleClosedException.class, chunk::use);
            assertThrows(IllegalStateException.class, chunk::reopen);
            chunk.release();
            closer.get(2L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertFalse(chunk.inUse());
        chunk.reopen();
        assertFalse(chunk.isClosed());
    }

    @Test(timeout = 30_000L)
    public void concurrentUsesAndSealAttemptsNeverLoseTheCount() throws Exception {
        int threads = 8;
        TestChunk chunk = new TestChunk();
        CyclicBarrier barrier = new CyclicBarrier(threads + 1);
        ExecutorService executor = Executors.newFixedThreadPool(threads + 1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                futures.add(executor.submit(() -> {
                    barrier.await(5L, TimeUnit.SECONDS);
                    for (int round = 0; round < 20_000; round++) {
                        chunk.use();
                        chunk.release();
                    }
                    return null;
                }));
            }
            futures.add(executor.submit(() -> {
                barrier.await(5L, TimeUnit.SECONDS);
                for (int round = 0; round < 20_000; round++) {
                    if (chunk.tryLockUses()) {
                        chunk.unlockUses(false);
                    }
                }
                return null;
            }));
            for (Future<?> future : futures) {
                future.get(20L, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertFalse(chunk.inUse());
        assertTrue(chunk.tryLockUses());
    }

    private static final class TestChunk extends MantleChunkSupport<int[]> {
        private TestChunk() {
            super(1, 0, 0);
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
