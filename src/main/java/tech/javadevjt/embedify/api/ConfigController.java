package tech.javadevjt.embedify.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {
    private static final String COFFEE_URL = "https://buymeacoffee.com/javadevjt";

    @GetMapping("/api/config")
    public ConfigResponse config() {
        return new ConfigResponse(COFFEE_URL, 60);
    }

    public record ConfigResponse(String coffeeUrl, int refreshSeconds) {}
}
