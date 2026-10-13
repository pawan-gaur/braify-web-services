package com.braify.feature.signer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One row in a signer's document inbox — metadata only, never PDF bytes. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignerDocumentItem {

    /** Inbox tab the document belongs to for this signer. */
    public enum Bucket { ACTION, UPCOMING, SIGNED, OTHER }

    /** How this email relates to the document. */
    public enum Role { SIGNER, CC }

    private String id;
    private String title;

    private String orgId;
    private String orgName;

    /** ESignDocument.Status name. */
    private String status;
    private Bucket bucket;
    private Role   myRole;

    /** SignatoryStatus name for this signer (PENDING / VIEWED / SIGNED); null for CC. */
    private String mySignatoryStatus;
    private Integer mySigningOrder;
    private String signingMode;

    private int signatoriesTotal;
    private int signatoriesSigned;

    /** UPCOMING only: who the document is currently waiting on. */
    private String waitingOnName;

    private LocalDateTime sentAt;
    private LocalDateTime expiresAt;
    private LocalDateTime mySignedAt;
    private LocalDateTime completedAt;

    private boolean canSign;
    private boolean canView;
    private boolean canDownload;
}
