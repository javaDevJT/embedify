package tech.javadevjt.embedify.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSecurityFilterTest {
    @Test
    void rejectsChunkedOversizeBodyBeforeControllerReadsIt() throws Exception {
        ClientRequestQuota quota = new ClientRequestQuota();
        RequestSecurityFilter filter = new RequestSecurityFilter(quota, new TrustedProxyMatcher(""));
        byte[] oversized = new byte[512 * 1024 + 1];
        MockHttpServletRequest base = new MockHttpServletRequest();
        base.setMethod("POST");
        base.setRequestURI("/api/style");
        base.setContent(oversized);
        HttpServletRequest request = new HttpServletRequestWrapper(base) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainReached = new AtomicBoolean();

        filter.doFilterInternal(request, response, (req, res) -> chainReached.set(true));

        assertEquals(413, response.getStatus());
        assertFalse(chainReached.get());
        assertTrue(response.getContentAsString().contains("Request body is too large."));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
    }

    @Test
    void doesNotAllocatePastConfiguredCapForOversizeChunkedBody() throws Exception {
        ClientRequestQuota quota = new ClientRequestQuota();
        RequestSecurityFilter filter = new RequestSecurityFilter(quota, new TrustedProxyMatcher(""));
        CountingInputRequest request = new CountingInputRequest(512 * 1024 + 100_000);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> {
            throw new AssertionError("oversize body reached the controller");
        });

        assertEquals(413, response.getStatus());
        assertEquals(512 * 1024 + 1, request.bytesRead);
    }

    private static final class CountingInputRequest extends MockHttpServletRequest {
        private final int bodyLength;
        private int bytesRead;

        private CountingInputRequest(int bodyLength) {
            this.bodyLength = bodyLength;
            setMethod("POST");
            setRequestURI("/api/style");
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private int position;

                @Override
                public int read() {
                    if (position >= bodyLength) return -1;
                    position++;
                    bytesRead++;
                    return 'x';
                }

                @Override
                public boolean isFinished() {
                    return position >= bodyLength;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                }
            };
        }
    }
}
