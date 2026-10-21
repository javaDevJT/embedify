import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            int status = exchange.getRequestURI().getPath().equals("/failure") ? 503 : 200;
            byte[] body = (status == 200 ? "fixture-ok" : "fixture-failure")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        fixture.start();

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(appUrl)).GET().build();
            HttpResponse<Void> appResponse = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            if (appResponse.statusCode() != 200) {
                throw new AssertionError("The real Embedify root route did not return HTTP 200.");
            }

            int port = fixture.getAddress().getPort();
            try (CloseableHttpClient client = HttpClients.createDefault()) {
                int success = client.execute(new HttpGet(
                        "http://127.0.0.1:" + port + "/SMOKE_PATH/success?token=SMOKE_QUERY"),
                        response -> response.getCode());
                int failure = client.execute(new HttpGet(
                        "http://127.0.0.1:" + port + "/failure?token=SMOKE_QUERY"),
                        response -> response.getCode());
                if (success != 200 || failure != 503) {
                    throw new AssertionError("Controlled HTTP fixture returned an unexpected status.");
                }
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
