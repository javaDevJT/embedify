import java.net.HttpURLConnection;
import java.net.URI;

public final class ContainerHealthCheck {
    private ContainerHealthCheck() {}

    public static void main(String[] args) {
        HttpURLConnection connection = null;
        try {
            String healthcheckUrl = System.getenv().getOrDefault(
                    "EMBEDIFY_HEALTHCHECK_URL", "http://127.0.0.1:8080/healthz");
            connection = (HttpURLConnection) URI.create(healthcheckUrl)
                    .toURL()
                    .openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                System.exit(1);
            }
        } catch (Exception ignored) {
            System.exit(1);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
