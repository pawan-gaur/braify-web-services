package com.braify.feature.bulkemail.service;

import com.braify.feature.bulkemail.model.BulkEmailEvent;
import com.braify.feature.bulkemail.model.BulkEmailJob;
import com.braify.feature.bulkemail.model.EmailSuppression;
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
 * Records engagement events fired by the public {@code /api/track} endpoints and keeps the
 * denormalised counters on {@link BulkEmailJob} in sync.
 *
 * <h3>Race-safe distinct counting</h3>
 * Distinct-recipient counters ({@code openedCount}/{@code clickedCount}) are incremented only
 * on a recipient's <em>first</em> open/click. This is done with a guarded positional update
 * whose query requires {@code openCount == 0}: because MongoDB serialises concurrent updates
 * to the same document, a second simultaneous "first" hit no longer matches the guard once the
 * first has bumped the count, so the distinct counter is never double-incremented — no read is
 * needed. The analytics endpoint recomputes distinct counts exactly from the event log anyway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrackingEventService {

    private final MongoTemplate mongoTemplate;

    /** Look up the recipient (and its job/org context) behind an opaque tracking token. */
    private BulkEmailJob context(String token) {
        Query q = new Query(Criteria.where("rows.trackingId").is(token));
        q.fields().include("orgId").elemMatch("rows", Criteria.where("trackingId").is(token));
        BulkEmailJob ctx = mongoTemplate.findOne(q, BulkEmailJob.class);
        return (ctx != null && ctx.getRows() != null && !ctx.getRows().isEmpty()) ? ctx : null;
    }

    // ── OPEN ────────────────────────────────────────────────────────────────────

    public void recordOpen(String token, String ip, String userAgent) {
        BulkEmailJob ctx = context(token);
        if (ctx == null) return;
        BulkEmailJob.BulkEmailRow row = ctx.getRows().get(0);
        LocalDateTime now = LocalDateTime.now();

        insertEvent(ctx, row, token, BulkEmailEvent.Type.OPEN, null, ip, userAgent, now);

        Update first = new Update()
                .inc("rows.$.openCount", 1)
                .set("rows.$.firstOpenedAt", now)
                .set("rows.$.lastOpenedAt", now)
                .inc("totalOpens", 1)
                .inc("openedCount", 1);
        long modified = mongoTemplate.updateFirst(firstHitQuery(token, "openCount"), first, BulkEmailJob.class)
                .getModifiedCount();
        if (modified == 0) {
            mongoTemplate.updateFirst(byTokenQuery(token), new Update()
                    .inc("rows.$.openCount", 1)
                    .set("rows.$.lastOpenedAt", now)
                    .inc("totalOpens", 1), BulkEmailJob.class);
        }
    }

    // ── CLICK ───────────────────────────────────────────────────────────────────

    public void recordClick(String token, String url, String ip, String userAgent) {
        BulkEmailJob ctx = context(token);
        if (ctx == null) return;
        BulkEmailJob.BulkEmailRow row = ctx.getRows().get(0);
        LocalDateTime now = LocalDateTime.now();

        insertEvent(ctx, row, token, BulkEmailEvent.Type.CLICK, url, ip, userAgent, now);

        Update first = new Update()
                .inc("rows.$.clickCount", 1)
                .set("rows.$.firstClickedAt", now)
                .set("rows.$.lastClickedAt", now)
                .inc("totalClicks", 1)
                .inc("clickedCount", 1);
        long modified = mongoTemplate.updateFirst(firstHitQuery(token, "clickCount"), first, BulkEmailJob.class)
                .getModifiedCount();
        if (modified == 0) {
            mongoTemplate.updateFirst(byTokenQuery(token), new Update()
                    .inc("rows.$.clickCount", 1)
                    .set("rows.$.lastClickedAt", now)
                    .inc("totalClicks", 1), BulkEmailJob.class);
        }
    }

    // ── UNSUBSCRIBE ─────────────────────────────────────────────────────────────

    /** Records an unsubscribe and adds the address to the org suppression list. Returns the
     *  recipient email (for the confirmation page), or {@code null} for an unknown token. */
    public String recordUnsubscribe(String token, String ip, String userAgent) {
        BulkEmailJob ctx = context(token);
        if (ctx == null) return null;
        BulkEmailJob.BulkEmailRow row = ctx.getRows().get(0);
        String email = row.getRecipientEmail();
        LocalDateTime now = LocalDateTime.now();

        // Idempotent: re-hitting the link just re-confirms without double-counting.
        if (!row.isUnsubscribed()) {
            insertEvent(ctx, row, token, BulkEmailEvent.Type.UNSUBSCRIBE, null, ip, userAgent, now);

            Criteria notYet = new Criteria().andOperator(
                    Criteria.where("trackingId").is(token),
                    new Criteria().orOperator(
                            Criteria.where("unsubscribed").is(false),
                            Criteria.where("unsubscribed").exists(false)));
            long modified = mongoTemplate.updateFirst(
                    Query.query(Criteria.where("rows").elemMatch(notYet)),
                    new Update()
                            .set("rows.$.unsubscribed", true)
                            .set("rows.$.unsubscribedAt", now)
                            .inc("unsubscribedCount", 1),
                    BulkEmailJob.class).getModifiedCount();
            if (modified > 0) addSuppression(ctx.getOrgId(), email, ctx.getId(), now);
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

    private void insertEvent(BulkEmailJob ctx, BulkEmailJob.BulkEmailRow row, String token,
                             BulkEmailEvent.Type type, String url,
                             String ip, String userAgent, LocalDateTime now) {
        try {
            mongoTemplate.insert(BulkEmailEvent.builder()
                    .jobId(ctx.getId())
                    .orgId(ctx.getOrgId())
                    .trackingId(token)
                    .recipientEmail(row.getRecipientEmail())
                    .rowIndex(row.getRowIndex())
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

    /** Matches the job whose row has this token AND has not yet been counted for {@code countField}. */
    private Query firstHitQuery(String token, String countField) {
        Criteria elem = new Criteria().andOperator(
                Criteria.where("trackingId").is(token),
                new Criteria().orOperator(
                        Criteria.where(countField).is(0),
                        Criteria.where(countField).exists(false)));
        return Query.query(Criteria.where("rows").elemMatch(elem));
    }

    private Query byTokenQuery(String token) {
        return Query.query(Criteria.where("rows.trackingId").is(token));
    }
}
