package com.braify.feature.bulkemail.migration;

import com.braify.feature.bulkemail.model.BulkEmailDetailRow;
import com.braify.feature.bulkemail.model.BulkEmailJob;
import com.braify.feature.bulkemail.model.BulkEmailMigrationRecord;
import com.braify.feature.bulkemail.service.BulkEmailJobStore;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moves legacy (schema v1) bulk-email jobs' embedded {@code rows} and {@code detailSheetRows}
 * into the {@code bulk_email_recipients} and {@code bulk_email_detail_rows} collections, with a
 * no-data-loss guarantee:
 *
 * <ol>
 *   <li><b>Backup</b> — the legacy arrays are written verbatim to {@code bulk_email_legacy_backup}.</li>
 *   <li><b>Copy</b> — each row becomes one document via an insert-only upsert keyed by
 *       {@code (jobId, seq)}, where {@code seq} is the row's array position. Re-running is safe and
 *       never overwrites a document that already exists.</li>
 *   <li><b>Verify</b> — counts must match and every field of every legacy row must equal the copy
 *       (engagement counters may only have grown).</li>
 *   <li><b>Commit</b> — only then are the embedded arrays removed from the job. If anything fails,
 *       the job is left exactly as it was, the failure is recorded, and it is retried next run.</li>
 * </ol>
 *
 * Runs for every legacy job at startup ({@link BulkEmailMigrationRunner}) and lazily, per job, the
 * first time anything touches a not-yet-migrated job ({@link #ensureMigrated}). The legacy data is
 * read as raw BSON, so it does not depend on the current Java model.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkEmailRowMigrationService {

    static final String JOBS       = "bulk_email_jobs";
    static final String RECIPIENTS = "bulk_email_recipients";
    static final String DETAIL     = "bulk_email_detail_rows";
    static final String BACKUP     = "bulk_email_legacy_backup";

    private static final int BATCH = 500;

    /** Counters tracking may legitimately advance after a row is copied: must not go down. */
    private static final Set<String> MONOTONIC = Set.of("openCount", "clickCount");
    /** Engagement values tracking may legitimately update: must not be lost. */
    private static final Set<String> NOT_LOST  = Set.of(
            "firstOpenedAt", "lastOpenedAt", "firstClickedAt", "lastClickedAt",
            "unsubscribed", "unsubscribedAt");

    private final MongoTemplate     mongo;
    private final BulkEmailJobStore jobStore;

    /** One lock per job id so startup and lazy migrations of the same job never interleave. */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public enum Outcome { MIGRATED, ALREADY_MIGRATED, NOT_FOUND, FAILED }

    public record RunReport(int scanned, int migrated, int failed, List<String> failedJobIds) {}

    // ── Public API ──────────────────────────────────────────────────────────────

    /** True when the job still embeds legacy rows (i.e. has not been migrated yet). */
    public boolean needsMigration(String jobId) {
        return jobs().find(Filters.and(idFilter(jobId), legacyFilter()))
                .projection(Projections.include("_id")).first() != null;
    }

    /**
     * Guarantees the job is migrated before the caller reads or changes its recipients.
     * A cheap existence check when already migrated.
     *
     * @throws IllegalStateException if the job's migration failed verification (its data is untouched)
     */
    public void ensureMigrated(String jobId) {
        if (!needsMigration(jobId)) return;
        if (migrateJob(jobId) == Outcome.FAILED) {
            throw new IllegalStateException("This campaign's recipient data is awaiting migration and could "
                    + "not be migrated automatically. No data was changed — check bulk_email_migration_log for job "
                    + jobId + ".");
        }
    }

    /**
     * Migrates the legacy job whose embedded rows contain {@code trackingId}, if any. Lets an
     * open/click/unsubscribe hit on an old email be recorded even before startup migration
     * reached that job.
     *
     * @return true if such a job existed and is now migrated; false if no legacy job owns the token
     * @throws IllegalStateException if the owning job exists but its migration failed verification
     */
    public boolean migrateJobOwningToken(String trackingId) {
        Document legacy = jobs().find(Filters.eq("rows.trackingId", trackingId))
                .projection(Projections.include("_id")).first();
        if (legacy == null) return false;
        String jobId = idString(legacy.get("_id"));
        if (migrateJob(jobId) == Outcome.FAILED) {
            throw new IllegalStateException("Campaign " + jobId + " awaits migration (verification failed)");
        }
        return true;
    }

    /** A recipient read straight from a legacy job's embedded rows (read-only). */
    public record LegacyRecipient(String jobId, String orgId, String email) {}

    /**
     * Read-only lookup of a recipient inside a not-yet-migrated job. Used only so an unsubscribe
     * is always honoured (suppression recorded) even if that job's migration failed verification.
     */
    public LegacyRecipient findLegacyRecipient(String trackingId) {
        Document legacy = jobs().find(Filters.eq("rows.trackingId", trackingId))
                .projection(Projections.fields(Projections.include("_id", "orgId"),
                        Projections.elemMatch("rows", Filters.eq("trackingId", trackingId))))
                .first();
        if (legacy == null) return null;
        List<Document> rows = documents(legacy.get("rows"));
        String email = rows.isEmpty() ? null : rows.get(0).getString("recipientEmail");
        return new LegacyRecipient(idString(legacy.get("_id")), legacy.getString("orgId"), email);
    }

    /** Migrates every legacy job. Safe to call repeatedly. */
    public RunReport migrateAll() {
        List<String> ids = new ArrayList<>();
        for (Document d : jobs().find(legacyFilter()).projection(Projections.include("_id"))) {
            ids.add(idString(d.get("_id")));
        }
        if (ids.isEmpty()) {
            log.info("[BulkEmailMigration] No legacy campaigns need migrating");
            return new RunReport(0, 0, 0, List.of());
        }

        log.info("[BulkEmailMigration] Migrating {} legacy campaign(s) to the recipients collection", ids.size());
        int migrated = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            Outcome o = migrateJob(id);
            if (o == Outcome.FAILED) failed.add(id);
            else if (o == Outcome.MIGRATED || o == Outcome.ALREADY_MIGRATED) migrated++;
        }
        if (failed.isEmpty()) {
            log.info("[BulkEmailMigration] Done: {}/{} campaign(s) migrated and verified", migrated, ids.size());
        } else {
            log.error("[BulkEmailMigration] {} campaign(s) FAILED verification and were left unchanged: {}",
                    failed.size(), failed);
        }
        return new RunReport(ids.size(), migrated, failed.size(), failed);
    }

    /** Migrates one job. Never throws — failures are recorded and reported as {@link Outcome#FAILED}. */
    public Outcome migrateJob(String jobId) {
        Object lock = locks.computeIfAbsent(jobId, k -> new Object());
        synchronized (lock) {
            try {
                return doMigrate(jobId);
            } catch (Exception e) {
                log.error("[BulkEmailMigration] Job {} migration error: {}", jobId, e.getMessage(), e);
                recordFailure(jobId, -1, -1, "Unexpected error: " + e.getMessage());
                return Outcome.FAILED;
            }
        }
    }

    /** Overview for operators: what is left, what failed, how much has been moved. */
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalCampaigns",      jobs().countDocuments());
        s.put("legacyCampaignsLeft", jobs().countDocuments(legacyFilter()));
        s.put("migratedCampaigns",   mongo.count(Query.query(Criteria.where("status")
                .is(BulkEmailMigrationRecord.Status.MIGRATED)), BulkEmailMigrationRecord.class));
        s.put("recipientDocuments",  mongo.getCollection(RECIPIENTS).countDocuments());
        s.put("detailRowDocuments",  mongo.getCollection(DETAIL).countDocuments());
        s.put("backupDocuments",     mongo.getCollection(BACKUP).countDocuments());
        s.put("failedCampaigns",     mongo.find(Query.query(Criteria.where("status")
                .is(BulkEmailMigrationRecord.Status.FAILED)), BulkEmailMigrationRecord.class));
        return s;
    }

    // ── Migration of one job ────────────────────────────────────────────────────

    private Outcome doMigrate(String jobId) {
        Document legacy = jobs().find(idFilter(jobId))
                .projection(Projections.include("_id", "rows", "detailSheetRows", "orgId", "detailSheetIdColumn"))
                .first();
        if (legacy == null) return Outcome.NOT_FOUND;
        if (!legacy.containsKey("rows") && !legacy.containsKey("detailSheetRows")) return Outcome.ALREADY_MIGRATED;

        Object         rawId    = legacy.get("_id");
        List<Document> rows     = documents(legacy.get("rows"));
        List<Document> details  = documents(legacy.get("detailSheetRows"));
        String         orgId    = legacy.getString("orgId");
        String         idColumn = legacy.getString("detailSheetIdColumn");
        long started = System.currentTimeMillis();

        // 1. Backup — verbatim copy of the legacy arrays; the rollback source until manual cleanup.
        mongo.getCollection(BACKUP).replaceOne(Filters.eq("_id", jobId),
                new Document("_id", jobId)
                        .append("jobObjectId", rawId)
                        .append("orgId", orgId)
                        .append("rows", rows)
                        .append("detailSheetRows", details)
                        .append("backedUpAt", new Date()),
                new ReplaceOptions().upsert(true));

        // 2. Copy recipients — insert-only upserts keyed by (jobId, seq): re-runnable, never overwrite.
        MongoCollection<Document> recipients = mongo.getCollection(RECIPIENTS);
        List<WriteModel<Document>> batch = new ArrayList<>(BATCH);
        for (int seq = 0; seq < rows.size(); seq++) {
            Document fields = new Document(rows.get(seq));   // every legacy field, verbatim
            fields.remove("_id");
            fields.remove("jobId");
            fields.remove("seq");
            if (orgId != null) fields.put("orgId", orgId);
            if (!fields.containsKey("status")) fields.put("status", "PENDING");
            batch.add(new UpdateOneModel<>(
                    Filters.and(Filters.eq("jobId", jobId), Filters.eq("seq", seq)),
                    new Document("$setOnInsert", fields),
                    new UpdateOptions().upsert(true)));
            if (batch.size() == BATCH) flush(recipients, batch);
        }
        flush(recipients, batch);

        // 3. Copy Sheet-2 rows the same way, adding the indexed match key.
        MongoCollection<Document> detailColl = mongo.getCollection(DETAIL);
        for (int seq = 0; seq < details.size(); seq++) {
            Document data = details.get(seq);
            Object idValue = idColumn == null ? null : data.get(idColumn);
            batch.add(new UpdateOneModel<>(
                    Filters.and(Filters.eq("jobId", jobId), Filters.eq("seq", seq)),
                    new Document("$setOnInsert", new Document("data", data)
                            .append("matchKey", BulkEmailDetailRow.matchKeyOf(idValue == null ? "" : idValue.toString()))),
                    new UpdateOptions().upsert(true)));
            if (batch.size() == BATCH) flush(detailColl, batch);
        }
        flush(detailColl, batch);

        // 4. Verify every row. Any mismatch aborts here — the legacy arrays are still untouched.
        String problem = verifyRecipients(jobId, rows);
        if (problem == null) problem = verifyDetails(jobId, details);
        if (problem != null) {
            recordFailure(jobId, rows.size(), details.size(), problem);
            log.error("[BulkEmailMigration] Job {} FAILED verification — left unchanged: {}", jobId, problem);
            return Outcome.FAILED;
        }

        // 5. Commit — remove the (now provably copied) embedded arrays and stamp the job.
        jobs().updateOne(Filters.eq("_id", rawId), Updates.combine(
                Updates.unset("rows"),
                Updates.unset("detailSheetRows"),
                Updates.unset("migrationError"),
                Updates.set("schemaVersion",  BulkEmailJob.SCHEMA_VERSION),
                Updates.set("rowsMigratedAt", new Date()),
                Updates.set("detailRowCount", details.size())));
        jobStore.refreshCounters(jobId);   // counters re-derived from the migrated rows

        recordSuccess(jobId, rows.size(), details.size());
        log.info("[BulkEmailMigration] Job {} migrated: {} recipient(s), {} detail row(s) in {} ms",
                jobId, rows.size(), details.size(), System.currentTimeMillis() - started);
        return Outcome.MIGRATED;
    }

    // ── Verification ────────────────────────────────────────────────────────────

    /** Null when every legacy row has an identical copy; otherwise a description of the first problem. */
    private String verifyRecipients(String jobId, List<Document> rows) {
        MongoCollection<Document> recipients = mongo.getCollection(RECIPIENTS);
        long count = recipients.countDocuments(Filters.eq("jobId", jobId));
        if (count != rows.size()) return "recipient count " + count + " != legacy row count " + rows.size();

        int expected = 0;
        for (Document copy : recipients.find(Filters.eq("jobId", jobId)).sort(Sorts.ascending("seq")).batchSize(BATCH)) {
            int seq = ((Number) copy.get("seq")).intValue();
            if (seq != expected) return "recipient seq " + expected + " missing (found " + seq + ")";
            String diff = compareRow(rows.get(seq), copy);
            if (diff != null) return "recipient seq " + seq + ": " + diff;
            expected++;
        }
        return expected == rows.size() ? null : "only " + expected + " of " + rows.size() + " recipients readable";
    }

    private String verifyDetails(String jobId, List<Document> details) {
        MongoCollection<Document> detailColl = mongo.getCollection(DETAIL);
        long count = detailColl.countDocuments(Filters.eq("jobId", jobId));
        if (count != details.size()) return "detail row count " + count + " != legacy " + details.size();

        int expected = 0;
        for (Document copy : detailColl.find(Filters.eq("jobId", jobId)).sort(Sorts.ascending("seq")).batchSize(BATCH)) {
            int seq = ((Number) copy.get("seq")).intValue();
            if (seq != expected) return "detail row seq " + expected + " missing (found " + seq + ")";
            if (!Objects.equals(details.get(seq), copy.get("data"))) return "detail row seq " + seq + " data differs";
            expected++;
        }
        return expected == details.size() ? null : "only " + expected + " of " + details.size() + " detail rows readable";
    }

    /** Compares every field of a legacy row with its copy. */
    private static String compareRow(Document legacy, Document copy) {
        for (Map.Entry<String, Object> e : legacy.entrySet()) {
            String key = e.getKey();
            Object was = e.getValue();
            Object now = copy.get(key);
            if (MONOTONIC.contains(key)) {
                if (number(now) < number(was)) return "'" + key + "' went down (" + was + " → " + now + ")";
            } else if (NOT_LOST.contains(key)) {
                if (was != null && !Boolean.FALSE.equals(was) && now == null) return "'" + key + "' was lost";
            } else if (!Objects.equals(was, now)) {
                return "field '" + key + "' differs";
            }
        }
        if (!legacy.containsKey("status") && !"PENDING".equals(copy.get("status"))) return "status differs";
        return null;
    }

    // ── Bookkeeping ─────────────────────────────────────────────────────────────

    private void recordSuccess(String jobId, int rows, int details) {
        LocalDateTime now = LocalDateTime.now();
        mongo.upsert(Query.query(Criteria.where("jobId").is(jobId)), new Update()
                        .set("status",             BulkEmailMigrationRecord.Status.MIGRATED)
                        .set("legacyRows",         rows)
                        .set("migratedRows",       rows)
                        .set("legacyDetailRows",   details)
                        .set("migratedDetailRows", details)
                        .unset("error")
                        .inc("attempts", 1)
                        .set("migratedAt", now)
                        .set("updatedAt",  now),
                BulkEmailMigrationRecord.class);
    }

    private void recordFailure(String jobId, int rows, int details, String error) {
        String msg = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        try {
            jobs().updateOne(idFilter(jobId), Updates.set("migrationError", msg));   // legacy arrays untouched
            mongo.upsert(Query.query(Criteria.where("jobId").is(jobId)), new Update()
                            .set("status",           BulkEmailMigrationRecord.Status.FAILED)
                            .set("legacyRows",       rows)
                            .set("legacyDetailRows", details)
                            .set("error",            msg)
                            .inc("attempts", 1)
                            .set("updatedAt", LocalDateTime.now()),
                    BulkEmailMigrationRecord.class);
        } catch (Exception e) {
            log.error("[BulkEmailMigration] Could not record failure for job {}: {}", jobId, e.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private MongoCollection<Document> jobs() {
        return mongo.getCollection(JOBS);
    }

    private static Bson legacyFilter() {
        return Filters.or(Filters.exists("rows"), Filters.exists("detailSheetRows"));
    }

    /** Matches the job whether its _id is stored as an ObjectId (the norm) or a string. */
    private static Bson idFilter(String jobId) {
        List<Object> ids = new ArrayList<>(2);
        if (ObjectId.isValid(jobId)) ids.add(new ObjectId(jobId));
        ids.add(jobId);
        return Filters.in("_id", ids);
    }

    private static String idString(Object id) {
        return id instanceof ObjectId o ? o.toHexString() : String.valueOf(id);
    }

    /** The legacy array as documents. Anything unexpected aborts the migration (job left unchanged). */
    private static List<Document> documents(Object value) {
        List<Document> out = new ArrayList<>();
        if (value == null) return out;
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("legacy field is not an array: " + value.getClass().getSimpleName());
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Document d)) {
                throw new IllegalStateException("legacy array element " + i + " is not a document");
            }
            out.add(d);
        }
        return out;
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static void flush(MongoCollection<Document> coll, List<WriteModel<Document>> batch) {
        if (batch.isEmpty()) return;
        coll.bulkWrite(batch, new BulkWriteOptions().ordered(false));
        batch.clear();
    }
}
