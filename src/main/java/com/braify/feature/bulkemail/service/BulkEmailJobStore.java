package com.braify.feature.bulkemail.service;

import com.braify.feature.bulkemail.model.BulkEmailJob;
import com.braify.feature.bulkemail.model.BulkEmailJob.BulkEmailAuditEvent;
import com.braify.feature.bulkemail.model.BulkEmailJob.JobStatus;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Job-level writes for bulk-email campaigns, done exclusively as targeted
 * {@code $set}/{@code $push} updates. A full-document save of an existing job is never issued:
 * it would race with the tracking endpoints' {@code $inc}s and with cancel, and on a legacy
 * (not yet migrated) document it would drop the embedded rows that are absent from the Java model.
 */
@Component
@RequiredArgsConstructor
public class BulkEmailJobStore {

    static final String RECIPIENTS = "bulk_email_recipients";

    /** Heavy fields never needed when reading a job's summary (legacy arrays included). */
    static final String[] HEAVY_FIELDS = {
            "rows", "detailSheetRows", "emailTemplateHtml", "uploadedPdfData",
            "columnMapping", "pdfColumnMapping", "externalApiHeaders", "externalApiBody",
    };

    private final MongoTemplate mongo;

    /** Per-status recipient counts for a job, computed from the recipients collection. */
    public record Counts(int total, int sent, int failed, int pending) {}

    public Counts countRecipients(String jobId) {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("jobId", jobId)),
                new Document("$group", new Document("_id", "$status").append("n", new Document("$sum", 1))));
        int sent = 0, failed = 0, pending = 0;
        for (Document d : mongo.getCollection(RECIPIENTS).aggregate(pipeline)) {
            int n = ((Number) d.get("n")).intValue();
            switch (String.valueOf(d.get("_id"))) {
                case "SENT"   -> sent = n;
                case "FAILED" -> failed = n;
                default       -> pending += n;   // PENDING (and anything unexpected)
            }
        }
        return new Counts(sent + failed + pending, sent, failed, pending);
    }

    /**
     * Re-derives the job's progress counters from its recipients and stores them. Counters are
     * always a function of the recipient rows, so they cannot drift or be lost to a race.
     */
    public Counts refreshCounters(String jobId) {
        Counts c = countRecipients(jobId);
        mongo.updateFirst(byId(jobId), new Update()
                        .set("totalCount",   c.total())
                        .set("sentCount",    c.sent())
                        .set("failedCount",  c.failed())
                        .set("pendingCount", c.pending()),
                BulkEmailJob.class);
        return c;
    }

    /** Appends an audit event without touching any other field. */
    public void pushAudit(String jobId, BulkEmailAuditEvent.EventType type, String description) {
        mongo.updateFirst(byId(jobId), new Update().push("auditEvents", BulkEmailAuditEvent.builder()
                        .type(type)
                        .description(description)
                        .timestamp(LocalDateTime.now())
                        .build()),
                BulkEmailJob.class);
    }

    /**
     * Moves a job to {@code to} only if it is currently in one of {@code from}.
     *
     * @param extra additional fields to set in the same atomic update (may be null)
     * @return true if this caller performed the transition
     */
    public boolean transition(String jobId, Collection<JobStatus> from, JobStatus to, Update extra) {
        Update u = extra != null ? extra : new Update();
        u.set("status", to);
        return mongo.updateFirst(
                Query.query(Criteria.where("_id").is(jobId).and("status").in(from)),
                u, BulkEmailJob.class).getModifiedCount() > 0;
    }

    /** The job's current status, or null if it does not exist. */
    public JobStatus status(String jobId) {
        Query q = byId(jobId);
        q.fields().include("status");
        BulkEmailJob j = mongo.findOne(q, BulkEmailJob.class);
        return j == null ? null : j.getStatus();
    }

    /** The job without its heavy fields — for API responses and guards. */
    public BulkEmailJob findSummary(String jobId) {
        Query q = byId(jobId);
        for (String f : HEAVY_FIELDS) q.fields().exclude(f);
        return mongo.findOne(q, BulkEmailJob.class);
    }

    private static Query byId(String jobId) {
        return Query.query(Criteria.where("_id").is(jobId));
    }
}
