package art.arcane.volmlib.util.parallel;

import art.arcane.volmlib.util.VolmLog;
import art.arcane.volmlib.util.collection.KList;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SuppressWarnings("ALL")
public class BurstExecutorSupport {
    private final Supplier<ExecutorService> executorSource;
    private final Consumer<Throwable> errorHandler;
    @Getter
    private final KList<Future<?>> futures;
    private final KList<ClaimedTask> claimedTasks;
    @Setter
    private boolean multicore = true;

    public BurstExecutorSupport(ExecutorService executor, int burstSizeEstimate) {
        this(executor, burstSizeEstimate,
                failure -> VolmLog.severe("BurstExecutor", "Burst task failed", failure));
    }

    public BurstExecutorSupport(ExecutorService executor, int burstSizeEstimate, Consumer<Throwable> errorHandler) {
        this(() -> executor, burstSizeEstimate, errorHandler);
    }

    /**
     * Resolves the executor per submission instead of pinning it at construction, so a burst
     * obtained before its owning pool closed degrades to the pool's post-close fallback
     * (inline execution) instead of throwing RejectedExecutionException at the caller.
     */
    public BurstExecutorSupport(Supplier<ExecutorService> executorSource, int burstSizeEstimate, Consumer<Throwable> errorHandler) {
        this.executorSource = executorSource;
        this.errorHandler = errorHandler == null
                ? failure -> VolmLog.severe("BurstExecutor", "Burst task failed", failure)
                : errorHandler;
        futures = new KList<Future<?>>(burstSizeEstimate);
        claimedTasks = new KList<ClaimedTask>(burstSizeEstimate);
    }

    @SuppressWarnings("UnusedReturnValue")
    public Future<?> queue(Runnable r) {
        ExecutorService executor = executorSource.get();
        if (shouldRunInline(executor)) {
            r.run();
            return CompletableFuture.completedFuture(null);
        }

        synchronized (futures) {
            ClaimedTask claimed = executor instanceof ForkJoinPool ? new ClaimedTask(r) : null;
            Future<?> c;
            try {
                c = executor.submit(claimed == null ? r : claimed);
            } catch (RejectedExecutionException rejected) {
                errorHandler.accept(rejected);
                r.run();
                return CompletableFuture.completedFuture(null);
            }
            futures.add(c);
            claimedTasks.add(claimed);
            return c;
        }
    }

    public BurstExecutorSupport queue(List<Runnable> r) {
        if (shouldRunInline(executorSource.get())) {
            for (Runnable i : new KList<>(r)) {
                i.run();
            }

            return this;
        }

        synchronized (futures) {
            for (Runnable i : new KList<>(r)) {
                queue(i);
            }
        }

        return this;
    }

    public BurstExecutorSupport queue(Runnable[] r) {
        if (shouldRunInline(executorSource.get())) {
            for (Runnable i : new KList<>(r)) {
                i.run();
            }

            return this;
        }

        synchronized (futures) {
            for (Runnable i : r) {
                queue(i);
            }
        }

        return this;
    }

    public void complete() {
        if (shouldRunInline(executorSource.get())) {
            return;
        }

        List<Future<?>> queued;
        List<ClaimedTask> claimable;
        synchronized (futures) {
            if (futures.isEmpty()) {
                return;
            }
            queued = new KList<>(futures);
            claimable = new KList<>(claimedTasks);
            futures.clear();
            claimedTasks.clear();
        }

        runUnstartedTasks(queued, claimable);
        boolean interrupted = false;
        try {
            for (Future<?> future : queued) {
                if (future == null) {
                    continue;
                }
                while (true) {
                    try {
                        future.get();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (CancellationException e) {
                        errorHandler.accept(e);
                        break;
                    } catch (ExecutionException e) {
                        errorHandler.accept(e);
                        break;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runUnstartedTasks(List<Future<?>> queued, List<ClaimedTask> claimable) {
        boolean interrupted = Thread.interrupted();
        try {
            for (int index = claimable.size() - 1; index >= 0; index--) {
                ClaimedTask task = claimable.get(index);
                if (task == null || !task.claim()) {
                    continue;
                }
                queued.set(index, null);
                try {
                    task.work.run();
                } catch (Throwable failure) {
                    errorHandler.accept(new ExecutionException(failure));
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean shouldRunInline(ExecutorService executor) {
        if (!multicore) {
            return true;
        }

        if (!(executor instanceof ForkJoinPool)) {
            return false;
        }
        ForkJoinPool pool = (ForkJoinPool) executor;

        Thread thread = Thread.currentThread();
        if (!(thread instanceof ForkJoinWorkerThread)) {
            return false;
        }
        ForkJoinWorkerThread worker = (ForkJoinWorkerThread) thread;

        return worker.getPool() == pool;
    }

    private static final class ClaimedTask implements Runnable {
        private final Runnable work;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private ClaimedTask(Runnable work) {
            this.work = work;
        }

        private boolean claim() {
            return !claimed.get() && claimed.compareAndSet(false, true);
        }

        @Override
        public void run() {
            if (claim()) {
                work.run();
            }
        }
    }
}
