package com.braify.feature.esign.service;

import com.braify.feature.esign.dto.ContactResponse;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.esign.model.OrgContact;
import com.braify.feature.esign.repository.OrgContactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Maintains the per-org recipient "address book" ({@link OrgContact}) and serves
 * suggestions to the send-flow autocomplete. Writes are best-effort: recording a
 * contact must never break document creation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgContactService {

    /** Max contacts returned for prefetch — the client filters this list locally as the user types. */
    private static final int SUGGEST_LIMIT = 200;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final MongoTemplate           mongo;
    private final OrgContactRepository     repo;

    /** Records (upserts) a single recipient. No-op for null/blank/invalid input. */
    public void remember(String orgId, String name, String email) {
        if (orgId == null || orgId.isBlank() || email == null) return;
        String normalized = email.trim().toLowerCase();
        if (!EMAIL.matcher(normalized).matches()) return;

        // orgId + email come from the query equality on insert — do NOT $setOnInsert them
        // (Mongo would flag a path conflict). useCount starts at 1 via $inc from absent(0).
        Query q = new Query(Criteria.where("orgId").is(orgId).and("email").is(normalized));
        Update u = new Update()
                .inc("useCount", 1)
                .set("lastUsedAt", LocalDateTime.now());

        String trimmedName = name == null ? null : name.trim();
        if (trimmedName != null && !trimmedName.isEmpty()) {
            u.set("name", trimmedName);
        }

        try {
            mongo.upsert(q, u, OrgContact.class);
        } catch (Exception e) {
            // e.g. a rare duplicate-key race on first insert — safe to ignore.
            log.warn("Could not record contact '{}' for org {}: {}", normalized, orgId, e.getMessage());
        }
    }

    /** Records a batch of emails with no known name (e.g. CC lists). */
    public void rememberAll(String orgId, Collection<String> emails) {
        if (emails == null) return;
        for (String email : emails) remember(orgId, null, email);
    }

    /** Records every recipient on a document: all signatories plus CC lists. */
    public void rememberDocumentRecipients(ESignDocument doc) {
        if (doc == null || doc.getOrgId() == null) return;
        String orgId = doc.getOrgId();
        if (doc.getSignatories() != null && !doc.getSignatories().isEmpty()) {
            for (ESignDocument.Signatory s : doc.getSignatories()) remember(orgId, s.getName(), s.getEmail());
        } else {
            remember(orgId, doc.getClientName(), doc.getClientEmail());
        }
        rememberAll(orgId, doc.getCcEmails());
        rememberAll(orgId, doc.getCompletionCcEmails());
    }

    /** Top recipients for an org, most useful first. */
    public List<ContactResponse> suggest(String orgId) {
        var pageable = PageRequest.of(0, SUGGEST_LIMIT,
                Sort.by(Sort.Direction.DESC, "useCount").and(Sort.by(Sort.Direction.DESC, "lastUsedAt")));
        return repo.findByOrgId(orgId, pageable).stream()
                .map(c -> new ContactResponse(c.getName(), c.getEmail(), c.getUseCount()))
                .toList();
    }

    /**
     * One-time backfill: seeds {@code org_contacts} from existing e-sign documents so the
     * address book is useful on day one. Runs only when the collection is empty, and projects
     * away PDF byte fields so it never loads document payloads into memory.
     *
     * @return number of recipient records processed (0 when skipped)
     */
    public long backfillIfEmpty() {
        if (repo.count() > 0) return 0;

        Query q = new Query();
        q.fields()
                .include("orgId").include("clientEmail").include("clientName")
                .include("signatories").include("ccEmails").include("completionCcEmails");

        List<ESignDocument> docs = mongo.find(q, ESignDocument.class);
        long processed = 0;
        for (ESignDocument d : docs) {
            rememberDocumentRecipients(d);
            processed++;
        }
        if (processed > 0) log.info("OrgContact backfill: seeded address book from {} e-sign document(s)", processed);
        return processed;
    }
}
