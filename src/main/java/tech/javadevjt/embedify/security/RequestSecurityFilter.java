package tech.javadevjt.embedify.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
final class RequestSecurityFilter extends OncePerRequestFilter {
    private static final int MAX_API_BODY_BYTES = 512 * 1024;
    private static final int MAX_CONCURRENT_API_REQUESTS = 48;

    private final ClientRequestQuota quota;
    private final TrustedProxyMatcher trustedProxyMatcher;
    private final Semaphore apiSlots = new Semaphore(MAX_CONCURRENT_API_REQUESTS, true);

    RequestSecurityFilter(ClientRequestQuota quota, TrustedProxyMatcher trustedProxyMatcher) {
        this.quota = quota;
        this.trustedProxyMatcher = trustedProxyMatcher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        addHeaders(request, response);
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean styleRequest = "/api/style".equals(path);
        int requestLimit = styleRequest ? 12 : 90;
        String quotaClass = styleRequest ? "style" : "api";
        // Forwarded headers are deliberately ignored; without an explicit trusted-proxy allowlist,
        // the only safe client identity is the address of the connected peer.
        String clientAddress = trustedProxyMatcher.clientAddress(request.getRemoteAddr(), request.getHeader("X-Real-IP"));
        if (!quota.allow(clientAddress, quotaClass, requestLimit, System.currentTimeMillis())) {
            writeError(response, 429,
                    "Too many requests. Please retry shortly.", true);
            return;
        }
        if (!apiSlots.tryAcquire()) {
            writeError(response, 429,
                    "The service is busy. Please retry shortly.", true);
            return;
        }

        try {
            HttpServletRequest boundedRequest = request;
            if (!"GET".equalsIgnoreCase(request.getMethod()) && !"HEAD".equalsIgnoreCase(request.getMethod())) {
                String contentType = request.getContentType();
                if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
                    writeError(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                            "Multipart requests are not supported.", false);
                    return;
                }
                if (request.getContentLengthLong() > MAX_API_BODY_BYTES) {
                    writeError(response, 413,
                            "Request body is too large.", false);
                    return;
                }
                byte[] body = request.getInputStream().readNBytes(MAX_API_BODY_BYTES + 1);
                if (body.length > MAX_API_BODY_BYTES) {
                    writeError(response, 413,
                            "Request body is too large.", false);
                    return;
                }
                boundedRequest = new BufferedRequest(request, body);
            }
            filterChain.doFilter(boundedRequest, response);
        } finally {
            apiSlots.release();
        }
    }

    private static void addHeaders(HttpServletRequest request, HttpServletResponse response) {
        String path = request.getRequestURI();
        boolean embeddable = "/embed".equals(path);
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", "default-src 'self'; base-uri 'self'; object-src 'none'; "
                + "form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                + "font-src 'self' data:; connect-src 'self'; frame-ancestors " + (embeddable ? "*" : "'none'"));
        if (!embeddable) {
            response.setHeader("X-Frame-Options", "DENY");
        }
    }

    private void writeError(HttpServletResponse response, int status, String message, boolean retry) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        if (retry) response.setHeader("Retry-After", "5");
        // Every caller passes a fixed internal message; no request or upstream text reaches this JSON.
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    try {
                        if (input.available() > 0) readListener.onDataAvailable();
                        if (input.available() == 0) readListener.onAllDataRead();
                    } catch (IOException exception) {
                        readListener.onError(exception);
                    }
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
