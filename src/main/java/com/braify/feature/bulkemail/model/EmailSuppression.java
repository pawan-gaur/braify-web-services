package com.braify.feature.bulkemail.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * An email address that must NOT receive further bulk email for a given organisation —
 * populated when a recipient clicks the one-click unsubscribe link (and, in future, on
 * hard bounces / complaints). The bulk-email builder filters these out at send time.
 *
 * <p>{@code email} is stored lower-cased; the {@code (orgId, email)} pair is unique so
 * repeated unsubscribes are idempotent.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "email_suppressions")
@CompoundIndexes({
    @CompoundIndex(name = "idx_org_email_unique", def = "{'orgId': 1, 'email': 1}", unique = true),
})
public class EmailSuppression {

    public enum Reason { UNSUBSCRIBE, BOUNCE, COMPLAINT, MANUAL }

    @Id private String id;

    private String  orgId;
    private String  email;          // lower-cased
    private Reason  reason;
    private String  sourceJobId;    // the campaign the unsubscribe came from, if any
    private LocalDateTime createdAt;
}
