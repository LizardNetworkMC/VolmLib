package art.arcane.volmlib.nativelib.terrain;

import org.junit.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ServerShutdownBoundaryTest {
    @Test
    public void reachedBoundaryDoesNotWait() {
        assertTrue(new ServerShutdownBoundary(() -> true, Thread.currentThread())
                .await(0L, TimeUnit.NANOSECONDS));
    }

    @Test
    public void serverThreadCannotWaitForItself() {
        assertFalse(new ServerShutdownBoundary(() -> false, Thread.currentThread())
                .await(1L, TimeUnit.DAYS));
    }

    @Test(timeout = 5000L)
    public void terminatedServerThreadStillWaitsForBoundary() throws Exception {
        Thread serverThread = new Thread(() -> { }, "terminated-server-test");
        serverThread.start();
        serverThread.join();
        assertDelayedBoundary(serverThread, null);
    }

    @Test(timeout = 5000L)
    public void serverThreadTerminationDoesNotCompleteBoundary() throws Exception {
        CountDownLatch releaseServer = new CountDownLatch(1);
        Thread serverThread = new Thread(() -> await(releaseServer), "terminating-server-test");
        serverThread.start();
        try {
            assertDelayedBoundary(serverThread, releaseServer);
        } finally {
            releaseServer.countDown();
            serverThread.join();
        }
    }

    @Test(timeout = 5000L)
    public void deadServerWaitIsBoundedWithoutBusyPolling() {
        AtomicInteger reads = new AtomicInteger();
        ServerShutdownBoundary boundary = new ServerShutdownBoundary(() -> {
            reads.incrementAndGet();
            return false;
        }, new Thread());

        assertFalse(boundary.await(50L, TimeUnit.MILLISECONDS));
        assertTrue("Dead server thread must not cause a polling loop", reads.get() < 20);
    }

    @Test
    public void expiredTimeoutDoesNotWaitForDeadServer() {
        assertFalse(new ServerShutdownBoundary(() -> false, new Thread())
                .await(0L, TimeUnit.NANOSECONDS));
        assertFalse(new ServerShutdownBoundary(() -> false, new Thread())
                .await(-1L, TimeUnit.SECONDS));
    }

    @Test(timeout = 5000L)
    public void interruptSurvivesTimeoutAfterServerTerminates() {
        try {
            Thread.currentThread().interrupt();
            assertFalse(new ServerShutdownBoundary(() -> false, new Thread())
                    .await(20L, TimeUnit.MILLISECONDS));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test(timeout = 5000L)
    public void interruptSurvivesBoundaryFailure() {
        AtomicInteger reads = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("Boundary unavailable");
        ServerShutdownBoundary boundary = new ServerShutdownBoundary(() -> {
            if (reads.incrementAndGet() == 4) {
                throw failure;
            }
            return false;
        }, new Thread());
        try {
            Thread.currentThread().interrupt();
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> boundary.await(1L, TimeUnit.SECONDS)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test(timeout = 5000L)
    public void interruptDoesNotPermitCleanupBeforeBoundary() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        AtomicBoolean result = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        ServerShutdownBoundary boundary = new ServerShutdownBoundary(() -> {
            if (Thread.currentThread().getName().equals("interrupted-boundary-test")) {
                observed.countDown();
            }
            return reached.get();
        }, new Thread());
        Thread waiter = new Thread(() -> {
            result.set(boundary.await(3L, TimeUnit.SECONDS));
            interrupted.set(Thread.currentThread().isInterrupted());
            finished.countDown();
        }, "interrupted-boundary-test");
        waiter.start();
        try {
            assertTrue(observed.await(1L, TimeUnit.SECONDS));
            waiter.interrupt();
            assertFalse(finished.await(50L, TimeUnit.MILLISECONDS));
            reached.set(true);
            assertTrue(finished.await(2L, TimeUnit.SECONDS));
            assertTrue(result.get());
            assertTrue(interrupted.get());
        } finally {
            reached.set(true);
            waiter.interrupt();
            waiter.join();
        }
    }

    @Test
    public void preparationLinksSupplierBeforeShutdown() {
        AtomicInteger reads = new AtomicInteger();
        ServerShutdownBoundary boundary = new ServerShutdownBoundary(
                () -> reads.incrementAndGet() > 1, Thread.currentThread());
        assertEquals(1, reads.get());
        assertTrue(boundary.await(0L, TimeUnit.NANOSECONDS));
    }

    @Test
    public void preparedBoundaryRemainsUsableAfterClassLoaderCloses() throws Exception {
        URL classes = ServerShutdownBoundary.class.getProtectionDomain().getCodeSource().getLocation();
        AtomicBoolean reached = new AtomicBoolean();
        BooleanSupplier supplier = reached::get;
        Object boundary;
        Method awaitMethod;
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes}, ClassLoader.getPlatformClassLoader())) {
            Class<?> boundaryType = Class.forName(ServerShutdownBoundary.class.getName(), true, loader);
            boundary = boundaryType.getConstructor(BooleanSupplier.class, Thread.class)
                    .newInstance(supplier, Thread.currentThread());
            awaitMethod = boundaryType.getMethod("await", long.class, TimeUnit.class);
        }
        reached.set(true);
        assertEquals(Boolean.TRUE, awaitMethod.invoke(boundary, 0L, TimeUnit.NANOSECONDS));
    }

    private static void assertDelayedBoundary(Thread serverThread, CountDownLatch releaseServer) throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        ServerShutdownBoundary boundary = new ServerShutdownBoundary(() -> {
            if (Thread.currentThread().getName().equals("delayed-boundary-test")) {
                observed.countDown();
            }
            return reached.get();
        }, serverThread);
        Thread waiter = new Thread(() -> {
            result.set(boundary.await(3L, TimeUnit.SECONDS));
            finished.countDown();
        }, "delayed-boundary-test");
        waiter.start();
        try {
            assertTrue(observed.await(1L, TimeUnit.SECONDS));
            if (releaseServer != null) {
                releaseServer.countDown();
                serverThread.join();
            }
            assertFalse(finished.await(50L, TimeUnit.MILLISECONDS));
            reached.set(true);
            assertTrue(finished.await(2L, TimeUnit.SECONDS));
            assertTrue(result.get());
        } finally {
            reached.set(true);
            waiter.interrupt();
            waiter.join();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
