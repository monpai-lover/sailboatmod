package com.monpai.sailboatmod.market.web.map;

import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapStateWriterTest {
    @TempDir Path tempDir;

    @Test
    void coalescesLatestPerDestinationWithoutBlockingRequestsOrGrowingTheQueue() throws Exception {
        MarketWebMapStateWriter writer = new MarketWebMapStateWriter(2, 10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        Path file = tempDir.resolve("progress.json");
        try {
            assertTrue(writer.request(file, () -> {
                started.countDown();
                await(release);
                writes.incrementAndGet();
                MarketWebMapStateWriter.writeJson(file, new JsonPrimitive(-1));
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                for (int value = 0; value < 1000; value++) {
                    int latest = value;
                    assertTrue(writer.request(file, () -> {
                        writes.incrementAndGet();
                        MarketWebMapStateWriter.writeJson(file, new JsonPrimitive(latest));
                    }));
                }
                Path other = tempDir.resolve("dirty.json");
                assertTrue(writer.request(other, () -> MarketWebMapStateWriter.writeJson(other, new JsonPrimitive(7))));
                assertFalse(writer.request(tempDir.resolve("overflow.json"), () -> fail("overflow ran")));
                assertEquals(2, writer.pendingDestinations());
            });
            assertFalse(writer.flush(10));
            release.countDown();
            assertTrue(writer.flush(2000));
            assertEquals("999", Files.readString(file));
            assertEquals(2, writes.get());
            assertEquals(0, writer.pendingDestinations());
        } finally {
            release.countDown();
            assertTrue(writer.shutdown(2000));
        }
    }

    @Test
    void failedOldRequestCannotOverwriteNewPendingState() throws Exception {
        MarketWebMapStateWriter writer = new MarketWebMapStateWriter(2, 10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger oldAttempts = new AtomicInteger();
        Path file = tempDir.resolve("progress.json");
        try {
            writer.request(file, () -> {
                oldAttempts.incrementAndGet();
                started.countDown();
                await(release);
                throw new IOException("temporary old-state failure");
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(writer.request(file, () -> MarketWebMapStateWriter.writeJson(file, new JsonPrimitive("latest"))));
            release.countDown();
            assertTrue(writer.flush(2000));
            assertEquals("\"latest\"", Files.readString(file));
            assertEquals(1, oldAttempts.get());
        } finally {
            release.countDown();
            assertTrue(writer.shutdown(2000));
        }
    }

    @Test
    void failedLatestStateRetriesAndShutdownDrainsIt() throws Exception {
        MarketWebMapStateWriter writer = new MarketWebMapStateWriter(2, 10);
        AtomicInteger attempts = new AtomicInteger();
        Path file = tempDir.resolve("progress.json");
        assertTrue(writer.request(file, () -> {
            if (attempts.incrementAndGet() < 3) throw new IOException("temporary failure");
            MarketWebMapStateWriter.writeJson(file, new JsonPrimitive(42));
        }));
        assertTrue(writer.shutdown(2000));
        assertEquals(3, attempts.get());
        assertEquals("42", Files.readString(file));
        assertFalse(writer.request(file, () -> fail("request after shutdown ran")));
    }

    @Test
    void deletionRunsAfterInFlightSaveAndCannotBeUndoneByIt() throws Exception {
        MarketWebMapStateWriter writer = new MarketWebMapStateWriter(2, 10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Path file = tempDir.resolve("progress.json");
        try {
            writer.request(file, () -> {
                started.countDown();
                await(release);
                MarketWebMapStateWriter.writeJson(file, new JsonPrimitive("cancelled job"));
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(writer.delete(file));
            release.countDown();
            assertTrue(writer.flush(2000));
            assertFalse(Files.exists(file));
        } finally {
            release.countDown();
            assertTrue(writer.shutdown(2000));
        }
    }

    @Test
    void shutdownIsBoundedAndReportsUnwrittenPendingState() throws Exception {
        MarketWebMapStateWriter writer = new MarketWebMapStateWriter(2, 10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger pendingWrites = new AtomicInteger();
        Path file = tempDir.resolve("progress.json");
        try {
            writer.request(file, () -> {
                started.countDown();
                await(release);
                try {
                    MarketWebMapStateWriter.writeJson(file, new JsonPrimitive(1));
                } finally {
                    finished.countDown();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(writer.request(file, pendingWrites::incrementAndGet));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertFalse(writer.shutdown(20)));
            assertFalse(writer.request(file, () -> fail("stopped request ran")));
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertFalse(writer.shutdown(2000));
            assertEquals(0, pendingWrites.get());
            assertEquals(1, writer.pendingDestinations());
        } finally {
            release.countDown();
        }
    }

    @Test
    void unexpectedWorkerInterruptionRejectsFurtherRequestsInsteadOfSilentlyLosingThem() {
        MarketWebMapStateWriter writer = new MarketWebMapStateWriter(2, 10);
        Path file = tempDir.resolve("interrupted.json");
        assertTrue(writer.request(file, () -> {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted IO");
        }));
        assertFalse(writer.flush(2000));
        assertFalse(writer.request(file, () -> fail("a terminated worker accepted more work")));
        assertFalse(writer.shutdown(2000));
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("timed out waiting for test latch");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }
}
