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
        var page = fetcher.fetch(url, 256 * 1024);
        String type = page.contentType().toLowerCase(Locale.ROOT);
        if (type.startsWith("text/css") || type.startsWith("text/plain")) {
            if (page.body().getBytes(StandardCharsets.UTF_8).length > MAX_CSS) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The stylesheet exceeds 64 KiB.");
            return CssPalette.extract(page.body(), List.of("Extracted from a public stylesheet."));
        }
        if (!type.startsWith("text/html") && !type.startsWith("application/xhtml+xml"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a public HTML page or CSS stylesheet URL.");
        var document = Jsoup.parse(page.body(), page.url());
        var css = new StringBuilder();
        var notes = new ArrayList<String>();
        for (var style : document.select("style")) {
            if (css.length() >= MAX_CSS) break;
            String data = style.data();
            css.append(data, 0, Math.min(data.length(), MAX_CSS - css.length())).append('\n');
        }
        URI origin = URI.create(page.url());
        int fetched = 0;
        for (var link : document.select("link[rel~=stylesheet][href]")) {
            if (fetched >= 2 || css.length() >= MAX_CSS) break;
            URI target;
            try { target = origin.resolve(link.attr("href")); }
            catch (IllegalArgumentException ignored) { continue; }
            if (!sameOrigin(origin, target)) continue;
            fetched++;
            try {
                var sheet = fetcher.fetchSameOrigin(target.toString(), MAX_CSS);
                if (!sameOrigin(origin, URI.create(sheet.url())) || !sheet.contentType().toLowerCase(Locale.ROOT).startsWith("text/css")) continue;
                css.append(sheet.body(), 0, Math.min(sheet.body().length(), MAX_CSS - css.length())).append('\n');
            } catch (ResponseStatusException error) {
                if (error.getStatusCode().value() == 429) throw error;
                notes.add("One linked stylesheet could not be read.");
            }
        }
        notes.add("Read inline CSS and up to two same-origin stylesheets. Scripts, imports, and external assets were not loaded.");
        return CssPalette.extract(css.toString(), notes);
    }

    static boolean sameOrigin(URI a, URI b) {
        return "https".equalsIgnoreCase(b.getScheme()) && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
            && (a.getPort() == -1 ? 443 : a.getPort()) == (b.getPort() == -1 ? 443 : b.getPort());
    }
}
