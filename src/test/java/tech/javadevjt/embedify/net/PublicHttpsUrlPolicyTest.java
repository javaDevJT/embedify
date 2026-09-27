package tech.javadevjt.embedify.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicHttpsUrlPolicyTest {
    @Test
    void normalizesWebcalAndPinsOnlyPublicDnsAnswers() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        PublicHttpsUrlPolicy policy = new PublicHttpsUrlPolicy(host -> new InetAddress[]{publicAddress});

        URI uri = policy.normalize("webcal://calendar.example/events.ics?key=secret");
        PublicHttpsUrlPolicy.Target target = policy.validateAndResolve(uri);

        assertEquals("https://calendar.example/events.ics?key=secret", uri.toString());
        assertEquals("calendar.example", target.host());
        assertArrayEquals(new InetAddress[]{publicAddress}, target.addresses());
    }

    @Test
    void rejectsCredentialsNonHttpsPortsIpLiteralsAndPrivateOrMixedDns() throws Exception {
        PublicHttpsUrlPolicy any = new PublicHttpsUrlPolicy(host -> new InetAddress[]{
                InetAddress.getByAddress(new byte[]{8, 8, 8, 8})
        });

        assertThrows(SafeFetchException.class, () -> any.normalize("https://user:pass@example.com/feed"));
        assertThrows(SafeFetchException.class, () -> any.normalize("http://example.com/feed"));
        assertThrows(SafeFetchException.class, () -> any.normalize("https://example.com:8443/feed"));
        assertThrows(SafeFetchException.class, () -> any.normalize("https://127.0.0.1/feed"));

        PublicHttpsUrlPolicy privateDns = new PublicHttpsUrlPolicy(host -> new InetAddress[]{
                InetAddress.getByAddress(new byte[]{10, 2, 3, 4})
        });
        assertThrows(SafeFetchException.class,
                () -> privateDns.validateAndResolve(privateDns.normalize("https://example.com/feed")));

        PublicHttpsUrlPolicy mixedDns = new PublicHttpsUrlPolicy(host -> new InetAddress[]{
                InetAddress.getByAddress(new byte[]{8, 8, 8, 8}),
                InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, 0, 1})
        });
        assertThrows(SafeFetchException.class,
                () -> mixedDns.validateAndResolve(mixedDns.normalize("https://example.com/feed")));
    }

    @Test
    void blocksReservedIpv6Ranges() throws Exception {
        assertNotPublic(InetAddress.getByAddress(new byte[16]));
        assertNotPublic(InetAddress.getByName("::1"));
        assertNotPublic(InetAddress.getByName("fe80::1"));
        assertNotPublic(InetAddress.getByName("fc00::1"));
        assertNotPublic(InetAddress.getByName("2001:db8::1"));
        assertNotPublic(InetAddress.getByName("2002::1"));
        assertEquals(true, PublicHttpsUrlPolicy.isPublic(InetAddress.getByName("2001:4860:4860::8888")));
    }

    private static void assertNotPublic(InetAddress address) {
        assertEquals(false, PublicHttpsUrlPolicy.isPublic(address), address.getHostAddress());
    }
}
