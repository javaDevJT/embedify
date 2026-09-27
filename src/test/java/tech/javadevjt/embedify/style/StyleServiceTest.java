package tech.javadevjt.embedify.style;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tech.javadevjt.embedify.net.SafeFetcher;

class StyleServiceTest {
    @Test void acceptsTextOnlyAndBoundsPastedContent() {
        SafeFetcher fetcher = mock(SafeFetcher.class);
        StyleService service = new StyleService(fetcher);
        assertEquals("#112233", service.suggest("body{color:#123;background:#fff}", null).text());
        assertThrows(ResponseStatusException.class, () -> service.suggest("body{color:red}", "https://example.org"));
        assertThrows(ResponseStatusException.class, () -> service.suggest("a".repeat(StyleService.MAX_CSS + 1), null));
        verifyNoInteractions(fetcher);
    }

    @Test void boundsLinkedStylesheetsCachesSuggestionsAndIgnoresExecutableAssets() {
        SafeFetcher fetcher = mock(SafeFetcher.class);
        AtomicInteger calls = new AtomicInteger();
        when(fetcher.fetch(anyString(), anyInt())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            String url = invocation.getArgument(0);
            if (url.equals("https://example.org/")) return new SafeFetcher.FetchResult("""
                <base href="https://evil.example/">
                <script src="https://evil.example/run.js"></script>
                <link rel="stylesheet" href="https://evil.example/cross.css">
                <link rel="stylesheet" href="/one.css">
                <link rel="stylesheet" href="/two.css">
                <link rel="stylesheet" href="/three.css">
                """, "text/html", url);
            throw new AssertionError("Linked stylesheets must use the strict redirect policy");
        });
        when(fetcher.fetchSameOrigin(anyString(), anyInt())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            String url = invocation.getArgument(0);
            assertTrue(url.equals("https://example.org/one.css") || url.equals("https://example.org/two.css"));
            return new SafeFetcher.FetchResult("body{background:#fff;color:#123;font-family:monospace}", "text/css", url);
        });
        StyleService service = new StyleService(fetcher);
        var first = service.suggest(null, "https://example.org/");
        assertEquals("mono", first.font());
        assertEquals(first, service.suggest(null, "https://example.org/"));
        assertEquals(3, calls.get());
    }
}
