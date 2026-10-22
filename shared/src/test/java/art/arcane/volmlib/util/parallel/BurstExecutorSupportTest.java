package art.arcane.volmlib.util.parallel;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BurstExecutorSupportTest {
    @Test
    public void completeRunsEveryQueuedTaskBeforeReturning() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger completed = new AtomicInteger();
        try {
            BurstExecutorSupport burst = new BurstExecutorSupport(executor, 2);
            burst.queue(completed::incrementAndGet);
            burst.queue(completed::incrementAndGet);

            burst.complete();

            assertEquals(2, completed.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void completeWaitsThroughInterruptionAndRestoresInterruptStatus() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch allowTaskCompletion = new CountDownLatch(1);
        AtomicBoolean taskCompleted = new AtomicBoolean();
        AtomicBoolean completeReturned = new AtomicBoolean();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try {
            BurstExecutorSupport burst = new BurstExecutorSupport(executor, 1, errors::add);
            burst.queue(() -> {
                taskStarted.countDown();
                awaitUninterruptibly(allowTaskCompletion);
                taskCompleted.set(true);
            });
            assertTrue(taskStarted.await(1L, TimeUnit.SECONDS));

            Thread completionThread = new Thread(() -> {
                Thread.currentThread().interrupt();
                burst.complete();
                interruptRestored.set(Thread.currentThread().isInterrupted());
                completeReturned.set(true);
            }, "burst-complete-test");
            completionThread.start();
            completionThread.join(100L);

            assertTrue(completionThread.isAlive());
            assertFalse(completeReturned.get());
            allowTaskCompletion.countDown();
            completionThread.join(1_000L);

            assertFalse(completionThread.isAlive());
            assertTrue(taskCompleted.get());
            assertTrue(completeReturned.get());
            assertTrue(interruptRestored.get());
            assertTrue(errors.isEmpty());
        } finally {
            allowTaskCompletion.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void completeReportsTaskFailure() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        IllegalStateException failure = new IllegalStateException("failure");
        try {
            BurstExecutorSupport burst = new BurstExecutorSupport(executor, 1, errors::add);
            burst.queue(() -> {
                throw failure;
            });

            burst.complete();

            assertEquals(1, errors.size());
            assertTrue(errors.get(0) instanceof ExecutionException);
            assertSame(failure, errors.get(0).getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void externalCompleteRunsQueuedTasksThePoolHasNotStarted() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(1);
        CountDownLatch workerBusy = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        AtomicInteger completed = new AtomicInteger();
        try {
            pool.execute(() -> {
                workerBusy.countDown();
                awaitUninterruptibly(releaseWorker);
            });
            assertTrue(workerBusy.await(5L, TimeUnit.SECONDS));
            Thread caller = Thread.currentThread();
            BurstExecutorSupport burst = new BurstExecutorSupport(pool, 3);
            for (int index = 0; index < 3; index++) {
                burst.queue(() -> {
                    threads.add(Thread.currentThread());
                    completed.incrementAndGet();
                });
            }

            burst.complete();

            assertEquals(3, completed.get());
            assertEquals(Set.of(caller), threads);
            assertEquals(1L, releaseWorker.getCount());
        } finally {
            releaseWorker.countDown();
            pool.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void everyTaskRunsExactlyOnceWhenTheCallerAndThePoolShareTheBurst() {
        ForkJoinPool pool = new ForkJoinPool(3);
        int taskCount = 2000;
        AtomicIntegerArray runs = new AtomicIntegerArray(taskCount);
        try {
            for (int round = 0; round < 20; round++) {
                BurstExecutorSupport burst = new BurstExecutorSupport(pool, taskCount / 20);
                for (int index = round * (taskCount / 20); index < (round + 1) * (taskCount / 20); index++) {
                    int task = index;
                    burst.queue(() -> runs.incrementAndGet(task));
                }
                burst.complete();
            }
        } finally {
            pool.shutdownNow();
        }
        for (int index = 0; index < taskCount; index++) {
            assertEquals(1, runs.get(index));
        }
    }

    @Test(timeout = 10000)
    public void callerRunTaskFailureIsReportedLikeAPoolFailure() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(1);
        CountDownLatch workerBusy = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        IllegalStateException failure = new IllegalStateException("caller-run failure");
        try {
            pool.execute(() -> {
                workerBusy.countDown();
                awaitUninterruptibly(releaseWorker);
            });
            assertTrue(workerBusy.await(5L, TimeUnit.SECONDS));
            BurstExecutorSupport burst = new BurstExecutorSupport(pool, 1, errors::add);
            burst.queue(() -> {
                throw failure;
            });

            burst.complete();

            assertEquals(1, errors.size());
            assertTrue(errors.get(0) instanceof ExecutionException);
            assertSame(failure, errors.get(0).getCause());
        } finally {
            releaseWorker.countDown();
            pool.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void callerRunTasksDoNotSeeTheCallersInterruptAndItIsRestored() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(1);
        CountDownLatch workerBusy = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean taskSawInterrupt = new AtomicBoolean(true);
        try {
            pool.execute(() -> {
                workerBusy.countDown();
                awaitUninterruptibly(releaseWorker);
            });
            assertTrue(workerBusy.await(5L, TimeUnit.SECONDS));
            CompletableFuture<Boolean> restored = new CompletableFuture<>();
            Thread completion = new Thread(() -> {
                BurstExecutorSupport burst = new BurstExecutorSupport(pool, 1);
                burst.queue(() -> taskSawInterrupt.set(Thread.currentThread().isInterrupted()));
                Thread.currentThread().interrupt();
                burst.complete();
                restored.complete(Thread.currentThread().isInterrupted());
            }, "burst-help-interrupt-test");
            completion.start();

            assertTrue(restored.get(5L, TimeUnit.SECONDS));
            assertFalse(taskSawInterrupt.get());
        } finally {
            releaseWorker.countDown();
            pool.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void plainExecutorTasksStayOnTheExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        try {
            BurstExecutorSupport burst = new BurstExecutorSupport(executor, 3);
            burst.queue(() -> awaitUninterruptibly(release));
            for (int index = 0; index < 3; index++) {
                burst.queue(() -> threads.add(Thread.currentThread()));
            }
            Thread caller = Thread.currentThread();
            CompletableFuture.runAsync(release::countDown, CompletableFuture.delayedExecutor(100L, TimeUnit.MILLISECONDS));

            burst.complete();

            assertEquals(1, threads.size());
            assertFalse(threads.contains(caller));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
