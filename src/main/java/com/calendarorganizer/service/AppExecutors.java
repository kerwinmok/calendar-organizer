package com.calendarorganizer.service;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AppExecutors {

    private interface LowPriorityRenderTask extends Runnable {
    }

    private static final class PriorityRenderDeque extends LinkedBlockingDeque<Runnable> {
        private static final int MAX_PREFETCH_QUEUE_SIZE = 4;

        @Override
        public boolean offer(Runnable runnable) {
            if (runnable instanceof LowPriorityRenderTask) {
                if (size() >= MAX_PREFETCH_QUEUE_SIZE) {
                    return false;
                }
                return offerLast(runnable);
            }
            return offerFirst(runnable);
        }
    }

    private static final Object LOCK = new Object();
    private static ThreadPoolExecutor ioPool;
    private static ThreadPoolExecutor renderPool;

    private AppExecutors() {
    }

    public static Executor ioExecutor() {
        synchronized (LOCK) {
            if (ioPool == null || ioPool.isShutdown()) {
                ioPool = createBoundedPool(3, 30L, "calendar-io-");
            }
            return ioPool;
        }
    }

    public static Executor renderExecutor() {
        synchronized (LOCK) {
            if (renderPool == null || renderPool.isShutdown()) {
                renderPool = createRenderPool();
            }
            return renderPool;
        }
    }

    public static void submitRenderPrefetch(Runnable task) {
        if (task == null) {
            return;
        }
        renderExecutor().execute((LowPriorityRenderTask) task::run);
    }

    public static void shutdown() {
        synchronized (LOCK) {
            if (ioPool != null) {
                ioPool.shutdownNow();
                ioPool = null;
            }
            if (renderPool != null) {
                renderPool.shutdownNow();
                renderPool = null;
            }
        }
    }

    private static ThreadPoolExecutor createRenderPool() {
        ThreadFactory factory = createNamedDaemonFactory("calendar-render-");
        BlockingDeque<Runnable> queue = new PriorityRenderDeque();
        RejectedExecutionHandler rejectedHandler = (runnable, executor) -> {
            if (executor.isShutdown() || runnable instanceof LowPriorityRenderTask) {
                return;
            }
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1,
                1,
                20L,
                TimeUnit.SECONDS,
                queue,
                factory,
                rejectedHandler
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static ThreadPoolExecutor createBoundedPool(int maxThreads, long keepAliveSeconds, String prefix) {
        ThreadFactory factory = createNamedDaemonFactory(prefix);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                maxThreads,
                maxThreads,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                factory
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static ThreadFactory createNamedDaemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
