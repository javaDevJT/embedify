package tech.javadevjt.embedify.calendar;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import tech.javadevjt.embedify.net.SafeFetcher;
import tech.javadevjt.embedify.telemetry.TelemetryCapture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarFeedServiceTelemetryTest {
    @Test
    void tracesCalendarWorkAndRecordsBoundedCacheAndResultMetrics() throws IOException {
        try (TelemetryCapture capture = new TelemetryCapture()) {
            String source = readFixture();
            String url = "https://calendar.example.test/feed.ics?token=fixture-secret";
            SafeFetcher fetcher = mock(SafeFetcher.class);
            when(fetcher.fetch(url, 1_048_576))
                    .thenReturn(new SafeFetcher.FetchResult(source, "text/calendar", url));
            CalendarFeedService service = new CalendarFeedService(fetcher, capture.telemetry);
            Span parent = capture.openTelemetry.getTracer("telemetry-test")
                    .spanBuilder("http.server.request")
                    .startSpan();
            EventsResponse first;
            EventsResponse second;
            try (Scope ignored = parent.makeCurrent()) {
                first = service.events(List.of(url), LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-10-13"), "America/Detroit");
                second = service.events(List.of(url), LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-10-13"), "America/Detroit");
            } finally {
                parent.end();
            }

            assertTrue(first.events().size() > 0);
            assertEquals(first.events().size(), second.events().size());
            verify(fetcher, org.mockito.Mockito.times(2)).fetch(url, 1_048_576);

            List<SpanData> spans = capture.spans.getFinishedSpanItems();
            SpanData firstEvents = spans.stream().filter(span -> span.getName().equals("embedify.calendar.events"))
                    .findFirst().orElseThrow();
            SpanData parse = spans.stream().filter(span -> span.getName().equals("embedify.calendar.feed.parse"))
                    .findFirst().orElseThrow();
            assertEquals(parent.getSpanContext().getTraceId(), firstEvents.getTraceId());
            assertEquals(parent.getSpanContext().getSpanId(), firstEvents.getParentSpanId());
            assertEquals(firstEvents.getSpanId(), parse.getParentSpanId());
            assertTrue(parse.getAttributes().get(AttributeKey.longKey("embedify.event.count")) > 0);
            assertFalse(telemetryText(spans).contains("calendar.example.test"));
            assertFalse(telemetryText(spans).contains("fixture-secret"));

            Map<String, Long> callsByOperation = new HashMap<>();
            metric(capture, "embedify.operation.calls").getLongSumData().getPoints().forEach(point ->
                    callsByOperation.put(point.getAttributes().get(AttributeKey.stringKey("embedify.operation")),
                            point.getValue()));
            assertEquals(2L, callsByOperation.get("calendar.events"));
            assertEquals(1L, callsByOperation.get("calendar.feed.parse"));

            Map<String, Long> cacheResults = new HashMap<>();
            metric(capture, "embedify.cache.requests").getLongSumData().getPoints().forEach(point -> {
                if ("parsed_feed".equals(point.getAttributes().get(AttributeKey.stringKey("embedify.cache")))) {
                    cacheResults.put(point.getAttributes().get(AttributeKey.stringKey("embedify.cache.result")),
                            point.getValue());
                }
            });
            assertEquals(1L, cacheResults.get("miss"));
            assertEquals(1L, cacheResults.get("hit"));

            var requestedFeeds = metric(capture, "embedify.calendar.request.feeds")
                    .getHistogramData().getPoints().iterator().next();
            assertEquals(2, requestedFeeds.getCount());
            assertEquals(2.0, requestedFeeds.getSum(), 0.0);
            var returnedEvents = metric(capture, "embedify.calendar.response.events")
                    .getHistogramData().getPoints().iterator().next();
            assertEquals(2, returnedEvents.getCount());
            assertEquals((double) first.events().size() * 2, returnedEvents.getSum(), 0.0);
        }
    }

    private static String readFixture() throws IOException {
        try (InputStream input = CalendarFeedServiceTelemetryTest.class
                .getResourceAsStream("/calendarlabs-us-holidays.ics")) {
            if (input == null) throw new IOException("Calendar test fixture is missing.");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static MetricData metric(TelemetryCapture capture, String name) {
        return capture.metrics.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private static String telemetryText(List<SpanData> spans) {
        return spans.stream().map(span -> span.getAttributes().asMap().toString()).toList().toString();
    }
}
