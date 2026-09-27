package tech.javadevjt.embedify.api;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiStartupSmokeTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthAndPublicConfigAreAvailableWithNoStoreHeaders() throws Exception {
        mockMvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.version").value("0.1.0"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coffeeUrl").value("https://buymeacoffee.com/javadevjt"))
                .andExpect(jsonPath("$.refreshSeconds").value(60));
    }

    @Test
    void invalidFeedErrorIsJsonAndDoesNotEchoTheSecretUrl() throws Exception {
        mockMvc.perform(get("/api/events")
                        .param("feed", "https://user:secret@example.com/private.ics?token=do-not-echo")
                        .param("from", "2026-09-27")
                        .param("to", "2026-09-28")
                        .param("tz", "UTC"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.message").value(containsString("URL")))
                .andExpect(content().string(not(containsString("do-not-echo"))))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void onlyEmbedRouteAllowsExternalFraming() throws Exception {
        mockMvc.perform(get("/embed"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors *")))
                .andExpect(header().doesNotExist("X-Frame-Options"));

        mockMvc.perform(get("/"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
    }

    @Test
    void missingStaticResourceRemainsNotFound() throws Exception {
        mockMvc.perform(get("/missing-resource.svg"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Not Found"));
    }

    @Test
    void publicLicenseMatchesTheCanonicalRepositoryLicense() throws Exception {
        mockMvc.perform(get("/license"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(Files.readString(Path.of("LICENSE"))))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
