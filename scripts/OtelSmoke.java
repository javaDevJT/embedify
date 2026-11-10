import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OtelSmoke {
    private static final Logger LOG = LoggerFactory.getLogger(OtelSmoke.class);

    private OtelSmoke() {}

    public static void main(String[] args) throws Exception {
        String scenario = args.length == 0 ? "healthy" : args[0];
        String appUrl = args.length < 2 ? "http://127.0.0.1:18080/" : args[1];
        var tracer = GlobalOpenTelemetry.get().getTracer("tech.javadevjt.embedify.smoke");
        ConcurrentLinkedQueue<String> observedTraceparents = new ConcurrentLinkedQueue<>();
        AtomicInteger calendarFetches = new AtomicInteger();
        String calendarPath = "/SMOKE_CALENDAR_FEED.ics";
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals(calendarPath)) calendarFetches.incrementAndGet();
            String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
            if (traceparent != null && (path.equals("/SMOKE_PATH/success") || path.equals("/failure"))) {
                observedTraceparents.add(traceparent);
            }
            int status = path.equals("/failure") ? 503 : 200;
            String bodyText = path.equals(calendarPath)
                    ? "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"
                    : status == 200 ? "fixture-ok" : "fixture-failure";
            byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        fixture.start();

        try {
            int port = fixture.getAddress().getPort();
            String calendarUrl = "http://127.0.0.1:" + port + calendarPath;
            Span appParent = tracer.spanBuilder("fixture.app.request").startSpan();
            String appTraceId = appParent.getSpanContext().getTraceId();
            String appParentSpanId = appParent.getSpanContext().getSpanId();
            String appTraceFlags = appParent.getSpanContext().getTraceFlags().asHex();
            try (Scope ignored = appParent.makeCurrent()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(appUrl))
                        .header("traceparent", "00-" + appTraceId + "-" + appParentSpanId + "-" + appTraceFlags)
                        .GET()
                        .build();
                HttpResponse<Void> appResponse = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.discarding());
                if (appResponse.statusCode() != 200) {
                    throw new AssertionError("The real Embedify root route did not return HTTP 200.");
                }

                URI eventsUri = URI.create(appUrl).resolve(
                        "/api/events?feed=" + URLEncoder.encode(calendarUrl, StandardCharsets.UTF_8)
                                + "&from=2026-09-01&to=2026-09-02");
                HttpRequest rejectedFeed = HttpRequest.newBuilder(eventsUri)
                        .header("traceparent", "00-" + appTraceId + "-" + appParentSpanId + "-" + appTraceFlags)
                        .GET()
                        .build();
                HttpResponse<Void> eventsResponse = HttpClient.newHttpClient()
                        .send(rejectedFeed, HttpResponse.BodyHandlers.discarding());
                if (eventsResponse.statusCode() != 400) {
                    throw new AssertionError("The loopback URL rejection fixture returned an unexpected status.");
                }
                if (calendarFetches.get() != 0) {
                    throw new AssertionError("Embedify fetched the loopback calendar before rejecting its HTTP URL.");
                }
                LOG.info("fixture-app-request-trace-id-{}", appTraceId);
            } finally {
                appParent.end();
            }

            Span clientParent = tracer.spanBuilder("fixture.apache-client.parent").startSpan();
            String clientTraceId = clientParent.getSpanContext().getTraceId();
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                int success;
                int failure;
                try (Scope ignored = clientParent.makeCurrent()) {
                    success = client.execute(new HttpGet(
                            "http://127.0.0.1:" + port + "/SMOKE_PATH/success?token=SMOKE_QUERY"),
                            response -> response.getCode());
                    failure = client.execute(new HttpGet(
                            "http://127.0.0.1:" + port + "/failure?token=SMOKE_QUERY"),
                            response -> response.getCode());
                }
                if (success != 200 || failure != 503) {
                    throw new AssertionError("Controlled HTTP fixture returned an unexpected status.");
                }
            } finally {
                clientParent.end();
            }
            if (observedTraceparents.size() < 2 || observedTraceparents.stream()
                    .anyMatch(value -> !value.startsWith("00-" + clientTraceId + "-"))) {
                throw new AssertionError(
                        "Apache HttpClient did not propagate the active W3C trace context to each request.");
            }

            Span correlatedLogSpan = tracer.spanBuilder("fixture.correlated-log").startSpan();
            String correlatedTraceId = correlatedLogSpan.getSpanContext().getTraceId();
            String correlatedSpanId = correlatedLogSpan.getSpanContext().getSpanId();
            try (Scope ignored = correlatedLogSpan.makeCurrent()) {
                LOG.warn("fixture-correlated-log traceId={} spanId={}", correlatedTraceId, correlatedSpanId);
            } finally {
                correlatedLogSpan.end();
            }

            LOG.info("fixture-untraced-log-{}", scenario);
            LOG.warn("fixture-multiline-first\nfixture-multiline-second");
            try {
                throw new IllegalStateException(
                        "Fixture exception at https://fixture-user:fixture-pass@calendar.example.test/SMOKE_PATH?token=SMOKE_QUERY");
            } catch (IllegalStateException exception) {
                LOG.error("fixture-redaction-message https://fixture-user:fixture-pass@calendar.example.test/SMOKE_PATH?token=SMOKE_QUERY",
                        exception);
            }
            LOG.info("fixture-export-failure-isolation-{}", scenario);
            Thread.sleep(1600);
        } finally {
            fixture.stop(0);
        }
    }
}
