package tech.javadevjt.embedify.net;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

/** Validates public HTTPS URLs and pins a validated DNS snapshot for one request. */
final class PublicHttpsUrlPolicy {
    @FunctionalInterface
    interface DnsLookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    record Target(URI uri, String host, InetAddress[] addresses) {
        Target {
            addresses = Arrays.copyOf(addresses, addresses.length);
        }

        @Override
        public InetAddress[] addresses() {
            return Arrays.copyOf(addresses, addresses.length);
        }
    }

    private final DnsLookup dnsLookup;

    PublicHttpsUrlPolicy() {
        this(InetAddress::getAllByName);
    }

    PublicHttpsUrlPolicy(DnsLookup dnsLookup) {
        this.dnsLookup = dnsLookup;
    }

    URI normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 2048 || !rawUrl.equals(rawUrl.trim())) {
            throw invalid();
        }
        try {
            String candidate = rawUrl.regionMatches(true, 0, "webcal://", 0, 9)
                    ? "https://" + rawUrl.substring(9)
                    : rawUrl;
            URI parsed = new URI(candidate).normalize();
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getRawAuthority() == null
                    || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null) {
                throw invalid();
            }

            String authority = parsed.getRawAuthority();
            if (authority.indexOf('@') >= 0 || authority.indexOf('%') >= 0 || authority.startsWith("[")) {
                throw invalid();
            }

            String rawHost = parsed.getHost();
            int port = parsed.getPort();
            if (rawHost == null) {
                int colon = authority.lastIndexOf(':');
                if (colon >= 0) {
                    if (authority.indexOf(':') != colon) {
                        throw invalid();
                    }
                    String rawPort = authority.substring(colon + 1);
                    if (rawPort.isEmpty() || !rawPort.chars().allMatch(Character::isDigit)) {
                        throw invalid();
                    }
                    port = Integer.parseInt(rawPort);
                    rawHost = authority.substring(0, colon);
                } else {
                    rawHost = authority;
                }
            }
            if (port != -1 && port != 443) {
                throw invalid();
            }

            String host = IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            if (!validDnsName(host) || looksLikeIpLiteral(host)) {
                throw invalid();
            }

            StringBuilder canonical = new StringBuilder("https://").append(host);
            String path = parsed.getRawPath();
            canonical.append(path == null || path.isEmpty() ? "/" : path);
            if (parsed.getRawQuery() != null) {
                canonical.append('?').append(parsed.getRawQuery());
            }
            return URI.create(canonical.toString());
        } catch (SafeFetchException ex) {
            throw ex;
        } catch (IllegalArgumentException | URISyntaxException ex) {
            throw invalid();
        }
    }

    Target validateAndResolve(URI uri) {
        String host = uri.getHost();
        if (host == null || !validDnsName(host) || looksLikeIpLiteral(host)
                || !"https".equalsIgnoreCase(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw invalid();
        }
        try {
            InetAddress[] resolved = dnsLookup.resolve(host);
            if (resolved.length == 0 || Arrays.stream(resolved).anyMatch(address -> !isPublic(address))) {
                throw invalid();
            }
            return new Target(uri, host, resolved);
        } catch (UnknownHostException ex) {
            throw invalid();
        }
    }

    private static boolean validDnsName(String host) {
        if (host == null || host.isEmpty() || host.length() > 253) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeIpLiteral(String host) {
        return host.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.')
                || host.indexOf(':') >= 0;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            byte[] b = address.getAddress();
            int a = b[0] & 0xff;
            int second = b[1] & 0xff;
            int third = b[2] & 0xff;
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
            if (a == 100 && second >= 64 && second <= 127) return false;
            if (a == 169 && second == 254) return false;
            if (a == 172 && second >= 16 && second <= 31) return false;
            if (a == 192 && (second == 0 || second == 168 || (second == 88 && third == 99))) return false;
            if (a == 192 && second == 0 && third == 2) return false;
            if (a == 198 && (second == 18 || second == 19 || (second == 51 && third == 100))) return false;
            if (a == 203 && second == 0 && third == 113) return false;
            return true;
        }
        if (!(address instanceof Inet6Address ipv6) || ipv6.getScopeId() != 0) {
            return false;
        }
        byte[] b = ipv6.getAddress();
        // Only global unicast 2000::/3 is accepted. Special-purpose and documentation blocks are excluded.
        if ((b[0] & 0xe0) != 0x20) return false;
        if (prefix(b, new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8}, 32)) return false; // documentation
        if (prefix(b, new byte[]{0x20, 0x01, 0x00}, 23)) return false; // IETF protocol assignments
        if (prefix(b, new byte[]{0x20, 0x02}, 16)) return false; // 6to4
        if (prefix(b, new byte[]{0x3f, (byte) 0xff}, 20)) return false; // documentation
        return true;
    }

    private static boolean prefix(byte[] address, byte[] prefix, int bits) {
        int fullBytes = bits / 8;
        int remainingBits = bits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != prefix[i]) return false;
        }
        if (remainingBits == 0) return true;
        int mask = 0xff << (8 - remainingBits);
        return (address[fullBytes] & mask) == (prefix[fullBytes] & mask);
    }

    private static SafeFetchException invalid() {
        return new SafeFetchException(SafeFetchException.Kind.INVALID_INPUT);
    }
}
