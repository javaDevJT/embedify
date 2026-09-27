package tech.javadevjt.embedify.net;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

/** Bounded, DNS-pinned HTTPS fetcher shared by calendar feeds and style extraction. */
@Component
public final class SafeFetcher {
    private static final int MAX_ALLOWED_BYTES = 1024 * 1024;
    private static final int MAX_REDIRECTS = 2;
    private static final long FETCH_DEADLINE_MILLIS = 8_000;
    private static final long CACHE_SUCCESS_NANOS = Duration.ofSeconds(60).toNanos();
    private static final long CACHE_FAILURE_NANOS = Duration.ofSeconds(4).toNanos();
    private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;
    private static final int DNS_THREADS = 4;
    private final Cache<CacheKey, CachedFetch> cache;
    private final Semaphore upstreamSlots;
    private final PublicHttpsUrlPolicy urlPolicy;
    private final RemoteFetch remoteFetch;
    private final long successTtlNanos;
    private final long failureTtlNanos;
    private final ThreadPoolExecutor dnsPool;

    @Autowired
    public SafeFetcher(@Value("${embedify.upstream.max-concurrent:12}") int maxConcurrentFetches) {
        this(maxConcurrentFetches, null, null, Duration.ofSeconds(60), Duration.ofSeconds(4));
    }

    SafeFetcher(int maxConcurrentFetches, PublicHttpsUrlPolicy urlPolicy, RemoteFetch remoteFetch,
                Duration successTtl, Duration failureTtl) {
        if (maxConcurrentFetches < 1 || maxConcurrentFetches > 64) {
            throw new IllegalArgumentException("embedify.upstream.max-concurrent must be between 1 and 64");
        }
        this.upstreamSlots = new Semaphore(maxConcurrentFetches, true);
        this.dnsPool = new ThreadPoolExecutor(
                DNS_THREADS, DNS_THREADS, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), daemonThreads("embedify-dns"),
                new ThreadPoolExecutor.AbortPolicy());
        this.urlPolicy = urlPolicy == null
                ? new PublicHttpsUrlPolicy(this::resolveBounded)
                : Objects.requireNonNull(urlPolicy);
        this.remoteFetch = remoteFetch == null ? this::fetchRedirects : remoteFetch;
        this.successTtlNanos = Objects.requireNonNull(successTtl).toNanos();
        this.failureTtlNanos = Objects.requireNonNull(failureTtl).toNanos();
        this.cache = Caffeine.<CacheKey, CachedFetch>newBuilder()
                .maximumWeight(MAX_CACHE_BYTES)
                .weigher((CacheKey key, CachedFetch value) -> value.weight())
                .expireAfter(new Expiry<CacheKey, CachedFetch>() {
                    @Override
                    public long expireAfterCreate(CacheKey key, CachedFetch value, long currentTime) {
                        return value.failure == null ? successTtlNanos : failureTtlNanos;
                    }

                    @Override
                    public long expireAfterUpdate(CacheKey key, CachedFetch value, long currentTime,
                                                  long currentDuration) {
                        return value.failure == null ? successTtlNanos : failureTtlNanos;
                    }

                    @Override
                    public long expireAfterRead(CacheKey key, CachedFetch value, long currentTime,
                                                long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    /**
     * Fetches UTF-8 text after validating the URL and every redirect. Successful results are
     * coalesced and cached for 60 seconds; failures are coalesced briefly to dampen retries.
     */
    public FetchResult fetch(String rawUrl, int maxBytes) {
        return fetch(rawUrl, maxBytes, false);
    }

    /** Fetches a resource and requires every redirect to stay on the initial HTTPS origin. */
    public FetchResult fetchSameOrigin(String rawUrl, int maxBytes) {
        return fetch(rawUrl, maxBytes, true);
    }

    private FetchResult fetch(String rawUrl, int maxBytes, boolean sameOriginOnly) {
        if (maxBytes < 1 || maxBytes > MAX_ALLOWED_BYTES) {
            throw new IllegalArgumentException("maxBytes must be between 1 and 1048576");
        }
        URI normalized = urlPolicy.normalize(rawUrl);
        CacheKey key = new CacheKey(normalized.toASCIIString(), maxBytes, sameOriginOnly);
        CachedFetch value = cache.get(key, ignored -> fetchAndCache(normalized, maxBytes, sameOriginOnly));
        if (value.failure != null) {
            throw value.failure;
        }
        FetchResult result = value.result;
        return new FetchResult(result.body(), result.contentType(), result.url());
    }

    private CachedFetch fetchAndCache(URI initialUrl, int maxBytes, boolean sameOriginOnly) {
        if (!upstreamSlots.tryAcquire()) {
            return CachedFetch.failed(new SafeFetchException(SafeFetchException.Kind.BUSY));
        }
        try {
            return CachedFetch.succeeded(remoteFetch.fetch(initialUrl, maxBytes, sameOriginOnly));
        } catch (SafeFetchException exception) {
            return CachedFetch.failed(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return CachedFetch.failed(new SafeFetchException(SafeFetchException.Kind.BUSY));
        } catch (Exception exception) {
            return CachedFetch.failed(new SafeFetchException(SafeFetchException.Kind.UPSTREAM));
        } finally {
            upstreamSlots.release();
        }
    }

    private FetchResult fetchRedirects(URI initialUrl, int maxBytes, boolean sameOriginOnly) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FETCH_DEADLINE_MILLIS);
        URI current = initialUrl;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            int remainingMillis = remainingMillis(deadline);
            if (remainingMillis <= 0) {
                throw upstreamFailure();
            }

            PublicHttpsUrlPolicy.Target target;
            try {
                target = urlPolicy.validateAndResolve(current);
            } catch (SafeFetchException exception) {
                if (redirects == 0) throw exception;
                throw upstreamFailure();
            }

            remainingMillis = remainingMillis(deadline);
            if (remainingMillis <= 0) throw upstreamFailure();
            HopResponse response = requestOnce(target, maxBytes, remainingMillis, deadline);
            if (response.location == null) {
                if (response.status < 200 || response.status >= 300) {
                    throw upstreamFailure();
                }
                return new FetchResult(decode(response.body, response.contentType), response.contentType,
                        current.toASCIIString());
            }
            if (redirects == MAX_REDIRECTS) throw upstreamFailure();
            current = validatedRedirectTarget(urlPolicy, initialUrl, current, response.location, sameOriginOnly);
        }
        throw upstreamFailure();
    }

    private HopResponse requestOnce(PublicHttpsUrlPolicy.Target target, int maxBytes,
                                    int remainingMillis, long deadline) throws InterruptedException {
        DnsResolver pinnedDns = new PinnedDnsResolver(target);
        HttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(pinnedDns)
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .build();
        int timeoutMillis = Math.max(1, Math.min(1_000, remainingMillis));
        RequestConfig config = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMillis))
                .setConnectTimeout(Timeout.ofMilliseconds(timeoutMillis))
                // A short read timeout plus an absolute deadline also bounds slow-drip responses.
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMillis))
                .build();

        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(config)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .build()) {
            HttpGet request = new HttpGet(target.uri());
            request.setHeader("Accept", "text/calendar, text/css, text/html;q=0.9, */*;q=0.1");
            request.setHeader("Accept-Encoding", "identity");
            request.setHeader("User-Agent", "Embedify/0.1");
            try (CloseableHttpResponse response = client.execute(request)) {
                int status = response.getCode();
                String location = header(response, "Location");
                if (isRedirect(status)) {
                    return new HopResponse(status, location, new byte[0], safeContentType(header(response, "Content-Type")));
                }
                if (status < 200 || status >= 300) {
                    return new HopResponse(status, null, new byte[0], "application/octet-stream");
                }

                var entity = response.getEntity();
                if (entity == null) {
                    return new HopResponse(status, null, new byte[0], safeContentType(header(response, "Content-Type")));
                }
                long declaredLength = entity.getContentLength();
                if (declaredLength > maxBytes) throw upstreamFailure();
                byte[] body;
                try (InputStream input = entity.getContent(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (System.nanoTime() >= deadline || total > maxBytes - read) {
                            throw upstreamFailure();
                        }
                        output.write(buffer, 0, read);
                        total += read;
                    }
                    body = output.toByteArray();
                }
                return new HopResponse(status, null, body, safeContentType(header(response, "Content-Type")));
            }
        } catch (SafeFetchException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw upstreamFailure();
        }
    }

    private InetAddress[] resolveBounded(String host) throws UnknownHostException {
        Future<InetAddress[]> future;
        try {
            future = dnsPool.submit(() -> InetAddress.getAllByName(host));
        } catch (RuntimeException exception) {
            throw new UnknownHostException("DNS capacity is unavailable");
        }
        try {
            InetAddress[] addresses = future.get(1_500, TimeUnit.MILLISECONDS);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !PublicHttpsUrlPolicy.isPublic(address))) {
                throw new UnknownHostException("Host is not public");
            }
            return addresses;
        } catch (TimeoutException | ExecutionException exception) {
            future.cancel(true);
            throw new UnknownHostException("DNS lookup failed");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new UnknownHostException("DNS lookup interrupted");
        }
    }

    private static String decode(byte[] body, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        try {
            if (contentType != null && !contentType.isBlank()) {
                Charset declared = ContentType.parse(contentType).getCharset();
                if (declared != null) charset = declared;
            }
        } catch (RuntimeException ignored) {
            // A malformed upstream charset falls back to UTF-8; it is never echoed to a caller.
        }
        return new String(body, charset);
    }

    private static String safeContentType(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
            return "application/octet-stream";
        }
        return value;
    }

    private static String header(CloseableHttpResponse response, String name) {
        var header = response.getFirstHeader(name);
        return header == null ? null : header.getValue();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static int remainingMillis(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return 0;
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private static SafeFetchException upstreamFailure() {
        return new SafeFetchException(SafeFetchException.Kind.UPSTREAM);
    }

    static URI validatedRedirectTarget(PublicHttpsUrlPolicy policy, URI initialUrl, URI currentUrl,
                                       String location, boolean sameOriginOnly) {
        try {
            URI resolved = currentUrl.resolve(new URI(location));
            URI next = policy.normalize(resolved.toString());
            if (sameOriginOnly && !sameOrigin(initialUrl, next)) throw upstreamFailure();
            return next;
        } catch (Exception exception) {
            throw upstreamFailure();
        }
    }

    private static boolean sameOrigin(URI first, URI second) {
        return "https".equalsIgnoreCase(first.getScheme())
                && "https".equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    @FunctionalInterface
    interface RemoteFetch {
        FetchResult fetch(URI url, int maxBytes, boolean sameOriginOnly) throws InterruptedException;
    }

    private static ThreadFactory daemonThreads(String prefix) {
        return new ThreadFactory() {
            private int sequence;

            @Override
            public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task, prefix + "-" + ++sequence);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    @PreDestroy
    void stopDnsPool() {
        dnsPool.shutdownNow();
    }

    public record FetchResult(String body, String contentType, String url) {
        public FetchResult {
            Objects.requireNonNull(body);
            Objects.requireNonNull(contentType);
            Objects.requireNonNull(url);
        }
    }

    private record CacheKey(String url, int maxBytes, boolean sameOriginOnly) {}

    private static final class CachedFetch {
        private final FetchResult result;
        private final SafeFetchException failure;

        private CachedFetch(FetchResult result, SafeFetchException failure) {
            this.result = result;
            this.failure = failure;
        }

        static CachedFetch succeeded(FetchResult result) {
            return new CachedFetch(result, null);
        }

        static CachedFetch failed(SafeFetchException failure) {
            return new CachedFetch(null, failure);
        }

        int weight() {
            return result == null ? 128 : Math.max(128, result.body().getBytes(StandardCharsets.UTF_8).length);
        }
    }

    private record HopResponse(int status, String location, byte[] body, String contentType) {}

    private static final class PinnedDnsResolver implements DnsResolver {
        private final PublicHttpsUrlPolicy.Target target;

        private PinnedDnsResolver(PublicHttpsUrlPolicy.Target target) {
            this.target = target;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!target.host().equalsIgnoreCase(host)) {
                throw new UnknownHostException("Unvalidated host");
            }
            return target.addresses();
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            if (!target.host().equalsIgnoreCase(host)) {
                throw new UnknownHostException("Unvalidated host");
            }
            return target.host();
        }
    }
}
