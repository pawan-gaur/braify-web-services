package com.braify.feature.esign.model;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "esign_documents")
public class ESignDocument {

    @Id private String id;

    /* ── Ownership ─────────────────────────────────────────────────────── */
    @Indexed private String createdBy;   // userId of the creator
    private String orgId;
    private String title;

    /* ── Bulk batch link ────────────────────────────────────────────────── */
    /** ID of the {@link ESignBulkBatch} this document belongs to. Null for single-sign documents. */
    @Indexed private String bulkBatchId;

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
    private String       clientEmail;
    private String       clientName;
    /**
     * Optional CC recipients for the signing invitation email.
     * Populated from the Excel sheet's CC column during bulk send;
     * also settable on single-sign documents.
     */
    private List<String> ccEmails;

    /* ── Signed output ─────────────────────────────────────────────────── */
    private byte[] signedPdfData;
    private String signedPdfHash;        // SHA-256 — tamper-proof verification

    /* ── Email template ────────────────────────────────────────────────── */
    /**
     * Optional ID of the org's email template to use for the signing invitation.
     * When set, the template's HTML is used instead of the default built-in HTML.
     */
    private String emailTemplateId;

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

    /* ── Post-signing upload permission ────────────────────────────────── */
    /**
     * When {@code true}, the client is shown an optional file-upload section after signing.
     * Inherited from {@link ESignBulkBatch#isAllowClientUpload()} at document-creation time;
     * always {@code false} for single-sign documents unless explicitly set.
     */
    @Builder.Default
    private boolean allowClientUpload = false;

    /* ── Client-uploaded attachments (optional, post-signing) ─────────── */
    /**
     * Files the client voluntarily uploads after signing — e.g. ID proof, supporting docs.
     * Stored inline as embedded sub-documents; capped at 5 files × 10 MB each in the service layer.
     */
    @Builder.Default
    private List<ClientAttachment> clientAttachments = new ArrayList<>();

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ClientAttachment {
        private String        id;           // UUID
        private String        fileName;     // original file name
        private String        contentType;  // MIME type
        private byte[]        data;         // raw bytes
        private long          fileSize;     // bytes
        private LocalDateTime uploadedAt;
        private String        uploadedFromIp;
    }
}
