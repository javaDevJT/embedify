package tech.javadevjt.embedify.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Bounded, URL-free application telemetry backed by the configured OpenTelemetry provider. */
@Component
public final class EmbedifyTelemetry {
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("embedify.operation");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("embedify.outcome");
    private static final AttributeKey<String> CACHE = AttributeKey.stringKey("embedify.cache");
    private static final AttributeKey<String> CACHE_RESULT = AttributeKey.stringKey("embedify.cache.result");
    private static final AttributeKey<Boolean> TRUNCATED = AttributeKey.booleanKey("embedify.response.truncated");
    private static final Set<String> OUTCOMES = Set.of(
            "success", "invalid_input", "busy", "upstream", "failure", "incomplete");
    private static final Set<String> CACHES = Set.of("upstream", "parsed_feed");
    private static final Set<String> CACHE_RESULTS = Set.of("hit", "miss");

    private final Tracer tracer;
    private final LongCounter operationCalls;
    private final DoubleHistogram operationDuration;
    private final LongHistogram requestedFeeds;
    private final LongHistogram returnedEvents;
    private final LongCounter cacheRequests;

    @Autowired
    public EmbedifyTelemetry() {
        this(GlobalOpenTelemetry.get());
    }

    public EmbedifyTelemetry(OpenTelemetry openTelemetry) {
        var meter = openTelemetry.getMeter("tech.javadevjt.embedify");
        this.tracer = openTelemetry.getTracer("tech.javadevjt.embedify");
        this.operationCalls = meter.counterBuilder("embedify.operation.calls")
                .setDescription("Calendar and upstream operations completed by Embedify.")
                .setUnit("1")
                .build();
        this.operationDuration = meter.histogramBuilder("embedify.operation.duration")
                .setDescription("Duration of Embedify calendar and upstream operations.")
                .setUnit("s")
                .build();
        this.requestedFeeds = meter.histogramBuilder("embedify.calendar.request.feeds")
                .ofLongs()
                .setDescription("Calendar feeds requested in one API operation.")
                .setUnit("{feed}")
                .build();
        this.returnedEvents = meter.histogramBuilder("embedify.calendar.response.events")
                .ofLongs()
                .setDescription("Calendar events returned in one API operation.")
                .setUnit("{event}")
                .build();
        this.cacheRequests = meter.counterBuilder("embedify.cache.requests")
                .setDescription("Embedify cache lookups by cache and hit or miss result.")
                .setUnit("1")
                .build();
    }

    public Operation startOperation(String spanName, String operation) {
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        span.setAttribute(OPERATION, operation);
        return new Operation(span, operation, System.nanoTime());
    }

    public void recordCacheRequest(String cache, String result) {
        if (!CACHES.contains(cache) || !CACHE_RESULTS.contains(result)) {
            throw new IllegalArgumentException("Embedify cache telemetry values must use the bounded set.");
        }
        cacheRequests.add(1, Attributes.of(CACHE, cache, CACHE_RESULT, result));
    }

    public void recordRequestedFeeds(long count) {
        requestedFeeds.record(count);
    }

    public void recordReturnedEvents(long count, boolean truncated) {
        returnedEvents.record(count, Attributes.of(TRUNCATED, truncated));
    }

    public final class Operation implements AutoCloseable {
        private final Span span;
        private final String operation;
        private final long startedNanos;
        private final Scope scope;
        private boolean completed;

        private Operation(Span span, String operation, long startedNanos) {
            this.span = span;
            this.operation = operation;
            this.startedNanos = startedNanos;
            this.scope = span.makeCurrent();
        }

        public Operation attribute(String name, String value) {
            if (value != null) span.setAttribute(name, value);
            return this;
        }

        public Operation attribute(String name, long value) {
            span.setAttribute(name, value);
            return this;
        }

        public Operation attribute(String name, boolean value) {
            span.setAttribute(name, value);
            return this;
        }

        public void succeed() {
            finish("success");
        }

        public void fail(String outcome) {
            finish(OUTCOMES.contains(outcome) && !"success".equals(outcome) ? outcome : "failure");
        }

        private void finish(String outcome) {
            if (completed) return;
            completed = true;
            span.setAttribute(OUTCOME, outcome);
            if ("success".equals(outcome)) {
                span.setStatus(StatusCode.OK);
            } else if (!"invalid_input".equals(outcome)) {
                // Do not attach exception messages: they can contain user-supplied calendar URLs.
                span.setStatus(StatusCode.ERROR);
            }
            Attributes attributes = Attributes.of(OPERATION, operation, OUTCOME, outcome);
            operationCalls.add(1, attributes);
            operationDuration.record((double) (System.nanoTime() - startedNanos)
                    / TimeUnit.SECONDS.toNanos(1), attributes);
        }

        @Override
        public void close() {
            if (!completed) finish("incomplete");
            try {
                scope.close();
            } finally {
                span.end();
            }
        }
    }
}
