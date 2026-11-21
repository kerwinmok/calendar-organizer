package com.calendarorganizer.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
}
