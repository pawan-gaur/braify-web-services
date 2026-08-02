package com.braify.feature.esign.dto;

import lombok.Data;

@Data
public class SignFieldRequest {
    private String signingMethod;   // DRAW | TYPE | UPLOAD
    private String value;           // base64 PNG data-URL or plain text
    /** Signer's IANA timezone (e.g. "Africa/Kigali") captured from their browser at signing time. */
    private String timeZone;
    /** Optional font-size override (points) for TEXT/DATE values, if the signer adjusts it. */
    private Integer fontSize;
}
