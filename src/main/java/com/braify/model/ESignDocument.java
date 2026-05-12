package com.braify.model;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "esign_documents")
public class ESignDocument {

    @Id private String id;

    /* ── Ownership ─────────────────────────────────────────────────────── */
    @Indexed private String createdBy;   // userId of the creator
    private String orgId;
    private String title;

    /* ── Source PDF ────────────────────────────────────────────────────── */
    public enum SourceType { TEMPLATE, UPLOAD }
    private SourceType sourceType;
    private String     templateId;       // set when sourceType == TEMPLATE
    private byte[]     sourcePdfData;    // raw uploaded / rendered PDF bytes
    private String     sourcePdfHash;    // SHA-256 of sourcePdfData

    /* ── State machine ─────────────────────────────────────────────────── */
    public enum Status {
        DRAFT,      // fields not yet placed / not yet sent
        PENDING,    // sent to client, awaiting open
        IN_REVIEW,  // client opened the signing link
        SIGNED,     // client submitted all signatures
        COMPLETED,  // signed PDF generated & emailed
        EXPIRED,    // signing token expired before submission
        CANCELLED   // manually cancelled by creator
    }
    @Builder.Default
    private Status status = Status.DRAFT;

    /* ── Client ────────────────────────────────────────────────────────── */
    private String clientEmail;
    private String clientName;

    /* ── Signed output ─────────────────────────────────────────────────── */
    private byte[] signedPdfData;
    private String signedPdfHash;        // SHA-256 — tamper-proof verification

    /* ── Token ─────────────────────────────────────────────────────────── */
    private String        signingTokenJti;
    private LocalDateTime tokenExpiresAt;

    /* ── Key timestamps ─────────────────────────────────────────────────── */
    private LocalDateTime sentAt;
    private LocalDateTime viewedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime completedAt;

    @CreatedDate  private LocalDateTime createdAt;
    @LastModifiedDate private LocalDateTime updatedAt;
}
