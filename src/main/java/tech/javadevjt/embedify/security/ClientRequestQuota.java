package tech.javadevjt.embedify.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed-window per-caller quota keyed only by the socket peer address. */
@Component
final class ClientRequestQuota {
    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(3))
            .build();

    boolean allow(String clientAddress, String routeClass, int limit, long nowMillis) {
        String key = (clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress)
                + "|" + routeClass;
        AtomicBoolean allowed = new AtomicBoolean();
        windows.asMap().compute(key, (ignored, previous) -> {
            Window current = previous;
            if (current == null || nowMillis - current.startedAtMillis >= WINDOW_MILLIS
                    || nowMillis < current.startedAtMillis) {
                current = new Window(nowMillis);
            }
            if (current.count < limit) {
                current.count++;
                allowed.set(true);
            }
            return current;
        });
        return allowed.get();
    }

    private static final class Window {
        private final long startedAtMillis;
        private int count;

        private Window(long startedAtMillis) {
            this.startedAtMillis = startedAtMillis;
        }
    }
}
