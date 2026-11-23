package com.calendarorganizer.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppExecutorsTest {

    @AfterEach
    void tearDown() {
        AppExecutors.shutdown();
    }

    @Test
    void runsTasksOnNamedDaemonPools() throws Exception {
        CompletableFuture<String> ioThreadName = new CompletableFuture<>();
        CompletableFuture<Boolean> ioDaemon = new CompletableFuture<>();
        AppExecutors.ioExecutor().execute(() -> {
            Thread current = Thread.currentThread();
            ioThreadName.complete(current.getName());
            ioDaemon.complete(current.isDaemon());
        });

        CompletableFuture<String> renderThreadName = new CompletableFuture<>();
        CompletableFuture<Boolean> renderDaemon = new CompletableFuture<>();
        AppExecutors.renderExecutor().execute(() -> {
            Thread current = Thread.currentThread();
            renderThreadName.complete(current.getName());
            renderDaemon.complete(current.isDaemon());
        });

        assertTrue(ioThreadName.get(5, TimeUnit.SECONDS).startsWith("calendar-io-"));
        assertTrue(ioDaemon.get(5, TimeUnit.SECONDS));
        assertTrue(renderThreadName.get(5, TimeUnit.SECONDS).startsWith("calendar-render-"));
        assertTrue(renderDaemon.get(5, TimeUnit.SECONDS));
    }

    @Test
    void runsVisibleRenderTasksLifoAheadOfLowPriorityPrefetch() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch allQueuedDone = new CountDownLatch(3);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        AppExecutors.renderExecutor().execute(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

        AppExecutors.submitRenderPrefetch(() -> {
            executionOrder.add("prefetch");
            allQueuedDone.countDown();
        });
        AppExecutors.renderExecutor().execute(() -> {
            executionOrder.add("visible-older");
            allQueuedDone.countDown();
        });
        AppExecutors.renderExecutor().execute(() -> {
            executionOrder.add("visible-newer");
            allQueuedDone.countDown();
        });

        releaseBlocker.countDown();
        assertTrue(allQueuedDone.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("visible-newer", "visible-older", "prefetch"), executionOrder);
    }
}
