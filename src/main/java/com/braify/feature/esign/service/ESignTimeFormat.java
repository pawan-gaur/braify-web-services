package com.braify.feature.esign.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats e-sign timestamps for display in the SIGNER's timezone (captured from their browser at
 * signing time) rather than the server's, with a GMT-offset label — e.g. "Jul 4, 2026 03:50:27 GMT+2".
 */
public final class ESignTimeFormat {

    // "O" renders a localized GMT offset like "GMT+2" (matching common e-sign audit trails).
    private static final DateTimeFormatter CAPTION =
            DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss O", Locale.ENGLISH);

    private ESignTimeFormat() {}

    /**
     * Renders {@code signedAt} (a server-local {@link LocalDateTime}) in the signer's timezone.
     *
     * @param signedAt the timestamp as stored (created via {@code LocalDateTime.now()}, i.e. in the
     *                 server's default zone)
     * @param timeZone the signer's IANA zone (e.g. "Africa/Kigali"); if null/blank/invalid, the
     *                 server's default zone is used so a labelled offset is still shown
     */
    public static String caption(LocalDateTime signedAt, String timeZone) {
        if (signedAt == null) return "";
        ZoneId serverZone = ZoneId.systemDefault();
        ZoneId targetZone = serverZone;
        if (timeZone != null && !timeZone.isBlank()) {
            try { targetZone = ZoneId.of(timeZone.trim()); }
            catch (Exception ignored) { /* invalid zone → keep server zone */ }
        }
        ZonedDateTime zoned = signedAt.atZone(serverZone).withZoneSameInstant(targetZone);
        return CAPTION.format(zoned);
    }

    // Audit-report timestamp — a single consistent zone (UTC) across all lines, e.g.
    // "2026-06-29 - 12:36:36 PM GMT".
    private static final DateTimeFormatter AUDIT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd - hh:mm:ss a 'GMT'", Locale.ENGLISH);

    /** Renders a timestamp in UTC (converted from the server zone) for the audit report. */
    public static String audit(LocalDateTime dt) {
        if (dt == null) return "";
        ZonedDateTime utc = dt.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC);
        return AUDIT.format(utc);
    }
}
