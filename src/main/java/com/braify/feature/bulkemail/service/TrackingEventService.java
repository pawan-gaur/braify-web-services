package com.braify.feature.bulkemail.service;

import com.braify.feature.bulkemail.migration.BulkEmailRowMigrationService;
import com.braify.feature.bulkemail.model.BulkEmailEvent;
import com.braify.feature.bulkemail.model.BulkEmailJob;
import com.braify.feature.bulkemail.model.BulkEmailRecipient;
import com.braify.feature.bulkemail.model.EmailSuppression;
import com.braify.feature.bulkemail.repository.BulkEmailRecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Records engagement events fired by the public {@code /api/track} endpoints, updating the
 * recipient document and the job's denormalised counters.
 *
 * <h3>Race-safe distinct counting</h3>
 * {@code openedCount}/{@code clickedCount} are incremented only on a recipient's <em>first</em>
 * open/click, via an update guarded by {@code openCount == 0} on that recipient's own document:
 * MongoDB serialises concurrent updates to one document, so two simultaneous "first" hits cannot
 * both match. The analytics endpoint recomputes distinct counts exactly from the event log anyway.
 *
 * <h3>Legacy campaigns</h3>
 * A token not found in {@code bulk_email_recipients} may belong to a campaign that hasn't been
 * migrated yet; that campaign is migrated on the spot and the hit is then recorded normally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrackingEventService {

    private final MongoTemplate                mongoTemplate;
    private final BulkEmailRecipientRepository recipientRepo;
    private final BulkEmailRowMigrationService migrationService;

    /** Resolves a token to its recipient, migrating its legacy campaign first if necessary. */
    private BulkEmailRecipient resolve(String token) {
        if (token == null || token.isBlank()) return null;
        BulkEmailRecipient r = recipientRepo.findFirstByTrackingId(token).orElse(null);
        if (r == null) {
            if (!migrationService.migrateJobOwningToken(token)) return null;
            r = recipientRepo.findFirstByTrackingId(token).orElse(null);
            if (r == null) return null;
        }
        // The copy can exist while its campaign's migration is still being verified — finish it
        // first so this update can never race the verification.
        migrationService.ensureMigrated(r.getJobId());
        return r;
    }

    // ── OPEN ────────────────────────────────────────────────────────────────────

    public void recordOpen(String token, String ip, String userAgent) {
        BulkEmailRecipient r = resolve(token);
        if (r == null) return;
        LocalDateTime now = LocalDateTime.now();
        insertEvent(r, token, BulkEmailEvent.Type.OPEN, null, ip, userAgent, now);

        boolean first = mongoTemplate.updateFirst(firstHit(r.getId(), "openCount"), new Update()
                        .inc("openCount", 1)
                        .set("firstOpenedAt", now)
                        .set("lastOpenedAt", now),
                BulkEmailRecipient.class).getModifiedCount() > 0;
        if (!first) {
            mongoTemplate.updateFirst(byId(r.getId()), new Update()
                    .inc("openCount", 1)
                    .set("lastOpenedAt", now), BulkEmailRecipient.class);
        }
        Update job = new Update().inc("totalOpens", 1);
        if (first) job.inc("openedCount", 1);
        mongoTemplate.updateFirst(byId(r.getJobId()), job, BulkEmailJob.class);
    }

    // ── CLICK ───────────────────────────────────────────────────────────────────

    public void recordClick(String token, String url, String ip, String userAgent) {
        BulkEmailRecipient r = resolve(token);
        if (r == null) return;
        LocalDateTime now = LocalDateTime.now();
        insertEvent(r, token, BulkEmailEvent.Type.CLICK, url, ip, userAgent, now);

        boolean first = mongoTemplate.updateFirst(firstHit(r.getId(), "clickCount"), new Update()
                        .inc("clickCount", 1)
                        .set("firstClickedAt", now)
                        .set("lastClickedAt", now),
                BulkEmailRecipient.class).getModifiedCount() > 0;
        if (!first) {
            mongoTemplate.updateFirst(byId(r.getId()), new Update()
                    .inc("clickCount", 1)
                    .set("lastClickedAt", now), BulkEmailRecipient.class);
        }
        Update job = new Update().inc("totalClicks", 1);
        if (first) job.inc("clickedCount", 1);
        mongoTemplate.updateFirst(byId(r.getJobId()), job, BulkEmailJob.class);
    }

    // ── UNSUBSCRIBE ─────────────────────────────────────────────────────────────

    /** Records an unsubscribe and adds the address to the org suppression list. Returns the
     *  recipient email (for the confirmation page), or {@code null} for an unknown token. */
    public String recordUnsubscribe(String token, String ip, String userAgent) {
        BulkEmailRecipient r;
        try {
            r = resolve(token);
        } catch (IllegalStateException migrationFailed) {
            // The campaign could not be migrated — still honour the opt-out from the legacy row.
            BulkEmailRowMigrationService.LegacyRecipient legacy = migrationService.findLegacyRecipient(token);
            if (legacy == null) return null;
            addSuppression(legacy.orgId(), legacy.email(), legacy.jobId(), LocalDateTime.now());
            log.warn("Unsubscribe for token {} recorded as suppression only — campaign {} awaits migration",
                    token, legacy.jobId());
            return legacy.email();
        }
        if (r == null) return null;

        String email = r.getRecipientEmail();
        LocalDateTime now = LocalDateTime.now();
        // Idempotent: re-hitting the link just re-confirms without double-counting.
        long modified = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(r.getId()).and("unsubscribed").ne(true)),
                new Update().set("unsubscribed", true).set("unsubscribedAt", now),
                BulkEmailRecipient.class).getModifiedCount();
        if (modified > 0) {
            insertEvent(r, token, BulkEmailEvent.Type.UNSUBSCRIBE, null, ip, userAgent, now);
            mongoTemplate.updateFirst(byId(r.getJobId()), new Update().inc("unsubscribedCount", 1), BulkEmailJob.class);
            addSuppression(orgIdOf(r), email, r.getJobId(), now);
        }
        return email;
    }

    private void addSuppression(String orgId, String email, String jobId, LocalDateTime now) {
        if (orgId == null || email == null || email.isBlank()) return;
        String normalised = email.trim().toLowerCase();
        try {
            // Upsert keyed by the unique (orgId, email) index → idempotent.
            mongoTemplate.upsert(
                    Query.query(Criteria.where("orgId").is(orgId).and("email").is(normalised)),
                    new Update()
                            .setOnInsert("orgId", orgId)
                            .setOnInsert("email", normalised)
                            .setOnInsert("reason", EmailSuppression.Reason.UNSUBSCRIBE)
                            .setOnInsert("sourceJobId", jobId)
                            .setOnInsert("createdAt", now),
                    EmailSuppression.class);
        } catch (DuplicateKeyException ignored) {
            // Concurrent unsubscribe of the same address — already suppressed, nothing to do.
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** The recipient's org, falling back to the job's (older recipients may not carry it). */
    private String orgIdOf(BulkEmailRecipient r) {
        if (r.getOrgId() != null) return r.getOrgId();
        Query q = byId(r.getJobId());
        q.fields().include("orgId");
        BulkEmailJob job = mongoTemplate.findOne(q, BulkEmailJob.class);
        return job != null ? job.getOrgId() : null;
    }

    private void insertEvent(BulkEmailRecipient r, String token, BulkEmailEvent.Type type, String url,
                             String ip, String userAgent, LocalDateTime now) {
        try {
            mongoTemplate.insert(BulkEmailEvent.builder()
                    .jobId(r.getJobId())
                    .orgId(orgIdOf(r))
                    .trackingId(token)
                    .recipientEmail(r.getRecipientEmail())
                    .rowIndex(r.getRowIndex())
                    .type(type)
                    .url(url)
                    .timestamp(now)
                    .ip(ip)
                    .userAgent(userAgent)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to record {} event for token {}: {}", type, token, e.getMessage());
        }
    }

    /** Matches the recipient only if it has not yet been counted for {@code countField}. */
    private static Query firstHit(String recipientId, String countField) {
        return Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(recipientId),
                new Criteria().orOperator(
                        Criteria.where(countField).is(0),
                        Criteria.where(countField).exists(false))));
    }

    private static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(id));
    }
}
