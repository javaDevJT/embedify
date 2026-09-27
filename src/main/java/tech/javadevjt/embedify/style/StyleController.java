package tech.javadevjt.embedify.style;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StyleController {
    private final StyleService service;
    public StyleController(StyleService service) { this.service = service; }
    public record Request(String css, String url) {}

    @PostMapping(value = "/api/style", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StyleService.Suggestion suggest(@RequestBody Request request) {
        return service.suggest(request.css(), request.url());
    }
}
