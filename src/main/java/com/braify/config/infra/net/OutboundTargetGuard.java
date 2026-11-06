package com.braify.config.infra.net;

import java.net.InetAddress;
import java.net.URI;

/**
 * Blocks server-side requests to internal addresses (SSRF). Use it before connecting to any
 * host or URL that a tenant can configure — PDF template resources, an org's HTTP SMS endpoint,
 * an org's SMTP host. Platform-Admin configuration may still point at internal hosts.
 */
public final class OutboundTargetGuard {

    private OutboundTargetGuard() {}

    /** Throws {@link SecurityException} unless {@code url} is http(s) and its host resolves to public addresses only. */
    public static void assertPublicHttpUrl(String url) {
        URI u;
        try { u = URI.create(url == null ? "" : url.trim()); }
        catch (IllegalArgumentException e) { throw new SecurityException("Invalid URL"); }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) throw new SecurityException("Only http(s) URLs are allowed");
        assertPublicHost(u.getHost());
    }

    /** Throws {@link SecurityException} if {@code host} is missing or resolves to any internal address. */
    public static void assertPublicHost(String host) {
        if (host == null || host.isBlank()) throw new SecurityException("Missing host");
        InetAddress[] addrs;
        try { addrs = InetAddress.getAllByName(host.trim()); }
        catch (Exception e) { throw new SecurityException("Host can't be resolved: " + host); }
        for (InetAddress a : addrs) {
            if (isInternal(a)) throw new SecurityException("Internal addresses are not allowed: " + host);
        }
    }

    /** Loopback, private, link-local (incl. cloud metadata), CGNAT, multicast, unique-local IPv6 … */
    public static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            return b0 == 0
                || (b0 == 100 && b1 >= 64 && b1 <= 127)
                || (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0);
        }
        return (b[0] & 0xfe) == 0xfc;
    }
}
