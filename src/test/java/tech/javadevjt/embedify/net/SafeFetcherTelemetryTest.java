package tech.javadevjt.embedify.net;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import tech.javadevjt.embedify.telemetry.TelemetryCapture;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeFetcherTelemetryTest {
    @Test
    void emitsBoundedNestedSpansAndCacheMetricsWithoutFeedUrls() throws Exception {
        try (TelemetryCapture capture = new TelemetryCapture()) {
            AtomicInteger remoteCalls = new AtomicInteger();
            PublicHttpsUrlPolicy policy = publicTestPolicy();
            SafeFetcher.RemoteFetch remoteFetch = (url, maxBytes, sameOriginOnly, prefixMode) -> {
                remoteCalls.incrementAndGet();
                return new SafeFetcher.FetchResult("fixture-calendar", "text/calendar", url.toASCIIString());
            };
            SafeFetcher fetcher = new SafeFetcher(2, policy, remoteFetch,
                    Duration.ofSeconds(60), Duration.ofSeconds(4), capture.telemetry);
            Span parent = capture.openTelemetry.getTracer("telemetry-test")
                    .spanBuilder("http.server.request")
                    .startSpan();
            String url = "https://calendar.example.test/feed.ics?token=fixture-secret";
            try (Scope ignored = parent.makeCurrent()) {
                assertEquals("fixture-calendar", fetcher.fetch(url, 1024).body());
                assertEquals("fixture-calendar", fetcher.fetch(url, 1024).body());
            } finally {
                parent.end();
                fetcher.stopDnsPool();
            }

            assertEquals(1, remoteCalls.get());
            SpanData fetch = findSpan(capture, "embedify.upstream.fetch");
            SpanData request = findSpan(capture, "embedify.upstream.request");
            assertEquals(parent.getSpanContext().getTraceId(), fetch.getTraceId());
            assertEquals(parent.getSpanContext().getSpanId(), fetch.getParentSpanId());
            assertEquals(fetch.getSpanId(), request.getParentSpanId());
            assertEquals("calendar_feed", fetch.getAttributes().get(
                    AttributeKey.stringKey("embedify.fetch.mode")));
            assertEquals("success", fetch.getAttributes().get(
                    AttributeKey.stringKey("embedify.outcome")));
            assertFalse(telemetryText(fetch).contains("calendar.example.test"));
            assertFalse(telemetryText(fetch).contains("fixture-secret"));

            Map<String, Long> cacheResults = new HashMap<>();
            var cacheMetric = capture.metrics.collectAllMetrics().stream()
                    .filter(metric -> metric.getName().equals("embedify.cache.requests"))
                    .findFirst().orElseThrow();
            cacheMetric.getLongSumData().getPoints().forEach(point ->
                    cacheResults.put(point.getAttributes().get(AttributeKey.stringKey("embedify.cache.result")),
                            point.getValue()));
            assertEquals(1L, cacheResults.get("miss"));
            assertEquals(1L, cacheResults.get("hit"));

            Map<String, Long> callsByOperation = new HashMap<>();
            var callsMetric = capture.metrics.collectAllMetrics().stream()
                    .filter(metric -> metric.getName().equals("embedify.operation.calls"))
                    .findFirst().orElseThrow();
            callsMetric.getLongSumData().getPoints().forEach(point ->
                    callsByOperation.put(point.getAttributes().get(AttributeKey.stringKey("embedify.operation")),
                            point.getValue()));
            assertEquals(2L, callsByOperation.get("upstream.fetch"));
            assertEquals(1L, callsByOperation.get("upstream.request"));

            var durationMetric = capture.metrics.collectAllMetrics().stream()
                    .filter(metric -> metric.getName().equals("embedify.operation.duration"))
                    .findFirst().orElseThrow();
            assertEquals(3, durationMetric.getHistogramData().getPoints().stream()
                    .mapToLong(point -> point.getCount()).sum());
        }
    }

    @Test
    void recordsSafeFailureOutcomeWithoutExceptionDetails() throws Exception {
        try (TelemetryCapture capture = new TelemetryCapture()) {
            SafeFetcher.RemoteFetch remoteFetch = (url, maxBytes, sameOriginOnly, prefixMode) -> {
                throw new SafeFetchException(SafeFetchException.Kind.UPSTREAM);
            };
            SafeFetcher fetcher = new SafeFetcher(1, publicTestPolicy(), remoteFetch,
                    Duration.ofSeconds(60), Duration.ofSeconds(4), capture.telemetry);
            try {
                assertThrows(SafeFetchException.class,
                        () -> fetcher.fetch("https://calendar.example.test/private?token=fixture-secret", 1024));
            } finally {
                fetcher.stopDnsPool();
            }

            SpanData fetch = findSpan(capture, "embedify.upstream.fetch");
            SpanData request = findSpan(capture, "embedify.upstream.request");
            assertEquals("upstream", fetch.getAttributes().get(AttributeKey.stringKey("embedify.outcome")));
            assertEquals("upstream", request.getAttributes().get(AttributeKey.stringKey("embedify.outcome")));
            assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, fetch.getStatus().getStatusCode());
            assertNotNull(capture.metrics.collectAllMetrics().stream()
                    .filter(metric -> metric.getName().equals("embedify.operation.calls"))
                    .findFirst().orElse(null));
            assertFalse(telemetryText(fetch).contains("fixture-secret"));
        }
    }

    private static PublicHttpsUrlPolicy publicTestPolicy() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        return new PublicHttpsUrlPolicy(host -> new InetAddress[]{publicAddress});
    }

    private static SpanData findSpan(TelemetryCapture capture, String name) {
        return capture.spans.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private static String telemetryText(SpanData span) {
        return span.getName() + span.getAttributes().asMap();
    }
}
