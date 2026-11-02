package com.braify.feature.events;

import java.util.List;

/** Event type names — part of the public webhook contract; don't rename. */
public final class PlatformEventTypes {

    private PlatformEventTypes() {}

    public static final String ESIGN_DOCUMENT_SENT      = "esign.document.sent";
    public static final String ESIGN_DOCUMENT_VIEWED    = "esign.document.viewed";
    public static final String ESIGN_SIGNER_SIGNED      = "esign.signer.signed";
    public static final String ESIGN_DOCUMENT_COMPLETED = "esign.document.completed";
    public static final String ESIGN_DOCUMENT_CANCELLED = "esign.document.cancelled";
    public static final String ESIGN_DOCUMENT_EXPIRED   = "esign.document.expired";
    public static final String BULK_EMAIL_FINISHED      = "bulk_email.job.finished";
    public static final String PDF_BULK_FINISHED        = "pdf.bulk.finished";
    public static final String FILE_UPLOADED            = "file.uploaded";
    /** Sent only by "Send test event". */
    public static final String PING                     = "ping";

    /** Subscribable types (shown in the webhook UI). */
    public static final List<String> ALL = List.of(
            ESIGN_DOCUMENT_SENT, ESIGN_DOCUMENT_VIEWED, ESIGN_SIGNER_SIGNED, ESIGN_DOCUMENT_COMPLETED,
            ESIGN_DOCUMENT_CANCELLED, ESIGN_DOCUMENT_EXPIRED, BULK_EMAIL_FINISHED, PDF_BULK_FINISHED,
            FILE_UPLOADED);
}
