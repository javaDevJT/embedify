package tech.javadevjt.embedify.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final ObjectProvider<BuildProperties> buildProperties;
    private final String sourceCommit;

    public HealthController(
            ObjectProvider<BuildProperties> buildProperties,
            @Value("${QB_SOURCE_COMMIT:}") String sourceCommit) {
        this.buildProperties = buildProperties;
        this.sourceCommit = sourceCommit;
    }

    @GetMapping("/healthz")
    public HealthResponse health() {
        BuildProperties properties = buildProperties.getIfAvailable();
        String version = properties == null ? "0.1.0" : properties.getVersion();
        return new HealthResponse("ok", version, normalizeSourceCommit(sourceCommit));
    }

    static String normalizeSourceCommit(String commit) {
        return commit != null && commit.matches("[a-fA-F0-9]{40}") ? commit : "unknown";
    }

    public record HealthResponse(String status, String version, String sourceCommit) {}
}
