package com.braify.feature.esign.model;

import lombok.*;
import org.springframework.data.annotation.*;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "esign_documents")
@CompoundIndexes({
    // ORG_ADMIN list + dashboard counters (orgId + status), createdAt suffix serves the sort
    @CompoundIndex(name = "idx_org_status_batch_created",
                   def = "{'orgId':1,'status':1,'bulkBatchId':1,'createdAt':-1}"),
    // Creator-scoped list (USER/ADMIN)
    @CompoundIndex(name = "idx_createdBy_status_batch_created",
                   def = "{'createdBy':1,'status':1,'bulkBatchId':1,'createdAt':-1}"),
})
public class ESignDocument {

    @Id private String id;

    /* ── Ownership ─────────────────────────────────────────────────────── */
    @CreatedBy
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
    private byte[]     sourcePdfData;    // LEGACY embedded bytes (older docs); null for cloud-stored docs
    private String     sourcePdfKey;     // cloud storage key for the source PDF (preferred)
    private String     sourcePdfHash;    // SHA-256 of the source PDF

    /* ── Cloud storage reference (shared by source + signed PDFs) ───────── */
    private String     pdfBucket;         // cloud bucket holding this doc's PDFs (null = legacy embedded)
    private String     pdfCloudProvider;  // AWS | AZURE | GCP

    /* ── State machine ─────────────────────────────────────────────────── */
    public enum Status {
        DRAFT,            // fields not yet placed / not yet sent
        PENDING,          // sent to signatories, awaiting open
        IN_REVIEW,        // a signatory opened the signing link
        PARTIALLY_SIGNED, // some (but not all) signatories have submitted
        SIGNED,           // all signatories submitted
        COMPLETED,        // signed PDF generated & emailed
        EXPIRED,          // signing token expired before submission
        CANCELLED         // manually cancelled by creator
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

    /**
     * Additional recipients who receive a copy of the FINAL signed PDF by email once
     * signing is complete (distinct from {@link #ccEmails}, which CCs the invitation).
     * Set on the New Document form; null/empty means only the client and creator are notified.
     */
    private List<String> completionCcEmails;

    /* ── Signatories (multi-party signing) ─────────────────────────────── */
    /** Whether signatories may sign in any order (PARALLEL) or one after another (SEQUENTIAL). */
    public enum SigningMode { PARALLEL, SEQUENTIAL }

    @Builder.Default
    private SigningMode signingMode = SigningMode.PARALLEL;

    /**
     * Ordered list of people who must sign this document. Always populated on documents
     * created after the multi-signatory feature (single-signer docs carry exactly one entry,
     * mirrored into {@link #clientEmail}/{@link #clientName}). Legacy documents created before
     * this feature have a null/empty list and fall back to the single client fields.
     */
    @Builder.Default
    private List<Signatory> signatories = new ArrayList<>();

    /* ── Signed output ─────────────────────────────────────────────────── */
    private byte[] signedPdfData;        // LEGACY embedded bytes (older docs); null for cloud-stored docs
    private String signedPdfKey;         // cloud storage key for the signed PDF (preferred)
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

    /**
     * Restricts which file-extension types the client may upload (case-insensitive, without dot,
     * e.g. {@code ["pdf","jpg","png"]}). An empty list means all file types are accepted.
     * Inherited from the parent {@link ESignBulkBatch} at document-creation time.
     */
    @Builder.Default
    private List<String> allowedClientUploadFileTypes = new ArrayList<>();

    /* ── Client-uploaded attachments (optional, post-signing) ─────────── */
    /**
     * Files the client voluntarily uploads after signing — e.g. ID proof, supporting docs.
     * Stored inline as embedded sub-documents; capped at 5 files × 10 MB each in the service layer.
     */
    @Builder.Default
    private List<ClientAttachment> clientAttachments = new ArrayList<>();

    /**
     * Record of who was emailed the final signed document (and the CC "signed" notice) once
     * signing completed — one entry per recipient, with per-recipient delivery status. Populated
     * by the completion-email flow and refreshed on "resend final copy".
     */
    @Builder.Default
    private List<CompletionNotification> completionNotifications = new ArrayList<>();

    /* ── Signatory sub-document ─────────────────────────────────────────── */
    public enum SignatoryStatus { PENDING, VIEWED, SIGNED }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Signatory {
        private String id;              // stable UUID; referenced by ESignSignatureField.signatoryId
        private String name;
        private String email;
        private int    signingOrder;    // 1-based; drives SEQUENTIAL invitations
        @Builder.Default
        private SignatoryStatus status = SignatoryStatus.PENDING;
        private String        tokenJti;  // current signing token for this signatory
        private LocalDateTime invitedAt; // when this signatory was first emailed their signing link
        private LocalDateTime viewedAt;
        private LocalDateTime signedAt;
        /** When this signatory affirmatively consented to use electronic records & signatures (ESIGN/UETA). */
        private LocalDateTime consentedAt;
    }

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

    /* ── Completion-notification sub-document ────────────────────────────── */

    /** Which "bucket" a completion recipient belongs to (drives the label + whether the PDF is attached). */
    public enum NotificationRole { SIGNATORY, CREATOR, COMPLETION_CC, INVITATION_CC }

    public enum NotificationStatus { SENT, FAILED }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CompletionNotification {
        private String             email;
        private String             name;            // may be null (CC lists store emails only)
        private NotificationRole   role;
        private NotificationStatus status;
        private boolean            withAttachment;  // true = signed PDF attached; false = view-only notice
        private LocalDateTime      sentAt;
    }
}
