package tech.javadevjt.embedify.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class EmbedController {
    @GetMapping("/embed")
    public String embed() {
        return "forward:/embed.html";
    }

    @GetMapping(value = "/license", produces = "text/plain;charset=UTF-8")
    @ResponseBody
    public Resource license() {
        return new ClassPathResource("LICENSE");
    }
}
