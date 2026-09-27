package tech.javadevjt.embedify.style;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tech.javadevjt.embedify.net.SafeFetcher;
import tech.javadevjt.embedify.net.SafeFetchException;

class StyleServiceTest {
    @Test void acceptsTextOnlyAndBoundsPastedContent() {
        SafeFetcher fetcher = mock(SafeFetcher.class);
        StyleService service = new StyleService(fetcher);
        assertEquals("#112233", service.suggest("body{color:#123;background:#fff}", null).text());
        assertThrows(ResponseStatusException.class, () -> service.suggest("body{color:red}", "https://example.org"));
        assertThrows(ResponseStatusException.class, () -> service.suggest("a".repeat(StyleService.MAX_CSS + 1), null));
        verifyNoInteractions(fetcher);
    }

    @Test void readsCdnTokensEvenWhenLargeInlineCssUsesItsBudgetAndCachesSuggestions() {
        SafeFetcher fetcher = mock(SafeFetcher.class);
        AtomicInteger calls = new AtomicInteger();
        when(fetcher.fetchStylePrefix(anyString(), anyInt())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            String url = invocation.getArgument(0);
            if (url.equals("https://example.org/")) {
                assertEquals(1024 * 1024, (int) invocation.getArgument(1));
                return new SafeFetcher.FetchResult(
                    "<style>/*" + "x".repeat(300_000) + "*/</style>" + """
                    <base href="https://ignored.example/">
                    <script src="https://ignored.example/run.js"></script>
                    <link rel="stylesheet" href="https://cdn.example/tokens.css">
                    <link rel="stylesheet" href="https://cdn.example/tokens.css">
                    <link rel="stylesheet" href="/two.css">
                    <link rel="stylesheet" href="/three.css">
                    <link rel="stylesheet" href="/four.css">
                    """, "text/html", url);
            }
            assertEquals(StyleService.MAX_CSS, (int) invocation.getArgument(1));
            assertTrue(java.util.Set.of("https://cdn.example/tokens.css", "https://example.org/two.css",
                "https://example.org/three.css").contains(url));
            return new SafeFetcher.FetchResult(
                ":root{--ds-surface:#fff;--ds-text:#292a2e;--ds-link:#1868db}", "text/css", url);
        });
        StyleService service = new StyleService(fetcher);
        var first = service.suggest(null, "https://example.org/");
        assertEquals("#1868db", first.accent());
        assertEquals("#ffffff", first.surface());
        assertEquals(first, service.suggest(null, "https://example.org/"));
        assertEquals(4, calls.get());
        verify(fetcher, never()).fetch(anyString(), anyInt());
    }

    @Test void skipsUnreadableOrPrivateLinkedStylesheetsButPropagatesCapacityLimit() {
        SafeFetcher fetcher = mock(SafeFetcher.class);
        when(fetcher.fetchStylePrefix(eq("https://example.org/"), anyInt()))
            .thenReturn(new SafeFetcher.FetchResult(
                "<style>body{background:#fff;color:#123}</style><link rel='stylesheet' href='https://127.0.0.1/private.css'>",
                "text/html", "https://example.org/"));
        SafeFetchException invalid = mock(SafeFetchException.class);
        when(invalid.kind()).thenReturn(SafeFetchException.Kind.INVALID_INPUT);
        when(fetcher.fetchStylePrefix(eq("https://127.0.0.1/private.css"), anyInt())).thenThrow(invalid);
        var result = new StyleService(fetcher).suggest(null, "https://example.org/");
        assertEquals("#112233", result.text());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("could not be read")));

        SafeFetchException busy = mock(SafeFetchException.class);
        when(busy.kind()).thenReturn(SafeFetchException.Kind.BUSY);
        when(fetcher.fetchStylePrefix(eq("https://127.0.0.1/private.css"), anyInt())).thenThrow(busy);
        assertEquals(SafeFetchException.Kind.BUSY, assertThrows(SafeFetchException.class,
            () -> new StyleService(fetcher).suggest(null, "https://example.org/")).kind());
    }

    @Test void extractsABoundedSampleFromLargerDirectStylesheets() {
        SafeFetcher fetcher = mock(SafeFetcher.class);
        when(fetcher.fetchStylePrefix(eq("https://example.org/site.css"), anyInt()))
            .thenReturn(new SafeFetcher.FetchResult("body{background:#fff;color:#123}/*" + "x".repeat(100_000),
                "text/css", "https://example.org/site.css"));
        assertEquals("#112233", new StyleService(fetcher).suggest(null, "https://example.org/site.css").text());
    }
}
