package tech.javadevjt.embedify.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Trusts X-Real-IP only when the connected peer matches an explicit literal IP/CIDR allowlist. */
@Component
final class TrustedProxyMatcher {
    private final List<Cidr> trustedNetworks;

    TrustedProxyMatcher(@Value("${embedify.trusted-proxies:}") String configuredNetworks) {
        this.trustedNetworks = parseNetworks(configuredNetworks);
    }

    String clientAddress(String remoteAddress, String realIpHeader) {
        InetAddress peer = parseLiteral(remoteAddress);
        if (peer == null) return "unknown";
        boolean trustedPeer = trustedNetworks.stream().anyMatch(network -> network.contains(peer));
        if (trustedPeer) {
            InetAddress forwarded = parseLiteral(realIpHeader);
            if (forwarded != null) return forwarded.getHostAddress();
        }
        return peer.getHostAddress();
    }

    private static List<Cidr> parseNetworks(String configuredNetworks) {
        if (configuredNetworks == null || configuredNetworks.isBlank()) return List.of();
        List<Cidr> parsed = new ArrayList<>();
        for (String token : configuredNetworks.split(",", -1)) {
            String value = token.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("embedify.trusted-proxies contains an empty entry");
            }
            String[] parts = value.split("/", -1);
            if (parts.length > 2) throw new IllegalArgumentException("embedify.trusted-proxies contains an invalid CIDR");
            InetAddress address = parseLiteral(parts[0]);
            if (address == null) throw new IllegalArgumentException("embedify.trusted-proxies requires literal IP addresses");
            int maxBits = address.getAddress().length * 8;
            int prefix = maxBits;
            if (parts.length == 2) {
                try {
                    prefix = Integer.parseInt(parts[1]);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("embedify.trusted-proxies contains an invalid CIDR");
                }
            }
            if (prefix < 0 || prefix > maxBits) {
                throw new IllegalArgumentException("embedify.trusted-proxies contains an invalid CIDR");
            }
            parsed.add(new Cidr(address.getAddress(), prefix));
        }
        return List.copyOf(parsed);
    }

    private static InetAddress parseLiteral(String input) {
        if (input == null || input.isBlank() || input.length() > 64 || !input.equals(input.trim())
                || input.indexOf(',') >= 0 || input.indexOf('%') >= 0) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(input);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record Cidr(byte[] network, int prefixBits) {
        private Cidr {
            network = network.clone();
        }

        private boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) return false;
            int wholeBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int i = 0; i < wholeBytes; i++) {
                if (candidate[i] != network[i]) return false;
            }
            if (remainingBits == 0) return true;
            int mask = 0xff << (8 - remainingBits);
            return (candidate[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }
}
