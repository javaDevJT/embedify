package tech.javadevjt.embedify.style;

import static org.junit.jupiter.api.Assertions.*;
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
    @Test void choosesBodyFontInsteadOfUnusedFontFacesOrCodeFontTokens() {
        var result = CssPalette.extract("""
            :root { --ds-font-family-body: Arial, sans-serif; --ds-surface:#fff; --ds-link:#1868db; }
            body { font-family:var(--ds-font-family-body); color:#292a2e; }
            @font-face { font-family: 'Example Mono'; src:url(https://cdn.example/mono.woff2); }
            :root { --ds-font-family-code:monospace; }
            """, List.of());
        assertEquals("sans", result.font());
        assertEquals("#ffffff", result.background());
        assertEquals("#1868db", result.accent());
    }
}
