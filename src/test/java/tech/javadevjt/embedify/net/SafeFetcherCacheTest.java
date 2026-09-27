package tech.javadevjt.embedify.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeFetcherCacheTest {
    @Test
    void coalescesConcurrentMissesAndExpiresSuccessfulResults() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SafeFetcher fetcher = fetcher((uri, maxBytes, strict) -> {
            calls.incrementAndGet();
            Thread.sleep(60);
            return new SafeFetcher.FetchResult("calendar", "text/calendar", uri.toString());
        }, Duration.ofMillis(100), Duration.ofMillis(30));

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<SafeFetcher.FetchResult>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fetcher.fetch("https://calendar.example/feed.ics", 1024);
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            for (Future<SafeFetcher.FetchResult> future : futures) {
                assertEquals("calendar", future.get(2, TimeUnit.SECONDS).body());
            }
            assertEquals(1, calls.get());

            fetcher.fetch("https://calendar.example/feed.ics", 1024);
            assertEquals(1, calls.get());
            Thread.sleep(140);
            fetcher.fetch("https://calendar.example/feed.ics", 1024);
            assertEquals(2, calls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void brieflyCachesFailuresToDampenRepeatedBadPulls() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SafeFetcher fetcher = fetcher((uri, maxBytes, strict) -> {
            calls.incrementAndGet();
            throw new SafeFetchException(SafeFetchException.Kind.UPSTREAM);
        }, Duration.ofSeconds(1), Duration.ofMillis(50));

        assertThrows(SafeFetchException.class, () -> fetcher.fetch("https://calendar.example/feed", 1024));
        assertThrows(SafeFetchException.class, () -> fetcher.fetch("https://calendar.example/feed", 1024));
        assertEquals(1, calls.get());
        Thread.sleep(80);
        assertThrows(SafeFetchException.class, () -> fetcher.fetch("https://calendar.example/feed", 1024));
        assertEquals(2, calls.get());
    }

    @Test
    void strictAndPermissiveFetchesUseDifferentCacheEntries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SafeFetcher fetcher = fetcher((uri, maxBytes, strict) -> {
            calls.incrementAndGet();
            return new SafeFetcher.FetchResult(Boolean.toString(strict), "text/css", uri.toString());
        }, Duration.ofSeconds(1), Duration.ofMillis(50));

        assertEquals("false", fetcher.fetch("https://calendar.example/theme.css", 1024).body());
        assertEquals("true", fetcher.fetchSameOrigin("https://calendar.example/theme.css", 1024).body());
        assertEquals(2, calls.get());
    }

    private static SafeFetcher fetcher(SafeFetcher.RemoteFetch operation,
                                       Duration successTtl, Duration failureTtl) throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        PublicHttpsUrlPolicy policy = new PublicHttpsUrlPolicy(host -> new InetAddress[]{publicAddress});
        return new SafeFetcher(4, policy, operation, successTtl, failureTtl);
    }
}
