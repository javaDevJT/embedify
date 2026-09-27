package tech.javadevjt.embedify.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrustedProxyMatcherTest {
    @Test
    void ignoresSpoofedRealIpUnlessSocketPeerIsExplicitlyTrusted() {
        TrustedProxyMatcher noTrust = new TrustedProxyMatcher("");
        assertEquals("198.51.100.7", noTrust.clientAddress("198.51.100.7", "203.0.113.8"));

        TrustedProxyMatcher configured = new TrustedProxyMatcher("10.20.0.0/16, 2001:db8:100::/48");
        assertEquals("192.0.2.10", configured.clientAddress("192.0.2.10", "198.51.100.8"));
        assertEquals("198.51.100.8", configured.clientAddress("10.20.1.4", "198.51.100.8"));
        assertEquals("10.20.1.4", configured.clientAddress("10.20.1.4", "198.51.100.8, 203.0.113.8"));
        assertEquals("10.20.1.4", configured.clientAddress("10.20.1.4", "visitor.example"));
    }
}
