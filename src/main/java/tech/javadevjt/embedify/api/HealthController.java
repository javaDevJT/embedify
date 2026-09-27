package tech.javadevjt.embedify.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final ObjectProvider<BuildProperties> buildProperties;

    public HealthController(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping("/healthz")
    public HealthResponse health() {
        String version = buildProperties.getIfAvailable() == null
                ? "0.1.0"
                : buildProperties.getIfAvailable().getVersion();
        return new HealthResponse("ok", version);
    }

    public record HealthResponse(String status, String version) {}
}
