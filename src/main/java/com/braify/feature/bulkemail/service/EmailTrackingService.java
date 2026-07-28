package com.braify.feature.bulkemail.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-hosted open/click/unsubscribe tracking for bulk email.
 *
 * <p>At send time {@link #applyTracking(String, String)} transforms the fully-rendered
 * HTML for one recipient:
 * <ol>
 *   <li>every {@code http(s)} link is rewritten to a signed redirect through
 *       {@code /api/track/c/{trackingId}} so clicks can be attributed;</li>
 *   <li>a 1×1 open pixel ({@code /api/track/o/{trackingId}}) is injected;</li>
 *   <li>a one-click unsubscribe footer ({@code /api/track/u/{trackingId}}) is appended.</li>
 * </ol>
 *
 * <h3>Security</h3>
 * The click redirect carries the destination URL in the query string, so it is
 * HMAC-signed to prevent the endpoint being abused as an open redirect: a request whose
 * signature does not match {@code sign(trackingId + "\n" + url)} is rejected and never
 * redirected. Tracking tokens themselves are opaque and unguessable (generated per
 * recipient) so opens/clicks cannot be forged or enumerated for other recipients.
 *
 * <h3>Reliability note</h3>
 * Pixel opens are inherently noisy — Apple Mail Privacy Protection pre-fetches images
 * (inflating opens) and image-blocking clients undercount them. Clicks are the reliable
 * engagement signal; the analytics UI leans on clicks accordingly.
 */
@Slf4j
@Service
public class EmailTrackingService {

    /** Matches an http(s) URL inside an href attribute value (single or double quoted). */
    private static final Pattern HREF = Pattern.compile(
            "(href\\s*=\\s*[\"'])(https?://[^\"'\\s>]+)([\"'])", Pattern.CASE_INSENSITIVE);

    private final String  baseUrl;          // normalised, no trailing slash
    private final byte[]  secret;
    private final boolean openEnabled;
    private final boolean clickEnabled;
    private final boolean unsubscribeEnabled;

    public EmailTrackingService(
            @Value("${app.tracking.base-url:${app.base-url:http://localhost:5173}}") String baseUrl,
            @Value("${app.tracking.secret:${jwt.secret:}}") String secret,
            @Value("${app.tracking.open-enabled:true}")        boolean openEnabled,
            @Value("${app.tracking.click-enabled:true}")       boolean clickEnabled,
            @Value("${app.tracking.unsubscribe-enabled:true}") boolean unsubscribeEnabled) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.secret  = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
        this.openEnabled        = openEnabled;
        this.clickEnabled       = clickEnabled;
        this.unsubscribeEnabled = unsubscribeEnabled;
        if (this.secret.length == 0) {
            log.warn("app.tracking.secret is empty — falling back to an empty HMAC key. "
                    + "Set TRACKING_SECRET (or JWT_SECRET) so click/unsubscribe links are tamper-proof.");
        }
    }

    /** True when at least one form of tracking is active (used to skip the transform entirely). */
    public boolean isEnabled() {
        return openEnabled || clickEnabled || unsubscribeEnabled;
    }

    // ── Send-side transform ─────────────────────────────────────────────────────

    /**
     * Rewrites links + injects the open pixel and unsubscribe footer for one recipient.
     * Returns {@code html} unchanged when nothing is enabled or the token is blank.
     */
    public String applyTracking(String html, String trackingId) {
        if (html == null || trackingId == null || trackingId.isBlank() || !isEnabled()) return html;

        String out = clickEnabled ? rewriteLinks(html, trackingId) : html;

        StringBuilder inject = new StringBuilder();
        if (unsubscribeEnabled) inject.append(unsubscribeFooter(trackingId));
        if (openEnabled)        inject.append(openPixel(trackingId));

        if (inject.length() == 0) return out;
        return injectBeforeBodyEnd(out, inject.toString());
    }

    private String rewriteLinks(String html, String trackingId) {
        Matcher m = HREF.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rawUrl = htmlUnescape(m.group(2));
            // Don't wrap our own tracking links (idempotent / avoids double-encoding).
            String replacementUrl = rawUrl.startsWith(baseUrl + "/api/track/")
                    ? m.group(2)
                    : htmlEscapeAttr(clickUrl(trackingId, rawUrl));
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + replacementUrl + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String openPixel(String trackingId) {
        return "<img src=\"" + baseUrl + "/api/track/o/" + enc(trackingId) + "\" alt=\"\" "
                + "width=\"1\" height=\"1\" style=\"display:none;max-height:0;overflow:hidden\" />";
    }

    private String unsubscribeFooter(String trackingId) {
        String href = htmlEscapeAttr(unsubscribeUrl(trackingId));
        return "<div style=\"text-align:center;font-size:11px;line-height:1.5;color:#9ca3af;"
                + "padding:16px 8px;font-family:Arial,Helvetica,sans-serif\">"
                + "<a href=\"" + href + "\" style=\"color:#9ca3af;text-decoration:underline\">Unsubscribe</a>"
                + " from these emails.</div>";
    }

    /** Inserts {@code snippet} just before the last {@code </body>} (or appends if none). */
    private String injectBeforeBodyEnd(String html, String snippet) {
        int idx = html.toLowerCase().lastIndexOf("</body>");
        if (idx < 0) return html + snippet;
        return html.substring(0, idx) + snippet + html.substring(idx);
    }

    // ── URL builders ────────────────────────────────────────────────────────────

    public String clickUrl(String trackingId, String url) {
        return baseUrl + "/api/track/c/" + enc(trackingId)
                + "?u=" + b64(url)
                + "&s=" + sign(trackingId + "\n" + url);
    }

    public String unsubscribeUrl(String trackingId) {
        return baseUrl + "/api/track/u/" + enc(trackingId)
                + "?s=" + sign(trackingId + "\nunsub");
    }

    // ── Verification (controller side) ──────────────────────────────────────────

    /**
     * Verifies a click and returns the decoded destination URL, or {@code null} when the
     * signature is invalid / the payload is malformed (caller must NOT redirect on null).
     */
    public String verifyClick(String trackingId, String encodedUrl, String sig) {
        if (trackingId == null || encodedUrl == null || sig == null) return null;
        String url;
        try {
            url = new String(b64Decode(encodedUrl), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return null;   // only ever redirect to http(s)
        return constantTimeEquals(sig, sign(trackingId + "\n" + url)) ? url : null;
    }

    public boolean verifyUnsubscribe(String trackingId, String sig) {
        return trackingId != null && sig != null
                && constantTimeEquals(sig, sign(trackingId + "\nunsub"));
    }

    // ── Crypto / encoding helpers ───────────────────────────────────────────────

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.length == 0 ? new byte[]{0} : secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign tracking payload", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] b64Decode(String s) {
        int pad = (4 - s.length() % 4) % 4;
        return Base64.getUrlDecoder().decode(s + "=".repeat(pad));
    }

    /** URL-path-encode the tracking token (tokens are already URL-safe base64url, so this is a safety net). */
    private static String enc(String s) {
        return s.replaceAll("[^A-Za-z0-9._~\\-]", "");
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String htmlUnescape(String s) {
        return s.replace("&amp;", "&").replace("&#38;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'");
    }

    /** Escape a URL for safe placement inside a double-quoted HTML attribute. */
    private static String htmlEscapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;");
    }
}
