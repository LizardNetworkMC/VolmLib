package art.arcane.volmlib.nativelib.terrain;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class ServerShutdownBoundary {
    private static final long MAX_WAIT_SLICE_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private final BooleanSupplier boundaryReached;
    private final Thread serverThread;

    public ServerShutdownBoundary(BooleanSupplier boundaryReached, Thread serverThread) {
        this.boundaryReached = Objects.requireNonNull(boundaryReached, "Server shutdown boundary");
        this.serverThread = Objects.requireNonNull(serverThread, "Server thread");
        this.boundaryReached.getAsBoolean();
    }

    public boolean await(long timeout, TimeUnit unit) {
        TimeUnit activeUnit = Objects.requireNonNull(unit, "Server shutdown timeout unit");
        if (boundaryReached.getAsBoolean()) {
            return true;
        }
        if (serverThread == Thread.currentThread()) {
            return false;
        }

        long timeoutNanos = Math.max(0L, activeUnit.toNanos(timeout));
        long started = System.nanoTime();
        boolean interrupted = false;
        try {
            while (!boundaryReached.getAsBoolean()) {
                long remaining = timeoutNanos - (System.nanoTime() - started);
                if (remaining <= 0L) {
                    return boundaryReached.getAsBoolean();
                }

                long waitNanos = Math.min(MAX_WAIT_SLICE_NANOS, remaining);
                try {
                    if (serverThread.isAlive()) {
                        TimeUnit.NANOSECONDS.timedJoin(serverThread, waitNanos);
                    } else {
                        TimeUnit.NANOSECONDS.sleep(waitNanos);
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
