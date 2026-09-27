package tech.javadevjt.embedify.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeFetcherCacheTest {
    @Test
    void coalescesConcurrentMissesAndExpiresSuccessfulResults() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SafeFetcher fetcher = fetcher((uri, maxBytes, strict, prefix) -> {
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
        SafeFetcher fetcher = fetcher((uri, maxBytes, strict, prefix) -> {
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
    void strictAndPrefixFetchesUseDifferentCacheEntries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SafeFetcher fetcher = fetcher((uri, maxBytes, strict, prefix) -> {
            calls.incrementAndGet();
            return new SafeFetcher.FetchResult(strict + ":" + prefix, "text/css", uri.toString());
        }, Duration.ofSeconds(1), Duration.ofMillis(50));

        String url = "https://calendar.example/theme.css";
        assertEquals("false:false", fetcher.fetch(url, 1024).body());
        assertEquals("true:false", fetcher.fetchSameOrigin(url, 1024).body());
        assertEquals("false:true", fetcher.fetchStylePrefix(url, 1024).body());
        assertEquals("false:false", fetcher.fetch(url, 1024).body());
        assertEquals(3, calls.get());
    }

    @Test
    void prefixReaderStopsAtLimitAndCancelsBeforeReturning() throws Exception {
        byte[] source = "prefix-tail".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(source);
        AtomicBoolean cancelled = new AtomicBoolean();

        byte[] body = SafeFetcher.readBoundedBody(input, 6, Long.MAX_VALUE, true, () -> cancelled.set(true));

        assertArrayEquals("prefix".getBytes(StandardCharsets.UTF_8), body);
        assertEquals(source.length - body.length, input.available());
        assertTrue(cancelled.get());
    }

    @Test
    void strictReaderRejectsOversizeAndPrefixAllowsLargerDeclaredLength() {
        byte[] source = "12345".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean closedAfterCancellation = new AtomicBoolean();
        ByteArrayInputStream input = new ByteArrayInputStream(source) {
            @Override
            public void close() {
                closedAfterCancellation.set(cancelled.get());
            }
        };
        assertThrows(SafeFetchException.class, () -> {
            try (input) {
                SafeFetcher.readBoundedBody(input, 4, Long.MAX_VALUE, false, () -> cancelled.set(true));
            }
        });
        assertTrue(cancelled.get());
        assertTrue(closedAfterCancellation.get());
        assertThrows(SafeFetchException.class, () -> SafeFetcher.validateDeclaredLength(5, 4, false));
        SafeFetcher.validateDeclaredLength(5, 4, true);
    }

    @Test
    void prefixModeAllowsOnlyExpectedTextContentTypes() {
        assertTrue(SafeFetcher.isAllowedStylePrefixContentType("text/html; charset=UTF-8"));
        assertTrue(SafeFetcher.isAllowedStylePrefixContentType("application/xhtml+xml"));
        assertTrue(SafeFetcher.isAllowedStylePrefixContentType("text/css"));
        assertTrue(SafeFetcher.isAllowedStylePrefixContentType("text/plain"));
        assertFalse(SafeFetcher.isAllowedStylePrefixContentType("application/octet-stream"));
        assertFalse(SafeFetcher.isAllowedStylePrefixContentType("image/png"));
    }

    private static SafeFetcher fetcher(SafeFetcher.RemoteFetch operation,
                                       Duration successTtl, Duration failureTtl) throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        PublicHttpsUrlPolicy policy = new PublicHttpsUrlPolicy(host -> new InetAddress[]{publicAddress});
        return new SafeFetcher(4, policy, operation, successTtl, failureTtl);
    }
}
