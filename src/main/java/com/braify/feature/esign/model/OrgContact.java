package com.braify.feature.esign.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Org-level "address book" entry — a recipient the organisation has sent an e-sign
 * document (or CC) to at least once. Powers the recipient autocomplete on the send
 * flow so users don't retype the same emails.
 *
 * <p>One document per (orgId, email) pair — the compound unique index de-duplicates
 * recipients within an organisation. Ranked for suggestions by {@code useCount} then
 * {@code lastUsedAt}. The list is shared org-wide (any member's recipients are
 * suggested to every member), matching the team-tool intent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "org_contacts")
@CompoundIndexes({
    @CompoundIndex(name = "idx_org_email_unique", def = "{'orgId':1,'email':1}", unique = true),
    @CompoundIndex(name = "idx_org_rank",         def = "{'orgId':1,'useCount':-1,'lastUsedAt':-1}")
})
public class OrgContact {

    @Id
    private String id;

    private String orgId;

    /** Normalised (trimmed, lower-cased) email — the natural key within an org. */
    private String email;

    /** Most recently seen display name for this recipient (may be blank if never supplied). */
    private String name;

    /** How many times this recipient has been used — drives suggestion ranking. */
    @Builder.Default
    private long useCount = 0;

    /** Last time this recipient was used — secondary ranking / recency. */
    private LocalDateTime lastUsedAt;
}
