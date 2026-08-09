package com.braify.feature.emaillog.model;

import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One row per outbound email the platform sends, across every category (e-sign, user invites,
 * password resets, bulk email, template sends, onboarding, API-triggered). Written at each send
 * site via {@code EmailLogService.record(...)}; read by the Email Activity viewer — Org_Admins see
 * their own org's rows, Platform_Admins see all orgs.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "email_logs")
@CompoundIndexes({
    // Org-scoped viewer list (Org_Admin), newest first
    @CompoundIndex(name = "idx_org_created", def = "{'orgId':1,'createdAt':-1}"),
    // Platform viewer filtered by category/status, newest first
    @CompoundIndex(name = "idx_category_created", def = "{'category':1,'createdAt':-1}"),
})
public class EmailLog {

    @Id private String id;

    /** Owning organization; {@code null} for platform-level emails not tied to any org. */
    @Indexed private String orgId;

    /** What kind of email this was. */
    public enum Category {
        ESIGN_INVITATION,
        ESIGN_REMINDER,
        ESIGN_COMPLETION,
        ESIGN_CC,
        USER_INVITE,
        PASSWORD_RESET,
        BULK_EMAIL,
        TEMPLATE_SEND,
        ONBOARDING,
        API_EMAIL,
        OTHER
    }
    private Category category;

    /** Whether the send succeeded or the provider/transport rejected it. */
    public enum Status { SENT, FAILED }
    private Status status;

    private String       recipient;      // the address this row tracks (To address, or a CC address when cc=true)
    private List<String> ccEmails;       // the send's CC recipients (populated on the primary/To row for reference)
    private String       subject;
    private String       senderName;     // display name used in the From field (org name, etc.)

    /**
     * True when this row tracks a CC recipient of a send (its {@link #recipient} is the CC address),
     * so CC deliveries appear as their own entries in the viewer. False for the primary To recipient.
     */
    @Builder.Default
    private boolean cc = false;

    /** Provider message id (e.g. Resend id) when the send succeeded. */
    private String providerMessageId;
    /** Error detail when {@code status == FAILED}. */
    private String errorMessage;

    /** Loose link back to the entity that triggered this email (e.g. E_SIGN_DOCUMENT + documentId). */
    public enum RelatedType { E_SIGN_DOCUMENT, USER, ONBOARDING_REQUEST, BULK_EMAIL_JOB, EMAIL_TEMPLATE, API_KEY }
    private RelatedType relatedType;
    private String      relatedId;

    /** userId of whoever triggered the send; {@code null} for system/scheduled sends. */
    private String createdBy;

    @CreatedDate
    @Indexed
    private LocalDateTime createdAt;
}
