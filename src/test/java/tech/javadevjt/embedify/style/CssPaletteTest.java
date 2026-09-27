package tech.javadevjt.embedify.style;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CssPaletteTest {
    @Test void extractsVariablesRgbAndLocalFontsWithoutExecutingCss() {
        var result = CssPalette.extract(":root { --brand: #a34; --bg: #f9f5f1; } body {background: var(--bg); color: rgb(20, 24, 28); font-family: Georgia, serif;} a { color:var(--brand); }", List.of());
        assertEquals("#f9f5f1", result.background());
        assertEquals("#aa3344", result.accent());
        assertEquals("#14181c", result.text());
        assertEquals("serif", result.font());
    }
    @Test void repairsLowContrastAndNeverReturnsRawCss() {
        var result = CssPalette.extract("body { background:#111; color:#222; } a {color:#111} .card {background:#fafafa} x { background-image:url(https://attacker.test/#123456); }", List.of());
        assertTrue(CssPalette.contrast(result.text(), result.background()) >= 4.5);
        assertTrue(CssPalette.contrast(result.text(), result.surface()) >= 4.5);
        assertTrue(result.background().matches("#[0-9a-f]{6}"));
        assertFalse(result.toString().contains("attacker"));
    }
    @Test void rejectsNonStylesAndLimitsVariableCycles() {
        assertThrows(ResponseStatusException.class, () -> CssPalette.extract("<script>alert(1)</script>", List.of()));
        assertThrows(ResponseStatusException.class, () -> CssPalette.extract(":root{--x:var(--y);--y:var(--x)}body{color:var(--x)}", List.of()));
        assertTimeout(java.time.Duration.ofSeconds(2), () -> {
            var result = CssPalette.extract("body{color:#123}/*" + "/*".repeat(30000), List.of());
            assertEquals("#112233", result.text());
        });
    }
    @Test void linkedStylesheetsMustKeepOrigin() {
        URI origin = URI.create("https://example.org/path");
        assertTrue(StyleService.sameOrigin(origin, URI.create("https://example.org:443/site.css")));
        assertFalse(StyleService.sameOrigin(origin, URI.create("http://example.org/site.css")));
        assertFalse(StyleService.sameOrigin(origin, URI.create("https://cdn.example.org/site.css")));
        assertFalse(StyleService.sameOrigin(origin, URI.create("https://example.org:444/site.css")));
    }
}
