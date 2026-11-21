package com.calendarorganizer.service;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AppExecutors {

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
                renderPool = createBoundedPool(2, 20L, "calendar-render-");
            }
            return renderPool;
        }
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

    private static ThreadPoolExecutor createBoundedPool(int maxThreads, long keepAliveSeconds, String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
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
}
