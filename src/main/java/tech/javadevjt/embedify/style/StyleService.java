package tech.javadevjt.embedify.style;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tech.javadevjt.embedify.net.SafeFetcher;
import tech.javadevjt.embedify.net.SafeFetchException;

@Service
public class StyleService {
    static final int MAX_CSS = 64 * 1024;
    private final SafeFetcher fetcher;
    private final Cache<String, Suggestion> cache = Caffeine.newBuilder().maximumSize(256)
        .expireAfterWrite(Duration.ofSeconds(60)).build();

    public record Suggestion(String background, String surface, String text, String accent, String font, List<String> notes) {}
    public StyleService(SafeFetcher fetcher) { this.fetcher = fetcher; }

    public Suggestion suggest(String css, String url) {
        boolean hasCss = css != null && !css.isBlank(), hasUrl = url != null && !url.isBlank();
        if (hasCss == hasUrl) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide either CSS text or one public HTTPS URL.");
        if (hasCss) {
            if (css.getBytes(StandardCharsets.UTF_8).length > MAX_CSS) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "CSS must be 64 KiB or smaller.");
            return CssPalette.extract(css, List.of());
        }
        if (url.length() > 2048) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The URL is too long.");
        return cache.get(url.trim(), this::fromUrl);
    }

    private Suggestion fromUrl(String url) {
        long stylesheetDeadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        // Large marketing pages often put their usable CSS before megabytes of page data.
        var page = fetcher.fetchStylePrefix(url, 1024 * 1024);
        String type = page.contentType().toLowerCase(Locale.ROOT);
        if (type.startsWith("text/css") || type.startsWith("text/plain")) {
            return CssPalette.extract(prefix(page.body()),
                List.of("Read a bounded sample from a public stylesheet."));
        }
        if (!type.startsWith("text/html") && !type.startsWith("application/xhtml+xml")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a public HTML page or CSS stylesheet URL.");
        }
        var document = Jsoup.parse(page.body(), page.url());
        var css = new ArrayList<String>();
        int inlineChars = 0;
        var notes = new ArrayList<String>();
        for (var style : document.select("style")) {
            if (inlineChars >= MAX_CSS) break;
            String data = style.data();
            String sample = data.substring(0, Math.min(data.length(), MAX_CSS - inlineChars));
            css.add(sample);
            inlineChars += sample.length();
        }
        URI origin = URI.create(page.url());
        int fetched = 0;
        var seen = new java.util.HashSet<URI>();
        for (var link : document.select("link[rel~=stylesheet][href]")) {
            if (fetched >= 3) break;
            // One fetch can take eight seconds; stop starting new work before the proxy's 20s deadline.
            if (System.nanoTime() >= stylesheetDeadline) {
                notes.add("Some stylesheets were skipped to keep the suggestion responsive.");
                break;
            }
            URI target;
            try { target = origin.resolve(link.attr("href")); }
            catch (IllegalArgumentException ignored) { continue; }
            if (!seen.add(target)) continue;
            fetched++;
            try {
                // CDN stylesheets use the same public-address, redirect, and byte guards.
                var sheet = fetcher.fetchStylePrefix(target.toString(), MAX_CSS);
                if (!sheet.contentType().toLowerCase(Locale.ROOT).startsWith("text/css")) continue;
                css.add(prefix(sheet.body()));
            } catch (SafeFetchException error) {
                if (error.kind() == SafeFetchException.Kind.BUSY) throw error;
                notes.add("One linked stylesheet could not be read; the available CSS was used.");
            }
        }
        notes.add("Read a bounded page sample and up to three public stylesheets, including CDNs. Scripts, imports, and fonts were not loaded.");
        return CssPalette.extractSamples(css, notes);
    }

    private static String prefix(String css) {
        return css.substring(0, Math.min(css.length(), MAX_CSS));
    }
}
