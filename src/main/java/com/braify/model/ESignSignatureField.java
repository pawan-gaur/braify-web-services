package com.braify.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "esign_signature_fields")
public class ESignSignatureField {

    @Id private String id;

    @Indexed private String documentId;

    /* ── Placement — all values as % of page dimensions ───────────────── */
    private int    page;    // 1-based; 0 = stamp on EVERY page
    private double x;       // left edge %
    private double y;       // top edge  % (from top of page)
    private double width;   // field width  as % of page width
    private double height;  // field height as % of page height

    /* ── Field definition ──────────────────────────────────────────────── */
    public enum FieldType { SIGNATURE, INITIALS, DATE, TEXT }
    private FieldType fieldType;
    private String    label;
    @Builder.Default
    private boolean   required = true;

    /* ── Client-filled value ───────────────────────────────────────────── */
    public enum SigningMethod { DRAW, TYPE, UPLOAD }
    private String        value;          // base64 PNG (SIGNATURE/INITIALS) or plain text
    private SigningMethod  signingMethod;
    private LocalDateTime  signedAt;
}
