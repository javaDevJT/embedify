package tech.javadevjt.embedify.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class EmbedController {
    @GetMapping("/embed")
    public String embed() {
        return "forward:/embed.html";
    }
}
