package com.braify.feature.esign.model;

import lombok.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "esign_signature_fields")
public class ESignSignatureField {

    @Id private String id;

    @Indexed private String documentId;

    /** Which signatory fills this field (ESignDocument.Signatory.id). Null = legacy single-signer field. */
    private String signatoryId;

    /**
     * Who supplies this field's value:
     * <ul>
     *   <li>{@code SIGNER} (default) — a recipient fills it during signing (assigned via {@link #signatoryId}).</li>
     *   <li>{@code CREATOR} — the document author pre-fills it at authoring time; {@link #value} is set in the
     *       builder, {@link #signatoryId} is null, and it is never counted as a signer's obligation.</li>
     * </ul>
     */
    public enum FilledBy { SIGNER, CREATOR }
    @Builder.Default
    private FilledBy filledBy = FilledBy.SIGNER;

    /** ID of the AppUser who placed these signature fields. */
    @CreatedBy
    private String createdBy;

    /* ── Placement — all values as % of page dimensions ───────────────── */
    private int    page;    // 1-based; 0 = stamp on EVERY page
    private double x;       // left edge %
    private double y;       // top edge  % (from top of page)
    private double width;   // field width  as % of page width
    private double height;  // field height as % of page height

    /* ── Field definition ──────────────────────────────────────────────── */
    public enum FieldType { SIGNATURE, INITIALS, DATE, TEXT, STAMP, CHECKBOX }
    private FieldType fieldType;
    private String    label;
    @Builder.Default
    private boolean   required = true;
    /** Font size in points for TEXT/DATE values (both pre-filled and signer-typed). Null = default (12pt). */
    private Integer   fontSize;

    /* ── Client-filled value ───────────────────────────────────────────── */
    public enum SigningMethod { DRAW, TYPE, UPLOAD }
    private String        value;          // base64 PNG (SIGNATURE/INITIALS) or plain text
    private SigningMethod  signingMethod;
    private LocalDateTime  signedAt;
    /** Signer's IANA timezone captured at signing (e.g. "Africa/Kigali"); null for legacy records. */
    private String         signedTimeZone;
}
