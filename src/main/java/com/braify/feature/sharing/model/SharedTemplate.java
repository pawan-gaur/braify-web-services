package com.braify.feature.sharing.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Records a template share between two organisations.
 *
 * <ul>
 *   <li>VIEW  — target org members can preview the template read-only</li>
 *   <li>USE   — target org can generate PDFs / e-sign docs from the template</li>
 *   <li>EDIT  — a fork is created in the target org; they can edit their copy</li>
 * </ul>
 *
 * For EDIT shares, {@code forkedTemplateId} holds the ID of the copy created
 * in the target org.  Revoking an EDIT share soft-deletes the fork.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shared_templates")
@CompoundIndexes({
    @CompoundIndex(name = "target_status", def = "{'targetOrgId':1,'status':1}"),
    @CompoundIndex(name = "source_status", def = "{'sourceOrgId':1,'status':1}"),
    @CompoundIndex(name = "template_idx",  def = "{'templateId':1}")
})
public class SharedTemplate {

    public enum Permission { VIEW, USE, EDIT }
    public enum Status     { ACTIVE, REVOKED }
    public enum TemplateType { TEMPLATE, EMAIL_TEMPLATE }

    @Id
    private String id;

    /** Org that owns the source template. */
    @Indexed
    private String sourceOrgId;

    /** Org receiving access. */
    @Indexed
    private String targetOrgId;

    /** ID of the source template being shared. */
    private String templateId;

    /** Whether this is a PDF template or an email template. */
    @Builder.Default
    private TemplateType templateType = TemplateType.TEMPLATE;

    /** Level of access granted to the target org. */
    @Builder.Default
    private Permission permission = Permission.VIEW;

    /** Email of the user who created this share. */
    private String sharedBy;

    /** Stable user-ID snapshot of the sharer. */
    private String sharedByUserId;

    /** ID of the AppUser who created this share (alias for sharedByUserId for consistency). */
    private String createdBy;

    /** Optional message from the sharer. Max 300 chars. */
    private String note;

    @Builder.Default
    private Status status = Status.ACTIVE;

    @CreatedDate
    private LocalDateTime sharedAt;

    /** Set when status transitions to REVOKED. */
    private LocalDateTime revokedAt;
    private String        revokedBy;

    /**
     * For EDIT permission only: the ID of the forked template created in the target org.
     * Null for VIEW and USE shares.
     */
    private String forkedTemplateId;
}
